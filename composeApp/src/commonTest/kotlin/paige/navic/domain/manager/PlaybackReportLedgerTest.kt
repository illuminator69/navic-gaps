package paige.navic.domain.manager

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import paige.navic.domain.manager.PlaybackReportState.PAUSED
import paige.navic.domain.manager.PlaybackReportState.PLAYING
import paige.navic.domain.manager.PlaybackReportState.STARTING
import paige.navic.domain.manager.PlaybackReportState.STOPPED

/*
 * Q-042: a force stop left the phone in Navidrome's now-playing until the entry expired, because
 * the open id lived only in memory. The ledger persists what the server confirmed, and the next
 * reporter closes it first.
 */
class PlaybackReportLedgerTest {
	/** The fakes never suspend, so the coroutine runs to completion inside startCoroutine. */
	private fun <T> runSuspend(block: suspend () -> T): T {
		var out: Result<T>? = null
		block.startCoroutine(Continuation(EmptyCoroutineContext) { out = it })
		return out!!.getOrThrow()
	}

	/** A preference stand-in that counts its writes. */
	private class Store(var value: String = "") {
		var writes = 0
		fun ledger() = PlaybackReportLedger(load = { value }, save = { value = it; writes++ })
	}

	/** A sender answering from a script, and recording what it was handed. */
	private class FakeSender(vararg answers: Boolean) {
		private val script = ArrayDeque(answers.toList())
		val sent = mutableListOf<PlaybackReport>()
		suspend fun send(report: PlaybackReport): Boolean {
			sent += report
			return script.removeFirstOrNull() ?: true
		}
	}

	private fun report(id: String, state: PlaybackReportState) = PlaybackReport(id, state, 1_000L, 1f)

	private fun deliver(ledger: PlaybackReportLedger, sender: FakeSender, r: PlaybackReport) =
		runSuspend { ledger.deliver(r, sender::send) }

	@Test
	fun aConfirmedReportIsRemembered() {
		val store = Store()
		deliver(store.ledger(), FakeSender(true), report("s1", PLAYING))
		assertEquals("s1", store.value)
	}

	@Test
	fun aReportTheServerDidNotConfirmChangesNothing() {
		val store = Store("s0")
		deliver(store.ledger(), FakeSender(false), report("s1", STARTING))
		assertEquals("s0", store.value)
	}

	@Test
	fun aConfirmedStoppedForTheHeldIdClearsIt() {
		val store = Store("s1")
		deliver(store.ledger(), FakeSender(true), report("s1", STOPPED))
		assertEquals("", store.value)
	}

	@Test
	fun aStoppedForAnotherIdLeavesTheHeldOne() {
		// Navidrome ignores it, so the entry for s1 is still open.
		val store = Store("s1")
		deliver(store.ledger(), FakeSender(true), report("s2", STOPPED))
		assertEquals("s1", store.value)
	}

	@Test
	fun aStartAfterAForceStopClosesTheExactStoredId() {
		val close = Store("s9").ledger().closeLeftOpen()
		assertEquals(PlaybackReport("s9", STOPPED, 0L, 1f), close)
	}

	@Test
	fun nothingHeldMeansNothingToClose() {
		assertNull(Store("").ledger().closeLeftOpen())
		assertNull(Store("  ").ledger().closeLeftOpen())
	}

	@Test
	fun aDroppedCloseKeepsTheIdForTheNextStart() {
		val store = Store("s9")
		val first = store.ledger()
		val sender = FakeSender(false)
		deliver(first, sender, first.closeLeftOpen()!!)
		assertEquals(listOf(PlaybackReport("s9", STOPPED, 0L, 1f)), sender.sent)
		assertEquals("s9", store.value)
		// The next process tries again, for the same id.
		assertEquals("s9", store.ledger().closeLeftOpen()?.mediaId)
	}

	@Test
	fun aConfirmedCloseLeavesNothingForTheNextStart() {
		val store = Store("s9")
		val ledger = store.ledger()
		deliver(ledger, FakeSender(true), ledger.closeLeftOpen()!!)
		assertNull(store.ledger().closeLeftOpen())
	}

	@Test
	fun aWholeSessionEndsWithNothingHeld() {
		val store = Store()
		val ledger = store.ledger()
		val sender = FakeSender()
		listOf(
			report("s1", STARTING), report("s1", PLAYING), report("s1", PAUSED),
			report("s2", STARTING), report("s2", PLAYING), report("s2", STOPPED)
		).forEach { deliver(ledger, sender, it) }
		assertEquals("", store.value)
		// One write per change of what the server holds, not one per report.
		assertEquals(3, store.writes)
	}

	private fun snap(id: String, playing: Boolean) =
		PlaybackSnapshot(id, isPlaying = playing, playWhenReady = playing, stopped = false, positionMs = 5_000L)

	@Test
	fun aTeardownRightAfterTheSwipeStillSendsTheStopped() {
		// Q-040: the swipe queues its `stopped` and closes the tracker; the service is destroyed
		// before the queue drains, and release() drops the queue. The ledger still holds s1.
		val store = Store()
		val ledger = store.ledger()
		val tracker = PlaybackReportTracker()
		val sender = FakeSender()
		tracker.onMediaChanged(snap("s1", playing = true), suppressed = false)
			.forEach { deliver(ledger, sender, it) }
		val queuedBySwipe = tracker.onTaskRemoved(snap("s1", playing = true))
		assertEquals(listOf("s1" to STOPPED), queuedBySwipe.map { it.mediaId to it.state })
		// ...never delivered. The teardown:
		val last = ledger.closeOnTeardown(tracker.onTeardown(snap("s1", playing = false)))
		assertEquals(listOf(PlaybackReport("s1", STOPPED, 0L, 1f)), last)
	}

	@Test
	fun aTeardownAfterTheSwipesStoppedLandedSendsNothing() {
		val store = Store()
		val ledger = store.ledger()
		val tracker = PlaybackReportTracker()
		val sender = FakeSender()
		tracker.onMediaChanged(snap("s1", playing = true), suppressed = false)
			.forEach { deliver(ledger, sender, it) }
		tracker.onTaskRemoved(snap("s1", playing = true)).forEach { deliver(ledger, sender, it) }
		assertEquals(emptyList<PlaybackReport>(), ledger.closeOnTeardown(tracker.onTeardown(snap("s1", playing = false))))
	}

	@Test
	fun anOrdinaryTeardownKeepsTheTrackersOwnClose() {
		// The tracker's close carries the real position; the ledger's fallback is only for when
		// the tracker has nothing left to close.
		val store = Store()
		val ledger = store.ledger()
		val tracker = PlaybackReportTracker()
		tracker.onMediaChanged(snap("s1", playing = true), suppressed = false)
			.forEach { deliver(ledger, FakeSender(), it) }
		val last = ledger.closeOnTeardown(tracker.onTeardown(snap("s1", playing = false)))
		assertEquals(listOf(PlaybackReport("s1", STOPPED, 5_000L, 1f)), last)
	}

	@Test
	fun aSessionCutOffMidTrackLeavesTheTrackHeld() {
		val store = Store()
		val ledger = store.ledger()
		val sender = FakeSender(true, true, false)
		listOf(report("s1", STARTING), report("s1", PLAYING), report("s1", STOPPED))
			.forEach { deliver(ledger, sender, it) }
		assertEquals("s1", store.value)
		assertEquals("s1", store.ledger().closeLeftOpen()?.mediaId)
	}
}
