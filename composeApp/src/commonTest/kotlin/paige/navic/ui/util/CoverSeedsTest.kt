package paige.navic.ui.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The two colours a cover is reduced to ([deriveCoverSeeds]), from its palette's swatches. */
class CoverSeedsTest {

	private fun swatch(rgb: Int, hue: Float, population: Int, saturation: Float = 0.7f, lightness: Float = 0.5f) =
		SwatchSample(rgb = rgb, hue = hue, saturation = saturation, lightness = lightness, population = population)

	@Test
	fun emptySwatchesGiveNoSeeds() {
		assertNull(deriveCoverSeeds(emptyList()))
	}

	@Test
	fun theBiggestFamilyBeatsTheBiggestBox() {
		val red = swatch(0xFFC0203A.toInt(), 350f, 150)
		val seeds = deriveCoverSeeds(
			listOf(
				red,
				swatch(0xFFB81830.toInt(), 355f, 140),
				swatch(0xFFC83020.toInt(), 5f, 130),
				swatch(0xFFB0C8E8.toInt(), 210f, 160, saturation = 0.45f, lightness = 0.8f)
			)
		)!!
		assertEquals(red.rgb, seeds.dominant)
	}

	@Test
	fun aBlackAndWhiteCoverHasNoVividAndStaysNeutral() {
		val grey = swatch(0xFF808080.toInt(), 0f, 300, saturation = 0.02f, lightness = 0.5f)
		val seeds = deriveCoverSeeds(
			listOf(
				swatch(0xFF101010.toInt(), 200f, 120, saturation = 0.05f, lightness = 0.06f),
				grey,
				swatch(0xFFF0F0F0.toInt(), 40f, 200, saturation = 0.1f, lightness = 0.94f)
			)
		)!!
		assertNull(seeds.vivid)
		assertEquals(grey.rgb, seeds.dominant)
	}

	@Test
	fun vividRejectsNearBlackAndTakesTheColourfulFamily() {
		val navy = swatch(0xFF050A1A.toInt(), 228f, 500, saturation = 0.5f, lightness = 0.05f)
		val yellow = swatch(0xFFE8D020.toInt(), 53f, 120, saturation = 0.8f, lightness = 0.55f)
		val seeds = deriveCoverSeeds(listOf(navy, yellow))!!
		assertEquals(navy.rgb, seeds.dominant)
		assertEquals(yellow.rgb, seeds.vivid)
	}
}
