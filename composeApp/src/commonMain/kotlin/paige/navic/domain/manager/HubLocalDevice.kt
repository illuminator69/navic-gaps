package paige.navic.domain.manager

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt

/**
 * The hardware this app runs on, as the hub is told about it and as a hub directive may change
 * it: what kind of device it is, and its media volume (PROTOCOL §3.1, and `deviceState` in §5.3).
 *
 * A volume the hub sets is the phone's own media volume, the one the hardware buttons move
 * and the system's slider shows. It used to be the player's gain, which nothing showed,
 * nothing reset, and which stayed in force for local playback afterwards.
 *
 * Platform-provided, in the same shape as [HubSocketClientFactory]: an interface here and one
 * Koin registration in each `PlatformModule`, consumed by [HubManager]'s constructor. Android
 * registers `AndroidHubLocalDevice`; iOS registers [PlainHubLocalDevice].
 */
interface HubLocalDevice {
	/** `hello.device.kind`: `phone` or `tablet`. Read at every `hello`. */
	val kind: String

	/**
	 * The device's media volume as the system gives it, 0..100, following every change whoever
	 * made it: the hardware buttons, another app, a hub directive. Null where the platform
	 * gives none, and nothing is then reported to the hub.
	 */
	val mediaVolume: StateFlow<Int?>

	/**
	 * Set the media volume to [level] (0..100), with no system slider. False where it cannot
	 * be set, and the caller then falls back to the player's own gain.
	 */
	fun setMediaVolume(level: Int): Boolean

	/**
	 * Whether [setMediaVolume] works on this device, whatever is or is not playing. It is what
	 * `hello` promises with the [CAP_IDLE_VOLUME] cap, asked at every `hello`.
	 */
	val setsMediaVolume: Boolean
}

/**
 * `hello.device.caps`, and a row's `caps`: the device applies a `do:setVolume` whenever one
 * arrives, also while it is not the playing device (PROTOCOL §3.1, §5.1).
 */
const val CAP_IDLE_VOLUME = "idleVolume"

/** A phone that reports no volume and sets none: iOS, where the hub's volume stays the player's gain. */
class PlainHubLocalDevice : HubLocalDevice {
	override val kind: String = "phone"
	override val mediaVolume: StateFlow<Int?> = MutableStateFlow<Int?>(null).asStateFlow()
	override fun setMediaVolume(level: Int): Boolean = false
	override val setsMediaVolume: Boolean = false
}

/** The least time between two `deviceState` volume reports: at most 4 a second (PROTOCOL §5.3). */
internal const val VOLUME_REPORT_GAP_MS = 250L

/**
 * The step of a volume stream that runs from [minIndex] to [maxIndex] for a hub level of
 * 0..100. A phone has 15 to 25 steps, so several levels share one step.
 */
fun streamIndexForLevel(level: Int, minIndex: Int, maxIndex: Int): Int {
	val span = maxIndex - minIndex
	if (span <= 0) return minIndex
	return minIndex + (level.coerceIn(0, 100) * span / 100.0).roundToInt()
}

/**
 * The hub level a stream at step [index] is reported as. [streamIndexForLevel] of the result
 * is [index] again, so a reported level that comes back as a command moves nothing.
 */
fun levelForStreamIndex(index: Int, minIndex: Int, maxIndex: Int): Int {
	val span = maxIndex - minIndex
	if (span <= 0) return 100
	return ((index.coerceIn(minIndex, maxIndex) - minIndex) * 100.0 / span).roundToInt()
}
