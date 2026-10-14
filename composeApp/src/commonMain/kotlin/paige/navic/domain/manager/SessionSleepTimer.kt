package paige.navic.domain.manager

/*
 * The session's sleep timer (PROTOCOL §4.2 and §5.1): ONE timer, held by the hub, set from any
 * device and shown on all of them. The hub pauses the session when it runs out, whether or not
 * the device that set it is still awake.
 *
 * A `session` frame says how long is left at the moment the frame was built, and the hub
 * sends nothing while the timer counts down. So this device keeps the END, on its own clock,
 * worked out when the frame arrived, and counts down to that.
 *
 * Timed only. "After N songs" and "at the end of the queue" act at a track boundary, which
 * only the playing device sees: they stay local (`SleepTimerManager`).
 *
 * Free of the socket and of the player so that the arithmetic runs in a test
 * (`SessionSleepTimerTest`).
 */

/** The longest timer the hub takes, in minutes. */
internal const val SLEEP_TIMER_MAX_MINUTES = 1_440

/**
 * How long past the timer's end the playing device waits for the hub's pause before it
 * pauses itself.
 */
internal const val SLEEP_GUARD_GRACE_MS = 5_000L

/**
 * The session's timer as the last frame gave it: when it ends on THIS device's clock, the
 * minutes that were asked for, and the id of the device that set it (which may have gone).
 */
data class SessionSleepTimer(val endsAtMs: Long, val minutes: Int, val setBy: String?)

/**
 * `session.sleepTimer` of a frame that arrived at [arrivedAtMs]. Null for no timer, and for
 * one with no readable `remainingMs`: a timer whose end is unknown cannot be counted down.
 */
fun sessionSleepTimer(remainingMs: Long?, minutes: Int?, setBy: String?, arrivedAtMs: Long): SessionSleepTimer? {
	remainingMs ?: return null
	val remaining = remainingMs.coerceIn(0L, SLEEP_TIMER_MAX_MINUTES * 60_000L)
	return SessionSleepTimer(
		endsAtMs = arrivedAtMs + remaining,
		// What was asked for is only shown; a frame without it is still a timer.
		minutes = minutes ?: ((remaining + 59_999) / 60_000).toInt(),
		setBy = setBy?.takeIf { it.isNotBlank() }
	)
}

/** How long the timer has left at [nowMs], never below zero. */
fun SessionSleepTimer.remainingMs(nowMs: Long): Long = (endsAtMs - nowMs).coerceAtLeast(0)

/**
 * The minutes a countdown shows, rounded UP: a timer set for 30 minutes starts at 30, and its
 * last minute reads 1 until it is over.
 */
fun SessionSleepTimer.remainingMinutes(nowMs: Long): Int =
	((remainingMs(nowMs) + 59_999) / 60_000).toInt().coerceAtLeast(1)

/**
 * The timer where this device can vouch for it, else null. With the link up to a hub that
 * holds timers it is in force on every device. With the link down only the playing device
 * still knows what will happen: its own guard ([sleepGuard]) pauses it. A controller that has
 * lost the hub shows nothing, since the hub may have fired or been told otherwise since.
 */
fun sessionTimerInForce(
	timer: SessionSleepTimer?,
	hubHoldsTimers: Boolean,
	isActiveDevice: Boolean
): SessionSleepTimer? = timer?.takeIf { hubHoldsTimers || isActiveDevice }

/**
 * The name to give for who set the timer, or null when it was this device or nobody is
 * named: only another device's hand needs saying. A device the list no longer has is still
 * "another device".
 */
fun sleepTimerSetOn(timer: SessionSleepTimer, myDeviceId: String?, devices: List<HubDevice>): String? {
	val setBy = timer.setBy ?: return null
	if (setBy == myDeviceId) return null
	return devices.firstOrNull { it.id == setBy }?.name?.takeIf { it.isNotBlank() } ?: "another device"
}

/** What the playing device's guard does about the mirrored timer on one look. */
enum class SleepGuard {
	/** No timer, or it has not been over for [SLEEP_GUARD_GRACE_MS] yet. */
	WAIT,

	/** Over, and nothing here to stop: the timer is forgotten. */
	DROP,

	/** Over, the hub's pause never came, and this device is still playing: pause, and forget it. */
	PAUSE
}

/**
 * The local guard of the session's timer. The hub pauses the session at the timer's end and
 * says so in a frame that also clears the timer, so a timer still in the mirror
 * [SLEEP_GUARD_GRACE_MS] past its end is one the hub never got to act on from here: the link
 * dropped. The device that is playing then stops itself, or the music runs all night.
 *
 * Only the active device pauses, and only if it is playing. Everywhere else an expired timer
 * is just dropped. A wall clock set back keeps the timer waiting; the hub's own clock is the
 * one that fires it then.
 */
fun sleepGuard(
	timer: SessionSleepTimer?,
	isActiveDevice: Boolean,
	isPlaying: Boolean,
	nowMs: Long
): SleepGuard = when {
	timer == null || nowMs < timer.endsAtMs + SLEEP_GUARD_GRACE_MS -> SleepGuard.WAIT
	isActiveDevice && isPlaying -> SleepGuard.PAUSE
	else -> SleepGuard.DROP
}
