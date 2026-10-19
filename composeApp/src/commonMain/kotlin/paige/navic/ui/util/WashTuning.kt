package paige.navic.ui.util

import androidx.compose.ui.graphics.Color

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
