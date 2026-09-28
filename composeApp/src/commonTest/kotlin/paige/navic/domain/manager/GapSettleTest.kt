package paige.navic.domain.manager

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/*
 * Q-031 (Navic half): lb-bot reports `picking` for two buckets — the picker holding candidates and
 * waiting on the user ("your move"), and its needs_match bucket, files already `downloaded` and
 * waiting on a manual match only lb-bot's own workspace can do. `gapSettle` is the mapping
 * applyGapSummary writes to the ledger when a gap stops being busy, and `gapLedgerState` the row's
 * state while it is polled. Mirrors Feishin's `gapSettleOutcome` (fill-announce-logic.ts): the
 * needs_match bucket settles the same way a stalled placement and an album fill's needs_match
 * already do — failed, at needs_match — so it is announced "Downloaded X — needs sorting out in
 * lb-bot" and the Download Center row reads the needs_match wording, not "Waiting for you to pick a
 * source".
 */
class GapSettleTest {
	private fun gap(
		status: String,
		vararg trackStates: String,
		stalledPlacement: Boolean = false
	) = LbGap(
		status = status,
		tracks = trackStates.map { LbGapTrack(state = it) },
		stalledPlacement = stalledPlacement
	)

	// --- gapSettle -------------------------------------------------------------------------

	@Test
	fun aPickingGapWithADownloadedTrackSettlesFailedAtNeedsMatch() {
		assertEquals(
			GapSettle(LbBotManager.OUTCOME_FAILED, "needs_match"),
			gapSettle(gap("picking", "present", "downloaded", "picked"), searching = false, transferring = false)
		)
	}

	@Test
	fun aPickingGapWithNoDownloadedTrackStillSettlesNeedsPick() {
		assertEquals(
			GapSettle(LbBotManager.OUTCOME_NEEDS_PICK, "picking"),
			gapSettle(gap("picking", "present", "picked", "missing"), searching = false, transferring = false)
		)
	}

	@Test
	fun aStalledPlacementSettlesFailedAtNeedsMatch() {
		assertEquals(
			GapSettle(LbBotManager.OUTCOME_FAILED, "needs_match"),
			gapSettle(gap("failed", "downloaded", "downloaded", stalledPlacement = true), searching = false, transferring = false)
		)
	}

	@Test
	fun aPlainFailureSettlesFailedWithItsOwnState() {
		assertEquals(
			GapSettle(LbBotManager.OUTCOME_FAILED, "failed"),
			gapSettle(gap("failed", "failed", "missing"), searching = false, transferring = false)
		)
	}

	@Test
	fun aCompleteGapSettlesDone() {
		assertEquals(
			GapSettle(LbBotManager.OUTCOME_DONE, "complete"),
			gapSettle(gap("complete", "present", "done"), searching = false, transferring = false)
		)
	}

	@Test
	fun aGapStillMovingDoesNotSettle() {
		assertNull(gapSettle(gap("downloading", "downloading", "queued"), searching = false, transferring = false))
		assertNull(gapSettle(gap("ready", "missing"), searching = false, transferring = false))
	}

	// --- gapLedgerState --------------------------------------------------------------------

	@Test
	fun theLedgerRowReadsNeedsMatchForAPendingMatchAndAStalledPlacement() {
		assertEquals("needs_match", gapLedgerState(gap("picking", "downloaded"), searching = false, transferring = false))
		assertEquals("needs_match", gapLedgerState(gap("failed", "downloaded", stalledPlacement = true), searching = false, transferring = false))
	}

	@Test
	fun aSearchInFlightIsNeverReadAsAPendingMatch() {
		// Asking for sources flips the group to `picking` before it has found anything, so a
		// `downloaded` track seen mid-search does not yet mean the match is stuck (awaitingMatch).
		assertEquals("picking", gapLedgerState(gap("picking", "downloaded"), searching = true, transferring = false))
	}

	@Test
	fun aTransferInFlightIsNeverReadAsAPendingMatch() {
		// A fetch on a gap that already holds a `downloaded` track: lb-bot queues the new tracks
		// before it flips the status off `picking`, so for a tick or two the group reads `picking`
		// + `downloaded` + `queued`. Nothing it says is final while a transfer runs (Feishin's
		// gapIsBusy never settles it), and reading it as needs_match would announce "Downloaded X
		// — needs sorting out" at the start of a fetch. A transfer in flight is never a reason to
		// stop: lb-bot ranks a failed or needs_match bucket above it, so gapSettle must not fire
		// while transferring is true, or the watch stops polling a fill that is still running.
		val fetching = gap("picking", "downloaded", "queued", "picked")
		assertEquals("picking", gapLedgerState(fetching, searching = false, transferring = true))
		assertNull(gapSettle(fetching, searching = false, transferring = true))
		// Once the transfer ends (its tracks resolve off `queued`), the same `downloaded` track
		// reads as needs_match, exactly as it does with no transfer ever having been in flight.
		val settled = fetching.copy(tracks = fetching.tracks.filterNot { it.state == "queued" })
		assertEquals(
			GapSettle(LbBotManager.OUTCOME_FAILED, "needs_match"),
			gapSettle(settled, searching = false, transferring = false)
		)
	}

	@Test
	fun aFailedBucketWithAQueuedTrackDoesNotSettleWhileTransferring() {
		// lb-bot's `_review_group_next_action` ranks the failed bucket above an in-flight
		// transfer: a fill missing one track reads `failed` + `queued` on its first poll, while
		// the rest of the album is still downloading.
		val g = gap("failed", "failed", "queued")
		assertNull(gapSettle(g, searching = false, transferring = true))
		assertEquals(
			GapSettle(LbBotManager.OUTCOME_FAILED, "failed"),
			gapSettle(g, searching = false, transferring = false)
		)
	}

	@Test
	fun aNeedsMatchBucketWithAQueuedTrackDoesNotSettleWhileTransferring() {
		// Q-031: a `picking` gap already holding a `downloaded` track stays `picking` for the
		// whole transfer while lb-bot fetches the rest, so this is needs_match's own case of the
		// same rule.
		val g = gap("picking", "downloaded", "queued")
		assertNull(gapSettle(g, searching = false, transferring = true))
		assertEquals(
			GapSettle(LbBotManager.OUTCOME_FAILED, "needs_match"),
			gapSettle(g, searching = false, transferring = false)
		)
	}

	@Test
	fun otherwiseTheLedgerRowReadsLbBotsOwnStatus() {
		assertEquals("picking", gapLedgerState(gap("picking", "picked"), searching = false, transferring = false))
		assertEquals("downloading", gapLedgerState(gap("downloading", "downloading"), searching = false, transferring = false))
		assertEquals("failed", gapLedgerState(gap("failed", "failed"), searching = false, transferring = false))
	}
}
