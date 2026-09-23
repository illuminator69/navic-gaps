package paige.navic.shared

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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import paige.navic.di.ResourceProvider
import paige.navic.domain.manager.DownloadManager
import paige.navic.util.Logger

/**
 * The foreground service that lets a download survive the app being backgrounded.
 *
 * Deliberately thin: it owns **no** download logic. [DownloadManager] already runs
 * every transfer on a process-scoped `SupervisorJob` that nothing cancels, and the
 * problem was never that the work stopped being scheduled — it was that the process
 * got frozen. So all this does is hold the process in the foreground, and the
 * existing progress notification keeps being published by `NotificationManager` on
 * the same id, which updates this service's own notification in place.
 *
 * Modelled on `PlaybackService` next door for placement and Koin access, but
 * started differently: media3 calls `startForeground` itself when playback begins,
 * whereas this has to be started explicitly and must call `startForeground` inside
 * `onStartCommand` or the system kills it.
 */
class DownloadService : Service(), KoinComponent {

	private val downloadManager: DownloadManager by inject()
	private val resourceProvider: ResourceProvider by inject()
	private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
	private var wakeLock: PowerManager.WakeLock? = null

	override fun onBind(intent: Intent?): IBinder? = null

	override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
		// A notification must exist before startForeground, and the first one has to
		// come from here: the manager's progress notification is only published once
		// a transfer reports, which is well after the window the system allows.
		val notification = NotificationCompat.Builder(this, CHANNEL_DOWNLOAD_ID)
			.setSmallIcon(resourceProvider.icNavic)
			.setContentTitle(applicationInfo.loadLabel(packageManager).toString())
			.setContentText(DOWNLOADING)
			.setPriority(NotificationCompat.PRIORITY_LOW)
			.setOngoing(true)
			.setOnlyAlertOnce(true)
			.setProgress(0, 0, true)
			.build()

		try {
			ServiceCompat.startForeground(
				this,
				NOTIFICATION_ID,
				notification,
				if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
					ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
				} else {
					0
				}
			)
		} catch (e: Exception) {
			// ForegroundServiceStartNotAllowedException and friends. Downloads keep
			// running unprotected rather than the whole queue dying here.
			Logger.w("DownloadService", "startForeground refused: ${e.message}")
			stopSelf()
			return START_NOT_STICKY
		}

		acquireWakeLock()
		// NOT sticky: a restarted service with no queue behind it would show a
		// downloading notification over nothing. The manager restarts it when there
		// is work.
		return START_NOT_STICKY
	}

	/**
	 * Android 15's `dataSync` budget — roughly six hours in any 24 — has run out.
	 *
	 * The rows are parked **QUEUED**, not FAILED. A budget expiry is not an
	 * interruption: the files that landed are fine and the rest are still wanted, so
	 * the Download Center should read as paused rather than broken. This is the one
	 * place the distinction is visible, and `reconcileInterruptedDownloads` would
	 * otherwise mark every one of them `FAILED / "Interrupted"` on next launch.
	 */
	override fun onTimeout(startId: Int, fgsType: Int) {
		Logger.i("DownloadService", "dataSync budget expired; parking the queue")
		scope.launch { downloadManager.parkActiveDownloads() }
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
		const val NOTIFICATION_ID = 1001
		const val DOWNLOADING = "Downloading…"
		const val WAKE_LOCK_TAG = "navic:downloads"
		const val WAKE_LOCK_TIMEOUT_MS = 6L * 60L * 60L * 1000L
	}
}
