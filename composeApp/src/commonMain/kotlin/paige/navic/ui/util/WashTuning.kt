package paige.navic.ui.util

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import androidx.compose.ui.graphics.lerp
import kotlin.math.abs
import kotlin.math.cbrt

/**
 * The page wash's numbers, per page brightness (I-043 Task 8). [coverAmbientGradient] reads the
 * mixes, `CoverAmbientBackground` the alphas and the artwork scrim. `WashTuningTest` holds the
 * dark tuning to what it always was and the light one to its depth and contrast rules.
 */
data class WashTuning(
	/** The top colour is the seed moved this far toward black (dark) or white (light). */
	val topMix: Float,
	/** The bottom colour is the seed moved this far toward black (dark) or white (light)... */
	val bottomMix: Float,
	/**
	 * ...unless this is above 0: then the bottom sits this much Oklab lightness below the TOP,
	 * stepped toward a deeper shade of the seed, so every cover gets the same depth.
	 */
	val bottomDepth: Float,
	val topAlpha: Float,
	/** A middle stop's alpha; null draws the gradient with two stops, as it always was. */
	val midAlpha: Float?,
	val bottomAlpha: Float,
	/** Laid over the blurred artwork, under the gradient. */
	val artScrimColor: Color,
	val artScrimAlpha: Float,
	/**
	 * Light pages: neither stop is darker than this relative luminance. The top is lifted toward
	 * white and the bottom's depth cut short to keep them above it, so a light page whose seed was
	 * pulled toward a dark accent stays readable. 0 for dark pages: nothing is clamped.
	 */
	val minLuminance: Float = 0f
)

private val DarkWash = WashTuning(
	topMix = 0.22f,
	bottomMix = 0.45f,
	bottomDepth = 0f,
	topAlpha = 0.82f,
	midAlpha = null,
	bottomAlpha = 0.92f,
	artScrimColor = Color.Black,
	artScrimAlpha = 0.4f
)

/**
 * Light pages: the top in the cover's own field tone (the seed moved 0.28 toward white, about as
 * close to the cover as the old 0.34), the bottom one fixed Oklab lightness step (0.08, about 9 L*)
 * deeper in the cover's hue, so a light page has the depth a dark one has instead of the old 3 to
 * 9 L*. Over the blurred artwork lifted by a white scrim, so a dark band in it can never pull the
 * page below a mid grey, and nearly opaque, so the artwork reads as texture.
 *
 * An earlier tuning (I-043 Task 8, 2026-10-19) had a near-white top (0.92) under a thinner
 * gradient. Its contrast test took mid-tone seeds (L* 40 to 87) that a light page never gets, and
 * only a near-white top passed them; on real covers the page then came out paler than the cards
 * on it, whose surfaces are seeded from the same field colour. `WashTuningTest` now takes the
 * seeds a light page can get: a light cover's dominant is at least luminance 0.5, and the wash seed
 * is that pulled 35% toward the vivid swatch, which can reach 0.22. [WashTuning.minLuminance] keeps
 * those readable; a typical light cover (seed luminance 0.6 and up) keeps the full depth.
 */
private val LightWash = WashTuning(
	topMix = 0.28f,
	bottomMix = 0f,
	bottomDepth = 0.08f,
	topAlpha = 0.85f,
	midAlpha = 0.90f,
	bottomAlpha = 1f,
	artScrimColor = Color.White,
	artScrimAlpha = 0.43f,
	minLuminance = 0.5f
)

fun washTuning(isDark: Boolean): WashTuning = if (isDark) DarkWash else LightWash

/**
 * Where a page's wash top sits, CIE L*, for a cover of [tone] ([coverTone]): the page follows how
 * light the cover reads, inside the band its text stays readable in. The owner's covers of
 * 2026-10-28 are the cases: mid covers (L* 42 to 71) all landed at an extreme, a dark page at 21 to
 * 31 or a light one at 76 to 87.
 *
 * - Dark: from [DARK_TOP_MIN] for a black cover up to [DARK_TOP_MAX] at the split. Not higher:
 *   secondary text (L* 80) must keep 4.5:1 over the composite with white artwork under it
 *   (`WashTuningTest`).
 * - Light: from [LIGHT_TOP_MIN] at the split (the light floor, luminance 0.5) to [LIGHT_TOP_MAX] for
 *   a white cover, which leaves room for the bottom's depth step.
 */
fun washTopLstar(tone: Float, isDark: Boolean): Float =
	if (isDark) {
		DARK_TOP_MIN + (DARK_TOP_MAX - DARK_TOP_MIN) * (tone / PAGE_TONE_SPLIT).coerceIn(0f, 1f)
	} else {
		LIGHT_TOP_MIN + (LIGHT_TOP_MAX - LIGHT_TOP_MIN) *
			((tone - PAGE_TONE_SPLIT) / (100f - PAGE_TONE_SPLIT)).coerceIn(0f, 1f)
	}

const val DARK_TOP_MIN = 8f
const val DARK_TOP_MAX = 28f
const val LIGHT_TOP_MIN = 76f
const val LIGHT_TOP_MAX = 94f

/**
 * [seed] moved toward black or white, keeping its hue, so that [coverAmbientGradient]'s top lands at
 * [washTopLstar] for [tone]. The gradient's top is the seed moved [WashTuning.topMix] toward black
 * (dark) or white (light) in Oklab, where lightness moves linearly, so the seed's lightness is solved
 * for directly. The target is an L* turned into Oklab lightness as a grey's (the cube root of its
 * luminance): exact for a grey and close for a colour, and the light floor still applies after.
 */
fun seedForTone(seed: Color, tone: Float, isDark: Boolean): Color {
	val tuning = washTuning(isDark)
	val top = oklabLightnessOfLstar(washTopLstar(tone, isDark))
	val wanted = (if (isDark) top / (1f - tuning.topMix) else (top - tuning.topMix) / (1f - tuning.topMix))
		.coerceIn(0f, 1f)
	val current = seed.convert(ColorSpaces.Oklab).red
	if (abs(wanted - current) < 1e-4f) return seed
	return if (wanted > current) lerp(seed, Color.White, (wanted - current) / (1f - current))
	else lerp(seed, Color.Black, (current - wanted) / current)
}

/** A grey's Oklab lightness at CIE [lStar]: the cube root of its relative luminance. */
private fun oklabLightnessOfLstar(lStar: Float): Float {
	val y = if (lStar > 8f) ((lStar + 16f) / 116f).let { it * it * it } else lStar * 27f / 24389f
	return cbrt(y)
}
