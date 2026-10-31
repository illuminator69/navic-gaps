package paige.navic.ui.util

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.math.abs
import kotlin.math.cbrt
import kotlin.math.pow

/**
 * One palette swatch, as the colour rules need it: packed ARGB, HSL as `Palette.Swatch.hsl` gives
 * it (hue 0 to 360, saturation and lightness 0 to 1) and how many pixels it stands for. A plain
 * value so the rules below can be tested without kmpalette.
 */
data class SwatchSample(
	val rgb: Int,
	val hue: Float,
	val saturation: Float,
	val lightness: Float,
	val population: Int
)

/**
 * What the engine keeps about a cover, packed ARGB. Everything a page wears (the wash, the accent,
 * the brightness, the Material scheme) is derived from these in `rememberCoverColorScheme`, so these
 * are all that has to be known, or stored, about a cover. Since codec version 4 the page's colours
 * come from the cover's pixels ([readCover], I-057); the palette is kept for [vivid] alone.
 *
 * - [dominant]: what the cover is made of, its field ([CoverReading.field]): the scheme's neutrals,
 *   every background and card. Seeds worked out without pixels name the palette's most populous
 *   colour family instead.
 * - [vivid]: the palette's most populous COLOURFUL family (washed-out, near-black and near-white
 *   swatches rejected); null for a cover with no colour. kmpalette's default filter drops "skin
 *   tones", which is why a dark page still takes its accent from here: on dark photos the faces stay
 *   out of it.
 * - [accent]: what the scheme's `primary` (buttons, the play bar, links) is seeded from. On a dark page
 *   it is [vivid], unless that is the page's own colour; otherwise the pixels' accent
 *   ([CoverReading.accent]), the most salient colour that is not the page's.
 * - [tone]: how light the cover reads, CIE L* 0 to 100 ([CoverReading.tone]).
 * - [dark]: the page's side ([CoverReading.dark]); null for seeds without pixels, which then follow
 *   [tone], or the dominant's luminance.
 * - [wash] and [washL]: the page's colour and the L* its top lands at ([CoverReading.wash],
 *   [CoverReading.washL]); null for seeds without pixels, which wash as before (the dominant pulled
 *   toward [vivid], placed by [tone]).
 */
data class CoverSeeds(
	val dominant: Int,
	val vivid: Int?,
	val accent: Int? = vivid,
	val tone: Float? = null,
	val dark: Boolean? = null,
	val wash: Int? = null,
	val washL: Float? = null
)

/**
 * Whether a cover's page is dark: as its pixels decided ([CoverSeeds.dark]), else by its tone, else by
 * the dominant swatch's luminance (the rule before tone).
 */
fun CoverSeeds.pageIsDark(): Boolean = dark ?: tone?.let(::pageIsDark) ?: (Color(dominant).luminance() < 0.5f)

/** Below this [CoverSeeds.tone] a cover's page is dark (fitted to the owner's five covers, 2026-10-28). */
const val PAGE_TONE_SPLIT = 55f

fun pageIsDark(tone: Float): Boolean = tone < PAGE_TONE_SPLIT

/**
 * A grey pixel's weight in [coverTone]'s colour half, as Oklab chroma squared (chroma 0.01): enough
 * that a cover with no colour at all reads as its plain average, too little to outweigh real colour.
 */
private const val GREY_WEIGHT = 0.0001

/** sRGB channel (0 to 255) to linear light, once per value rather than once per pixel. */
private val linearChannel = DoubleArray(256) { i ->
	val c = i / 255.0
	if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
}

/**
 * How light a cover READS, CIE L* 0 to 100: half its plain average lightness, half the lightness of
 * its colours (each pixel weighted by its Oklab chroma squared). Null for no opaque pixels.
 *
 * Neither half is enough alone, measured on the owner's phone (2026-10-28, five covers with the page
 * they wanted). The plain average put Mitski's `The Land Is Inhospitable and So Are We` (a peach
 * frame round a near-black photo, average L* 42) with the dark covers, but the owner reads that sleeve
 * as its peach; the colour half alone would give a black sleeve with a small yellow logo a light page.
 * The page used to follow ONE swatch's luminance, the leading swatch of the most populous colour
 * family, after kmpalette's filters had dropped near-black pixels and every "skin tone" (hue 10 to 37,
 * saturation up to 0.82): KID A MNESIA's black band and most of Mitski's frame were never counted.
 */
fun coverTone(argb: IntArray): Float? {
	var count = 0
	var lightnessSum = 0.0
	var colourSum = 0.0
	var weightSum = 0.0
	for (pixel in argb) {
		if ((pixel ushr 24) == 0) continue
		val r = linearChannel[(pixel shr 16) and 0xFF]
		val g = linearChannel[(pixel shr 8) and 0xFF]
		val b = linearChannel[pixel and 0xFF]
		val y = 0.2126 * r + 0.7152 * g + 0.0722 * b
		val lStar = if (y > 216.0 / 24389.0) 116.0 * cbrt(y) - 16.0 else 24389.0 / 27.0 * y
		// Oklab's a and b, for the chroma only.
		val l = cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b)
		val m = cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b)
		val s = cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b)
		val a = 1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s
		val bb = 0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s
		val weight = a * a + bb * bb + GREY_WEIGHT
		count++
		lightnessSum += lStar
		colourSum += lStar * weight
		weightSum += weight
	}
	if (count == 0) return null
	return ((lightnessSum / count + colourSum / weightSum) / 2.0).toFloat()
}

/** The vivid rule's bounds: below them a swatch is washed out, near black or near white. */
private fun SwatchSample.isColourful(): Boolean = saturation >= 0.25f && lightness in 0.25f..0.85f

/**
 * A cover's [CoverSeeds] from its palette's [swatches] (kmpalette's default filter) and what its
 * pixels say ([reading], [readCover]); null when neither gives anything.
 *
 * With a reading, the page is the reading's: its side, its field as [CoverSeeds.dominant], its wash.
 * The accent on a dark page is the palette's [CoverSeeds.vivid], as it always was (the owner judged
 * dark covers' accents right, and the pixels' rule, with no skin-tone filter, put faces in), unless
 * vivid is the page's own colour: We Need Each Other's purple field on a purple page, where the
 * yellow ship is the accent. On a light page, and on a dark one without a vivid, the reading's accent.
 * A cover all black gives no swatches at all and is still a reading: a black page.
 *
 * Without a reading (no opaque pixel), the palette alone, as before tone existed.
 */
fun deriveCoverSeeds(swatches: List<SwatchSample>, reading: CoverReading? = null): CoverSeeds? {
	val vivid = swatches
		.filter { it.isColourful() }
		// By family here too, and for the same reason: the accent should be the colour the
		// artwork is known by, not whichever shade of it quantisation happened to keep whole.
		.dominantByColorFamily()
	if (reading == null) {
		val dominant = swatches.dominantByColorFamily() ?: return null
		return CoverSeeds(dominant = dominant.rgb, vivid = vivid?.rgb)
	}
	val vividIsPage = vivid != null && reading.fieldKind == FieldKind.HUE && reading.fieldHue != null &&
		oklabHueChroma(vivid.rgb).let { (hue, chroma) -> chroma >= NEUTRAL_CHROMA && hueGap(hue, reading.fieldHue) <= ACCENT_HUE_GAP }
	val accent = if (reading.dark && vivid != null && !vividIsPage) vivid.rgb else reading.accent
	return CoverSeeds(
		dominant = reading.field,
		vivid = vivid?.rgb,
		accent = accent,
		tone = reading.tone,
		dark = reading.dark,
		wash = reading.wash,
		washL = reading.washL
	)
}

/** Shortest distance between two hues on the colour wheel, in degrees (0-180). */
private fun hueDistance(a: Float, b: Float): Float {
	val d = abs(a - b) % 360f
	return if (d > 180f) 360f - d else d
}

/** Beyond this many degrees apart, two swatches are not the same colour to the eye. */
private const val HUE_FAMILY_DEGREES = 45f

/** Below this saturation a swatch has no meaningful hue, and belongs to the greyscale family. */
private const val NEUTRAL_SATURATION = 0.12f

/**
 * The leading swatch of the most populous colour FAMILY — what a person would say the artwork
 * "is" — rather than the single most populous swatch.
 *
 * `Palette.dominantSwatch` is the biggest *quantisation box*, which is not the same thing and on
 * real sleeves is often not even close. Median cut subdivides crowded regions, so a colour that
 * covers half the cover in a hundred shades is split across half a dozen boxes and each one is
 * small, while a flat minority area — a border, a patch of sky — survives whole and wins. Measured
 * on Yo La Tengo's `I Can Hear the Heart Beating as One`: red/magenta 43% of the sleeve, lilac
 * 43%, pale blue 13%, and `dominantSwatch` answered the blue. The page came out ice blue for a
 * cover nobody would describe as blue.
 *
 * So each swatch is scored by the population of its whole neighbourhood, weighted by how far each
 * neighbour's hue is from its own — a kernel density estimate over the hue wheel, with no bin
 * edges for a family to fall across. Swatches too desaturated to have a hue are scored as one
 * greyscale family instead, which is what keeps a black-and-white cover neutral rather than
 * handing it whichever faint tint happens to survive quantisation.
 */
private fun List<SwatchSample>.dominantByColorFamily(): SwatchSample? {
	if (isEmpty()) return null
	val neutral = filter { it.saturation < NEUTRAL_SATURATION }
	val chromatic = filter { it.saturation >= NEUTRAL_SATURATION }

	val neutralScore = neutral.sumOf { it.population.toDouble() }
	// Scored once, and the winner read off the same list: computing the score in two places is two
	// chances for the comparison and the pick to disagree.
	val (best, bestScore) = chromatic
		.map { swatch ->
			swatch to chromatic.sumOf { other ->
				val weight = 1f - hueDistance(swatch.hue, other.hue) / HUE_FAMILY_DEGREES
				if (weight <= 0f) 0.0 else other.population * weight.toDouble()
			}
		}
		.maxByOrNull { it.second }
		?: (null to 0.0)

	return if (best == null || neutralScore > bestScore) {
		neutral.maxByOrNull { it.population } ?: best
	} else {
		// The family's own leading swatch: the colour to name it by, at the tone it actually is.
		chromatic
			.filter { hueDistance(it.hue, best.hue) <= HUE_FAMILY_DEGREES }
			.maxByOrNull { it.population }
	}
}
