package paige.navic.ui

import paige.navic.domain.manager.AttentionPage
import paige.navic.domain.manager.AttentionRead
import paige.navic.domain.manager.LISTENING_KIND_ALBUM
import paige.navic.domain.manager.LbDownloads
import paige.navic.domain.manager.LbGap
import paige.navic.domain.manager.LbGapTrack
import paige.navic.domain.manager.LbUnownedRow
import paige.navic.domain.manager.attentionPage
import paige.navic.domain.manager.attentionRead
import paige.navic.domain.manager.unownedRowAction
import paige.navic.ui.screens.artist.artistSheetOffersFillGaps
import paige.navic.ui.screens.collection.GapInput
import paige.navic.ui.screens.collection.GapStage
import paige.navic.ui.screens.collection.GapView
import paige.navic.ui.screens.collection.gapFillOffered
import paige.navic.ui.screens.collection.gapStage
import paige.navic.ui.screens.external.viewmodels.arrivalSearchShouldStart
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/*
 * Worbus P1, N2: with lb-bot's downloading off, every way into a download is gone and what it detects
 * stays. The album page's request box is `RequestStageTest`'s and the avatar menu `AvatarMenuItemsTest`'s;
 * these are the rest: the gaps box keeps its missing songs and loses Fill gaps, the artist page's album
 * sheet has no Fill gaps, Needs attention lists no folder to file, and Statistics' Not in library has no
 * Find sources. Each also as it is today with downloading on (an older peer is on).
 */
class DownloadsOffSurfacesTest {
	private fun track(position: Int, state: String) = LbGapTrack(position = position, title = "T$position", state = state)

	private val gap = LbGap(
		id = "g-1", album = "Soft Machinery", present = 2, total = 4, status = "ready",
		tracks = listOf(track(1, "present"), track(2, "present"), track(3, "missing"), track(4, "missing"))
	)

	private fun gapInput(view: GapView, downloadsOn: Boolean) =
		GapInput(gap, 2, null, view, committed = false, searchAsked = false, now = 100_000L, downloadsOn = downloadsOn)

	@Test
	fun gap_box_keeps_missing_tracks_drops_fill_when_off() {
		// Folded: the line still counts what is missing; the button beside it is not drawn.
		assertEquals(GapStage.Folded(2), gapStage(gapInput(GapView.FOLDED, downloadsOn = false)))
		assertFalse(gapFillOffered(LbDownloads.OFF))
		// Unfolded: the missing songs.
		val tracks = gapStage(gapInput(GapView.TRACKS, downloadsOn = false))
		assertIs<GapStage.Tracks>(tracks)
		assertEquals(listOf(3, 4), tracks.missing.map { it.position })
		// A sources view left from before (a fill tapped as the mode flipped) is the missing songs again,
		// never a search.
		assertIs<GapStage.Tracks>(gapStage(gapInput(GapView.SOURCES, downloadsOn = false)))
	}

	@Test
	fun gap_box_keeps_missing_tracks_drops_fill_when_off_unchanged_when_on() {
		assertEquals(GapStage.Folded(2), gapStage(gapInput(GapView.FOLDED, downloadsOn = true)))
		assertTrue(gapFillOffered(LbDownloads.ON))
		assertTrue(gapFillOffered(LbDownloads.UNKNOWN))
		assertEquals(GapStage.Searching, gapStage(gapInput(GapView.SOURCES, downloadsOn = true)))
	}

	@Test
	fun artist_sheet_has_no_fill_gaps_when_off() {
		assertFalse(artistSheetOffersFillGaps(hasGap = true, downloads = LbDownloads.OFF))
	}

	@Test
	fun artist_sheet_has_no_fill_gaps_when_off_unchanged_when_on() {
		assertTrue(artistSheetOffersFillGaps(hasGap = true, downloads = LbDownloads.ON))
		assertTrue(artistSheetOffersFillGaps(hasGap = true, downloads = LbDownloads.UNKNOWN))
		assertFalse(artistSheetOffersFillGaps(hasGap = false, downloads = LbDownloads.ON))
	}

	private val attention = """
		{"version":1,"serverTime":1761000000.5,
		 "counts":{"failedGaps":0,"failedRequests":0,"folders":1,"foldersUnidentified":1,"picks":0},
		 "albums":[],
		 "folders":[{"id":"abcdef012345","fileCount":11,"formats":"flac","matchLabel":"","confidence":"unknown",
		   "canConfirm":false,"groupId":"","releaseMbid":"","identified":false}],
		 "truncated":{"albums":0,"folders":0}}
	""".trimIndent()

	private fun attentionRows(downloadsOn: Boolean): AttentionPage.Rows {
		val read = attentionRead(200, attention)
		val answer = (read as AttentionRead.Ok).attention
		return attentionPage(read, answer, 1L, emptyList(), null, downloadsOn = downloadsOn) as AttentionPage.Rows
	}

	@Test
	fun attention_has_no_place_links_when_off() {
		// A folder row's only action is lb-bot's `#/place/<id>`: filing it is a download's last step.
		assertEquals(emptyList(), attentionRows(downloadsOn = false).folders)
	}

	@Test
	fun attention_has_no_place_links_when_off_unchanged_when_on() {
		assertEquals(listOf("abcdef012345"), attentionRows(downloadsOn = true).folders.map { it.id })
	}

	private val unowned = LbUnownedRow(kind = LISTENING_KIND_ALBUM, title = "Record", artist = "Artist", artistMbid = "mb-a", rgid = "rg-1", plays = 9)

	@Test
	fun statistics_no_find_sources_when_off() {
		assertNull(unownedRowAction(unowned, emptySet(), downloadsOn = false))
		// And a page opened by one before the mode flipped starts no search.
		assertFalse(arrivalSearchShouldStart(true, false, true, false, false, true, downloadsOn = false))
	}

	@Test
	fun statistics_no_find_sources_when_off_unchanged_when_on() {
		val action = assertNotNull(unownedRowAction(unowned, emptySet(), downloadsOn = true))
		assertTrue(action.findSources)
		assertTrue(arrivalSearchShouldStart(true, false, true, false, false, true, downloadsOn = true))
	}
}
