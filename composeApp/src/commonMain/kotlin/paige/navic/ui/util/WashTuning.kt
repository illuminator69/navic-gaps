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
	val artScrimAlpha: Float
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
 * Light pages, from the owner's approved mockups (2026-10-19) as the contrast test allowed them:
 * the cover's blurred artwork LIFTED by a white scrim (so a dark band in it can never be darker
 * than a mid grey), a thin gradient over it (half at the top, 0.6 at mid height, opaque at the
 * bottom) so the artwork shows, and a top near white with a bottom one fixed lightness step deeper
 * in the cover's hue (L* about 96 to 84). They used to read flat: the old bottom was only 3 to 9
 * L* below the top. The mockup's thinner gradient over black-scrimmed artwork fell to 3.1:1 for
 * secondary text over a dark band; the white scrim is what pays for it. Smaller white scrims pass
 * only with a whiter top (0.40 needs topMix 0.96, top L* 97 to 99).
 */
private val LightWash = WashTuning(
	topMix = 0.92f,
	bottomMix = 0f,
	bottomDepth = 0.10f,
	topAlpha = 0.50f,
	midAlpha = 0.60f,
	bottomAlpha = 1f,
	artScrimColor = Color.White,
	artScrimAlpha = 0.43f
)

fun washTuning(isDark: Boolean): WashTuning = if (isDark) DarkWash else LightWash
