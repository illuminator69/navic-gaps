package paige.navic.domain.manager

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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

/** How long the service's teardown `stopped` may take, the wait for an in-flight report included. */
private const val TEARDOWN_SEND_MS = 2_000L

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
 * what clears the phone out of `getNowPlaying` on a handoff: [onRemoteActive] sends it when
 * `isRemoteActive` flips, or the swap's pause of the local player does, whichever comes first.
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

	/**
	 * The playback service is being destroyed: the open entry's one `stopped`, and nothing after
	 * it. Without this the entry lingers in `getNowPlaying` until Navidrome expires it (a paused
	 * one for 30 minutes).
	 */
	fun onTeardown(s: PlaybackSnapshot): List<PlaybackReport> = close(s)

	/**
	 * The app was swiped away from recents (B-036). The service pauses and asks to stop, but the
	 * app's own in-process MediaController keeps it bound, so it is not destroyed and [onTeardown]
	 * never runs: close the entry here. Playing again later opens a fresh one.
	 */
	fun onTaskRemoved(s: PlaybackSnapshot): List<PlaybackReport> = close(s)

	/**
	 * Another hub device just became the active one: close the entry whatever the local play
	 * state. The swap's `pause()` closes it through [onPlayingChanged] only when local was
	 * playing; paused already, it fires no event and the entry stayed open. Whichever of the two
	 * comes first sends the one `stopped`, and the other finds nothing open.
	 */
	fun onRemoteActive(s: PlaybackSnapshot): List<PlaybackReport> = close(s)

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
		/**
		 * Whether `reportPlayback` holds this device's now-playing entry right now, which makes the
		 * legacy `scrobble(submission=false)` ping redundant (B-027). It needs both: the server known
		 * to take it, and a reporter not suppressed. Suppressed, the reporter sends nothing, and the
		 * ping is the only now-playing local playback has.
		 */
		fun coversNowPlaying(supported: Boolean?, suppressed: Boolean): Boolean =
			supported == true && !suppressed

		/** Radio streams and lb-bot previews are not Navidrome songs; the server has no id for them. */
		fun reportableId(mediaId: String?): String? = mediaId
			?.takeUnless { it.isBlank() || it.startsWith("radio_") || PreviewManager.isPreviewId(it) }
	}
}

/**
 * The now-playing entry Navidrome may still hold for this device, kept across processes (Q-042).
 *
 * The tracker's [PlaybackReportTracker.openId] lives in memory, so a force stop (or a freeze
 * before [PlaybackReporter.release]'s `stopped` leaves) forgets an entry the server still shows:
 * `playing` until the track's remaining time runs out, `paused` for about 30 minutes. So the id
 * of every report the server CONFIRMED is stored, and the next reporter closes it first.
 *
 * Pure apart from the [load] / [save] pair, so the rules run in a host test with a fake sender.
 * A report the server did not confirm changes nothing: a dropped `stopped` keeps the id for the
 * next start, and a dropped `playing` was never shown. A `stopped` clears the id only when it is
 * for that exact id, because Navidrome ignores a `stopped` whose mediaId differs from the entry.
 */
class PlaybackReportLedger(
	private val load: () -> String,
	private val save: (String) -> Unit
) {
	/** The `stopped` that closes an entry an earlier process left open, or null when none is. */
	fun closeLeftOpen(): PlaybackReport? = load()
		.takeIf { it.isNotBlank() }
		?.let { PlaybackReport(it, PlaybackReportState.STOPPED, positionMs = 0L, playbackRate = 1f) }

	/** Send [report] through [send], then remember what the server holds if it confirmed. */
	suspend fun deliver(report: PlaybackReport, send: suspend (PlaybackReport) -> Boolean): Boolean {
		val confirmed = send(report)
		if (confirmed) record(report)
		return confirmed
	}

	private fun record(report: PlaybackReport) {
		val held = load()
		val next = when (report.state) {
			PlaybackReportState.STOPPED -> if (report.mediaId == held) "" else held
			else -> report.mediaId
		}
		// Written on a change only: most reports re-confirm the id already held.
		if (next != held) save(next)
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

	private val ledger = PlaybackReportLedger(
		load = { preferenceManager.playbackReportOpenId },
		save = { preferenceManager.playbackReportOpenId = it }
	)

	// Bounded: a server that stops answering must not let reports pile up behind it.
	private val outbox = Channel<PlaybackReport>(capacity = 32, onBufferOverflow = BufferOverflow.DROP_OLDEST)

	// Only touched from `scope`, which is the service's main scope, like the listener callbacks.
	private var supported: Boolean? = null
	private var probedAt = 0L
	private var seekJob: Job? = null
	private val consumer: Job

	init {
		// Q-042: close what an earlier process left open (a force stop, or a teardown frozen before
		// its `stopped` left), first in the queue and before the consumer runs, so nothing this
		// reporter sends can overtake it. It is for the stored id exactly: Navidrome ignores a
		// `stopped` for any other. Offline or unsupported, it is dropped and the id stays stored.
		ledger.closeLeftOpen()?.let { outbox.trySend(it) }
		consumer = scope.launch {
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

	/**
	 * True while this reporter holds the device's now-playing entry: the server is known to take
	 * `reportPlayback` and nothing suppresses it (no other hub device active, scrobbling on).
	 * Navidrome (0.64) files `scrobble(submission=false)` under the same player's one entry, as a
	 * `playing` report at position 0, and forwards NowPlaying to Last.fm / ListenBrainz for either
	 * call, so `ScrobbleManager` skips its ping while this holds. Support unknown (not yet probed)
	 * or absent, or the reporter suppressed, reads false, and the ping still goes.
	 */
	val reportsNowPlaying: Boolean
		get() = PlaybackReportTracker.coversNowPlaying(supported, suppressed)

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

	/**
	 * Swiped away from recents: see [PlaybackReportTracker.onTaskRemoved]. Through the outbox, not
	 * like [release]: the service lives on, so the queue stays open and keeps its order. Called
	 * before the swipe's pause, which then finds nothing open and reports nothing.
	 */
	fun onTaskRemoved(snapshot: PlaybackSnapshot) {
		seekJob?.cancel()
		enqueue(tracker.onTaskRemoved(snapshot))
	}

	/** `isRemoteActive` turned true: see [PlaybackReportTracker.onRemoteActive]. */
	fun onRemoteActive(snapshot: PlaybackSnapshot) {
		enqueue(tracker.onRemoteActive(snapshot))
	}

	/**
	 * The service is being destroyed, and [scope] with it. Closes the open entry with one
	 * `stopped`, sent from [teardownScope] because [scope] is cancelled right after this returns.
	 *
	 * The `stopped` is the last word. Reports still queued are dropped, and one already in flight
	 * (typically the `paused` from `onTaskRemoved`) is cancelled and waited out first, so a stale
	 * `paused` cannot land after it and reopen the entry. Sent only when the server is already
	 * known to support it: never a capability probe from a dying service.
	 */
	fun release(snapshot: PlaybackSnapshot) {
		seekJob?.cancel()
		outbox.cancel()
		consumer.cancel()
		val last = tracker.onTeardown(snapshot)
		if (last.isEmpty() || supported != true || !connectivityManager.isOnline.value) return
		// Best-effort: once the service is gone the process may be cached, and Android's freezer
		// can suspend it before the call leaves (CLAUDE.md §7). Navidrome then expires the entry.
		teardownScope.launch {
			withTimeoutOrNull(TEARDOWN_SEND_MS) {
				consumer.join()
				last.forEach { post(it) }
			}
		}
	}

	private fun enqueue(reports: List<PlaybackReport>) {
		reports.forEach { outbox.trySend(it) }
	}

	private suspend fun send(report: PlaybackReport) {
		if (!connectivityManager.isOnline.value) return
		if (!isSupported()) return
		post(report)
	}

	/** Through [ledger], so every report the server confirms is remembered across processes (Q-042). */
	private suspend fun post(report: PlaybackReport) {
		ledger.deliver(report) {
			try {
				withContext(Dispatchers.IO) {
					sessionManager.reportPlayback(
						mediaId = it.mediaId,
						state = it.state.wire,
						positionMs = it.positionMs,
						playbackRate = it.playbackRate
					)
				}
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				Logger.w(TAG, "reportPlayback ${it.state.wire} failed", e)
				false
			}
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

	private companion object {
		/**
		 * Outlives the service, for [release]'s `stopped`. Process-lifetime and never cancelled:
		 * every job on it is bounded by [TEARDOWN_SEND_MS].
		 */
		val teardownScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
	}
}
