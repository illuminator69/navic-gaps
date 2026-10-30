package paige.navic.domain.manager

import androidx.media3.common.util.UnstableApi
import paige.navic.domain.models.DomainReplayGain
import paige.navic.domain.models.settings.ReplayGainMode
import paige.navic.exoplayer.AudioGainProcessor

@UnstableApi
actual class AudioGainManager(
	private val audioGainProcessor: AudioGainProcessor
) {
	actual fun update(withReplayGain: Float, withoutReplayGain: Float, metadata: DomainReplayGain?, mode: ReplayGainMode) {
		audioGainProcessor.update(withReplayGain, withoutReplayGain, metadata, mode)
	}
}
