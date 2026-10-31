package paige.navic.ui.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The colours a cover is reduced to ([deriveCoverSeeds]), from its palette's swatches and its pixels' reading. */
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

	/** A reading as [readCover] would give it, with only what [deriveCoverSeeds] looks at set. */
	private fun reading(
		dark: Boolean,
		kind: FieldKind = FieldKind.BLACK,
		fieldHue: Float? = null,
		accent: Int? = 0xFFD02020.toInt()
	) = CoverReading(
		tone = if (dark) 30f else 70f,
		dark = dark,
		field = 0xFF101010.toInt(),
		fieldKind = kind,
		fieldHue = fieldHue,
		wash = 0xFF121212.toInt(),
		washL = if (dark) WASH_DARK_FLOOR else 90f,
		accent = accent,
		pageHue = fieldHue,
		background = true
	)

	private val yellow = swatch(0xFFE8D020.toInt(), 53f, 120, saturation = 0.8f, lightness = 0.55f)

	@Test
	fun aDarkPageKeepsThePalettesVividAsItsAccent() {
		val seeds = deriveCoverSeeds(listOf(yellow), reading(dark = true))!!
		assertEquals(yellow.rgb, seeds.vivid)
		assertEquals(yellow.rgb, seeds.accent)
	}

	@Test
	fun aDarkPagesVividThatIsThePagesOwnColourGivesWayToThePixelsAccent() {
		// We Need Each Other: a purple field on a purple page; the yellow ship is the accent.
		val purple = swatch(0xFF452779.toInt(), 265f, 600, saturation = 0.5f, lightness = 0.31f)
		val purpleHue = oklabHueChroma(purple.rgb).first
		val seeds = deriveCoverSeeds(listOf(purple), reading(dark = true, kind = FieldKind.HUE, fieldHue = purpleHue, accent = yellow.rgb))!!
		assertEquals(purple.rgb, seeds.vivid)
		assertEquals(yellow.rgb, seeds.accent)
	}

	@Test
	fun aLightPageTakesThePixelsAccent() {
		val red = 0xFFD02020.toInt()
		val seeds = deriveCoverSeeds(listOf(yellow), reading(dark = false, kind = FieldKind.WHITE, accent = red))!!
		assertEquals(red, seeds.accent)
	}

	@Test
	fun theReadingDecidesThePage() {
		val r = reading(dark = true)
		val seeds = deriveCoverSeeds(listOf(yellow), r)!!
		assertEquals(r.field, seeds.dominant)
		assertEquals(r.wash, seeds.wash)
		assertEquals(r.washL, seeds.washL)
		assertEquals(r.tone, seeds.tone)
		assertEquals(true, seeds.dark)
		assertTrue(seeds.pageIsDark())
	}

	@Test
	fun aCoverAllBlackStillGetsAPage() {
		// kmpalette drops every near-black pixel, so an all-black cover has no swatches at all. It
		// used to be unextractable; it is a black page.
		val seeds = deriveCoverSeeds(emptyList(), readCover(IntArray(64) { 0xFF000000.toInt() }, 8, 8))
		assertNotNull(seeds)
		assertNull(seeds.vivid)
		assertTrue(seeds.pageIsDark())
	}

	@Test
	fun theDarkSideOverridesTheTone() {
		// The Bends reads light (tone 60) and its page is dark: black is its background.
		assertTrue(CoverSeeds(dominant = 0xFF101010.toInt(), vivid = null, tone = 60f, dark = true).pageIsDark())
	}
}
