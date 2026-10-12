package paige.navic.domain.manager

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.core.content.ContextCompat
import paige.navic.shared.DownloadService
import paige.navic.util.Logger

actual class DownloadForegroundController(private val context: Context) {

	// What is held and whether a start is still unanswered. The rules are in ForegroundHold.kt.
	private val lock = Any()
	private var hold = ForegroundHold()
	private var lastToken = 0

	// One start/stop at a time, its call to the system included, so the system hears them in
	// the order they were decided: a `stopService` decided before a start but arriving after
	// it would bring down the service that start is waiting on. The service's own calls take
	// only [lock], so the main thread never waits behind a binder call here.
	private val sendLock = Any()

	// Set when the service gave up on its own. Until then a start is not attempted again:
	// each attempt creates a service the system is about to refuse.
	private var blockedUntil = 0L

	/** What the service is running for; it picks its notification from this. */
	fun heldReasons(): Set<ForegroundReason> = synchronized(lock) { hold.held }

	actual fun start(reason: ForegroundReason) {
		synchronized(sendLock) {
			var token = 0
			val signal = synchronized(lock) {
				if (SystemClock.elapsedRealtime() < blockedUntil) return
				val (next, signal) = hold.acquire(reason, lastToken + 1)
				hold = next
				if (signal == ForegroundSignal.START_FOREGROUND) token = ++lastToken
				signal
			}
			try {
				send(signal, token)
			} catch (e: Exception) {
				// A start from the background is refused on Android 12+ outside the
				// allowed cases. The work still runs — it simply loses the protection
				// — so this must never take the queue down with it.
				synchronized(lock) { hold = hold.refused(reason, token) }
				Logger.w("DownloadForegroundController", "could not start for $reason: ${e.message}")
			}
		}
	}

	actual fun stop(reason: ForegroundReason) {
		synchronized(sendLock) {
			val signal = synchronized(lock) {
				val (next, signal) = hold.release(reason)
				hold = next
				signal
			}
			try {
				send(signal)
			} catch (e: Exception) {
				Logger.w("DownloadForegroundController", "could not stop for $reason: ${e.message}")
			}
		}
	}

	private fun send(signal: ForegroundSignal, token: Int = 0) {
		val intent = Intent(context, DownloadService::class.java)
		when (signal) {
			ForegroundSignal.NONE -> Unit
			ForegroundSignal.START_FOREGROUND ->
				ContextCompat.startForegroundService(context, intent.putExtra(EXTRA_START_TOKEN, token))
			// A service that is already in the foreground only needs telling that its reasons
			// changed, and `startForegroundService` from the background would be refused.
			ForegroundSignal.REASONS_CHANGED -> context.startService(intent)
			ForegroundSignal.STOP -> context.stopService(intent)
		}
	}

	/**
	 * The `onStartCommand` that carries [token] has called `startForeground`, so that start is
	 * no longer pending and the service may be stopped again. Returns what is held now, read in
	 * the same step: while the start was pending no change to the reasons was sent to the
	 * service, so this is where it learns of them, with no gap for a release to fall into.
	 */
	fun onStartAnswered(token: Int): Set<ForegroundReason> = synchronized(lock) {
		hold = hold.answered(token)
		hold.held
	}

	/**
	 * The service stopped ITSELF: the system refused `startForeground`, or the `dataSync`
	 * budget ran out. Nothing is protected any more, so nothing is held, and the next start
	 * waits [blockMs] before trying again. [token] is that of the command that was refused, 0
	 * for a timeout.
	 */
	fun onServiceGaveUp(blockMs: Long, token: Int = 0) {
		synchronized(lock) {
			hold = hold.gaveUp(token)
			blockedUntil = SystemClock.elapsedRealtime() + blockMs
		}
	}

	companion object {
		/** On the Intent of a `startForegroundService`: which start this command answers. */
		const val EXTRA_START_TOKEN = "paige.navic.extra.FOREGROUND_START_TOKEN"
	}
}
