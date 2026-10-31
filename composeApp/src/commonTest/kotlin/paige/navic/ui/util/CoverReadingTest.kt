package paige.navic.ui.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What a cover's pixels say about its page ([readCover]), one rule at a time, on drawn covers. */
class CoverReadingTest {

	private val size = 40

	/** A [size]-square cover: [paint] gives each pixel's RGB from its x and y. */
	private fun cover(paint: (x: Int, y: Int) -> Int) =
		IntArray(size * size) { (0xFF shl 24) or paint(it % size, it / size) }

	private fun inDisc(x: Int, y: Int, radius: Int) = (x - size / 2) * (x - size / 2) + (y - size / 2) * (y - size / 2) <= radius * radius

	private fun hueOf(argb: Int) = oklabHueChroma(argb).first

	private val black = 0x050505
	private val white = 0xF4F4F2
	private val yellow = 0xF0D030
	private val red = 0xD02020
	private val sky = 0x7EA8D8
	private val cream = 0xF6E6A8
	private val pink = 0xE04890

	@Test
	fun nothingOpaqueIsNoReading() {
		assertNull(readCover(IntArray(16), 4, 4))
		assertNull(readCover(IntArray(3), 2, 2))
	}

	@Test
	fun aDiscOnBlackGetsABlackPageAndTheDiscsColour() {
		// A record on black (Thank You, Capablanca): black holds the frame, so it is the background.
		val reading = assertNotNull(readCover(cover { x, y -> if (inDisc(x, y, 16)) yellow else black }, size, size))
		assertTrue(reading.dark)
		assertTrue(reading.background)
		assertEquals(FieldKind.BLACK, reading.fieldKind)
		assertEquals(WASH_DARK_FLOOR, reading.washL)
		assertTrue(hueGap(hueOf(reading.accent!!), hueOf(0xFF000000.toInt() or yellow)) < 10f)
	}

	@Test
	fun blackIsTheBackgroundEvenWhenTheToneReadsLight() {
		// The Bends: pale colours bigger than the black behind them, the black at the top and sides.
		val reading = readCover(
			cover { x, y ->
				when {
					y < 6 || ((x < 5 || x >= 35) && y < 20) -> black
					x < 13 -> cream
					x < 27 -> 0xF4C8C8
					else -> 0xD0F0C8
				}
			},
			size, size
		)!!
		assertTrue(reading.tone >= PAGE_TONE_SPLIT, "tone ${reading.tone}")
		assertTrue(reading.dark)
		assertEquals(FieldKind.BLACK, reading.fieldKind)
	}

	@Test
	fun lineArtOnWhiteStaysLight() {
		// Black strokes at every edge, but white is most of the cover (The Virgin Suicides).
		val reading = readCover(cover { x, y -> if (x % 4 == 0 || y % 5 == 0) black else white }, size, size)!!
		assertFalse(reading.dark)
		assertFalse(reading.background)
		assertEquals(FieldKind.WHITE, reading.fieldKind)
	}

	@Test
	fun aVeryDarkPixelIsBlackWhateverItsCast() {
		// Live at Carnegie Hall's blacks have a faint blue cast: read as a colour, the page went navy.
		val navyBlack = 0x02031A
		val reading = readCover(cover { x, y -> if (inDisc(x, y, 14)) 0xE0C090 else navyBlack }, size, size)!!
		assertEquals(FieldKind.BLACK, reading.fieldKind)
		// The cast is cut to 0.015 Oklab chroma; 8-bit sRGB at L* 1 rounds it to 0.0164.
		assertTrue(oklabHueChroma(reading.wash).second <= 0.02f)
	}

	@Test
	fun aColouredBackgroundIsThePageAndTheAccentIsSomethingElse() {
		// Heavy Is the Crown: a sky round two dark figures with a little pink on them.
		val reading = readCover(
			cover { x, y ->
				when {
					y > 14 && x in 8..31 && x % 6 == 0 && y % 4 == 0 -> pink
					y > 14 && x in 8..31 -> 0x181818
					else -> sky
				}
			},
			size, size
		)!!
		assertTrue(reading.background)
		assertEquals(FieldKind.HUE, reading.fieldKind)
		assertTrue(hueGap(reading.fieldHue!!, hueOf(0xFF000000.toInt() or sky)) < 15f)
		assertTrue(hueGap(hueOf(reading.accent!!), hueOf(0xFF000000.toInt() or pink)) < 15f)
	}

	@Test
	fun aLightCoversAccentIsNotItsField() {
		// MOTTAINAI: a cream field with a thin red frame. The cream is the most colourful by area.
		val reading = readCover(
			cover { x, y -> if ((x in 6..33 && (y == 6 || y == 7 || y == 32 || y == 33)) || (y in 6..33 && (x == 6 || x == 7 || x == 32 || x == 33))) red else cream },
			size, size
		)!!
		assertFalse(reading.dark)
		assertEquals(FieldKind.HUE, reading.fieldKind)
		assertTrue(hueGap(hueOf(reading.accent!!), hueOf(0xFF000000.toInt() or red)) < 15f)
	}

	@Test
	fun aWhiteGroundGivesANearWhitePage() {
		// DS2: a white ground with a dark coloured cloud in it; the old rule gave a grey-blue L* 77.
		val reading = readCover(cover { x, y -> if (inDisc(x, y, 11)) 0x5A3070 else white }, size, size)!!
		assertFalse(reading.dark)
		assertEquals(FieldKind.WHITE, reading.fieldKind)
		assertTrue(reading.washL >= 90f, "washL ${reading.washL}")
	}

	@Test
	fun aCoverWithNoColourHasNoAccent() {
		val reading = readCover(cover { x, _ -> if (x < 20) 0x303030 else 0xC8C8C8 }, size, size)!!
		assertNull(reading.accent)
		assertNull(reading.pageHue)
	}

	@Test
	fun aGreyFieldIsTintedTowardTheCoversMainColour() {
		val grey = 0x808080
		val reading = readCover(cover { x, y -> if (inDisc(x, y, 12)) 0x3060D0 else grey }, size, size)!!
		assertEquals(FieldKind.GREY, reading.fieldKind)
		assertTrue(oklabHueChroma(reading.wash).second > 0.02f, "a grey field is tinted")
		assertNotNull(reading.pageHue)
	}

	@Test
	fun theWashsLightnessStaysInTheReadableBands() {
		for (paint in listOf<(Int, Int) -> Int>({ _, _ -> 0x000000 }, { _, _ -> 0xFFFFFF }, { x, _ -> if (x < 20) 0x404040 else 0xA0A0A0 })) {
			val reading = readCover(cover(paint), size, size)!!
			if (reading.dark) assertTrue(reading.washL in WASH_DARK_FLOOR..WASH_DARK_CEILING, "dark ${reading.washL}")
			else assertTrue(reading.washL in LIGHT_TOP_MIN..LIGHT_TOP_MAX, "light ${reading.washL}")
		}
	}
}
