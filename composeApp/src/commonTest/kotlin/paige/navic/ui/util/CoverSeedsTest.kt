package paige.navic.ui.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The colours a cover is reduced to ([deriveCoverSeeds]), from its palette's swatches. */
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

	@Test
	fun aDarkCoversAccentIsItsVivid() {
		val navy = swatch(0xFF050A1A.toInt(), 228f, 500, saturation = 0.5f, lightness = 0.05f)
		val yellow = swatch(0xFFE8D020.toInt(), 53f, 120, saturation = 0.8f, lightness = 0.55f)
		val red = swatch(0xFFD02020.toInt(), 0f, 60, saturation = 0.73f, lightness = 0.47f)
		val seeds = deriveCoverSeeds(listOf(navy, yellow), unfiltered = listOf(navy, yellow, red))!!
		assertEquals(yellow.rgb, seeds.accent)
	}

	@Test
	fun aLightCoversAccentIsTheColourThatStandsOutNotTheField() {
		// Mahjong Room's palette, near enough: a pale blue field with a red keyboard on it.
		val field = swatch(0xFFC0D8E0.toInt(), 195f, 460, saturation = 0.4f, lightness = 0.82f)
		val keyboard = swatch(0xFFD82820.toInt(), 3f, 170, saturation = 0.74f, lightness = 0.49f)
		val palette = listOf(field, keyboard)
		val seeds = deriveCoverSeeds(palette, unfiltered = palette)!!
		assertEquals(field.rgb, seeds.dominant)
		// The wash is unchanged: the field is still the most populous colourful family.
		assertEquals(field.rgb, seeds.vivid)
		assertEquals(keyboard.rgb, seeds.accent)
	}

	@Test
	fun aLightCoversAccentIsChosenFromThePaletteWithBrownsInIt() {
		// Lookaftering, near enough: a brown hare on cream paper. kmpalette's default filter drops
		// the hare (hue 10 to 37, saturation up to 0.82), so only the unfiltered palette has it.
		val cream = swatch(0xFFD8D8C8.toInt(), 60f, 400, saturation = 0.17f, lightness = 0.82f)
		val tan = swatch(0xFFD0C098.toInt(), 43f, 200, saturation = 0.37f, lightness = 0.71f)
		val hare = swatch(0xFFA05010.toInt(), 27f, 120, saturation = 0.8f, lightness = 0.35f)
		val seeds = deriveCoverSeeds(listOf(cream, tan), unfiltered = listOf(cream, tan, hare))!!
		assertEquals(tan.rgb, seeds.vivid)
		// Not the tan, though it is within the hare's 45 degree family and has more pixels.
		assertEquals(hare.rgb, seeds.accent)
	}

	@Test
	fun theAccentRuleFollowsTheTonesPageNotTheDominant() {
		// A near-black dominant with a peach on it, and a red only the unfiltered palette has: dark
		// by the dominant (accent = vivid, the peach), but the cover reads light (tone 60), so the
		// light-cover rule picks the most salient colour, the red.
		val photo = swatch(0xFF202020.toInt(), 0f, 1400, saturation = 0f, lightness = 0.13f)
		val peach = swatch(0xFFF8A058.toInt(), 27f, 350, saturation = 0.92f, lightness = 0.66f)
		val text = swatch(0xFFD02020.toInt(), 0f, 400, saturation = 0.73f, lightness = 0.47f)
		val dark = deriveCoverSeeds(listOf(photo, peach), unfiltered = listOf(photo, peach, text))!!
		assertEquals(peach.rgb, dark.accent)
		val light = deriveCoverSeeds(listOf(photo, peach), unfiltered = listOf(photo, peach, text), tone = 60f)!!
		assertEquals(60f, light.tone)
		assertEquals(text.rgb, light.accent)
	}
}
