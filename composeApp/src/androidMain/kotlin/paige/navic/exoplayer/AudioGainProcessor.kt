package paige.navic.exoplayer

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.StreamMetadata
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import paige.navic.domain.models.DomainReplayGain
import paige.navic.domain.models.settings.ReplayGainMode
import paige.navic.util.decibelsToLinear
import paige.navic.util.effectiveGain
import paige.navic.util.isUnityGain
import paige.navic.util.outputGainDb
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * ReplayGain and the two amplifiers, on 16-bit PCM only (B-278, adapted from upstream d8380643).
 *
 * Gains are in dB and 0 dB is unity. With no ReplayGain in force (mode Off, or a song without
 * tags) the plain amplifier applies; with ReplayGain the song's gain plus the ReplayGain
 * pre-amp. At unity the samples pass through bit for bit. The default used to be 1 read as
 * dB, so every song played 1 dB hot and was clipped.
 *
 * A song without tags no longer keeps the previous song's gain for the whole song. **The first
 * fraction of a second after a transition still can**: the player calls [update] on the main
 * thread from its transition callback, while this processor runs ahead of what is heard by the
 * AudioTrack's buffer (about 0.25 to 0.75 s), so the new song's first samples went through at the
 * old gain. Pre-existing, inherited from upstream; the fix is to resolve the gain on the playback
 * thread in [onFlush] from the period's MediaItem (BACKLOG B-282).
 *
 * Everything the output gain depends on is one immutable [GainState] in one volatile field, so the
 * playback thread never reads half of an update, and an [update] flushes at most once, only when
 * the output gain changed.
 */
@UnstableApi
class AudioGainProcessor : BaseAudioProcessor() {
	private class GainState(
		/** ReplayGain is switched on, whatever the song carries (see [isActive]). */
		val replayGainEnabled: Boolean,
		rgAmpDb: Float,
		ampDb: Float,
		/** The ReplayGain in force, in dB; null when none applies. */
		replayGainDb: Float?
	) {
		val linear: Float = outputGainDb(replayGainDb, rgAmpDb, ampDb).decibelsToLinear()
	}

	@Volatile
	private var state = GainState(replayGainEnabled = false, rgAmpDb = 0f, ampDb = 0f, replayGainDb = null)

	/** Flushes caused by a gain change, for the tests. */
	internal var gainFlushes = 0
		private set

	/** The output gain as a linear factor. */
	val linearGain: Float
		get() = state.linear

	/**
	 * The whole input to the output gain, at once: the ReplayGain pre-amp and the plain amplifier
	 * (dB), the playing song's tags (null: none) and the mode, already resolved
	 * (`resolveGainMode`: Dynamic is never passed). Flushes once, and only when the output gain
	 * changed: a needless flush skips a bit of the music, and a change without one can crackle.
	 */
	fun update(rgAmpDb: Float, ampDb: Float, metadata: DomainReplayGain?, mode: ReplayGainMode) {
		val next = GainState(
			replayGainEnabled = mode != ReplayGainMode.Off,
			rgAmpDb = rgAmpDb,
			ampDb = ampDb,
			replayGainDb = metadata?.effectiveGain(mode)
		)
		val before = state.linear
		state = next
		if (next.linear != before) {
			gainFlushes++
			flush(StreamMetadata.DEFAULT)
		}
	}

	/**
	 * Out of the chain only when nothing can apply a gain: ReplayGain off and the plain
	 * amplifier at 0 dB. media3 reads this only when it sets the chain up (a new format, a seek),
	 * not per song: with ReplayGain on, a song without tags (unity) followed gaplessly by one with
	 * tags would otherwise play the second without its gain. In the chain at unity the samples
	 * are copied unchanged.
	 */
	override fun isActive(): Boolean {
		val state = state
		return super.isActive() && (state.replayGainEnabled || !isUnityGain(state.linear))
	}

	override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
		if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT) {
			return inputAudioFormat
		}
		throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
	}

	override fun queueInput(inputBuffer: ByteBuffer) {
		val pos = inputBuffer.position()
		val limit = inputBuffer.limit()
		val outputBuffer = replaceOutputBuffer(limit - pos)

		inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
		outputBuffer.order(ByteOrder.LITTLE_ENDIAN)

		val computedVolume = linearGain

		if (isUnityGain(computedVolume)) {
			outputBuffer.put(inputBuffer)
		} else {
			val shortBufferInput = inputBuffer.asShortBuffer()
			val shortBufferOutput = outputBuffer.asShortBuffer()

			while (shortBufferInput.hasRemaining()) {
				// prevent popping
				val sample = shortBufferInput.get()
				val scaledSample = (sample * computedVolume)
					.roundToInt()
					.coerceAtLeast(Short.MIN_VALUE.toInt())
					.coerceAtMost(Short.MAX_VALUE.toInt())
					.toShort()

				shortBufferOutput.put(scaledSample)
			}
			inputBuffer.position(inputBuffer.position() + shortBufferInput.position() * 2)
			outputBuffer.position(outputBuffer.position() + shortBufferOutput.position() * 2)
		}
		outputBuffer.flip()
	}
}
