package paige.navic.ui.util

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/*
 * A cover tile is a tinted box from its first frame, and the tint is a pure function of the name, so a
 * tile is the same colour every time it is drawn and two different albums are usually two different
 * colours. The real cover then fades in over it (CoverArt, `placeholderName`).
 */
class TintedPlaceholderTest {

	@Test
	fun theSameNameAlwaysGivesTheSameHue() {
		assertEquals(placeholderHue("Salt Lines"), placeholderHue("Salt Lines"))
		assertEquals(placeholderHue(""), placeholderHue(""))
	}

	@Test
	fun hueIsInRange() {
		val names = listOf(
			"", "a", "Salt Lines", "Quiet Engines", "Glasshouse", "Low Tide Radio",
			"Ünïcödé — ✓", "日本語のタイトル", "x".repeat(200), " ".repeat(5)
		)
		names.forEach { name ->
			val hue = placeholderHue(name)
			assertTrue(hue in 0..359, "hue $hue for '${name.take(20)}'")
		}
	}

	@Test
	fun differentNamesUsuallyDiffer() {
		assertNotEquals(placeholderHue("Salt Lines"), placeholderHue("Quiet Engines"))
	}

	@Test
	fun caseDoesNotMatter() {
		assertEquals(placeholderHue("Salt Lines"), placeholderHue("SALT LINES"))
		assertEquals(placeholderHue("Salt Lines"), placeholderHue("salt lines"))
	}

	@Test
	fun hueIsFnv1aOverTheLowerCasedNameModuloThreeSixty() {
		// Pinned against reference values, so the hash cannot drift into some other function and
		// recolour every tile in the library. 32-bit FNV-1a: the empty string is the offset basis
		// 0x811C9DC5 (hue 61), "a" is 0xE40C292C (hue 340), "salt lines" is 0xA9D8397E (hue 118).
		assertEquals((0x811C9DC5L % 360).toInt(), placeholderHue(""))
		assertEquals(61, placeholderHue(""))
		assertEquals((0xE40C292CL % 360).toInt(), placeholderHue("a"))
		assertEquals(340, placeholderHue("a"))
		assertEquals((0xA9D8397EL % 360).toInt(), placeholderHue("Salt Lines"))
		assertEquals(118, placeholderHue("Salt Lines"))
	}

	@Test
	fun theGradientRunsToAHueThirtyDegreesOnAndDarker() {
		val hue = placeholderHue("Salt Lines")
		val (from, to) = placeholderColors("Salt Lines")
		assertEquals(Color.hsl(hue.toFloat(), 0.30f, 0.32f), from)
		assertEquals(Color.hsl(((hue + 30) % 360).toFloat(), 0.30f, 0.20f), to)
	}

	@Test
	fun theHueWrapsPastThreeSixty() {
		// 0xE40C292C gives hue 340, so the far corner is 370 -> 10, not 370.
		val (_, to) = placeholderColors("a")
		assertEquals(Color.hsl(10f, 0.30f, 0.20f), to)
	}
}
