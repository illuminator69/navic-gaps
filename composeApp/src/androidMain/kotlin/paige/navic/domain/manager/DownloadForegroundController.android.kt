package paige.navic.domain.manager

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.core.content.ContextCompat
import paige.navic.shared.DownloadService
import paige.navic.util.Logger

actual class DownloadForegroundController(private val context: Context) {

	private val lock = Any()
	private val held = mutableSetOf<ForegroundReason>()

	// Set when the service gave up on its own. Until then a start is not attempted again:
	// each attempt creates a service the system is about to refuse.
	private var blockedUntil = 0L

	/** What the service is running for; it picks its notification from this. */
	fun heldReasons(): Set<ForegroundReason> = synchronized(lock) { held.toSet() }

	actual fun start(reason: ForegroundReason) {
		val first = synchronized(lock) {
			if (SystemClock.elapsedRealtime() < blockedUntil) return
			// Already held: no Intent per claimed job.
			if (!held.add(reason)) return
			held.size == 1
		}
		try {
			val intent = Intent(context, DownloadService::class.java)
			// A service that is already in the foreground only needs telling that its reasons
			// changed, and `startForegroundService` from the background would be refused.
			if (first) ContextCompat.startForegroundService(context, intent)
			else context.startService(intent)
		} catch (e: Exception) {
			// A start from the background is refused on Android 12+ outside the
			// allowed cases. The work still runs — it simply loses the protection
			// — so this must never take the queue down with it.
			synchronized(lock) { held.remove(reason) }
			Logger.w("DownloadForegroundController", "could not start for $reason: ${e.message}")
		}
	}

	actual fun stop(reason: ForegroundReason) {
		val empty = synchronized(lock) {
			held.remove(reason)
			held.isEmpty()
		}
		try {
			val intent = Intent(context, DownloadService::class.java)
			// Stopping a service that is not running is a no-op, so this needs no "was it
			// started" bookkeeping, which is the flag that used to go stale.
			if (empty) context.stopService(intent)
			else context.startService(intent)
		} catch (e: Exception) {
			Logger.w("DownloadForegroundController", "could not stop for $reason: ${e.message}")
		}
	}

	/**
	 * The service stopped ITSELF: the system refused `startForeground`, or the `dataSync`
	 * budget ran out. Nothing is protected any more, so nothing is held, and the next start
	 * waits [blockMs] before trying again.
	 */
	fun onServiceGaveUp(blockMs: Long) {
		synchronized(lock) {
			held.clear()
			blockedUntil = SystemClock.elapsedRealtime() + blockMs
		}
	}
}
