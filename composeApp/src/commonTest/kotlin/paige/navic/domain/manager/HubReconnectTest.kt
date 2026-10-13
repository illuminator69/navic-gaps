package paige.navic.domain.manager

import kotlin.test.Test
import kotlin.test.assertEquals

/*
 * The rules in HubReconnect.kt. The case that shipped wrong (Q-048) is the second test: a link
 * that gets `welcome` and drops used to start the backoff over every time, so it redialled a
 * second later for as long as it kept doing that. The rest is the kick: the network came back,
 * dial now, but not once per flap.
 */
class HubReconnectTest {
	private val t0 = 1_000_000L

	private fun backoff() = HubReconnectBackoff(initialMs = 1_000, maxMs = 30_000, stableMs = 10_000)

	@Test
	fun theBackoffDoublesUpToItsCeiling() {
		val b = backoff()
		assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L), List(7) { b.next() })
	}

	@Test
	fun aLinkThatDropsRightAfterWelcomeKeepsBackingOff() {
		val b = backoff()
		val waits = List(4) {
			// Welcomed, and gone three seconds later.
			b.onLinkEnded(welcomedAtMs = t0, endedAtMs = t0 + 3_000)
			b.next()
		}
		assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L), waits)
	}

	@Test
	fun aLinkThatHeldStartsTheBackoffOver() {
		val b = backoff()
		repeat(5) { b.next() }
		b.onLinkEnded(welcomedAtMs = t0, endedAtMs = t0 + 10_000)
		assertEquals(1_000L, b.next())
		assertEquals(2_000L, b.next())
	}

	@Test
	fun tenSecondsIsTheLine() {
		val b = backoff()
		b.next()
		b.onLinkEnded(welcomedAtMs = t0, endedAtMs = t0 + 9_999)
		assertEquals(2_000L, b.next())
	}

	@Test
	fun anAttemptThatNeverGotWelcomeChangesNothing() {
		val b = backoff()
		b.next()
		b.next()
		b.onLinkEnded(welcomedAtMs = null, endedAtMs = t0 + 60_000)
		assertEquals(4_000L, b.next())
	}

	@Test
	fun aKickStartsTheBackoffOver() {
		val b = backoff()
		repeat(6) { b.next() }
		b.reset()
		assertEquals(1_000L, b.next())
	}

	// --- the kick's floor ------------------------------------------------------------------

	private fun wait(sinceLastKickedMs: Long, backoffLeftMs: Long = 30_000) =
		kickedWaitMs(sinceLastKickedMs, backoffLeftMs, floorMs = 2_000)

	@Test
	fun aKickDialsAtOnce() {
		// The first kick of the process, and any kick long after the last one.
		assertEquals(0L, wait(sinceLastKickedMs = t0))
		assertEquals(0L, wait(sinceLastKickedMs = 2_000))
	}

	@Test
	fun kickedAttemptsAreTwoSecondsApart() {
		assertEquals(1_500L, wait(sinceLastKickedMs = 500))
		assertEquals(1L, wait(sinceLastKickedMs = 1_999))
	}

	@Test
	fun aKickNeverMakesAnAttemptLaterThanItWasGoingToBe() {
		// 400 ms of a one-second backoff were left when the kick came.
		assertEquals(400L, wait(sinceLastKickedMs = 500, backoffLeftMs = 400))
		assertEquals(0L, wait(sinceLastKickedMs = 500, backoffLeftMs = -20))
	}

	@Test
	fun aClockThatWentBackwardsWaitsOutTheBackoffAtMost() {
		assertEquals(1_000L, wait(sinceLastKickedMs = -60_000, backoffLeftMs = 1_000))
	}
}
