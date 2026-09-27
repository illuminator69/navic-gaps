package paige.navic.domain.manager

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import paige.navic.util.Logger
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock

private const val TAG = "PlaybackReporter"

/** The OpenSubsonic extension that advertises `reportPlayback`. */
private const val PLAYBACK_REPORT_EXTENSION = "playbackReport"

/** A failed capability probe (offline, server down) is retried no sooner than this. */
private const val PROBE_RETRY_MS = 5 * 60_000L

/**
 * A seek is reported once the position settles: a scrub is many seeks, and only the last one
 * says where playback is. Debounced rather than throttled, so the final seek is never dropped.
 */
private const val SEEK_SETTLE_MS = 500L

/** OpenSubsonic `reportPlayback` states, spelled as the wire wants them (lower case). */
enum class PlaybackReportState(val wire: String) {
	STARTING("starting"),
	PLAYING("playing"),
	PAUSED("paused"),
	STOPPED("stopped")
}

data class PlaybackReport(
	val mediaId: String,
	val state: PlaybackReportState,
	val positionMs: Long,
	val playbackRate: Float
)

/** The local player at the moment of an event. */
data class PlaybackSnapshot(
	val mediaId: String?,
	val isPlaying: Boolean,
	/** The user wants it playing: stays true through a rebuffer, false once paused. */
	val playWhenReady: Boolean,
	/** Ended, or idle with nothing loaded. */
	val stopped: Boolean,
	val positionMs: Long,
	val playbackRate: Float = 1f
)

/**
 * Which `reportPlayback` calls a player event implies. Pure, so the rules run in a host test.
 *
 * It tracks the one item Navidrome holds a now-playing entry for on our behalf ([openId]).
 * `suppressed` is true while another hub device plays or scrobbling is off: nothing new is
 * reported then, and an entry still open is closed with one `stopped`. That closing report is
 * what clears the phone out of `getNowPlaying` on a handoff, because `RemoteSessionPlayer`'s swap
 * pauses the local player just after `isRemoteActive` flips.
 */
class PlaybackReportTracker {
	var openId: String? = null
		private set
	private var lastPositionMs = 0L

	fun onMediaChanged(s: PlaybackSnapshot, suppressed: Boolean): List<PlaybackReport> {
		if (suppressed) return close(s)
		val id = reportableId(s.mediaId) ?: return close(s)
		return when {
			s.isPlaying -> start(id, s)
			// Loading the next item with intent to play (a buffering start): `playing` follows
			// from onPlayingChanged once audio actually moves, which is when the spec wants the
			// server's clock to start.
			s.playWhenReady -> listOf(open(id, PlaybackReportState.STARTING, s))
			// Skipped to while paused. Worth reporting only if we were already showing something:
			// a queue restored at launch was never played and should not appear.
			openId != null -> listOf(open(id, PlaybackReportState.PAUSED, s))
			else -> emptyList()
		}
	}

	fun onPlayingChanged(s: PlaybackSnapshot, suppressed: Boolean): List<PlaybackReport> {
		if (suppressed) return close(s)
		val id = reportableId(s.mediaId) ?: return close(s)
		return when {
			s.isPlaying -> if (openId == id) {
				listOf(open(id, PlaybackReportState.PLAYING, s))
			} else {
				start(id, s)
			}
			s.stopped -> close(s)
			// Not playing but still meant to be: a rebuffer, not a pause.
			s.playWhenReady -> emptyList()
			openId == id -> listOf(open(id, PlaybackReportState.PAUSED, s))
			else -> emptyList()
		}
	}

	/** A seek within the item, or a playback-rate change: same state, new position or rate. */
	fun onPositionOrRateChanged(s: PlaybackSnapshot, suppressed: Boolean): List<PlaybackReport> {
		if (suppressed) return close(s)
		val id = reportableId(s.mediaId) ?: return emptyList()
		if (openId != id || s.stopped) return emptyList()
		val state = if (s.playWhenReady) PlaybackReportState.PLAYING else PlaybackReportState.PAUSED
		return listOf(open(id, state, s))
	}

	private fun start(id: String, s: PlaybackSnapshot) = listOf(
		open(id, PlaybackReportState.STARTING, s),
		open(id, PlaybackReportState.PLAYING, s)
	)

	private fun open(id: String, state: PlaybackReportState, s: PlaybackSnapshot): PlaybackReport {
		openId = id
		lastPositionMs = s.positionMs
		return PlaybackReport(id, state, s.positionMs, s.playbackRate)
	}

	private fun close(s: PlaybackSnapshot): List<PlaybackReport> {
		val id = openId ?: return emptyList()
		openId = null
		val position = if (s.mediaId == id) s.positionMs else lastPositionMs
		return listOf(PlaybackReport(id, PlaybackReportState.STOPPED, position, s.playbackRate))
	}

	companion object {
		/** Radio streams and lb-bot previews are not Navidrome songs; the server has no id for them. */
		fun reportableId(mediaId: String?): String? = mediaId
			?.takeUnless { it.isBlank() || it.startsWith("radio_") || PreviewManager.isPreviewId(it) }
	}
}

/**
 * Tells Navidrome what the LOCAL player is doing, through OpenSubsonic `reportPlayback`, so
 * `getNowPlaying` (Navidrome's own panel, tunelog) sees the phone's state and position instead
 * of just the scrobble-time "now playing" ping.
 *
 * Driven by the listener on the local ExoPlayer ([AndroidScrobbleManager]), so the mirrored
 * remote session is never reported from here; the device actually playing reports itself.
 *
 * Fire-and-forget throughout. Reports go out in order through one queue, and a failed call is
 * logged and dropped, never retried and never allowed near playback. Nothing is sent unless the
 * server advertises `playbackReport`, the device is online and scrobbling is enabled (the same
 * privacy switch the scrobble-time now-playing ping honours).
 */
class PlaybackReporter(
	private val sessionManager: SessionManager,
	private val preferenceManager: PreferenceManager,
	private val connectivityManager: ConnectivityManager,
	private val hubManager: HubManager,
	private val scope: CoroutineScope
) {
	private val tracker = PlaybackReportTracker()

	// Bounded: a server that stops answering must not let reports pile up behind it.
	private val outbox = Channel<PlaybackReport>(capacity = 32, onBufferOverflow = BufferOverflow.DROP_OLDEST)

	// Only touched from `scope`, which is the service's main scope, like the listener callbacks.
	private var supported: Boolean? = null
	private var probedAt = 0L
	private var seekJob: Job? = null

	init {
		scope.launch {
			for (report in outbox) send(report)
		}
		// Another login may be another server: probe again rather than trust the last answer.
		scope.launch {
			sessionManager.isLoggedIn.collect {
				supported = null
				probedAt = 0L
			}
		}
	}

	private val suppressed: Boolean
		get() = hubManager.isRemoteActive.value || !preferenceManager.enableScrobbling

	fun onMediaChanged(snapshot: PlaybackSnapshot) {
		seekJob?.cancel()
		enqueue(tracker.onMediaChanged(snapshot, suppressed))
	}

	fun onPlayingChanged(snapshot: PlaybackSnapshot) {
		enqueue(tracker.onPlayingChanged(snapshot, suppressed))
	}

	/** [snapshot] is read once the seek settles, so the report carries where playback landed. */
	fun onSeek(snapshot: () -> PlaybackSnapshot) {
		seekJob?.cancel()
		seekJob = scope.launch {
			delay(SEEK_SETTLE_MS)
			enqueue(tracker.onPositionOrRateChanged(snapshot(), suppressed))
		}
	}

	fun onRateChanged(snapshot: PlaybackSnapshot) {
		enqueue(tracker.onPositionOrRateChanged(snapshot, suppressed))
	}

	fun release() {
		seekJob?.cancel()
		outbox.close()
	}

	private fun enqueue(reports: List<PlaybackReport>) {
		reports.forEach { outbox.trySend(it) }
	}

	private suspend fun send(report: PlaybackReport) {
		if (!connectivityManager.isOnline.value) return
		if (!isSupported()) return
		try {
			withContext(Dispatchers.IO) {
				sessionManager.reportPlayback(
					mediaId = report.mediaId,
					state = report.state.wire,
					positionMs = report.positionMs,
					playbackRate = report.playbackRate
				)
			}
		} catch (e: CancellationException) {
			throw e
		} catch (e: Exception) {
			Logger.w(TAG, "reportPlayback ${report.state.wire} failed", e)
		}
	}

	private suspend fun isSupported(): Boolean {
		supported?.let { return it }
		val now = Clock.System.now().toEpochMilliseconds()
		if (probedAt != 0L && now - probedAt < PROBE_RETRY_MS) return false
		probedAt = now
		return try {
			withContext(Dispatchers.IO) { sessionManager.fetchOpenSubsonicExtensions() }
				.any { it.equals(PLAYBACK_REPORT_EXTENSION, ignoreCase = true) }
				.also { supported = it }
		} catch (e: CancellationException) {
			throw e
		} catch (e: Exception) {
			Logger.w(TAG, "playbackReport capability probe failed", e)
			false
		}
	}
}
