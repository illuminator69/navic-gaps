package paige.navic.shared

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import paige.navic.di.ResourceProvider
import paige.navic.domain.manager.DownloadForegroundController
import paige.navic.domain.manager.DownloadManager
import paige.navic.domain.manager.ForegroundReason
import paige.navic.domain.manager.NotificationIds
import paige.navic.util.Logger

/**
 * The foreground service that lets a download, or a full library sync, survive the app
 * being backgrounded.
 *
 * Deliberately thin: it owns **no** download logic. [DownloadManager] already runs
 * every transfer on a process-scoped `SupervisorJob` that nothing cancels, and the
 * problem was never that the work stopped being scheduled — it was that the process
 * got frozen. So all this does is hold the process in the foreground, and the
 * existing progress notification keeps being published by `NotificationManager` on
 * the same id, which updates this service's own notification in place. The id follows
 * what the service is held for ([DownloadForegroundController.heldReasons]): the download
 * notification while anything is downloading, the sync one when only a sync is running.
 *
 * Modelled on `PlaybackService` next door for placement and Koin access, but
 * started differently: media3 calls `startForeground` itself when playback begins,
 * whereas this has to be started explicitly and must call `startForeground` inside
 * `onStartCommand` or the system kills it.
 */
class DownloadService : Service(), KoinComponent {

	private val downloadManager: DownloadManager by inject()
	private val resourceProvider: ResourceProvider by inject()
	private val controller: DownloadForegroundController by inject()
	private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
	private var wakeLock: PowerManager.WakeLock? = null
	private var foregroundId = 0

	override fun onBind(intent: Intent?): IBinder? = null

	override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
		// Non-zero when this command is the answer to a `startForegroundService`. The system
		// is then waiting for `startForeground`, so it gets one whatever this instance shows.
		val token = intent?.getIntExtra(DownloadForegroundController.EXTRA_START_TOKEN, 0) ?: 0
		if (!enterForeground(controller.heldReasons(), always = token != 0)) {
			giveUp(REFUSED_BLOCK_MS, token)
			return START_NOT_STICKY
		}

		// Only from here may the controller stop this service: stopped while the system still
		// waits for `startForeground`, the whole app dies of
		// ForegroundServiceDidNotStartInTimeException (B-096). While the start was pending the
		// controller therefore sent nothing, so what is held now is read in the step that ends
		// the wait.
		val reasons = controller.onStartAnswered(token)

		// Everything was released meanwhile, and it falls to this service to stop. By
		// `startId`: a start asked for since that read is not taken down with it.
		if (reasons.isEmpty()) {
			stopSelf(startId)
			return START_NOT_STICKY
		}

		// A no-op unless the reasons moved during the wait (a download began under a sync
		// that was starting).
		if (!enterForeground(reasons)) {
			giveUp(REFUSED_BLOCK_MS)
			return START_NOT_STICKY
		}

		acquireWakeLock()
		// NOT sticky: a restarted service with no queue behind it would show a
		// downloading notification over nothing. The manager restarts it when there
		// is work.
		return START_NOT_STICKY
	}

	/**
	 * Stand in the foreground behind the notification [reasons] call for; a no-op when this
	 * service already does, unless [always]. False when the system refused.
	 */
	private fun enterForeground(reasons: Set<ForegroundReason>, always: Boolean = false): Boolean {
		val forSync = reasons == setOf(ForegroundReason.SYNC)
		val id = if (forSync) NotificationIds.SYNC_LIBRARY else NotificationIds.DOWNLOAD_LIBRARY
		if (id == foregroundId && !always) return true

		// A notification must exist before startForeground, and the first one has to
		// come from here: the manager's progress notification is only published once
		// a transfer reports, which is well after the window the system allows.
		val notification = NotificationCompat.Builder(
			this,
			if (forSync) CHANNEL_SYNC_ID else CHANNEL_DOWNLOAD_ID
		)
			.setSmallIcon(resourceProvider.icNavic)
			.setContentTitle(applicationInfo.loadLabel(packageManager).toString())
			.setContentText(if (forSync) SYNCING else DOWNLOADING)
			.setPriority(NotificationCompat.PRIORITY_LOW)
			.setOngoing(true)
			.setOnlyAlertOnce(true)
			.setProgress(0, 0, true)
			.build()

		try {
			ServiceCompat.startForeground(
				this,
				id,
				notification,
				if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
					ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
				} else {
					0
				}
			)
		} catch (e: Exception) {
			// ForegroundServiceStartNotAllowedException and friends. The work keeps
			// running unprotected rather than the whole queue dying here.
			Logger.w("DownloadService", "startForeground refused: ${e.message}")
			return false
		}

		// The notification this service stood behind until now. If its work is over
		// nobody else will take it down: the manager's own cancel was ignored while
		// it was a foreground notification.
		val previous = foregroundId
		foregroundId = id
		if (previous != id && previous == NotificationIds.DOWNLOAD_LIBRARY && ForegroundReason.DOWNLOADS !in reasons) {
			(getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(previous)
		}
		return true
	}

	/**
	 * Android 15's `dataSync` budget — roughly six hours in any 24 — has run out.
	 *
	 * The queue is parked as `FAILED / PAUSED_ERROR`, not left for the next launch to find:
	 * a budget expiry is not an interruption, the files that landed are fine and the rest
	 * are still wanted, so the Download Center should read as paused rather than broken.
	 *
	 * The park runs to its end before the service stops. It used to be launched and then
	 * cancelled by this service's own `onDestroy`, which could leave half the queue running
	 * with no service and no notification. A sync in flight is left to carry on unprotected.
	 */
	override fun onTimeout(startId: Int, fgsType: Int) {
		Logger.i("DownloadService", "dataSync budget expired; parking the queue")
		scope.launch {
			withContext(NonCancellable) { downloadManager.parkActiveDownloads() }
			giveUp(TIMEOUT_BLOCK_MS)
		}
	}

	/**
	 * Stop on the service's own initiative, and tell the controller nothing is held. [token] is
	 * that of the command being given up on, so that its start stops counting as pending.
	 */
	private fun giveUp(blockMs: Long, token: Int = 0) {
		controller.onServiceGaveUp(blockMs, token)
		stopSelf()
	}

	override fun onDestroy() {
		releaseWakeLock()
		scope.cancel()
		ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
		super.onDestroy()
	}

	private fun acquireWakeLock() {
		if (wakeLock != null) return
		val power = getSystemService(Context.POWER_SERVICE) as PowerManager
		wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
			setReferenceCounted(false)
			// Bounded rather than indefinite: a lock this service fails to release
			// is a flat battery, and the service is stopped long before six hours in
			// every path that works.
			acquire(WAKE_LOCK_TIMEOUT_MS)
		}
	}

	private fun releaseWakeLock() {
		wakeLock?.let { if (it.isHeld) it.release() }
		wakeLock = null
	}

	private companion object {
		// Matches NotificationManager.android.kt's own channel, so the progress
		// notification it publishes lands on this service's notification.
		const val CHANNEL_DOWNLOAD_ID = "music_downloads"
		const val CHANNEL_SYNC_ID = "library_sync"
		const val DOWNLOADING = "Downloading…"
		const val SYNCING = "Syncing…"
		// After a refusal, long enough that a queue of jobs does not create a service each.
		const val REFUSED_BLOCK_MS = 30_000L
		// The budget is gone for hours; asking again soon only gets refused.
		const val TIMEOUT_BLOCK_MS = 10L * 60L * 1000L
		const val WAKE_LOCK_TAG = "navic:downloads"
		const val WAKE_LOCK_TIMEOUT_MS = 6L * 60L * 60L * 1000L
	}
}
