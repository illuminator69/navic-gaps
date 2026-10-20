package paige.navic.ui.components.sheets

import kotlin.test.Test
import kotlin.test.assertEquals

/*
 * P1: the New sheet's rows, in the board's order. "Add from a link" needs lb-bot to say what the link
 * is, so without the lb-bot layer the row is absent (not greyed: a control claims an effect only if
 * it happens), and the sheet is the two rows that need nothing. (P5 adds Custom radio before it.)
 */
class NewSheetItemsTest {

	@Test
	fun withLbBotTheOrderIsPlaylistSmartCustomRadioLink() {
		assertEquals(
			listOf(NewSheetItem.Playlist, NewSheetItem.SmartPlaylist, NewSheetItem.CustomRadio, NewSheetItem.AddFromLink),
			newSheetItems(lbBotAvailable = true)
		)
	}

	@Test
	fun withoutLbBotThereIsNoLinkRowButStillACustomRadio() {
		assertEquals(
			listOf(NewSheetItem.Playlist, NewSheetItem.SmartPlaylist, NewSheetItem.CustomRadio),
			newSheetItems(lbBotAvailable = false)
		)
	}
}
