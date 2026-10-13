package paige.navic.domain.manager.cast

import kotlin.math.roundToInt

// Capability bits of a Cast device's TXT `ca` record, as the Android `CastDevice` constants
// and VLC's renderer discovery have them.
private const val CA_VIDEO_OUT = 1L
private const val CA_AUDIO_OUT = 4L
private const val CA_MULTIZONE_GROUP = 32L

/**
 * What a Chromecast is, for the icon a picker draws (`hello.device.kind`, PROTOCOL §12.2):
 * a group of speakers, else anything with a picture is a `tv`, else anything with sound a
 * `speaker`. Null, and no `kind` is sent, for a record with no readable `ca` or none of the
 * three bits.
 */
internal fun castKind(ca: String?): String? {
	val bits = ca?.trim()?.toLongOrNull() ?: return null
	return when {
		bits and CA_MULTIZONE_GROUP != 0L -> "group"
		bits and CA_VIDEO_OUT != 0L -> "tv"
		bits and CA_AUDIO_OUT != 0L -> "speaker"
		else -> null
	}
}

/**
 * A RECEIVER_STATUS volume as the hub's 0..100: a muted receiver is at 0, whatever level it
 * will come back to. Null when the status carried no level.
 */
internal fun receiverVolumeLevel(level: Double?, muted: Boolean?): Int? {
	if (muted == true) return 0
	return level?.let { (it * 100).roundToInt().coerceIn(0, 100) }
}
