package paige.navic.ui

import paige.navic.domain.manager.AttentionAlbum
import paige.navic.domain.manager.AttentionCounts
import paige.navic.domain.manager.AttentionPage
import paige.navic.domain.manager.AttentionRow
import paige.navic.domain.manager.LbBotManager
import paige.navic.domain.manager.LbDownloadsState
import paige.navic.domain.manager.LbFillEntry
import paige.navic.domain.manager.attentionBadge
import paige.navic.domain.manager.toFixCount
import paige.navic.domain.manager.AttentionRead
import paige.navic.domain.manager.LISTENING_KIND_ALBUM
import paige.navic.domain.manager.LbDownloads
import paige.navic.domain.manager.LbGap
import paige.navic.domain.manager.LbGapTrack
import paige.navic.domain.manager.LbUnownedRow
import paige.navic.domain.manager.attentionPage
import paige.navic.domain.manager.attentionRead
import paige.navic.domain.manager.unownedRowAction
import paige.navic.ui.components.common.FillButton
import paige.navic.ui.components.common.FillText
import paige.navic.ui.components.common.PillTone
import paige.navic.ui.screens.artist.artistSheetOffersFillGaps
import paige.navic.ui.screens.attention.attentionButtons
import paige.navic.ui.screens.attention.attentionPill
import paige.navic.ui.screens.collection.viewmodels.GapBoxUi
import paige.navic.ui.screens.collection.viewmodels.gapError
import paige.navic.ui.screens.downloads.viewmodels.refusalAnnounced
import paige.navic.ui.screens.wishlist.sourceHistoryShowsWishlist
import navic.composeapp.generated.resources.Res
import navic.composeapp.generated.resources.attention_missing_off
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

	// ---- the review's fixes ---- //

	private val offAnswer = """{"code":"downloads_off","error":"Downloading is not set up on this server."}"""

	@Test
	fun gap_box_409_downloads_off_leaves_no_error_line_and_turns_the_mode_off() {
		// Fill gaps on a box that still believed "on" (an older hub, a kept advert): lb-bot refuses with
		// its 409, which the manager's failure path reads (`failureFor` → the mode).
		val mode = LbDownloadsState()
		val error = mode.failureOf(409, offAnswer)
		val ui = GapBoxUi(view = GapView.SOURCES, working = true).copy(working = false, error = gapError(error))
		assertNull(ui.error)
		assertEquals(LbDownloads.OFF, mode.current)
		// The box answers the mode on the next frame: the missing songs, no search, no Fill gaps.
		assertIs<GapStage.Tracks>(gapStage(GapInput(gap, 2, null, ui.view, false, false, 100_000L, downloadsOn = mode.current.isOn)))
		assertFalse(gapFillOffered(mode.current))
		// Any other refusal is still said.
		val busy = LbBotManager.LbError.Rejected(409, "A fill for this album is in flight", "busy")
		assertEquals(busy, gapError(busy))
	}

	@Test
	fun gap_box_off_says_no_download_words_even_for_a_stalled_placement() {
		// "Downloaded, but needs sorting out in lb-bot" is a download's: off, the box is the missing songs.
		val stalled = gap.copy(stalledPlacement = true)
		val input = GapInput(stalled, 2, null, GapView.TRACKS, false, false, 100_000L, downloadsOn = false)
		assertIs<GapStage.Tracks>(gapStage(input))
		// A record that still says it is transferring is not drawn as a transfer either.
		val transferring = gap.copy(status = "downloading")
		assertIs<GapStage.Tracks>(gapStage(GapInput(transferring, 2, null, GapView.TRACKS, false, false, 100_000L, downloadsOn = false)))
		// On, both are today's.
		assertIs<GapStage.Attention>(gapStage(input.copy(downloadsOn = true)))
		assertIs<GapStage.Active>(gapStage(GapInput(transferring, 2, null, GapView.TRACKS, false, false, 100_000L, downloadsOn = true)))
	}

	private fun fillEntry(key: String) = LbFillEntry(
		key = key, isGap = false, rgid = "rg-$key", groupId = "", releaseMbid = "rel-$key",
		artist = "Artist", album = "Album", quality = "", state = "failed",
		outcome = LbBotManager.OUTCOME_FAILED, reason = "no peers had it", percent = 0, done = 0, total = 5,
		mp3WouldHelp = true, failureKind = "no_source", retryable = true, attempts = 3, settled = true,
		startedAt = 0L, finishedAt = 0L
	)

	private val today = listOf(FillButton.ALLOW_MP3, FillButton.RETRY, FillButton.TRY_ANOTHER, FillButton.WISHLIST, FillButton.DISMISS)

	@Test
	fun attention_album_rows_offer_nothing_that_fetches_when_off() {
		val album = AttentionAlbum(kind = "request", key = "rel-1", rgid = "rg-1", album = "B", state = "picking")
		val lbBotsRow = AttentionRow(id = "request:rel-1", album = album, entry = fillEntry("1"))
		val deviceRow = AttentionRow(id = "device:2", album = album, entry = fillEntry("2"))
		assertEquals(listOf(FillButton.OPEN_ALBUM, FillButton.WISHLIST), attentionButtons(lbBotsRow, today, true, downloadsOn = false))
		assertEquals(
			listOf(FillButton.OPEN_ALBUM, FillButton.WISHLIST, FillButton.DISMISS),
			attentionButtons(deviceRow, today, true, downloadsOn = false)
		)
		// Already wishlisted: no Wishlist.
		assertEquals(listOf(FillButton.OPEN_ALBUM), attentionButtons(lbBotsRow, today, false, downloadsOn = false))
		// One pill with no source and no failure in it.
		val pill = attentionPill(lbBotsRow, downloadsOn = false)
		assertEquals(FillText.Res(Res.string.attention_missing_off), pill.label)
		assertEquals(PillTone.NEUTRAL, pill.tone)
		// A refused row action that was lb-bot's `downloads_off` raises no snackbar.
		assertFalse(refusalAnnounced(LbBotManager.LbError.Rejected(409, "Downloading is not set up on this server.", "downloads_off")))
	}

	@Test
	fun attention_album_rows_offer_nothing_that_fetches_when_off_unchanged_when_on() {
		val album = AttentionAlbum(kind = "request", key = "rel-1", rgid = "rg-1", album = "B", state = "failed")
		val lbBotsRow = AttentionRow(id = "request:rel-1", album = album, entry = fillEntry("1"))
		// Dismiss only where it removes the row (L4b-09), the rest as the vocabulary says.
		assertEquals(today - FillButton.DISMISS, attentionButtons(lbBotsRow, today, true, downloadsOn = true))
		// An lb-bot-only row opens its album, in both modes.
		val lbOnly = AttentionRow(id = "request:rel-9", album = album, entry = null)
		assertEquals(listOf(FillButton.OPEN_ALBUM), attentionButtons(lbOnly, today, true, downloadsOn = true))
		assertEquals(listOf(FillButton.OPEN_ALBUM), attentionButtons(lbOnly, today, true, downloadsOn = false))
		assertEquals(PillTone.BAD, attentionPill(lbBotsRow, downloadsOn = true).tone)
		assertEquals(PillTone.WARN, attentionPill(lbBotsRow.copy(album = album.copy(state = "picking")), downloadsOn = true).tone)
		assertTrue(refusalAnnounced(LbBotManager.LbError.Rejected(409, "busy", "busy")))
		assertTrue(refusalAnnounced(LbBotManager.LbError.Busy))
	}

	@Test
	fun attention_badge_counts_no_folder_when_off() {
		// Ruling: the folders the page does not list while off are not counted either; the failed
		// albums, which stay listed, are.
		val counts = AttentionCounts(failedGaps = 3, failedRequests = 1, folders = 16)
		assertEquals(20, toFixCount(counts))
		assertEquals(4, toFixCount(counts, downloadsOn = false))
		// The page's headline counts the same way (this answer: one folder, nothing else).
		assertEquals(0, attentionRows(downloadsOn = false).toFix)
		assertEquals(1, attentionRows(downloadsOn = true).toFix)
		val read = (attentionRead(200, attention) as AttentionRead.Ok).attention
		assertEquals(0, attentionBadge(read, ledgerCount = 7, downloadsOn = false))
		assertEquals(1, attentionBadge(read, ledgerCount = 7, downloadsOn = true))
		// No read: this device's own count, in both modes.
		assertEquals(7, attentionBadge(null, ledgerCount = 7, downloadsOn = false))
	}

	@Test
	fun source_history_from_a_saved_stack_is_the_wishlist_when_off() {
		assertTrue(sourceHistoryShowsWishlist(LbDownloads.OFF))
		assertFalse(sourceHistoryShowsWishlist(LbDownloads.ON))
		assertFalse(sourceHistoryShowsWishlist(LbDownloads.UNKNOWN))
	}
}
