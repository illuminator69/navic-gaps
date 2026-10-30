package paige.navic.util

import paige.navic.domain.models.DomainReplayGain
import paige.navic.domain.models.DomainSong
import paige.navic.domain.models.settings.ReplayGainMode
import kotlin.math.abs
import kotlin.math.pow

/**
 * The ReplayGain (dB) [mode] applies to this song, or null when it applies none: the mode is
 * Off, or the song carries no gain at all. Null is not 0 dB: a song with no tags plays at the
 * plain amplifier, not at "ReplayGain 0 dB plus the ReplayGain pre-amp" (B-278; upstream
 * d8380643).
 */
fun DomainReplayGain.effectiveGain(mode: ReplayGainMode = ReplayGainMode.Track): Float? {
	return when (mode) {
		ReplayGainMode.Off -> null
		ReplayGainMode.Album -> albumGain ?: trackGain ?: fallbackGain ?: baseGain
		ReplayGainMode.Track, ReplayGainMode.Dynamic -> trackGain ?: albumGain ?: fallbackGain ?: baseGain
	}
}

/**
 * The mode the gain is applied in, Dynamic resolved: Album while every song in [queue] is from
 * [song]'s album, else Track; any other mode as it is. The ONE rule for the player
 * (`applyAudioGain`, which the settings screen goes through) and the song's details, which
 * used to read Dynamic as Track (B-278 review).
 */
fun resolveGainMode(queue: List<DomainSong>, song: DomainSong?, mode: ReplayGainMode): ReplayGainMode = when {
	mode != ReplayGainMode.Dynamic -> mode
	song != null && queue.all { it.albumId == song.albumId } -> ReplayGainMode.Album
	else -> ReplayGainMode.Track
}

fun Float.decibelsToLinear(): Float {
	return 10.0f.pow(this / 20.0f)
}

/**
 * The output gain in dB: the song's ReplayGain plus the ReplayGain pre-amp when ReplayGain
 * applies ([replayGainDb] not null), else the plain amplifier alone. With neither, 0 dB:
 * unity, the samples untouched (B-278: the default used to be 1, read as dB, so every song
 * played 1 dB hot and clipped).
 */
fun outputGainDb(replayGainDb: Float?, rgAmpDb: Float, ampDb: Float): Float =
	if (replayGainDb != null) replayGainDb + rgAmpDb else ampDb

/** A linear gain this close to 1 is unity: the samples pass through bit for bit. */
fun isUnityGain(linear: Float): Boolean = abs(linear - 1f) < UNITY_EPSILON

private const val UNITY_EPSILON = 0.0001f
