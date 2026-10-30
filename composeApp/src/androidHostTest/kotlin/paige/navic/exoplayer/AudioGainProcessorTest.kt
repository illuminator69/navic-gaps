package paige.navic.exoplayer

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import paige.navic.domain.models.DomainReplayGain
import paige.navic.domain.models.settings.ReplayGainMode
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/*
 * B-278: the processor driven as media3 drives it, on the JVM. Unity leaves the samples alone
 * (it used to add 1 dB and clip), a song without tags drops the gain the song before had, the
 * ReplayGain pre-amp applies only with ReplayGain and the plain amp otherwise.
 */
@UnstableApi
class AudioGainProcessorTest {
	private val format = AudioProcessor.AudioFormat(44_100, 2, C.ENCODING_PCM_16BIT)

	private fun processor() = AudioGainProcessor().apply {
		configure(format)
		flush(AudioProcessor.StreamMetadata.DEFAULT)
	}

	private fun rg(track: Float? = null, album: Float? = null) =
		DomainReplayGain(albumGain = album, albumPeak = null, trackGain = track, trackPeak = null, baseGain = null, fallbackGain = null)

	/** Every 16-bit value from full-scale negative to full-scale positive, in steps. */
	private val samples: ShortArray = (Short.MIN_VALUE..Short.MAX_VALUE step 7).map { it.toShort() }.toShortArray() +
		shortArrayOf(Short.MAX_VALUE, Short.MIN_VALUE, 0, 1, -1)

	private fun run(p: AudioGainProcessor, input: ShortArray): ShortArray {
		val bytes = ByteBuffer.allocateDirect(input.size * 2).order(ByteOrder.LITTLE_ENDIAN)
		bytes.asShortBuffer().put(input)
		p.queueInput(bytes)
		assertFalse(bytes.hasRemaining(), "the whole input is consumed")
		val out = p.output.order(ByteOrder.LITTLE_ENDIAN)
		val result = ShortArray(out.remaining() / 2)
		out.asShortBuffer().get(result)
		return result
	}

	private fun bytesOf(s: ShortArray): ByteArray {
		val b = ByteBuffer.allocate(s.size * 2).order(ByteOrder.LITTLE_ENDIAN)
		b.asShortBuffer().put(s)
		return b.array()
	}

	@Test
	fun replayGainOffAndNoAmpIsUnityAndOutOfTheChain() {
		val p = processor()
		p.applyGainMode(ReplayGainMode.Off)
		assertEquals(1f, p.linearGain)
		assertFalse(p.isActive)
	}

	@Test
	fun theDefaultIsUnityAndOutOfTheChain() {
		val p = processor()
		assertEquals(1f, p.linearGain)
		assertFalse(p.isActive, "a fresh processor added 1 dB before B-278")
	}

	@Test
	fun unityPassesEverySampleBitForBit() {
		val p = processor()
		p.applyGainMode(ReplayGainMode.Track) // on, but this song has no tags
		p.setReplayGainMetadata(null)
		assertEquals(1f, p.linearGain)
		val out = run(p, samples)
		assertContentEquals(bytesOf(samples), bytesOf(out))
	}

	@Test
	fun replayGainOnKeepsTheProcessorInTheChainEvenAtUnity() {
		// media3 reads isActive only when it sets the chain up, so a tagged song after an
		// untagged one must still find the processor there.
		val p = processor()
		p.setReplayGainMetadata(null)
		p.applyGainMode(ReplayGainMode.Track)
		assertTrue(p.isActive)
		assertEquals(1f, p.linearGain)
	}

	@Test
	fun aSongWithoutTagsNeverInheritsThePreviousSongsGain() {
		val p = processor()
		p.setReplayGainMetadata(rg(track = -6f))
		p.applyGainMode(ReplayGainMode.Track)
		assertEquals(0.501f, p.linearGain, 0.001f)

		p.setReplayGainMetadata(null)
		assertEquals(1f, p.linearGain, "dropped at once, before any applyGainMode")
		p.applyGainMode(ReplayGainMode.Track)
		assertEquals(1f, p.linearGain)
	}

	@Test
	fun theReplayGainPreAmpAppliesOnlyWithReplayGain() {
		val p = processor()
		p.rgAmpValue = 6f
		p.ampValue = 0f
		p.setReplayGainMetadata(null)
		p.applyGainMode(ReplayGainMode.Track)
		assertEquals(1f, p.linearGain, "no tags: the plain amp (0 dB), not the pre-amp")

		p.setReplayGainMetadata(rg(track = -6f))
		p.applyGainMode(ReplayGainMode.Track)
		assertEquals(1f, p.linearGain, 0.0001f) // -6 + 6
	}

	@Test
	fun thePlainAmpAppliesWithoutReplayGain() {
		val p = processor()
		p.rgAmpValue = 6f
		p.ampValue = -6f
		p.setReplayGainMetadata(rg(track = 3f))
		p.applyGainMode(ReplayGainMode.Off)
		assertEquals(0.501f, p.linearGain, 0.001f) // -6 dB, the song's tags ignored
		assertTrue(p.isActive)
	}

	@Test
	fun offThenOnAppliesThePlayingSongsTags() {
		val p = processor()
		p.setReplayGainMetadata(rg(track = -6f, album = -3f))
		p.applyGainMode(ReplayGainMode.Off)
		assertEquals(1f, p.linearGain)
		p.applyGainMode(ReplayGainMode.Album)
		assertEquals(0.708f, p.linearGain, 0.001f)
	}

	@Test
	fun resetForgetsTheTags() {
		val p = processor()
		p.setReplayGainMetadata(rg(track = -6f))
		p.applyGainMode(ReplayGainMode.Track)
		p.resetGain()
		assertEquals(1f, p.linearGain)
		assertFalse(p.isActive)
		p.applyGainMode(ReplayGainMode.Track)
		assertEquals(1f, p.linearGain, "the tags went with the reset")
	}

	@Test
	fun aGainScalesAndClipsAtFullScale() {
		val p = processor()
		p.ampValue = 6.0206f // x2
		assertTrue(p.isActive)
		val out = run(p, shortArrayOf(0, 100, -100, 20_000, -20_000, Short.MAX_VALUE, Short.MIN_VALUE))
		val expected = shortArrayOf(0, 200, -200, Short.MAX_VALUE, Short.MIN_VALUE, Short.MAX_VALUE, Short.MIN_VALUE)
		assertEquals(expected.size, out.size)
		// x2 within the float's rounding (truncation may give 199), clipped exactly at full scale.
		for (i in expected.indices) assertTrue(kotlin.math.abs(expected[i] - out[i]) <= 1, "sample $i: ${out[i]}")
		assertEquals(Short.MAX_VALUE, out[3])
		assertEquals(Short.MIN_VALUE, out[4])
	}

	@Test
	fun onlySixteenBitIsTaken() {
		val p = AudioGainProcessor()
		val float = AudioProcessor.AudioFormat(44_100, 2, C.ENCODING_PCM_FLOAT)
		var refused = false
		try {
			p.configure(float)
		} catch (_: AudioProcessor.UnhandledAudioFormatException) {
			refused = true
		}
		assertTrue(refused)
	}
}
