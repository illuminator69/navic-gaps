package paige.navic.domain.manager

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import paige.navic.shared.MediaPlayerViewModel
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

sealed interface SleepTimerMode {
	data object Disabled : SleepTimerMode

	/**
	 * A timed timer. [session] is set when it is the hub session's timer and not one armed
	 * on this device: it then says who set it, and nothing here will fire it.
	 */
	data class Time(val endTime: Instant, val session: SessionSleepTimer? = null) : SleepTimerMode
	data class Songs(val remaining: Int) : SleepTimerMode
	data object EndOfQueue : SleepTimerMode
}

/**
 * What the sleep-timer controls show. The session's timer comes first wherever it is in
 * force: it is the one every device shows. Otherwise the mode armed on this device.
 */
fun shownSleepMode(local: SleepTimerMode, session: SessionSleepTimer?): SleepTimerMode =
	session?.let { SleepTimerMode.Time(Instant.fromEpochMilliseconds(it.endsAtMs), it) } ?: local

/**
 * The sleep timer, in three modes. "After N songs" and "at the end of the queue" are counted
 * here, on this device, as upstream has them. The timed mode is the hub session's whenever the
 * hub holds timers ([HubManager.holdsSleepTimers], PROTOCOL §5.1): setting it sends an act and
 * arms nothing here, it shows on every device, and the hub pauses the session when it runs
 * out. With no hub, or one without the feature, the timed mode is a local timer as before.
 */
class SleepTimerManager(
	private val player: MediaPlayerViewModel,
	private val hubManager: HubManager
) {
	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
	private var job: Job? = null
	private var observerJob: Job? = null
	private var remainingSongs = 0

	/** The mode armed on this device. */
	private val _mode = MutableStateFlow<SleepTimerMode>(SleepTimerMode.Disabled)

	/** The mode to show ([shownSleepMode]): the session's timer, else this device's own. */
	val mode: StateFlow<SleepTimerMode> =
		combine(_mode, hubManager.sessionSleepTimer) { local, session -> shownSleepMode(local, session) }
			.stateIn(
				scope, SharingStarted.Eagerly,
				// Right from the first read: this manager is built by the first sheet that asks.
				shownSleepMode(_mode.value, hubManager.sessionSleepTimer.value)
			)

	val endTimeStamp: Instant?
		get() = (mode.value as? SleepTimerMode.Time)?.endTime

	/** Time left on the timed timer shown; the session's in whole minutes, rounded up. */
	val timeLeft: Duration?
		get() = (mode.value as? SleepTimerMode.Time)?.let { shown ->
			val now = Clock.System.now()
			shown.session?.remainingMinutes(now.toEpochMilliseconds())?.minutes ?: (shown.endTime - now)
		}

	fun startTimer(duration: Duration) {
		stopLocalTimer()
		// One timer for the session, held by the hub: it replaces one that runs there, and
		// nothing is armed on this device.
		if (hubManager.holdsSleepTimers) {
			hubManager.actSleepTimer(duration.inWholeMinutes.toInt().coerceAtLeast(1))
			return
		}
		val endTime = Clock.System.now() + duration
		_mode.value = SleepTimerMode.Time(endTime)

		job = scope.launch {
			delay(duration)
			player.pause()
			stopLocalTimer()
		}
	}

	fun startSongsTimer(count: Int) {
		stopTimer()
		remainingSongs = count
		_mode.value = SleepTimerMode.Songs(count)
		observeSongs()
	}

	fun startEndOfQueueTimer() {
		stopTimer()
		val state = player.uiState.value
		remainingSongs = if (state.currentIndex != -1) {
			state.queue.size - state.currentIndex
		} else {
			0
		}

		if (remainingSongs > 0) {
			_mode.value = SleepTimerMode.EndOfQueue
			observeSongs()
		}
	}

	private fun observeSongs() {
		observerJob?.cancel()
		observerJob = scope.launch {
			player.uiState
				.map { it.currentIndex }
				.distinctUntilChanged()
				.drop(1)
				.collect {
					remainingSongs--
					if (remainingSongs <= 0) {
						player.pause()
						stopLocalTimer()
					} else {
						val currentMode = _mode.value
						if (currentMode is SleepTimerMode.Songs) {
							_mode.value = SleepTimerMode.Songs(remainingSongs)
						}
					}
				}
		}
	}

	/**
	 * Turn the timer off, whichever it is: this device's, and the session's. Choosing a song
	 * mode comes through here first, so one mode stands at a time, as it always did.
	 */
	fun stopTimer() {
		stopLocalTimer()
		hubManager.cancelSleepTimer()
	}

	/** Disarm what this device armed. The session's timer is the hub's and is left alone. */
	private fun stopLocalTimer() {
		job?.cancel()
		job = null
		observerJob?.cancel()
		observerJob = null
		remainingSongs = 0
		_mode.value = SleepTimerMode.Disabled
	}
}
