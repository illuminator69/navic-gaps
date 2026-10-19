package paige.navic.domain.manager

import kotlin.test.Test
import kotlin.test.assertEquals

/*
 * P1: the dot on the header avatar. It counts what this device's own request ledger says needs a
 * person: a fill that finished as a failure, or one the picker is waiting on. A running fill is not
 * an alarm, a cancel is the user's own doing, and a "gave up" is lb-bot forgetting a row, not news.
 * A dismissed row leaves the ledger, so "not dismissed" needs no rule of its own here.
 */
class NeedsAttentionTest {

	private fun entry(outcome: String, settled: Boolean, key: String = "rg-1") = LbFillEntry(
		key = key,
		isGap = false,
		rgid = key,
		groupId = "",
		releaseMbid = "rel-1",
		artist = "After",
		album = "After EP",
		quality = "",
		state = if (settled) "failed" else "downloading",
		outcome = outcome,
		reason = "",
		percent = 0,
		done = 0,
		total = 5,
		mp3WouldHelp = false,
		failureKind = "",
		retryable = true,
		attempts = 1,
		settled = settled,
		startedAt = 0L,
		finishedAt = 0L
	)

	@Test
	fun aSettledFailureCounts() {
		assertEquals(1, needsAttentionCount(listOf(entry(LbBotManager.OUTCOME_FAILED, settled = true))))
	}

	@Test
	fun aSettledNeedsPickCounts() {
		assertEquals(1, needsAttentionCount(listOf(entry(LbBotManager.OUTCOME_NEEDS_PICK, settled = true))))
	}

	@Test
	fun aRunningRowDoesNot() {
		assertEquals(0, needsAttentionCount(listOf(entry(LbBotManager.OUTCOME_RUNNING, settled = false))))
	}

	@Test
	fun aDoneCancelledOrGaveUpRowDoesNot() {
		val rows = listOf(
			entry(LbBotManager.OUTCOME_DONE, settled = true, key = "a"),
			entry(LbBotManager.OUTCOME_CANCELLED, settled = true, key = "b"),
			entry(LbBotManager.OUTCOME_GAVE_UP, settled = true, key = "c")
		)
		assertEquals(0, needsAttentionCount(rows))
	}

	@Test
	fun anUnsettledFailedRowDoesNot() {
		// Between lb-bot saying "failed" and the ledger settling it, nothing is final yet.
		assertEquals(0, needsAttentionCount(listOf(entry(LbBotManager.OUTCOME_FAILED, settled = false))))
		assertEquals(0, needsAttentionCount(listOf(entry(LbBotManager.OUTCOME_NEEDS_PICK, settled = false))))
	}

	@Test
	fun anEmptyLedgerIsZero() {
		assertEquals(0, needsAttentionCount(emptyList()))
	}

	@Test
	fun eachRowCountsOnceWhateverElseIsInTheLedger() {
		val rows = listOf(
			entry(LbBotManager.OUTCOME_FAILED, settled = true, key = "a"),
			entry(LbBotManager.OUTCOME_FAILED, settled = true, key = "b"),
			entry(LbBotManager.OUTCOME_NEEDS_PICK, settled = true, key = "c"),
			entry(LbBotManager.OUTCOME_RUNNING, settled = false, key = "d"),
			entry(LbBotManager.OUTCOME_DONE, settled = true, key = "e")
		)
		assertEquals(3, needsAttentionCount(rows))
	}
}
