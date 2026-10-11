package paige.navic.ui.components.common

import navic.composeapp.generated.resources.Res
import navic.composeapp.generated.resources.lbbot_fill_left_out
import paige.navic.domain.manager.LbFillEntry
import paige.navic.domain.manager.LbFillStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/*
 * B-084: a whole-album download asks a folder for the release's tracks only, and lb-bot's fill
 * view says how many of the folder's audio files were not asked for (`leftOut`). Where a status
 * line for an album download already exists, it says so: the Download Center's row and the
 * missing-album sheet's progress block, while the album downloads and once it is in the library.
 * 0 (and so every gap fill, and every lb-bot older than the field) adds nothing, and the line is
 * then exactly what it was. Mirrors `leftOutText` and its two uses in Feishin's
 * `fill-vocabulary.ts`.
 */
class FillLeftOutTest {
	private val mb = 1024L * 1024L
	private val leftOut6 = FillText.Counted(Res.plurals.lbbot_fill_left_out, 6)

	private fun entry(
		state: String = "downloading",
		outcome: String = "running",
		settled: Boolean = false,
		leftOut: Int = 0,
		isGap: Boolean = false,
		lastSource: String = "bob",
		bytesDone: Long = 12 * mb,
		bytesTotal: Long = 80 * mb
	) = LbFillEntry(
		key = "rg-1",
		isGap = isGap,
		rgid = if (isGap) "" else "rg-1",
		groupId = if (isGap) "rg-1" else "",
		releaseMbid = "rel-1",
		artist = "After",
		album = "After EP",
		quality = "",
		state = state,
		outcome = outcome,
		reason = "",
		percent = 40,
		done = 2,
		total = 5,
		mp3WouldHelp = false,
		failureKind = "",
		retryable = true,
		attempts = 1,
		settled = settled,
		startedAt = 0L,
		finishedAt = 0L,
		bytesDone = bytesDone,
		bytesTotal = bytesTotal,
		lastSource = lastSource,
		leftOut = leftOut
	)

	private fun sublines(entry: LbFillEntry) = describeFill(entry, now = 0L, wishlistAvailable = false).sublines

	// --- the part itself ----------------------------------------------------------------------

	@Test
	fun nothingLeftOutIsNoPart() {
		assertNull(leftOutPart(0))
		assertNull(leftOutPart(-1))
	}

	@Test
	fun filesLeftOutAreCounted() {
		assertEquals(FillText.Counted(Res.plurals.lbbot_fill_left_out, 1), leftOutPart(1))
		assertEquals(leftOut6, leftOutPart(6))
	}

	// --- the Download Center's row ------------------------------------------------------------

	@Test
	fun aDownloadingRowWithNothingLeftOutSaysWhatItAlwaysSaid() {
		assertEquals(listOf<FillText>(FillText.Raw("12 MB / 80 MB · @bob")), sublines(entry()))
	}

	@Test
	fun aDownloadingRowAddsWhatWasLeftOutToItsLine() {
		assertEquals(
			listOf<FillText>(FillText.Joined(listOf(FillText.Raw("12 MB / 80 MB · @bob"), leftOut6))),
			sublines(entry(leftOut = 6))
		)
	}

	@Test
	fun aDownloadingRowWithNothingElseToSaySaysOnlyWhatWasLeftOut() {
		assertEquals(
			listOf<FillText>(leftOut6),
			sublines(entry(leftOut = 6, lastSource = "", bytesDone = 0L, bytesTotal = 0L))
		)
	}

	@Test
	fun aFinishedRowSaysWhatWasLeftOut() {
		assertEquals(
			listOf<FillText>(leftOut6),
			sublines(entry(state = "verified", outcome = "done", settled = true, leftOut = 6))
		)
	}

	@Test
	fun aFinishedRowWithNothingLeftOutSaysNothingMore() {
		assertEquals(
			emptyList(),
			sublines(entry(state = "verified", outcome = "done", settled = true))
		)
	}

	@Test
	fun noOtherRowSpeaksOfIt() {
		// Queued, being added, failed, cancelled, given up on: the count is the current
		// source's, and these rows have their own thing to say.
		val quiet = listOf(
			entry(state = "queued", leftOut = 6),
			entry(state = "placing", leftOut = 6),
			entry(state = "placed", leftOut = 6),
			entry(state = "failed", outcome = "failed", settled = true, leftOut = 6),
			entry(state = "cancelled", outcome = "cancelled", settled = true, leftOut = 6),
			entry(state = "downloading", outcome = "gave_up", settled = true, leftOut = 6)
		)
		quiet.forEach { row ->
			val said = sublines(row).flatMap { if (it is FillText.Joined) it.parts else listOf(it) }
			assertEquals(emptyList(), said.filterIsInstance<FillText.Counted>(), row.state + "/" + row.outcome)
		}
	}

	// --- the sheet's progress block -----------------------------------------------------------

	private fun status(
		state: String = "downloading",
		leftOut: Int? = 0,
		bytesTotal: Long = 80 * mb,
		speedBps: Long = 0L,
		verifyGaveUp: Boolean = false
	) = LbFillStatus(
		state = state,
		done = 2,
		total = 5,
		bytesDone = 12 * mb,
		bytesTotal = bytesTotal,
		speedBps = speedBps,
		verifyGaveUp = verifyGaveUp,
		leftOut = leftOut
	)

	@Test
	fun theSheetsLineIsWhatItWasWhenNothingWasLeftOut() {
		assertEquals(FillText.Raw("12 MB / 80 MB"), fillDetailLine(status()))
		assertEquals(FillText.Raw("12 MB / 80 MB · 1.5 MB/s"), fillDetailLine(status(speedBps = 3 * mb / 2)))
		assertNull(fillDetailLine(status(bytesTotal = 0L)))
		assertNull(fillDetailLine(status(state = "verified")))
		assertNull(fillDetailLine(status(state = "searching")))
	}

	@Test
	fun aViewThatDoesNotSayAddsNothingToTheSheetsLine() {
		// An older lb-bot, or the hub's `fill` frame before any full view was read.
		assertEquals(FillText.Raw("12 MB / 80 MB"), fillDetailLine(status(leftOut = null)))
		assertNull(fillDetailLine(status(state = "verified", leftOut = null)))
	}

	@Test
	fun theSheetsLineAddsWhatWasLeftOutWhileDownloading() {
		assertEquals(
			FillText.Joined(listOf(FillText.Raw("12 MB / 80 MB"), leftOut6)),
			fillDetailLine(status(leftOut = 6))
		)
		assertEquals(leftOut6, fillDetailLine(status(leftOut = 6, bytesTotal = 0L)))
	}

	@Test
	fun theSheetSaysWhatWasLeftOutOnceTheAlbumIsIn() {
		assertEquals(leftOut6, fillDetailLine(status(state = "verified", leftOut = 6)))
		// On disk and past the verifier's deadline is the ledger's "done" too.
		assertEquals(leftOut6, fillDetailLine(status(state = "placed", verifyGaveUp = true, leftOut = 6)))
	}

	@Test
	fun theSheetSaysNothingOfItInAnyOtherState() {
		listOf("searching", "queued", "placing", "placed", "failed", "cancelled", "unknown").forEach { state ->
			assertNull(fillDetailLine(status(state = state, leftOut = 6)), state)
		}
	}
}
