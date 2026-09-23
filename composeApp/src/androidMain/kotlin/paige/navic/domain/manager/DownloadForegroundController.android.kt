package paige.navic.domain.manager

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import paige.navic.shared.DownloadService
import paige.navic.util.Logger
import kotlin.concurrent.Volatile

actual class DownloadForegroundController(private val context: Context) {

	// Only so a start/stop storm does not fire an Intent per claimed job — the
	// service itself is idempotent either way.
	@Volatile
	private var running = false

	actual fun start() {
		if (running) return
		running = true
		try {
			ContextCompat.startForegroundService(
				context,
				Intent(context, DownloadService::class.java)
			)
		} catch (e: Exception) {
			// A start from the background is refused on Android 12+ outside the
			// allowed cases. Downloads still run — they simply lose the protection
			// — so this must never take the queue down with it.
			running = false
			Logger.w("DownloadForegroundController", "could not start: ${e.message}")
		}
	}

	actual fun stop() {
		if (!running) return
		running = false
		try {
			context.stopService(Intent(context, DownloadService::class.java))
		} catch (e: Exception) {
			Logger.w("DownloadForegroundController", "could not stop: ${e.message}")
		}
	}
}
