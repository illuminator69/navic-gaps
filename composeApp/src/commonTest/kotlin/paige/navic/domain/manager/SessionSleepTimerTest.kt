package paige.navic.domain.manager

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/*
 * The rules in SessionSleepTimer.kt: the hub's one sleep timer as this device mirrors it, and
 * the guard that stops the playing device when the hub's pause never arrives.
 */
class SessionSleepTimerTest {
	private val t0 = 1_000_000L

	private fun timer(remainingMs: Long = 30 * 60_000L, setBy: String? = "desk") =
		sessionSleepTimer(remainingMs, minutes = 30, setBy = setBy, arrivedAtMs = t0)!!

	@Test
	fun aFrameWithNoRemainingTimeIsNoTimer() {
		assertNull(sessionSleepTimer(null, minutes = 30, setBy = "desk", arrivedAtMs = t0))
	}

	@Test
	fun theEndIsCountedFromWhenTheFrameArrivedOnThisClock() {
		val timer = timer(remainingMs = 90_000)
		assertEquals(t0 + 90_000, timer.endsAtMs)
		assertEquals(60_000L, timer.remainingMs(t0 + 30_000))
		// Never below zero, however late it is looked at.
		assertEquals(0L, timer.remainingMs(t0 + 500_000))
	}

	@Test
	fun aFrameWithoutTheMinutesAskedForIsStillATimer() {
		val timer = sessionSleepTimer(90_000, minutes = null, setBy = " ", arrivedAtMs = t0)!!
		assertEquals(2, timer.minutes)
		// A blank id names nobody.
		assertNull(timer.setBy)
	}

	@Test
	fun theCountdownRoundsUpAndItsLastMinuteReadsOne() {
		val timer = timer()
		assertEquals(30, timer.remainingMinutes(t0))
		assertEquals(30, timer.remainingMinutes(t0 + 1))
		assertEquals(29, timer.remainingMinutes(t0 + 60_000))
		assertEquals(1, timer.remainingMinutes(t0 + 30 * 60_000L - 1))
		assertEquals(1, timer.remainingMinutes(t0 + 30 * 60_000L + 10_000))
	}

	@Test
	fun aControllerThatLostTheHubShowsNoTimer() {
		val timer = timer()
		assertEquals(timer, sessionTimerInForce(timer, hubHoldsTimers = true, isActiveDevice = false))
		// The playing device still knows what will happen: its own guard.
		assertEquals(timer, sessionTimerInForce(timer, hubHoldsTimers = false, isActiveDevice = true))
		assertNull(sessionTimerInForce(timer, hubHoldsTimers = false, isActiveDevice = false))
		assertNull(sessionTimerInForce(null, hubHoldsTimers = true, isActiveDevice = true))
	}

	@Test
	fun onlyAnotherDevicesHandIsNamed() {
		val desk = HubDevice(
			id = "desk", name = "Desk", platform = "feishin", online = true, isActive = false, volume = 100
		)
		assertNull(sleepTimerSetOn(timer(setBy = "phone"), myDeviceId = "phone", devices = listOf(desk)))
		assertNull(sleepTimerSetOn(timer(setBy = null), myDeviceId = "phone", devices = listOf(desk)))
		assertEquals("Desk", sleepTimerSetOn(timer(), myDeviceId = "phone", devices = listOf(desk)))
		// A device the list no longer has.
		assertEquals("another device", sleepTimerSetOn(timer(), myDeviceId = "phone", devices = emptyList()))
	}

	@Test
	fun theGuardWaitsFiveSecondsPastTheEndForTheHubsPause() {
		val timer = timer(remainingMs = 60_000)
		val end = t0 + 60_000
		assertEquals(SleepGuard.WAIT, sleepGuard(null, isActiveDevice = true, isPlaying = true, nowMs = end + 60_000))
		assertEquals(SleepGuard.WAIT, sleepGuard(timer, isActiveDevice = true, isPlaying = true, nowMs = end + SLEEP_GUARD_GRACE_MS - 1))
		// The hub's frame never came: the link dropped, and the music would run all night.
		assertEquals(SleepGuard.PAUSE, sleepGuard(timer, isActiveDevice = true, isPlaying = true, nowMs = end + SLEEP_GUARD_GRACE_MS))
		// Nothing here to stop: the timer is only forgotten.
		assertEquals(SleepGuard.DROP, sleepGuard(timer, isActiveDevice = true, isPlaying = false, nowMs = end + SLEEP_GUARD_GRACE_MS))
		assertEquals(SleepGuard.DROP, sleepGuard(timer, isActiveDevice = false, isPlaying = true, nowMs = end + SLEEP_GUARD_GRACE_MS))
	}
}
