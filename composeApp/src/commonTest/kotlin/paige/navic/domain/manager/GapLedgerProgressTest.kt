package paige.navic.domain.manager

import kotlin.test.Test
import kotlin.test.assertEquals

/*
 * B-024: one count for a gap fill's progress. `gapLedgerProgress` is what the ledger's
 * done/total/percent/failedFiles all come from — fed by LbGap's own tracksDone/tracksWanted/
 * tracksFailed (which already treat downloaded|done|skipped|cancelled as "done"), so the bar and
 * the headline can no longer disagree with each other the way applyGapSummary's old recount let
 * them (the bar counted cancelled, the headline didn't, and skipped was counted by neither).
 */
class GapLedgerProgressTest {
	private fun track(state: String) = LbGapTrack(state = state)

	private fun gap(vararg states: String) = LbGap(tracks = states.map { track(it) })

	@Test
	fun doneAndCancelledBothCountTowardDone() {
		val g = gap("done", "done", "done", "cancelled", "cancelled")
		val progress = gapLedgerProgress(g)
		assertEquals(5, progress.done)
		assertEquals(5, progress.total)
		assertEquals(100, progress.percent)
	}

	@Test
	fun skippedCountsTowardDoneAndFailedIsCountedSeparately() {
		val g = gap("downloaded", "downloaded", "skipped", "failed", "downloading")
		val progress = gapLedgerProgress(g)
		assertEquals(3, progress.done)
		assertEquals(5, progress.total)
		assertEquals(60, progress.percent)
		assertEquals(1, progress.failed)
	}

	@Test
	fun presentTracksAreExcludedFromTheTotal() {
		val g = gap("present", "present", "done")
		val progress = gapLedgerProgress(g)
		assertEquals(1, progress.done)
		assertEquals(1, progress.total)
		assertEquals(100, progress.percent)
	}

	@Test
	fun percentRoundsHalfUpLikeFeishinsMathRound() {
		// Feishin's gapProgress is Math.round(done * 100 / wanted); 2 of 3 is 66.67 and Math.round
		// takes .5 and up, so it reads 67, not the 66 a floor gives.
		val g = gap("downloaded", "downloaded", "downloading")
		val progress = gapLedgerProgress(g)
		assertEquals(2, progress.done)
		assertEquals(3, progress.total)
		assertEquals(67, progress.percent)
	}

	@Test
	fun anEmptyGapIsZeroZeroZero() {
		val progress = gapLedgerProgress(LbGap())
		assertEquals(0, progress.done)
		assertEquals(0, progress.total)
		assertEquals(0, progress.percent)
	}
}
