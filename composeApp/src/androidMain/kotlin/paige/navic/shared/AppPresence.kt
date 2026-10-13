package paige.navic.shared

import android.app.Activity
import android.app.Application
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import paige.navic.domain.manager.volumeKeysGoRemote
import paige.navic.util.Logger

/**
 * Whether Navic is on screen and whether the phone is locked: the two facts that decide who
 * the volume buttons belong to during remote playback (`volumeKeysGoRemote`, `VolumeKeys.kt`).
 *
 * Created with the process, before the first Activity, so that the count of started
 * activities is right from the first one. Everything here runs on the main thread: the
 * lifecycle callbacks and the receiver are both delivered there.
 */
class AppPresence(app: Application) {
	private val power = app.getSystemService(PowerManager::class.java)
	private val keyguard = app.getSystemService(KeyguardManager::class.java)

	private val main = Handler(Looper.getMainLooper())

	private var startedActivities = 0
	private var locked = readLocked()

	private val _volumeKeysGoRemote = MutableStateFlow(volumeKeysGoRemote(false, locked))
	val volumeKeysGoRemote: StateFlow<Boolean> = _volumeKeysGoRemote.asStateFlow()

	/** Screen off counts: the keyguard may engage seconds later, and nothing is in front meanwhile. */
	private fun readLocked(): Boolean =
		power?.isInteractive == false || keyguard?.isKeyguardLocked == true

	private fun publish() {
		val remote = volumeKeysGoRemote(startedActivities > 0, locked)
		if (remote == _volumeKeysGoRemote.value) return
		_volumeKeysGoRemote.value = remote
		Logger.i(
			"AppPresence",
			"volume buttons: ${if (remote) "the playing device" else "this phone"}" +
				" (on screen=${startedActivities > 0}, locked=$locked)"
		)
	}

	/**
	 * Ask the system again a moment after an event. The keyguard's answer can still be the old
	 * one while the broadcast that announces the change is being delivered, and a phone left
	 * believing it is locked would send the buttons to the playing device from every app.
	 */
	private val reread = Runnable {
		locked = readLocked()
		publish()
	}

	private fun rereadSoon() {
		main.removeCallbacks(reread)
		main.postDelayed(reread, 1_000)
	}

	init {
		app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
			override fun onActivityStarted(activity: Activity) {
				startedActivities++
				locked = readLocked()
				publish()
			}

			override fun onActivityStopped(activity: Activity) {
				startedActivities = (startedActivities - 1).coerceAtLeast(0)
				locked = readLocked()
				publish()
				rereadSoon()
			}

			override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
			override fun onActivityResumed(activity: Activity) = Unit
			override fun onActivityPaused(activity: Activity) = Unit
			override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
			override fun onActivityDestroyed(activity: Activity) = Unit
		})
		// Screen off, screen on (the lock screen may be what it shows) and unlocked. Exported:
		// "unlocked" is sent by the system UI's own uid, which a receiver that is not exported
		// never hears, and the phone then stayed "locked" until Navic was next opened. All three
		// are protected broadcasts, so nothing but the system can send them.
		ContextCompat.registerReceiver(
			app,
			object : BroadcastReceiver() {
				override fun onReceive(context: Context?, intent: Intent?) {
					locked = when (intent?.action) {
						Intent.ACTION_SCREEN_OFF -> true
						// The user is past the lock screen, whatever the keyguard still says.
						Intent.ACTION_USER_PRESENT -> false
						else -> readLocked()
					}
					publish()
					if (intent?.action != Intent.ACTION_USER_PRESENT) rereadSoon()
				}
			},
			IntentFilter().apply {
				addAction(Intent.ACTION_SCREEN_OFF)
				addAction(Intent.ACTION_SCREEN_ON)
				addAction(Intent.ACTION_USER_PRESENT)
			},
			ContextCompat.RECEIVER_EXPORTED
		)
	}
}
