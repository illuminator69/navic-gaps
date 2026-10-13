package paige.navic.domain.manager

/*
 * A value this device has just commanded is PINNED in the mirror until the hub answers for the
 * act that set it (PROTOCOL §5.1 and §5.4, `act.cid` and `ack`). The hub writes the `ack` after every
 * frame the act caused, so when it arrives the mirror already holds the outcome, and the pin
 * can give way to it. No clock decides, unlike the timed holds of `CommandHold.kt`, which stay
 * for a hub that lists no `actAck` in `welcome.features`.
 *
 * The mirror's own fields hold what is SHOWN, so every reader (the sliders, the play button,
 * the notification through `RemoteSessionPlayer`) is covered without knowing about pins. What
 * the hub last said about a pinned field is kept in the pin (`under`) and shows through when
 * the pin goes: at the ack of the newest act on that value, after [ACT_PIN_MS] without one, and
 * at any `welcome`. An older act's ack releases nothing, because a newer act replaced its `cid`.
 *
 * Pinned: play state and position here, volume in [VolumePin] (it lives in the device list,
 * not in the session). Skips and queue edits are not pinned: under shuffle the controller
 * cannot know the next index. Free of the socket so that the rules run in a test (`ActPinsTest`).
 */

/** How long a pin stands with no ack: the act was lost, or the hub is holding it for a transfer. */
internal const val ACT_PIN_MS = 2_500L

/** Play state this device commanded; [under] is what the hub last said. */
data class PlayPin(val cid: String, val atMs: Long, val under: Boolean)

/**
 * A seek this device commanded, on the track at [index] with id [trackId]. [underMs] at
 * [underAtMs] is the position the hub last gave. It belongs to that track: when the mirror's
 * index or the track there changes, the pin is dropped and not released.
 */
data class PositionPin(
	val cid: String,
	val atMs: Long,
	val index: Int,
	val trackId: String?,
	val underMs: Long,
	val underAtMs: Long
)

data class ActPins(val play: PlayPin? = null, val position: PositionPin? = null) {
	val none: Boolean get() = play == null && position == null
}

/** A volume this device commanded for [deviceId], until the ack for [cid]. */
data class VolumePin(val level: Int, val cid: String, val atMs: Long, val deviceId: String?) {
	fun stands(activeDeviceId: String?, nowMs: Long): Boolean =
		deviceId == activeDeviceId && pinIsYoung(atMs, nowMs)
}

/** Not `<`: a wall clock set back must end a pin, not extend it. */
private fun pinIsYoung(atMs: Long, nowMs: Long): Boolean = nowMs - atMs in 0 until ACT_PIN_MS

/** Where the playhead is shown at [nowMs]: a playing mirror runs on from its last position. */
private fun RemoteSessionState.shownPositionMs(nowMs: Long): Long =
	if (isPlaying) positionMs + (nowMs - positionAtMs).coerceAtLeast(0) else positionMs

/**
 * The mirror once this device has sent `play` or `pause` as act [cid]: showing it already.
 * The playhead is carried across the flip, so a pause stops it where it was shown and a play
 * starts it from there.
 */
internal fun RemoteSessionState.pinPlaying(playing: Boolean, cid: String, nowMs: Long): RemoteSessionState =
	copy(
		isPlaying = playing,
		positionMs = shownPositionMs(nowMs),
		positionAtMs = nowMs,
		// A pin already there keeps what the hub said: this device's own earlier guess is not it.
		pins = pins.copy(play = PlayPin(cid, nowMs, under = pins.play?.under ?: isPlaying))
	)

/** The mirror once this device has sent a seek to [targetMs] as act [cid]: there already. */
internal fun RemoteSessionState.pinPosition(targetMs: Long, cid: String, nowMs: Long): RemoteSessionState {
	val target = targetMs.coerceAtLeast(0)
	val standing = pins.position
	return copy(
		positionMs = target,
		positionAtMs = nowMs,
		// The pin replaces the timed hold; the two never stand together.
		seekHold = null,
		pins = pins.copy(
			position = PositionPin(
				cid = cid,
				atMs = nowMs,
				index = index,
				trackId = nowPlaying?.id,
				underMs = standing?.underMs ?: positionMs,
				underAtMs = standing?.underAtMs ?: positionAtMs
			)
		)
	)
}

/**
 * A `session` snapshot (this) laid under the pins [shown] holds: the pinned fields keep what
 * is shown and the snapshot's values go into the pins. A position pin whose track is no longer
 * the one at the mirror's index is dropped: the snapshot's position is another track's.
 */
internal fun RemoteSessionState.underPins(shown: RemoteSessionState, nowMs: Long): RemoteSessionState {
	val play = shown.pins.play
	val position = shown.pins.position?.takeIf { it.index == index && it.trackId == nowPlaying?.id }
	if (play == null && position == null) return this
	return copy(
		isPlaying = if (play != null) shown.isPlaying else isPlaying,
		positionMs = if (position != null) shown.positionMs else positionMs,
		positionAtMs = if (position != null) shown.positionAtMs else positionAtMs,
		pins = ActPins(
			play = play?.copy(under = isPlaying),
			position = position?.copy(underMs = positionMs, underAtMs = nowMs)
		)
	)
}

/** The mirror after the hub's ack for act [cid]: a pin that act set gives way to what the hub said. */
internal fun RemoteSessionState.afterAck(cid: String): RemoteSessionState =
	released(play = pins.play?.cid == cid, position = pins.position?.cid == cid)

/** The mirror with every pin older than [ACT_PIN_MS] released: its ack is not coming in time. */
internal fun RemoteSessionState.afterPinExpiry(nowMs: Long): RemoteSessionState =
	released(
		play = pins.play?.let { !pinIsYoung(it.atMs, nowMs) } == true,
		position = pins.position?.let { !pinIsYoung(it.atMs, nowMs) } == true
	)

private fun RemoteSessionState.released(play: Boolean, position: Boolean): RemoteSessionState {
	if (!play && !position) return this
	val playPin = pins.play.takeIf { play }
	val positionPin = pins.position.takeIf { position }
	return copy(
		isPlaying = playPin?.under ?: isPlaying,
		positionMs = positionPin?.underMs ?: positionMs,
		positionAtMs = positionPin?.underAtMs ?: positionAtMs,
		pins = ActPins(
			play = pins.play.takeIf { !play },
			position = pins.position.takeIf { !position }
		)
	)
}
