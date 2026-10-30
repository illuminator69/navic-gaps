package paige.navic.domain.manager

import paige.navic.domain.models.DomainReplayGain
import paige.navic.domain.models.settings.ReplayGainMode

expect class AudioGainManager {
	/**
	 * Everything the output gain depends on, in one call (at most one flush): the ReplayGain
	 * pre-amp and the plain amplifier in dB, the playing song's tags and the mode resolved by
	 * `resolveGainMode`. Called by the player only (`applyAudioGain`), the settings screen included.
	 */
	fun update(withReplayGain: Float, withoutReplayGain: Float, metadata: DomainReplayGain?, mode: ReplayGainMode)
}
