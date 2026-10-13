package paige.navic.domain.manager

import kotlin.math.abs

/*
 * A value this device has just commanded is held against the hub's lagging echoes for a short
 * time. The hub answers a command with a broadcast, and until that broadcast lands the mirror
 * still holds what the command replaced, while frames sent before the command keep arriving.
 * Two controls need it, and both are kept free of the socket and of media3 so that the rules
 * run in a test (`CommandHoldTest`).
 *
 * **Seek.** A scrubber previews the drag where the finger is and sends ONE seek on release
 * (`NowPlayingProgressBar`, `MiniPlayerProgressBar`). [afterSeekSent] puts the target in the
 * mirror at once, and [afterProgress] refuses a `progress` position that is still the old one.
 * The hold lives in the mirror and not in each slider, so that the lock-screen and notification
 * scrubber, which read the same mirror through `RemoteSessionPlayer`, are covered too.
 *
 * **Volume.** `RemoteSessionPlayer` steps the hardware rocker from the level it last sent, not
 * from the last `devices` frame: quick presses used to start from the same number each
 * ([volumeHoldStands]).
 */

/** A seek this device sent to the active receiver: where to, and when. */
data class SeekHold(val targetMs: Long, val atMs: Long)

/** How long a sent seek is defended against `progress` frames that predate it. */
internal const val SEEK_HOLD_MS = 1_000L

/**
 * How far from the target a `progress` position must be to count as predating the seek. The
 * receiver plays on from the target, so a real frame is at most the hold's own length past it.
 * The hub draws the same line for the receiver's reports.
 */
internal const val SEEK_HOLD_FAR_MS = 1_500L

/** The mirror once this device has sent a seek to [targetMs]: there already, and holding it. */
internal fun RemoteSessionState.afterSeekSent(targetMs: Long, nowMs: Long): RemoteSessionState {
	val target = targetMs.coerceAtLeast(0)
	return copy(positionMs = target, positionAtMs = nowMs, seekHold = SeekHold(target, nowMs))
}

/**
 * The mirror after a `progress` frame.
 *
 * A field the frame omits keeps its value: defaulting a missing `isPlaying` to false or a
 * missing `positionMs` to 0 snapped the scrubber and flickered a paused state.
 *
 * A position far from a seek sent under [SEEK_HOLD_MS] ago is the receiver's report from
 * before that seek, and is left out: the position and its timestamp stay the seek's. The first
 * position that is not ends the hold, as does a frame for another queue slot, which is a track
 * change and not an echo.
 */
internal fun RemoteSessionState.afterProgress(
	index: Int?,
	isPlaying: Boolean?,
	positionMs: Long?,
	nowMs: Long
): RemoteSessionState {
	val nextIndex = index ?: this.index
	val hold = seekHold
	val lagging = hold != null && positionMs != null && nextIndex == this.index &&
		nowMs - hold.atMs in 0 until SEEK_HOLD_MS &&
		abs(positionMs - hold.targetMs) >= SEEK_HOLD_FAR_MS
	return copy(
		index = nextIndex,
		isPlaying = isPlaying ?: this.isPlaying,
		positionMs = if (lagging) this.positionMs else positionMs ?: this.positionMs,
		positionAtMs = if (lagging) positionAtMs else nowMs,
		// A frame with no position neither confirms the seek nor contradicts it.
		seekHold = if (lagging || positionMs == null) hold else null
	)
}

/** The volume this device last asked the active receiver for. */
internal data class VolumeCommand(val level: Int, val atMs: Long, val activeDeviceId: String?)

/** How long a sent level stands in for the `devices` frame that has not confirmed it yet. */
internal const val VOLUME_HOLD_MS = 2_500L

/**
 * Does [command] still stand in for the level the last `devices` frame gave?
 *
 * It does while it is under [VOLUME_HOLD_MS] old, the active receiver is the one it was sent
 * to, and no frame has agreed with it. Only a frame that arrived AFTER the command can agree
 * ([frameIsNewer]): up and straight back down commands the level the frame still shows from
 * before either press, and taking that for the answer let the first press's echo through.
 */
internal fun volumeHoldStands(
	command: VolumeCommand,
	frameLevel: Int,
	frameIsNewer: Boolean,
	activeDeviceId: String?,
	nowMs: Long
): Boolean =
	nowMs - command.atMs in 0 until VOLUME_HOLD_MS &&
		command.activeDeviceId == activeDeviceId &&
		!(frameIsNewer && frameLevel == command.level)
