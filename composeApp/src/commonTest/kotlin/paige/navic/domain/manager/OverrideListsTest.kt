package paige.navic.domain.manager

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/*
 * I-022 / I-023 in the "lb-bot data" panel: the album overrides and the name merges, listed
 * beside the artist overrides, each with Undo (the sentences are in LbBotData.kt).
 *
 * Both lists ride the overrides body, and each may be ABSENT — an lb-bot that predates it, or
 * one that could not work it out — which is not the same as empty: a missing list renders
 * nothing, an empty one says there are none. Every other sentence is a field of a row.
 */
class OverrideListsTest {
	private val release = "d105e2cd-1111-4111-8111-111111111111"
	private val previous = "22222222-2222-4222-8222-222222222222"

	private fun album(
		state: String = "applied",
		done: Int = 12,
		total: Int = 12,
		failures: Int = 0,
		releaseMbid: String = release,
		prevReleaseMbid: String = previous,
		coverUrl: String = ""
	) = LbAlbumOverrideRow(
		id = "ov-1",
		state = state,
		releaseMbid = releaseMbid,
		prevReleaseMbid = prevReleaseMbid,
		artist = "Tame Impala",
		album = "Currents",
		albumId = "al-2",
		coverUrl = coverUrl,
		done = done,
		total = total,
		failures = List(failures) { LbAlbumFailure("Track $it", "read-only file") }
	)

	private fun merge(state: String = "merged", done: Int = 24, total: Int = 24, failures: Int = 0) = LbMergeRow(
		id = "mg-1",
		state = state,
		into = LbMergeArtist("ar-amp", "Belle & Sebastian"),
		from = LbMergeArtist(name = "Belle and Sebastian"),
		done = done,
		total = total,
		failures = List(failures) { LbMergeFailure("Tigermilk", "Track $it", "read-only file") }
	)

	// --- whether a list is there at all ------------------------------------------------------

	@Test
	fun aMissingListRendersNothing() {
		assertEquals(false, overrideListShown(LbPanelData.CURRENT, null))
		assertEquals(false, overrideListShown(LbPanelData.STALE, null))
	}

	@Test
	fun anEmptyListIsShownAndSaysThereAreNone() {
		assertEquals(true, overrideListShown(LbPanelData.CURRENT, emptyList<LbMergeRow>()))
		assertEquals(
			"None. An album's release is set from the album's page: Fix album match.",
			albumRowsNote(LbPanelData.CURRENT, 0)
		)
		assertEquals(
			"None. Two spellings of one artist are merged from the artist's page: Fix MusicBrainz match.",
			mergeRowsNote(LbPanelData.CURRENT, 0)
		)
	}

	@Test
	fun aListWithRowsSpeaksForItself() {
		assertEquals(true, overrideListShown(LbPanelData.CURRENT, listOf(merge())))
		assertNull(albumRowsNote(LbPanelData.CURRENT, 2))
		assertNull(mergeRowsNote(LbPanelData.CURRENT, 1))
	}

	@Test
	fun theCopyLoadedLastSaysThatItIs() {
		assertEquals(true, overrideListShown(LbPanelData.STALE, emptyList<LbMergeRow>()))
		assertEquals(LB_STALE_LINE, albumRowsNote(LbPanelData.STALE, 3))
		assertEquals(LB_STALE_LINE, mergeRowsNote(LbPanelData.STALE, 0))
	}

	@Test
	fun withNothingLoadedThereIsNoSection() {
		// The overrides list above already says lb-bot is being asked, is out of reach, or is
		// older; a second and third heading repeating it would say nothing more.
		val rows = emptyList<LbMergeRow>()
		assertEquals(false, overrideListShown(LbPanelData.READING, rows))
		assertEquals(false, overrideListShown(LbPanelData.UNREACHABLE, rows))
		assertEquals(false, overrideListShown(LbPanelData.UNSUPPORTED, rows))
	}

	@Test
	fun withoutTheUndoRouteThereIsNoSection() {
		// The list is lb-bot's to send, the Undo is the hub's to carry. A hub that does not offer
		// the route would make this a list whose every button can only fail.
		assertEquals(false, overrideListShown(LbPanelData.CURRENT, listOf(album()), controls = false))
		assertEquals(false, overrideListShown(LbPanelData.STALE, emptyList<LbMergeRow>(), controls = false))
		assertEquals(true, overrideListShown(LbPanelData.CURRENT, listOf(album()), controls = true))
	}

	// --- an album override, in words ---------------------------------------------------------

	@Test
	fun anAlbumRowIsNamedByArtistAndAlbum() {
		assertEquals("Tame Impala — Currents", albumRowTitle(album()))
		assertEquals("Currents", albumRowTitle(album().copy(artist = "")))
		assertEquals("Tame Impala", albumRowTitle(album().copy(album = " ")))
		// Never a blank row, and never a path: with no names, the row's own id.
		assertEquals("ov-1", albumRowTitle(album().copy(artist = "", album = "")))
	}

	@Test
	fun anAppliedRetagSaysHowManyFilesWereWritten() {
		assertEquals("Retagged 12 files.", albumRowDetail(album()))
		assertEquals("Retagged 1 file.", albumRowDetail(album(done = 1, total = 1)))
	}

	@Test
	fun aPartialRetagCountsProblemsNotFiles() {
		assertEquals("Retagged 10 of 12 files. 2 problems.", albumRowDetail(album(done = 10, failures = 2)))
		// A cover that could not be saved is a `failures` entry too — and not a file: every
		// file was written, and "1 could not be written" beside "12 of 12" was wrong (X9).
		val coverOnly = album().copy(failures = listOf(LbAlbumFailure("Cover image", "the Archive did not answer")))
		assertEquals("Retagged 12 of 12 files. 1 problem.", albumRowDetail(coverOnly))
	}

	@Test
	fun aCoverIsSaidBesideTheRetagOrOnItsOwn() {
		val cover = "https://coverartarchive.org/release/x/1.jpg"
		assertEquals("Retagged 12 files. Cover changed.", albumRowDetail(album(coverUrl = cover)))
		// The release was kept and no tag rewritten: the cover is all that changed.
		assertEquals(
			"Cover changed.",
			albumRowDetail(album(done = 0, total = 0, releaseMbid = release, prevReleaseMbid = release, coverUrl = cover))
		)
	}

	@Test
	fun aRowThatWroteNothingSaysSo() {
		assertEquals(
			"No tag was rewritten.",
			albumRowDetail(album(done = 0, total = 0, releaseMbid = release, prevReleaseMbid = release))
		)
	}

	@Test
	fun aRowThatDidNotFinishSaysHowFarItGotWhicheverJobLeftItSo() {
		// `failed` is an apply that did not finish, or an undo that did not: the row does not
		// say which, so the sentence is true of both, and the reasons under it say the rest.
		assertEquals(
			"Did not finish: 4 of 12 files written. Undo puts the previous tags back.",
			albumRowDetail(album(state = "failed", done = 4))
		)
		assertEquals(
			"Did not finish: 10 of 12 files written. 2 problems. Undo puts the previous tags back.",
			albumRowDetail(album(state = "failed", done = 10, failures = 2))
		)
	}

	@Test
	fun aRowStillBeingWorkedOnSaysWhatLbBotIsDoing() {
		assertEquals("lb-bot is writing the tags: 3 of 12 files.", albumRowDetail(album(state = "applying", done = 3)))
		assertEquals("lb-bot is writing the tags.", albumRowDetail(album(state = "applying", done = 0, total = 0)))
		assertEquals("lb-bot is putting the previous tags back.", albumRowDetail(album(state = "removing", done = 3)))
	}

	@Test
	fun aRowWhoseWritesAreOverSaysLbBotIsWaitingForNavidrome() {
		// lb-bot holds the row active after its last write until Navidrome has scanned, up to a
		// minute: said as a wait, with no "12 of 12" that reads as stuck (X4).
		val wait = " lb-bot is waiting for Navidrome to index them (up to a minute)."
		assertEquals("The tags are written.$wait", albumRowDetail(album(state = "applying")))
		assertEquals("The tags are written.$wait", albumRowDetail(album(state = "applying", done = 11, failures = 1)))
		assertEquals("The previous tags are back.$wait", albumRowDetail(album(state = "removing")))
		assertEquals("The files are retagged.$wait", mergeRowDetail(merge(state = "merging")))
		assertEquals("The files are retagged.$wait", mergeRowDetail(merge(state = "merging", done = 22, failures = 2)))
		assertEquals("The previous names are back.$wait", mergeRowDetail(merge(state = "undoing")))
	}

	// --- lb-bot's reasons, under the row -------------------------------------------------------

	@Test
	fun aRowAtRestListsLbBotsReasons() {
		val again = "the file was not found, so its earlier tags were not put back. They are still kept: " +
			"undo again to restore it if it is back, or to give up on it if it is still not there"
		val notUndone = album(state = "failed", done = 10).copy(
			failures = listOf(LbAlbumFailure("Eventually", again), LbAlbumFailure("Cover image", "read-only folder"))
		)
		// The reason is what tells the user that Undo is to be pressed again.
		assertEquals(listOf("Eventually — $again", "Cover image — read-only folder"), albumRowProblems(notUndone))
		assertEquals(
			listOf("Track 0 — read-only file", "Track 1 — read-only file"),
			albumRowProblems(album(done = 10, failures = 2))
		)
		assertEquals(
			listOf("Tigermilk — Track 0 — read-only file"),
			mergeRowProblems(merge(state = "failed", done = 23, failures = 1))
		)
		assertEquals(
			listOf("Tigermilk — Track 0 — read-only file", "Tigermilk — Track 1 — read-only file"),
			mergeRowProblems(merge(done = 22, failures = 2))
		)
	}

	@Test
	fun aReasonWithNoTitleIsStillAReason() {
		val row = album(state = "failed").copy(failures = listOf(LbAlbumFailure("", "interrupted"), LbAlbumFailure("Eventually", "")))
		assertEquals(listOf("interrupted", "Eventually"), albumRowProblems(row))
		val merged = merge(state = "failed").copy(failures = listOf(LbMergeFailure("", "Track 1", "read-only file")))
		assertEquals(listOf("Track 1 — read-only file"), mergeRowProblems(merged))
	}

	@Test
	fun aLongListIsCutAndSaysHowManyMoreThereAre() {
		val many = albumRowProblems(album(state = "failed", done = 0, total = 13, failures = 13))
		assertEquals(ROW_PROBLEMS_SHOWN + 1, many.size)
		assertEquals("Track 9 — read-only file", many[ROW_PROBLEMS_SHOWN - 1])
		assertEquals("…and 3 more.", many.last())
		assertEquals("…and 1 more.", mergeRowProblems(merge(state = "failed", done = 13, failures = 11)).last())
	}

	@Test
	fun noReasonsAreListedWhileLbBotIsStillWorkingOrWhenThereAreNone() {
		assertEquals(emptyList(), albumRowProblems(album()))
		assertEquals(emptyList(), albumRowProblems(album(state = "applying", done = 3, failures = 1)))
		assertEquals(emptyList(), albumRowProblems(album(state = "removing", done = 3, failures = 1)))
		assertEquals(emptyList(), mergeRowProblems(merge()))
		assertEquals(emptyList(), mergeRowProblems(merge(state = "merging", done = 3, failures = 1)))
		assertEquals(emptyList(), mergeRowProblems(merge(state = "undoing", done = 3, failures = 1)))
	}

	@Test
	fun aStateThisBuildHasNeverHeardOfIsNotPutIntoWords() {
		// No raw token on screen, and no sentence nothing backs.
		assertEquals("", albumRowDetail(album(state = "verifying")))
		assertEquals("", mergeRowDetail(merge(state = "verifying")))
	}

	@Test
	fun onlyARowAtRestCanBeUndone() {
		assertEquals(true, albumRowCanUndo(album(state = "applied")))
		assertEquals(true, albumRowCanUndo(album(state = "failed")))
		assertEquals(false, albumRowCanUndo(album(state = "applying")))
		assertEquals(false, albumRowCanUndo(album(state = "removing")))
		assertEquals(false, albumRowCanUndo(album().copy(id = "")))
	}

	// --- a name merge, in words --------------------------------------------------------------

	@Test
	fun aMergeRowIsNamedFromTheRewrittenSpellingToTheKeptOne() {
		assertEquals("Belle and Sebastian → Belle & Sebastian", mergeRowTitle(merge()))
		assertEquals("Belle & Sebastian", mergeRowTitle(merge().copy(from = LbMergeArtist())))
		assertEquals("mg-1", mergeRowTitle(merge().copy(from = LbMergeArtist(), into = LbMergeArtist())))
	}

	@Test
	fun aMergeSaysHowManyFilesWereRetagged() {
		assertEquals("Retagged 24 files.", mergeRowDetail(merge()))
		assertEquals("Retagged 22 of 24 files. 2 problems.", mergeRowDetail(merge(done = 22, failures = 2)))
		assertEquals("Retagged 23 of 24 files. 1 problem.", mergeRowDetail(merge(done = 23, failures = 1)))
		assertEquals(
			"Did not finish: 7 of 24 files written. Undo puts the previous names back.",
			mergeRowDetail(merge(state = "failed", done = 7))
		)
		assertEquals(
			"Did not finish: 20 of 24 files written. 4 problems. Undo puts the previous names back.",
			mergeRowDetail(merge(state = "failed", done = 20, failures = 4))
		)
	}

	@Test
	fun aMergeStillBeingWorkedOnSaysWhatLbBotIsDoing() {
		assertEquals("lb-bot is retagging the files: 5 of 24.", mergeRowDetail(merge(state = "merging", done = 5)))
		assertEquals("lb-bot is retagging the files.", mergeRowDetail(merge(state = "merging", done = 0, total = 0)))
		assertEquals("lb-bot is putting the previous names back.", mergeRowDetail(merge(state = "undoing", done = 3)))
	}

	@Test
	fun onlyAMergeAtRestCanBeUndone() {
		assertEquals(true, mergeRowCanUndo(merge(state = "merged")))
		assertEquals(true, mergeRowCanUndo(merge(state = "failed")))
		assertEquals(false, mergeRowCanUndo(merge(state = "merging")))
		assertEquals(false, mergeRowCanUndo(merge(state = "undoing")))
		assertEquals(false, mergeRowCanUndo(merge().copy(id = " ")))
	}

	// --- what pressing Undo came to ----------------------------------------------------------

	@Test
	fun anUndoThatStartedSaysWhatLbBotIsDoing() {
		val started = LbBotManager.LbResult.Ok(LbAlbumWrite(ok = true, id = "ov-1", state = "removing"))
		assertEquals("lb-bot is putting the previous tags back.", undoStartLine(started, merge = false))
		val unmerging = LbBotManager.LbResult.Ok(LbMergeWrite(ok = true, id = "mg-1", state = "undoing"))
		assertEquals("lb-bot is putting the previous names back.", undoStartLine(unmerging, merge = true))
	}

	@Test
	fun anUndoThatWasRefusedSaysWhyInLbBotsWords() {
		val busy = LbBotManager.LbResult.Failed(LbBotManager.LbError.Rejected(409, "A download for this album is running.", "busy"))
		assertEquals("Not undone: A download for this album is running.", undoStartLine(busy, merge = false))
		val down = LbBotManager.LbResult.Failed(LbBotManager.LbError.Busy)
		assertEquals("Not undone: lb-bot is busy or unreachable.", undoStartLine(down, merge = true))
	}

	@Test
	fun anOlderLbBotIsSaidAsThatNotAsAnError() {
		val older = LbBotManager.LbResult.Failed(LbBotManager.LbError.RouteUnknown)
		assertEquals("This lb-bot is older and has no such undo. Nothing was changed.", undoStartLine(older, merge = false))
		assertEquals("This lb-bot is older and has no such undo. Nothing was changed.", undoStartLine(older, merge = true))
	}

	// ----- the line under the lists, for as long as it is true ------------------------------------
	// lb-bot works in the background, and the job is followed by the manager. "lb-bot is putting
	// the previous tags back" is a sentence about a job still running — left standing after the
	// job ended it would be the panel saying something nobody has said for minutes.

	private val albumUndo = LbBotManager.LbResult.Ok(LbAlbumWrite(ok = true, id = "ov-1", state = "removing"))
	private val mergeUndo = LbBotManager.LbResult.Ok(LbMergeWrite(ok = true, id = "mg-1", state = "undoing"))

	@Test
	fun aStartedUndoIsSaidAsRunningOnlyWhileItsJobIsFollowed() {
		assertEquals("lb-bot is putting the previous tags back.", undoLine(albumUndo, merge = false, phase = LbJobPhase.POLLING))
		assertEquals("lb-bot is putting the previous names back.", undoLine(mergeUndo, merge = true, phase = LbJobPhase.POLLING))
		// Nobody follows it any more: the list is what is true, and the line says nothing.
		assertEquals("", undoLine(albumUndo, merge = false, phase = null))
		assertEquals("", undoLine(mergeUndo, merge = true, phase = null))
	}

	@Test
	fun anUndoThatEndedSaysHowItEnded() {
		assertEquals("lb-bot put the previous tags back.", undoLine(albumUndo, merge = false, phase = LbJobPhase.DONE))
		assertEquals("lb-bot put the previous names back.", undoLine(mergeUndo, merge = true, phase = LbJobPhase.DONE))
		assertEquals(
			"lb-bot could not put all the previous tags back.",
			undoLine(albumUndo, merge = false, phase = LbJobPhase.FAILED)
		)
		assertEquals(
			"lb-bot could not put all the previous names back.",
			undoLine(mergeUndo, merge = true, phase = LbJobPhase.FAILED)
		)
	}

	@Test
	fun anUndoStillRunningAfterThreeMinutesIsNotCalledFinishedOrFailed() {
		val line = "lb-bot is still working on it. Refresh reads the list again."
		assertEquals(line, undoLine(albumUndo, merge = false, phase = LbJobPhase.TIMED_OUT))
		assertEquals(line, undoLine(mergeUndo, merge = true, phase = LbJobPhase.TIMED_OUT))
	}

	@Test
	fun aRefusedUndoKeepsItsRefusalWhateverAnOlderJobSays() {
		val refused = LbBotManager.LbResult.Failed(
			LbBotManager.LbError.Rejected(409, "A download for this album is running.", "busy")
		)
		// The row may still carry the job of the apply that made it: that is not this undo's ending.
		assertEquals("Not undone: A download for this album is running.", undoLine(refused, merge = false, phase = LbJobPhase.DONE))
		assertEquals("Not undone: A download for this album is running.", undoLine(refused, merge = false, phase = null))
	}
}
