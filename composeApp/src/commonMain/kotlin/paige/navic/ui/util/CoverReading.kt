package paige.navic.ui.util

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * What a cover's pixels say about the page that wears it (I-057, 2026-10-31). Every decision is read
 * from the image itself, in Oklab, with no palette filter: kmpalette's default filter drops near-black
 * pixels before it counts anything, so a black background was invisible to the old rules (it saw 7% of
 * Cameron Winter's `Live at Carnegie Hall`), and the page of every black-framed cover came out a
 * charcoal tinted toward the cover's colour (median L* 17 over the 600 such covers in the owner's
 * library), never black.
 *
 * Fitted and checked on the owner's library (4,282 covers at 128 px) against a Python prototype, then on
 * 96 covers drawn at random: 29 better than the old rules, 64 the same, 3 slightly worse (accents).
 * The numbers and the covers behind each rule: navi-connect `RESULTS-navic-cover-colour-2026-10-31.md`.
 *
 * - [tone]: how light the cover reads, CIE L* 0 to 100: 0.6 of [coverTone] plus 0.4 of the median L*
 *   of the cover's outer ring ([RING]), its frame or background, the part the page continues.
 * - [dark]: whether the page is dark. By the tone (under [PAGE_TONE_SPLIT]), unless black is the
 *   cover's background ([background]).
 * - [field]: what the cover is made of, the colour its page is built from: the cover's background when
 *   it has one, else the largest colour family on the page's own side of the split (frame pixels count
 *   double), else the largest family.
 * - [wash]: the page's colour before its lightness is placed: the field, a grey field tinted a quarter
 *   of the way toward the cover's main colour, a black field with almost none of its cast.
 * - [washL]: the L* the wash's top lands at: the field's own lightness, kept inside the band text stays
 *   readable in ([WASH_DARK_FLOOR] to [WASH_DARK_CEILING] dark, [LIGHT_TOP_MIN] to [LIGHT_TOP_MAX] light).
 * - [accent]: the most salient colour family whose hue is not the page's own ([pageHue]); the page's
 *   own hue only when nothing else is colourful enough; null for a cover with no hue at all. A dark
 *   page wears it only when the palette's vivid swatch is the page's own hue ([deriveCoverSeeds]).
 */
data class CoverReading(
	val tone: Float,
	val dark: Boolean,
	val field: Int,
	val fieldKind: FieldKind,
	/** Oklab hue in degrees of a [FieldKind.HUE] field; null for black, grey and white. */
	val fieldHue: Float?,
	val wash: Int,
	val washL: Float,
	val accent: Int?,
	/** The hue an accent must keep clear of: the field's, or the tint a grey field took; null for none. */
	val pageHue: Float?,
	/** True when the field is the cover's background: black or one colour holding its frame. */
	val background: Boolean
)

enum class FieldKind { HUE, BLACK, GREY, WHITE }

/** The outer ring, as a fraction of the short side: the cover's frame or background. */
internal const val RING = 0.12f

/** How much the ring's median L* counts in [CoverReading.tone]. 0.4 and 0.5 tie on the owner's library; 0.4 flips fewer. */
internal const val RING_WEIGHT = 0.4f

/** Oklab chroma under which a pixel has no usable hue. */
internal const val NEUTRAL_CHROMA = 0.045f

/** Under this L* a pixel is black whatever its faint cast (Live at Carnegie Hall's blacks read navy otherwise). */
internal const val BLACK_ANY_L = 12f

/** A colour family's half-width, degrees of Oklab hue. */
private const val HUE_HALF_WIDTH = 30f

/** An accent this close in hue to the page's own colour is that colour. */
internal const val ACCENT_HUE_GAP = 40f
private const val ACCENT_MIN_AREA = 0.012f
private const val ACCENT_MIN_CHROMA = 0.07f
private const val ACCENT_WEAK_AREA = 0.005f

/** A family on the page's side of the split is its field from this share of the (frame-weighted) cover. */
private const val SIDE_MIN = 0.08f

/** A grey field is tinted toward the cover's main colour when that colour covers this much... */
private const val GREY_TINT_MIN = 0.15f

/** ...by this much. */
private const val GREY_TINT = 0.25f

/**
 * Black is the background when it is the frame's largest family with at least this share of it, at
 * least [BLACK_BG_COVER] of the cover, and at least [BLACK_BG_BALANCE] times as much of the cover as
 * white. The last keeps line art and black bands on white grounds light (The Virgin Suicides).
 */
private const val BLACK_BG_FRAME = 0.35f
private const val BLACK_BG_COVER = 0.20f
private const val BLACK_BG_BALANCE = 0.8f

/**
 * One colour (a whole hue family, every lightness) holding this share of the frame and
 * [COLOUR_BG_COVER] of the cover is the background (Heavy Is the Crown's sky). It sets the page's
 * colour, not its side: letting it set the side too flipped 88 covers, about 8 in 24 wrongly.
 */
private const val COLOUR_BG_FRAME = 0.5f
private const val COLOUR_BG_COVER = 0.3f

/** The darkest a dark page's wash top goes: a black background gives about this. */
const val WASH_DARK_FLOOR = 5f

/**
 * The lightest a pixel-read dark page's top goes: at [DARK_TOP_MAX] (28) a green seed's secondary text
 * measured 4.48:1 over the brightest artwork (`WashTuningTest`), under the 4.5 the band is held to.
 */
const val WASH_DARK_CEILING = 27f

/** The most Oklab chroma a black field's wash keeps of its cast. */
private const val BLACK_MAX_CHROMA = 0.015f

/** sRGB channel to linear light, per value. */
private val linearOf = FloatArray(256) { i ->
	val c = i / 255.0
	(if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)).toFloat()
}

/** Every opaque pixel in Oklab, its CIE L*, and whether it lies in the ring. */
private class CoverPixels(argb: IntArray, width: Int, height: Int) {
	val n: Int
	val l: FloatArray
	val a: FloatArray
	val b: FloatArray
	val chroma: FloatArray
	val hue: FloatArray
	val lStar: FloatArray
	val ring: BooleanArray

	init {
		val count = argb.count { (it ushr 24) != 0 }
		n = count
		l = FloatArray(count); a = FloatArray(count); b = FloatArray(count)
		chroma = FloatArray(count); hue = FloatArray(count); lStar = FloatArray(count)
		ring = BooleanArray(count)
		val short = min(width, height).toFloat()
		var i = 0
		for (y in 0 until height) for (x in 0 until width) {
			val pixel = argb[y * width + x]
			if ((pixel ushr 24) == 0) continue
			val r = linearOf[(pixel shr 16) and 0xFF]
			val g = linearOf[(pixel shr 8) and 0xFF]
			val bl = linearOf[pixel and 0xFF]
			val lms0 = cbrt(0.4122214708f * r + 0.5363325363f * g + 0.0514459929f * bl)
			val lms1 = cbrt(0.2119034982f * r + 0.6806995451f * g + 0.1073969566f * bl)
			val lms2 = cbrt(0.0883024619f * r + 0.2817188376f * g + 0.6299787005f * bl)
			l[i] = 0.2104542553f * lms0 + 0.7936177850f * lms1 - 0.0040720468f * lms2
			a[i] = 1.9779984951f * lms0 - 2.4285922050f * lms1 + 0.4505937099f * lms2
			b[i] = 0.0259040371f * lms0 + 0.7827717662f * lms1 - 0.8086757660f * lms2
			chroma[i] = hypot(a[i], b[i])
			hue[i] = degrees(atan2(b[i], a[i]))
			val yLum = 0.2126f * r + 0.7152f * g + 0.0722f * bl
			lStar[i] = if (yLum > 216f / 24389f) 116f * cbrt(yLum) - 16f else 24389f / 27f * yLum
			val edge = min(min(y, height - 1 - y), min(x, width - 1 - x))
			ring[i] = edge / short < RING
			i++
		}
	}
}

private fun degrees(radians: Float): Float {
	val d = radians * 180f / kotlin.math.PI.toFloat()
	return if (d < 0f) d + 360f else d
}

/** Shortest distance between two hues, degrees (0 to 180). */
internal fun hueGap(x: Float, y: Float): Float {
	val d = abs(x - y) % 360f
	return if (d > 180f) 360f - d else d
}

/** The median of [values] at [indices], as numpy takes it (the mean of the middle two for an even count). */
private fun median(values: FloatArray, indices: IntArray): Float {
	val sorted = FloatArray(indices.size) { values[indices[it]] }.also { it.sort() }
	val m = sorted.size / 2
	return if (sorted.size % 2 == 1) sorted[m] else (sorted[m - 1] + sorted[m]) / 2f
}

/** A set of pixels (a colour family, or a band of one) with what the rules read off it. */
private class Family(val kind: FieldKind, val hue: Float?, val idx: IntArray, px: CoverPixels, weights: FloatArray, weightSum: Float) {
	val area = idx.size.toFloat() / px.n
	val weightedArea = idx.sumOf { weights[it].toDouble() }.toFloat() / weightSum
	val frameShare = idx.count { px.ring[it] }.toFloat() / max(1, px.ring.count { it })
	val lStar = median(px.lStar, idx)

	/** The family's more colourful half: what names it, and its pull on the eye. */
	val top: IntArray = median(px.chroma, idx).let { mid -> idx.filter { px.chroma[it] >= mid }.toIntArray() }
	val topChroma = top.sumOf { px.chroma[it].toDouble() }.toFloat() / top.size
	val topL = top.sumOf { px.l[it].toDouble() }.toFloat() / top.size
}

/** Mean Oklab of [idx]. */
private fun meanOklab(px: CoverPixels, idx: IntArray): FloatArray {
	var l = 0.0; var a = 0.0; var b = 0.0
	for (i in idx) { l += px.l[i]; a += px.a[i]; b += px.b[i] }
	return floatArrayOf((l / idx.size).toFloat(), (a / idx.size).toFloat(), (b / idx.size).toFloat())
}

/** Hue families among the coloured pixels: peaks of a kernel density over Oklab hue, every 2 degrees. */
private fun hueFamilies(px: CoverPixels): List<Pair<Float, IntArray>> {
	val coloured = (0 until px.n).filter { px.chroma[it] >= NEUTRAL_CHROMA && px.lStar[it] >= BLACK_ANY_L }.toIntArray()
	if (coloured.isEmpty()) return emptyList()
	val grid = FloatArray(180) { it * 2f }
	val density = FloatArray(180) { g ->
		var sum = 0f
		for (i in coloured) {
			val w = 1f - hueGap(grid[g], px.hue[i]) / HUE_HALF_WIDTH
			if (w > 0f) sum += w
		}
		sum
	}
	val taken = BooleanArray(coloured.size)
	val families = mutableListOf<Pair<Float, IntArray>>()
	for (g in (0 until 180).sortedByDescending { density[it] }) {
		val members = coloured.indices.filter { !taken[it] && hueGap(grid[g], px.hue[coloured[it]]) <= HUE_HALF_WIDTH }
		if (members.isEmpty()) continue
		members.forEach { taken[it] = true }
		families += grid[g] to IntArray(members.size) { coloured[members[it]] }
	}
	return families
}

/**
 * A cover's [CoverReading] from its pixels (packed ARGB, row by row, [width] by [height]); null when
 * no pixel is opaque. Pure and allocation-heavy by design (a few float arrays of the image's size):
 * it runs once per cover, off the main thread, beside the palette.
 */
fun readCover(argb: IntArray, width: Int, height: Int): CoverReading? {
	if (width <= 0 || height <= 0 || argb.size < width * height) return null
	val px = CoverPixels(argb, width, height)
	if (px.n == 0) return null
	val weights = FloatArray(px.n) { if (px.ring[it]) 2f else 1f }
	val weightSum = weights.sum()
	fun family(kind: FieldKind, hue: Float?, idx: IntArray) = Family(kind, hue, idx, px, weights, weightSum)

	val hueFams = hueFamilies(px).map { (hue, idx) -> family(FieldKind.HUE, hue, idx) }
	// Field families: each hue family split by lightness (cream is not the orange beside it), and the neutrals.
	val fieldFams = mutableListOf<Family>()
	for (f in hueFams) {
		for ((lo, hi) in listOf(0f to 40f, 40f to 72f, 72f to 101f)) {
			val band = f.idx.filter { px.lStar[it] >= lo && px.lStar[it] < hi }.toIntArray()
			if (band.isNotEmpty()) fieldFams += family(FieldKind.HUE, f.hue, band)
		}
	}
	for ((kind, range) in listOf(FieldKind.BLACK to (0f to 30f), FieldKind.GREY to (30f to 72f), FieldKind.WHITE to (72f to 101f))) {
		val idx = (0 until px.n).filter {
			(px.chroma[it] < NEUTRAL_CHROMA || px.lStar[it] < BLACK_ANY_L) && px.lStar[it] >= range.first && px.lStar[it] < range.second
		}.toIntArray()
		if (idx.isNotEmpty()) fieldFams += family(kind, null, idx)
	}

	// Tone: coverTone, 0.4 of it replaced by the frame's median lightness.
	var lSum = 0.0; var colourSum = 0.0; var weightTotal = 0.0
	for (i in 0 until px.n) {
		val w = px.chroma[i].toDouble() * px.chroma[i] + 0.0001
		lSum += px.lStar[i]; colourSum += px.lStar[i] * w; weightTotal += w
	}
	val plainTone = ((lSum / px.n + colourSum / weightTotal) / 2.0).toFloat()
	val ringIdx = (0 until px.n).filter { px.ring[it] }.toIntArray()
	val ringMedian = if (ringIdx.isEmpty()) plainTone else median(px.lStar, ringIdx)
	val tone = (1f - RING_WEIGHT) * plainTone + RING_WEIGHT * ringMedian
	var dark = tone < PAGE_TONE_SPLIT

	// The background: black holding the frame decides the page's side and colour; one colour holding
	// it decides the colour only.
	val frameTop = fieldFams.maxBy { it.frameShare }
	fun coverShare(kind: FieldKind) = fieldFams.filter { it.kind == kind }.sumOf { it.area.toDouble() }.toFloat()
	var background: Family? = null
	if (frameTop.kind == FieldKind.BLACK && frameTop.frameShare >= BLACK_BG_FRAME && frameTop.area >= BLACK_BG_COVER &&
		coverShare(FieldKind.BLACK) >= BLACK_BG_BALANCE * coverShare(FieldKind.WHITE)
	) {
		background = frameTop
		dark = true
	} else {
		val colour = hueFams.maxByOrNull { it.frameShare }
		if (colour != null && colour.frameShare >= COLOUR_BG_FRAME && colour.area >= COLOUR_BG_COVER &&
			colour.frameShare >= frameTop.frameShare
		) background = colour
	}

	val field = background ?: run {
		val side = fieldFams.filter { (it.lStar < PAGE_TONE_SPLIT) == dark }.maxByOrNull { it.weightedArea }
		if (side != null && side.weightedArea >= SIDE_MIN) side else fieldFams.maxBy { it.weightedArea }
	}
	val fieldLab = meanOklab(px, field.idx)

	// The wash: the field, a grey one tinted toward the main colour, a black one nearly without its cast.
	var washLab = fieldLab
	var pageHue = field.hue
	if (field.kind == FieldKind.GREY) {
		val main = hueFams.maxByOrNull { it.area }
		if (main != null && main.area >= GREY_TINT_MIN) {
			val m = meanOklab(px, main.idx)
			washLab = FloatArray(3) { fieldLab[it] + (m[it] - fieldLab[it]) * GREY_TINT }
			pageHue = main.hue
		}
	}
	if (field.kind == FieldKind.BLACK) {
		val c = hypot(washLab[1], washLab[2])
		if (c > BLACK_MAX_CHROMA) washLab = floatArrayOf(washLab[0], washLab[1] * BLACK_MAX_CHROMA / c, washLab[2] * BLACK_MAX_CHROMA / c)
	}
	val washL = if (dark) field.lStar.coerceIn(WASH_DARK_FLOOR, WASH_DARK_CEILING) else field.lStar.coerceIn(LIGHT_TOP_MIN, LIGHT_TOP_MAX)

	// The accent: the most salient family (area's root, chroma squared, lighter on a dark page and darker
	// on a light one) whose hue is not the page's own.
	fun score(f: Family): Float = sqrt(f.area) * f.topChroma * f.topChroma * (if (dark) 0.5f + f.topL else 1.5f - f.topL)
	val eligible = hueFams.filter { it.area >= ACCENT_MIN_AREA && it.topChroma >= ACCENT_MIN_CHROMA }
	val candidates = pageHue?.let { h -> eligible.filter { hueGap(it.hue!!, h) > ACCENT_HUE_GAP } } ?: eligible
	val accentFamily = candidates.maxByOrNull(::score)
		?: field.takeIf { it.kind == FieldKind.HUE }
		?: eligible.maxByOrNull(::score)
		?: hueFams.filter { it.area >= ACCENT_WEAK_AREA }.maxByOrNull(::score)

	return CoverReading(
		tone = tone,
		dark = dark,
		field = oklabToArgb(fieldLab),
		fieldKind = field.kind,
		fieldHue = field.hue,
		wash = oklabToArgb(washLab),
		washL = washL,
		accent = accentFamily?.let { oklabToArgb(meanOklab(px, it.top)) },
		pageHue = pageHue,
		background = background != null
	)
}

/** Oklab hue in degrees and chroma of a packed sRGB colour. */
internal fun oklabHueChroma(argb: Int): Pair<Float, Float> {
	val px = CoverPixels(intArrayOf(argb or (0xFF shl 24)), 1, 1)
	return px.hue[0] to px.chroma[0]
}

/** Oklab to packed opaque sRGB, each channel clamped. */
internal fun oklabToArgb(lab: FloatArray): Int {
	val l = lab[0] + 0.3963377774f * lab[1] + 0.2158037573f * lab[2]
	val m = lab[0] - 0.1055613458f * lab[1] - 0.0638541728f * lab[2]
	val s = lab[0] - 0.0894841775f * lab[1] - 1.2914855480f * lab[2]
	val l3 = l * l * l; val m3 = m * m * m; val s3 = s * s * s
	val r = 4.0767416621f * l3 - 3.3077115913f * m3 + 0.2309699292f * s3
	val g = -1.2684380046f * l3 + 2.6097574011f * m3 - 0.3413193965f * s3
	val b = -0.0041960863f * l3 - 0.7034186147f * m3 + 1.7076147010f * s3
	fun channel(c: Float): Int {
		val v = c.coerceIn(0f, 1f).toDouble()
		val e = if (v <= 0.0031308) v * 12.92 else 1.055 * v.pow(1 / 2.4) - 0.055
		return (e * 255.0 + 0.5).toInt().coerceIn(0, 255)
	}
	return (0xFF shl 24) or (channel(r) shl 16) or (channel(g) shl 8) or channel(b)
}
