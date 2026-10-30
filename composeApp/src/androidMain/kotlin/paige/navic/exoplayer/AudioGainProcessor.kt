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

/**
 * ReplayGain and the two amplifiers, on 16-bit PCM only (B-278, adapted from upstream d8380643).
 *
 * Gains are in dB and 0 dB is unity. With no ReplayGain in force (mode Off, or a song without
 * tags) the plain amplifier applies; with ReplayGain the song's gain plus the ReplayGain
 * pre-amp. At unity the samples pass through bit for bit. The default used to be 1 read as
 * dB, so every song played 1 dB hot and was clipped, and a song without tags kept the previous
 * song's gain.
 *
 * Written on the main thread (the player's listener, the settings screen) and read on the
 * playback thread; the fields are volatile, and a change of the output gain flushes once.
 */
@UnstableApi
class AudioGainProcessor : BaseAudioProcessor() {
	/** The playing song's tags, kept while the mode is Off so turning it on applies them. */
	@Volatile
	private var replayGainMetadata: DomainReplayGain? = null

	/** The ReplayGain in force, in dB; null when none applies. */
	@Volatile
	private var replayGainDb: Float? = null

	/**
	 * ReplayGain is switched on, whatever the song carries. It keeps the processor in the
	 * chain (see [isActive]) so that the next song's gain is not missed.
	 */
	@Volatile
	private var replayGainEnabled = false

	@Volatile
	var rgAmpValue = 0f
		set(value) {
			val before = linearGain
			field = value
			flushIfChanged(before)
		}

	@Volatile
	var ampValue = 0f
		set(value) {
			val before = linearGain
			field = value
			flushIfChanged(before)
		}

	/** The output gain as a linear factor. */
	val linearGain: Float
		get() = outputGainDb(replayGainDb, rgAmpValue, ampValue).decibelsToLinear()

	fun applyGainMode(mode: ReplayGainMode) = changeGain {
		replayGainEnabled = mode != ReplayGainMode.Off
		replayGainDb = replayGainMetadata?.effectiveGain(mode)
	}

	/**
	 * The playing song's tags. Null (a song without tags) drops the gain in force at once, so
	 * it is never carried over from the song before; a song with tags takes effect on the
	 * [applyGainMode] that follows.
	 */
	fun setReplayGainMetadata(metadata: DomainReplayGain?) = changeGain {
		replayGainMetadata = metadata
		if (metadata == null) replayGainDb = null
	}

	/** ReplayGain off and forgotten; the plain amplifier stays. */
	fun resetGain() = changeGain {
		replayGainMetadata = null
		replayGainDb = null
		replayGainEnabled = false
	}

	// we should ONLY flush if the gain has changed, flushing needlessly will cause some "bits" of the music to skip
	// flushing the stream is needed because otherwise the user might hear some crackling after changing values
	private inline fun changeGain(change: () -> Unit) {
		val before = linearGain
		change()
		flushIfChanged(before)
	}

	private fun flushIfChanged(before: Float) {
		if (linearGain != before) flush(StreamMetadata.DEFAULT)
	}

	/**
	 * Out of the chain only when nothing can apply a gain: ReplayGain off and the plain
	 * amplifier at 0 dB. media3 reads this only when it sets the chain up (a new format, a seek),
	 * not per song: with ReplayGain on, a song without tags (unity) followed gaplessly by one with
	 * tags would otherwise play the second without its gain. In the chain at unity the samples
	 * are copied unchanged.
	 */
	override fun isActive(): Boolean {
		return super.isActive() && (replayGainEnabled || !isUnityGain(linearGain))
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
					.toInt()
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
