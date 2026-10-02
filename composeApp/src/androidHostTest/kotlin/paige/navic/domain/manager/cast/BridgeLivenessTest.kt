package paige.navic.domain.manager.cast

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/*
 * B-045 (defensive; the cause is unproven): a cast bridge stuck on "connecting…" for as long as
 * the app lived. These are the two pure pieces of the fix: the backoff resets on `welcome`, and
 * reconcile's verdict on a bridge that never got back to the hub.
 */
class BridgeLivenessTest {
	@Test
	fun theBackoffDoublesUpToItsCeiling() {
		val b = ReconnectBackoff(initialMs = 1_000, maxMs = 30_000)
		assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L), List(7) { b.next() })
	}

	@Test
	fun aWelcomeStartsTheBackoffOver() {
		val b = ReconnectBackoff(initialMs = 1_000, maxMs = 30_000)
		repeat(6) { b.next() }
		b.onWelcome()
		assertEquals(1_000L, b.next())
		assertEquals(2_000L, b.next())
	}

	private val t0 = 1_000_000L

	private fun stale(
		connected: Boolean = false,
		loopAlive: Boolean = true,
		standingDown: Boolean = false,
		downSince: Long = t0,
		hubUpSince: Long? = t0,
		now: Long = t0 + BRIDGE_STALE_MS + 1
	) = bridgeIsStale(connected, loopAlive, standingDown, downSince, hubUpSince, now)

	@Test
	fun aConnectedBridgeIsNeverStale() {
		assertFalse(stale(connected = true))
		assertFalse(stale(connected = true, loopAlive = false))
	}

	@Test
	fun aDeadLoopIsStaleAtOnce() {
		assertTrue(stale(loopAlive = false, now = t0 + 1))
		assertTrue(stale(loopAlive = false, hubUpSince = null, now = t0 + 1))
	}

	@Test
	fun aBridgeStandingDownIsLeftToItsOwnCallback() {
		assertFalse(stale(loopAlive = false, standingDown = true))
	}

	@Test
	fun aLiveLoopGetsTwoMinutesShortOfTheHub() {
		assertFalse(stale(now = t0 + BRIDGE_STALE_MS))
		assertTrue(stale(now = t0 + BRIDGE_STALE_MS + 1))
	}

	@Test
	fun theClockRunsOnlyWhileTheHubIsUp() {
		// Hub down: nobody can get in, so nothing is stale however long it lasts.
		assertFalse(stale(hubUpSince = null, now = t0 + 60 * BRIDGE_STALE_MS))
		// The bridge has been down an hour, but the hub came back a minute ago.
		val now = t0 + 60 * 60_000L
		assertFalse(stale(hubUpSince = now - 60_000L, now = now))
		assertTrue(stale(hubUpSince = now - BRIDGE_STALE_MS - 1, now = now))
	}
}
