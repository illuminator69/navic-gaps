package paige.navic.domain.manager

/*
 * What a hub device's row says and where it stands, wherever devices are listed: the picker,
 * the navi-connect settings and the line over the now-playing title (PROTOCOL §3.1-3.2). Kept
 * free of Compose so that the rules run in a test (`DeviceRowsTest`); Feishin's picker follows
 * the same ones.
 */

/** The picture a row draws for the `kind` its device gave in `hello`. */
enum class DeviceKindIcon { COMPUTER, PHONE, TABLET, SPEAKER, TV, GROUP, GENERIC }

/** A kind this build does not know, and a device that gave none, both draw [DeviceKindIcon.GENERIC]. */
fun deviceKindIcon(kind: String?): DeviceKindIcon = when (kind) {
	"computer" -> DeviceKindIcon.COMPUTER
	"phone" -> DeviceKindIcon.PHONE
	"tablet" -> DeviceKindIcon.TABLET
	"speaker" -> DeviceKindIcon.SPEAKER
	"tv" -> DeviceKindIcon.TV
	"group" -> DeviceKindIcon.GROUP
	else -> DeviceKindIcon.GENERIC
}

/**
 * This device's own `kind`, from the narrow side of its screen: 600 dp is the line Android
 * itself draws between a phone's layout and a tablet's. A width the system could not give
 * (0) is a phone.
 */
fun phoneOrTablet(smallestScreenWidthDp: Int): String =
	if (smallestScreenWidthDp >= 600) "tablet" else "phone"

/** What a row says about its device. */
enum class DeviceRowStatus(val label: String) {
	PLAYING("playing"),
	PAUSED("paused"),
	THIS_DEVICE("this device"),
	NOT_RESPONDING("not responding"),
	AVAILABLE("available"),
	OFFLINE("offline")
}

/**
 * The status of [device]'s row. The active device says whether the session is playing: it
 * holds the session while paused too, and "playing" over a silent room was what the row said.
 *
 * `online` on a bridged row means the BRIDGING client's socket is up, not the speaker. A TV
 * that is off in another house stays online, so a row the bridge calls unreachable says "not
 * responding" and is not offered as a target.
 */
fun deviceRowStatus(device: HubDevice, myDeviceId: String?, sessionPlaying: Boolean): DeviceRowStatus = when {
	device.isActive -> if (sessionPlaying) DeviceRowStatus.PLAYING else DeviceRowStatus.PAUSED
	device.id == myDeviceId -> DeviceRowStatus.THIS_DEVICE
	device.online && !device.transferable -> DeviceRowStatus.NOT_RESPONDING
	device.online -> DeviceRowStatus.AVAILABLE
	else -> DeviceRowStatus.OFFLINE
}

/** "playing" or "paused": the word for the active device wherever it is named. */
fun activeDeviceWord(sessionPlaying: Boolean): String =
	(if (sessionPlaying) DeviceRowStatus.PLAYING else DeviceRowStatus.PAUSED).label

/** The line over the now-playing title while the session is on another device. */
fun remoteSessionCue(deviceName: String, sessionPlaying: Boolean): String =
	"${if (sessionPlaying) "Playing" else "Paused"} on $deviceName"

/**
 * The order of the picker's rows: the playing device, this device, the rest by name whatever
 * their case, and last the ones that cannot be handed the session: not responding, then offline.
 * Two devices of one name keep their order by id, so that a new `devices` frame cannot swap them.
 */
fun pickerOrder(devices: List<HubDevice>, myDeviceId: String?): List<HubDevice> =
	devices.sortedWith(
		compareBy<HubDevice> {
			when {
				it.isActive -> 0
				it.id == myDeviceId -> 1
				it.transferable -> 2
				it.online -> 3
				else -> 4
			}
		}.thenBy { it.name.lowercase() }.thenBy { it.id }
	)

/**
 * Does [device]'s row carry a volume slider of its own? Only where a level set there will
 * land: the hub reads a `target` ([hubSetsRowVolumes]: the link is up and `welcome.features`
 * has `volumeTarget`; an older hub would move the PLAYING device instead), the device is
 * online and responding, and it promised to apply a volume while idle (`idleVolume`). Not on
 * the playing device, which has its own slider, nor on this one, whose volume is under the
 * user's thumb already.
 */
fun rowVolumeOffered(device: HubDevice, myDeviceId: String?, hubSetsRowVolumes: Boolean): Boolean =
	hubSetsRowVolumes && device.transferable && CAP_IDLE_VOLUME in device.caps &&
		!device.isActive && device.id != myDeviceId
