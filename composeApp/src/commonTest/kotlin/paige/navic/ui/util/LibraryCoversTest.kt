package paige.navic.ui.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The pages the owner's own covers get ([readCover] over [LibraryCovers]), as the owner approved them
 * on 2026-10-31 (I-057): the side, what the page is made of, whether that is the cover's background,
 * and the accent's hue. The expected values are the prototype's at the same 32 px.
 */
class LibraryCoversTest {

	private data class Page(
		val dark: Boolean,
		val kind: FieldKind,
		val background: Boolean,
		val accentHue: Float,
		val fieldHue: Float? = null
	)

	private fun check(name: String, cover: IntArray, want: Page) {
		val reading = assertNotNull(readCover(cover, 32, 32), name)
		assertEquals(want.dark, reading.dark, "$name: dark (tone ${reading.tone})")
		assertEquals(want.kind, reading.fieldKind, "$name: field")
		assertEquals(want.background, reading.background, "$name: background")
		want.fieldHue?.let { assertTrue(hueGap(it, reading.fieldHue!!) <= 10f, "$name: field hue ${reading.fieldHue}, wanted $it") }
		val accent = assertNotNull(reading.accent, "$name: accent")
		val hue = oklabHueChroma(accent).first
		assertTrue(hueGap(hue, want.accentHue) <= 20f, "$name: accent hue $hue, wanted ${want.accentHue}")
	}

	@Test
	fun coversOnBlackGetABlackPage() {
		// The owner's five (2026-10-31): a black page, the cover's own colour as the accent.
		check("The Bends", LibraryCovers.theBends, Page(true, FieldKind.BLACK, true, 87f))
		check("The Red Shoes", LibraryCovers.theRedShoes, Page(true, FieldKind.BLACK, true, 28f))
		check("Live at Carnegie Hall", LibraryCovers.liveAtCarnegieHall, Page(true, FieldKind.BLACK, true, 54f))
		check("1234", LibraryCovers.n1234, Page(true, FieldKind.BLACK, true, 40f))
		check("Loss of Life", LibraryCovers.lossOfLife, Page(true, FieldKind.BLACK, true, 72f))
		check("Thank You", LibraryCovers.thankYou, Page(true, FieldKind.BLACK, true, 68f))
		check("Capablanca", LibraryCovers.capablanca, Page(true, FieldKind.BLACK, true, 265f))
		check("KID A MNESIA", LibraryCovers.kidAMnesia, Page(true, FieldKind.BLACK, true, 26f))
	}

	@Test
	fun aBlackPageIsBlack() {
		for ((name, cover) in listOf(
			"The Bends" to LibraryCovers.theBends,
			"Loss of Life" to LibraryCovers.lossOfLife,
			"Live at Carnegie Hall" to LibraryCovers.liveAtCarnegieHall
		)) {
			val reading = readCover(cover, 32, 32)!!
			// The black's own lightness (The Bends' is L* 6.9), not a charcoal at 17.
			assertTrue(reading.washL <= 8f, "$name: page L* ${reading.washL}")
			// No navy cast on Carnegie Hall's blacks, no brown or olive on the others. (The cast is cut to
			// 0.015; rounding to 8-bit sRGB this dark adds a little.)
			val (_, chroma) = oklabHueChroma(reading.wash)
			assertTrue(chroma <= 0.02f, "$name: wash chroma $chroma")
		}
	}

	@Test
	fun aColouredBackgroundIsThePageAndNotTheAccent() {
		// Heavy Is the Crown: the sky holds the frame; the sweaters' pink is the accent.
		check("Heavy Is the Crown", LibraryCovers.heavyIsTheCrown, Page(true, FieldKind.HUE, true, 345f, fieldHue = 256f))
		check("We Need Each Other", LibraryCovers.weNeedEachOther, Page(true, FieldKind.HUE, true, 91f, fieldHue = 296f))
		check("Holy Hive", LibraryCovers.holyHive, Page(true, FieldKind.HUE, true, 264f, fieldHue = 324f))
	}

	@Test
	fun aLightCoversAccentIsWhatStandsOutNotItsField() {
		check("MOTTAINAI", LibraryCovers.mottainai, Page(false, FieldKind.HUE, true, 31f, fieldHue = 92f))
		check("Collection", LibraryCovers.collection, Page(false, FieldKind.HUE, true, 19f, fieldHue = 106f))
		check("Sweet Exorcist", LibraryCovers.sweetExorcist, Page(false, FieldKind.HUE, false, 28f, fieldHue = 242f))
		check("Fires in Heaven", LibraryCovers.firesInHeaven, Page(false, FieldKind.HUE, true, 64f, fieldHue = 234f))
		check("Mahjong Room", LibraryCovers.mahjongRoom, Page(false, FieldKind.WHITE, false, 28f))
		check("Lookaftering", LibraryCovers.lookaftering, Page(false, FieldKind.WHITE, false, 56f))
	}

	@Test
	fun aWhiteGroundGivesALightPage() {
		check("DS2", LibraryCovers.ds2, Page(false, FieldKind.WHITE, false, 333f))
		check("Antisocialites", LibraryCovers.antisocialites, Page(false, FieldKind.WHITE, false, 272f))
		// Black line art on white: black holds as much of the frame as the white, but far less of the cover.
		check("The Virgin Suicides", LibraryCovers.theVirginSuicides, Page(false, FieldKind.WHITE, false, 69f))
		val ds2 = readCover(LibraryCovers.ds2, 32, 32)!!
		assertTrue(ds2.washL >= 90f, "DS2 page L* ${ds2.washL}")
	}

	@Test
	fun aDarkPhotoAboveAWhiteStripStaysDark() {
		check("Gran Turismo", LibraryCovers.granTurismo, Page(true, FieldKind.BLACK, false, 66f))
	}
}
