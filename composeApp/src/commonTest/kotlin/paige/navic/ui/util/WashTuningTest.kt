package paige.navic.ui.util

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import kotlin.math.cbrt
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The page wash's numbers ([washTuning], [coverAmbientGradient]): dark pages exactly as they were,
 * light pages in the cover's own tone, with depth, and readable over the darkest artwork.
 */
class WashTuningTest {

	/** Twelve seeds, hue every 30 degrees, saturation 0.6, lightness 0.5: for the dark tuning. */
	private val seeds = (0 until 12).map { hsl(it * 30f, 0.6f, 0.5f) }

	/**
	 * The seeds a LIGHT page can get. A page is light when the cover's dominant is at least
	 * luminance 0.5, and the wash seed is that pulled 35% toward the vivid swatch, which reaches
	 * down to about 0.22. Hue every 30 degrees, saturation 0.3 and 0.7, at four luminances.
	 * The first light tuning was held to mid-tone seeds no light page gets (L* 40 to 87), and only
	 * a near-white top passed them: the page came out paler than the cards on it.
	 */
	private val lightSeeds = (0 until 12).flatMap { h ->
		listOf(0.3f, 0.7f).flatMap { s -> listOf(0.22f, 0.35f, 0.5f, 0.8f).map { y -> hslAtLuminance(h * 30f, s, y) } }
	}

	/** A typical light cover's seeds: luminance 0.6 and up. These keep the full depth. */
	private val typicalLightSeeds = (0 until 12).flatMap { h ->
		listOf(0.3f, 0.7f).flatMap { s -> listOf(0.6f, 0.8f).map { y -> hslAtLuminance(h * 30f, s, y) } }
	}

	/** The colour at hue [h] and saturation [s] whose relative luminance is [y]. */
	private fun hslAtLuminance(h: Float, s: Float, y: Float): Color {
		var low = 0f
		var high = 1f
		repeat(40) {
			val mid = (low + high) / 2
			if (hsl(h, s, mid).luminance() < y) low = mid else high = mid
		}
		return hsl(h, s, high)
	}

	private fun hsl(h: Float, s: Float, l: Float): Color {
		val c = (1 - kotlin.math.abs(2 * l - 1)) * s
		val x = c * (1 - kotlin.math.abs((h / 60f) % 2 - 1))
		val m = l - c / 2
		val (r, g, b) = when {
			h < 60 -> Triple(c, x, 0f); h < 120 -> Triple(x, c, 0f); h < 180 -> Triple(0f, c, x)
			h < 240 -> Triple(0f, x, c); h < 300 -> Triple(x, 0f, c); else -> Triple(c, 0f, x)
		}
		return Color(r + m, g + m, b + m)
	}

	/** CIE L* from relative luminance. */
	private fun lstar(color: Color): Float {
		val y = color.luminance()
		return if (y > 216f / 24389f) 116f * cbrt(y) - 16f else 24389f / 27f * y
	}

	/** WCAG contrast ratio. */
	private fun contrast(a: Color, b: Color): Float {
		val la = a.luminance(); val lb = b.luminance()
		return (max(la, lb) + 0.05f) / (min(la, lb) + 0.05f)
	}

	/** [top] at [alpha] over [below], blended in gamma-encoded sRGB, as `drawRect` blends. */
	private fun over(below: Color, top: Color, alpha: Float) = Color(
		below.red * (1 - alpha) + top.red * alpha,
		below.green * (1 - alpha) + top.green * alpha,
		below.blue * (1 - alpha) + top.blue * alpha
	)

	private val secondaryText = Color(0xFF424242) // L* 26
	private val primaryText = Color(0xFF161616) // L* 7

	@Test
	fun theDarkTuningIsUnchanged() {
		val dark = washTuning(isDark = true)
		assertEquals(0.22f, dark.topMix)
		assertEquals(0.45f, dark.bottomMix)
		assertEquals(0f, dark.bottomDepth)
		assertEquals(0.82f, dark.topAlpha)
		assertNull(dark.midAlpha)
		assertEquals(0.92f, dark.bottomAlpha)
		assertEquals(Color.Black, dark.artScrimColor)
		assertEquals(0.4f, dark.artScrimAlpha)
		// And the gradient is today's formula, colour for colour.
		for (seed in seeds) {
			assertEquals(lerp(seed, Color.Black, 0.22f) to lerp(seed, Color.Black, 0.45f), coverAmbientGradient(seed, true))
		}
	}

	@Test
	fun aLightPageHasDepth() {
		for (seed in typicalLightSeeds) {
			val (top, bottom) = coverAmbientGradient(seed, false)
			val span = lstar(top) - lstar(bottom)
			assertTrue(span >= 8f, "seed $seed: L* span $span")
		}
	}

	@Test
	fun aLightPageKeepsTheCoversTone() {
		// The top stays the cover's own field colour, not white: never more than this far above it.
		for (seed in typicalLightSeeds) {
			val (top, _) = coverAmbientGradient(seed, false)
			val lift = lstar(top) - lstar(seed)
			assertTrue(lift <= 8f, "seed $seed: top is $lift L* above the seed")
		}
	}

	@Test
	fun aLightPageStaysReadable() {
		for (seed in lightSeeds) {
			val (top, bottom) = coverAmbientGradient(seed, false)
			assertTrue(top.luminance() >= 0.5f - 1e-3f, "seed $seed: top luminance ${top.luminance()}")
			assertTrue(bottom.luminance() >= 0.5f - 1e-3f, "seed $seed: bottom luminance ${bottom.luminance()}")
		}
	}

	@Test
	fun aLightPageStaysReadableOverTheDarkestArtwork() {
		val light = washTuning(isDark = false)
		val midAlpha = light.midAlpha ?: (light.topAlpha + light.bottomAlpha) / 2
		for (artwork in listOf(Color.Black, Color(0.25f, 0.25f, 0.25f))) {
			val base = over(artwork, light.artScrimColor, light.artScrimAlpha)
			for (seed in lightSeeds) {
				val (top, bottom) = coverAmbientGradient(seed, false)
				// Top band, mid band, and the bottom, each as the page draws it.
				val bands = listOf(
					"top" to over(base, top, light.topAlpha),
					"mid" to over(base, lerp(top, bottom, 0.5f), midAlpha),
					"bottom" to over(base, bottom, light.bottomAlpha)
				)
				for ((band, colour) in bands) {
					for ((name, text) in listOf("secondary" to secondaryText, "primary" to primaryText)) {
						val ratio = contrast(text, colour)
						assertTrue(ratio >= 4.5f, "$name text over $artwork artwork, $band band, seed $seed: $ratio")
					}
				}
			}
		}
	}
}
