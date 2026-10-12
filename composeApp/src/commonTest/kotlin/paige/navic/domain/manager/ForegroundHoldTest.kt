package paige.navic.domain.manager

import paige.navic.domain.manager.ForegroundReason.DOWNLOADS
import paige.navic.domain.manager.ForegroundReason.SYNC
import paige.navic.domain.manager.ForegroundSignal.NONE
import paige.navic.domain.manager.ForegroundSignal.REASONS_CHANGED
import paige.navic.domain.manager.ForegroundSignal.START_FOREGROUND
import paige.navic.domain.manager.ForegroundSignal.STOP
import kotlin.test.Test
import kotlin.test.assertEquals

/*
 * The rules in ForegroundHold.kt. The case that shipped broken (B-096) is the second test: the
 * last reason released before the service has answered its start must not stop the service,
 * because that stop is a fatal exception for the whole app. The rest walk the ways a pending
 * start ends, and that nothing else ends it.
 */
class ForegroundHoldTest {
	private val idle = ForegroundHold()

	/** A start asked for with token 1 and not answered yet, held for [reasons]. */
	private fun pending(vararg reasons: ForegroundReason) = ForegroundHold(reasons.toSet(), pendingStart = 1)

	private fun running(vararg reasons: ForegroundReason) = ForegroundHold(reasons.toSet())

	@Test
	fun theFirstReasonStartsTheServiceAndWaitsForItsAnswer() {
		assertEquals(ForegroundHold(setOf(SYNC), pendingStart = 7) to START_FOREGROUND, idle.acquire(SYNC, token = 7))
	}

	@Test
	fun theLastReasonReleasedBeforeTheAnswerSendsNothing() {
		// A full pull with nobody logged in: over before `onStartCommand` has run.
		val (released, signal) = pending(SYNC).release(SYNC)
		assertEquals(NONE, signal)
		assertEquals(pending(), released)
		// The service answers, reads that nothing is held, and stops itself.
		assertEquals(idle, released.answered(1))
	}

	@Test
	fun theLastReasonReleasedAfterTheAnswerStopsTheService() {
		val answered = pending(SYNC).answered(1)
		assertEquals(running(SYNC), answered)
		assertEquals(idle to STOP, answered.release(SYNC))
	}

	@Test
	fun aSecondReasonDuringTheWaitSendsNothingAndIsHeldWhenTheStartAnswers() {
		val (both, signal) = pending(SYNC).acquire(DOWNLOADS, token = 2)
		assertEquals(NONE, signal)
		assertEquals(pending(SYNC, DOWNLOADS), both)
		assertEquals(running(SYNC, DOWNLOADS), both.answered(1))
	}

	@Test
	fun releasedAndTakenAgainDuringTheWaitIsStillTheOneStart() {
		val (released, _) = pending(SYNC).release(SYNC)
		val (again, signal) = released.acquire(SYNC, token = 2)
		// No second start, and the token waited for is still the first one's.
		assertEquals(NONE, signal)
		assertEquals(pending(SYNC), again)
		assertEquals(running(SYNC), again.answered(1))
	}

	@Test
	fun aStartTheSystemRefusedIsNeitherHeldNorPending() {
		val (asked, _) = idle.acquire(DOWNLOADS, token = 3)
		assertEquals(idle, asked.refused(DOWNLOADS, token = 3))
		// So the next one is a start again, not something sent to a service that never was.
		assertEquals(START_FOREGROUND, asked.refused(DOWNLOADS, token = 3).acquire(DOWNLOADS, token = 4).second)
	}

	@Test
	fun aRefusedStartForegroundEndsItsOwnWaitAndHoldsNothing() {
		assertEquals(idle, pending(SYNC, DOWNLOADS).gaveUp(token = 1))
	}

	@Test
	fun aTimeoutHoldsNothingButLeavesAnotherStartWaiting() {
		// The budget ran out on a service whose successor has already been asked for.
		val timedOut = pending(SYNC).gaveUp(token = 0)
		assertEquals(pending(), timedOut)
		// Releasing into that still must not stop the service the start is waiting on.
		assertEquals(NONE, timedOut.release(SYNC).second)
		assertEquals(idle, timedOut.answered(1))
	}

	@Test
	fun onlyTheCommandCarryingTheTokenEndsTheWait() {
		// A reasons-changed command (no token), or one left over from an earlier start.
		assertEquals(pending(SYNC), pending(SYNC).answered(0))
		assertEquals(ForegroundHold(setOf(SYNC), pendingStart = 5), ForegroundHold(setOf(SYNC), pendingStart = 5).answered(4))
		assertEquals(ForegroundHold(pendingStart = 5), ForegroundHold(setOf(SYNC), pendingStart = 5).gaveUp(4))
	}

	@Test
	fun withTheServiceUpReasonsComeAndGoAsBefore() {
		assertEquals(running(SYNC, DOWNLOADS) to REASONS_CHANGED, running(SYNC).acquire(DOWNLOADS, token = 2))
		assertEquals(running(DOWNLOADS) to REASONS_CHANGED, running(SYNC, DOWNLOADS).release(SYNC))
		// Idempotent both ways: no Intent per claimed job, and a stop with nothing held is the
		// no-op stop it always was.
		assertEquals(running(SYNC) to NONE, running(SYNC).acquire(SYNC, token = 2))
		assertEquals(idle to STOP, idle.release(DOWNLOADS))
	}
}
