package paige.navic.ui.util

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.math.abs

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
 * The two colours the engine keeps from a cover's palette, packed ARGB. Everything else a page
 * wears (the wash seed, the accent, the brightness, the Material scheme) is derived from these in
 * `rememberCoverColorScheme`, so these two are all that has to be known, or stored, about a cover.
 *
 * - [dominant]: the cover's most populous colour family, by its leading swatch — what a person
 *   would say the artwork "is". It decides page brightness and seeds the scheme's neutrals.
 * - [vivid]: the most populous COLOURFUL family (washed-out, near-black and near-white swatches
 *   rejected); null for a genuinely greyscale cover, which then stays neutral rather than having a
 *   hue invented for it. The page WASH is pulled toward it.
 * - [accent]: what the scheme's `primary` (buttons, the play bar, links) is seeded from. On a dark
 *   cover it is [vivid]. On a light one it is the most SALIENT colourful family instead (see
 *   [salientFamily]), because there the most populous one is usually the pale field itself.
 */
data class CoverSeeds(val dominant: Int, val vivid: Int?, val accent: Int? = vivid)

/** The vivid rule's bounds: below them a swatch is washed out, near black or near white. */
private fun SwatchSample.isColourful(): Boolean = saturation >= 0.25f && lightness in 0.25f..0.85f

/**
 * A cover's [CoverSeeds] from its swatches; null when quantisation gave none.
 *
 * [swatches] is the palette as kmpalette builds it by default, and decides [CoverSeeds.dominant]
 * and [CoverSeeds.vivid] exactly as they always were (page brightness and the wash are unchanged).
 * [unfiltered] is a palette of the same image built WITHOUT the default filter's skin-tone rule
 * (it drops every hue 10 to 37 degrees with saturation up to 0.82, so browns and tans never reach
 * a palette at all): the light-cover accent is chosen from it. Measured on Vashti Bunyan's
 * `Lookaftering`, a brown hare on cream, the filter removed the whole hare.
 */
fun deriveCoverSeeds(swatches: List<SwatchSample>, unfiltered: List<SwatchSample> = swatches): CoverSeeds? {
	val dominant = swatches.dominantByColorFamily() ?: return null
	val vivid = swatches
		.filter { it.isColourful() }
		// By family here too, and for the same reason: the accent should be the colour the
		// artwork is known by, not whichever shade of it quantisation happened to keep whole.
		.dominantByColorFamily()
	// The same test `rememberCoverColorScheme` uses for page brightness.
	val lightCover = Color(dominant.rgb).luminance() >= 0.5f
	val accent = if (!lightCover) vivid else unfiltered.filter { it.isColourful() }.salientFamily() ?: vivid
	return CoverSeeds(dominant = dominant.rgb, vivid = vivid?.rgb, accent = accent?.rgb)
}

/** Shortest distance between two hues on the colour wheel, in degrees (0-180). */
private fun hueDistance(a: Float, b: Float): Float {
	val d = abs(a - b) % 360f
	return if (d > 180f) 360f - d else d
}

/** Beyond this many degrees apart, two swatches are not the same colour to the eye. */
private const val HUE_FAMILY_DEGREES = 45f

/** A salient family is named by its own hue: swatches this close, not the whole family. */
private const val ACCENT_HUE_DEGREES = 15f

/** HSL chroma, 0 to 1: how far from grey a swatch is at its lightness. */
private fun SwatchSample.chroma(): Float = (1f - abs(2f * lightness - 1f)) * saturation

/** A swatch's pull on the eye: its area, weighted by the square of its chroma. */
private fun SwatchSample.salience(): Double = population * (chroma() * chroma()).toDouble()

/**
 * The colour that catches the eye on a light cover: the hue family with the most SALIENCE
 * (population times chroma squared) rather than the most pixels, named by its most salient swatch
 * within [ACCENT_HUE_DEGREES] of the winner.
 *
 * By population, a pastel field that passes the colourful bounds simply wins on size: on
 * `Mahjong Room`, pale blue covers about 46% of the sleeve and the red keyboard 17%, so the accent
 * was the page's own pale blue and nothing on the page was red. Chroma squared ranks the keyboard first
 * (chroma 0.6 against the field's 0.15). Dark covers never needed this: their dark field fails the
 * lightness bound, so the most populous colourful family was already the detail on it. And the
 * whole family's leading swatch is not the name: within 45 degrees of a brown hare sits the cream
 * paper it is printed on, which has more pixels.
 */
private fun List<SwatchSample>.salientFamily(): SwatchSample? {
	val best = maxByOrNull { swatch ->
		sumOf { other ->
			val weight = 1f - hueDistance(swatch.hue, other.hue) / HUE_FAMILY_DEGREES
			if (weight <= 0f) 0.0 else other.salience() * weight
		}
	} ?: return null
	return filter { hueDistance(it.hue, best.hue) <= ACCENT_HUE_DEGREES }.maxByOrNull { it.salience() }
}

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
