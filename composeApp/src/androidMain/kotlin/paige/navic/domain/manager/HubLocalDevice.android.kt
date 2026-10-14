package paige.navic.domain.manager

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import paige.navic.util.Logger

// Not public constants of AudioManager, and sent on every Android this app runs on: media3's
// own StreamVolumeManager listens for the first.
private const val VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION"
private const val STREAM_MUTE_CHANGED_ACTION = "android.media.STREAM_MUTE_CHANGED_ACTION"

/** How long after an output change the level is read a second time. */
private const val OUTPUT_SETTLE_MS = 1_000L

/**
 * The phone as a hub device: `phone` or `tablet`, and its media volume
 * (`AudioManager.STREAM_MUSIC`) read from the system and set in the system.
 *
 * The level is never remembered here. It is read again whenever the system says it moved: the
 * hardware buttons, the system's slider, another app, a mute, and an output that came or went
 * (headphones and each Bluetooth device keep a level of their own). A muted stream reads 0.
 */
class AndroidHubLocalDevice(context: Context) : HubLocalDevice {
	private val app = context.applicationContext
	private val audio = app.getSystemService(AudioManager::class.java)
	private val main = Handler(Looper.getMainLooper())

	// Asked at every `hello` and not kept: a foldable, or a new display size, moves the line.
	override val kind: String
		get() = phoneOrTablet(app.resources.configuration.smallestScreenWidthDp)

	private val _mediaVolume = MutableStateFlow(readLevel())
	override val mediaVolume: StateFlow<Int?> = _mediaVolume.asStateFlow()

	private fun minIndex(manager: AudioManager): Int =
		if (Build.VERSION.SDK_INT >= 28) manager.getStreamMinVolume(AudioManager.STREAM_MUSIC) else 0

	private fun readLevel(): Int? {
		val manager = audio ?: return null
		return try {
			levelForStreamIndex(
				index = manager.getStreamVolume(AudioManager.STREAM_MUSIC),
				minIndex = minIndex(manager),
				maxIndex = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
			)
		} catch (e: Exception) {
			Logger.w("HubLocalDevice", "media volume not readable", e)
			null
		}
	}

	private val reread = Runnable { _mediaVolume.value = readLevel() }

	init {
		// Exported, so that the system's own sender is heard whatever uid it has (see
		// `AppPresence`). All the receiver does with a broadcast is ask the system again, so
		// one from anywhere else costs a read.
		ContextCompat.registerReceiver(
			app,
			object : BroadcastReceiver() {
				override fun onReceive(context: Context?, intent: Intent?) {
					_mediaVolume.value = readLevel()
				}
			},
			IntentFilter().apply {
				addAction(VOLUME_CHANGED_ACTION)
				addAction(STREAM_MUTE_CHANGED_ACTION)
			},
			ContextCompat.RECEIVER_EXPORTED
		)
		// A new output brings its own level and announces no volume change. The route moves a
		// moment after the device is listed, hence the second look.
		audio?.registerAudioDeviceCallback(
			object : AudioDeviceCallback() {
				override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = outputChanged()
				override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = outputChanged()
			},
			main
		)
	}

	private fun outputChanged() {
		_mediaVolume.value = readLevel()
		main.removeCallbacks(reread)
		main.postDelayed(reread, OUTPUT_SETTLE_MS)
	}

	// A device whose volume is fixed (some TVs and cars) ignores the call.
	override val setsMediaVolume: Boolean
		get() = audio?.isVolumeFixed == false

	override fun setMediaVolume(level: Int): Boolean {
		val manager = audio ?: return false
		if (manager.isVolumeFixed) return false
		return try {
			manager.setStreamVolume(
				AudioManager.STREAM_MUSIC,
				streamIndexForLevel(
					level,
					minIndex = minIndex(manager),
					maxIndex = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
				),
				// No flags: no system slider, no sound.
				0
			)
			// Read back at once and not left to the broadcast: the level this device reports
			// is the one the system now holds, which is a step and seldom the number asked for.
			_mediaVolume.value = readLevel()
			true
		} catch (e: SecurityException) {
			// Do Not Disturb can refuse a change that would leave silent mode.
			Logger.w("HubLocalDevice", "media volume not set", e)
			false
		}
	}
}
