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
 * (it used to add 1 dB and clip), a song without tags drops the gain the song before had from the
 * update on, the ReplayGain pre-amp applies only with ReplayGain and the plain amp otherwise; one
 * update flushes at most once, and samples are rounded.
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

	/** What the player does at each song and on a settings change: one call with everything. */
	private fun AudioGainProcessor.set(
		meta: DomainReplayGain?,
		mode: ReplayGainMode,
		rgAmp: Float = 0f,
		amp: Float = 0f
	) = update(rgAmp, amp, meta, mode)

	@Test
	fun replayGainOffAndNoAmpIsUnityAndOutOfTheChain() {
		val p = processor()
		p.set(null, ReplayGainMode.Off)
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
		p.set(null, ReplayGainMode.Track) // on, but this song has no tags
		assertEquals(1f, p.linearGain)
		val out = run(p, samples)
		assertContentEquals(bytesOf(samples), bytesOf(out))
	}

	@Test
	fun replayGainOnKeepsTheProcessorInTheChainEvenAtUnity() {
		// media3 reads isActive only when it sets the chain up, so a tagged song after an
		// untagged one must still find the processor there.
		val p = processor()
		p.set(null, ReplayGainMode.Track)
		assertTrue(p.isActive)
		assertEquals(1f, p.linearGain)
	}

	@Test
	fun aSongWithoutTagsDoesNotKeepThePreviousSongsGain() {
		// From the update on. What reached the processor before it (up to the AudioTrack's buffer
		// after a transition) still went through at the old gain: B-282.
		val p = processor()
		p.set(rg(track = -6f), ReplayGainMode.Track)
		assertEquals(0.501f, p.linearGain, 0.001f)
		p.set(null, ReplayGainMode.Track)
		assertEquals(1f, p.linearGain)
	}

	@Test
	fun theReplayGainPreAmpAppliesOnlyWithReplayGain() {
		val p = processor()
		p.set(null, ReplayGainMode.Track, rgAmp = 6f, amp = 0f)
		assertEquals(1f, p.linearGain, "no tags: the plain amp (0 dB), not the pre-amp")
		p.set(rg(track = -6f), ReplayGainMode.Track, rgAmp = 6f, amp = 0f)
		assertEquals(1f, p.linearGain, 0.0001f) // -6 + 6
	}

	@Test
	fun thePlainAmpAppliesWithoutReplayGain() {
		val p = processor()
		p.set(rg(track = 3f), ReplayGainMode.Off, rgAmp = 6f, amp = -6f)
		assertEquals(0.501f, p.linearGain, 0.001f) // -6 dB, the song's tags ignored
		assertTrue(p.isActive)
	}

	@Test
	fun offThenOnAppliesThePlayingSongsTags() {
		val p = processor()
		val tags = rg(track = -6f, album = -3f)
		p.set(tags, ReplayGainMode.Off)
		assertEquals(1f, p.linearGain)
		p.set(tags, ReplayGainMode.Album)
		assertEquals(0.708f, p.linearGain, 0.001f)
	}

	@Test
	fun oneUpdateFlushesAtMostOnceAndOnlyOnAChange() {
		// B-278 review: a transition used to make up to three main-thread flushes (the amps, the
		// tags, the mode), each of which can skip a bit of the music.
		val p = processor()
		p.set(rg(track = -6f), ReplayGainMode.Track, rgAmp = 2f, amp = 1f)
		assertEquals(1, p.gainFlushes, "the amps, the tags and the mode changed: one flush")
		p.set(rg(track = -6f), ReplayGainMode.Track, rgAmp = 2f, amp = 1f)
		assertEquals(1, p.gainFlushes, "nothing changed: no flush")
		p.set(rg(track = -6f, album = -9f), ReplayGainMode.Track, rgAmp = 2f, amp = 1f)
		assertEquals(1, p.gainFlushes, "new tags, same output gain: no flush")
		p.set(null, ReplayGainMode.Off, rgAmp = 5f, amp = 3f)
		assertEquals(2, p.gainFlushes)
	}

	@Test
	fun samplesAreRoundedNotTruncated() {
		// B-278 review: toInt() truncated toward zero, a bias of up to one step on every sample.
		val p = processor()
		p.set(null, ReplayGainMode.Off, amp = -6f) // x0.501
		// 1.504, -1.504, 50.62, -50.62: truncated these were 1, -1, 50, -50.
		assertContentEquals(shortArrayOf(2, -2, 51, -51, 0), run(p, shortArrayOf(3, -3, 101, -101, 0)))
	}

	@Test
	fun aGainScalesAndClipsAtFullScale() {
		val p = processor()
		p.set(null, ReplayGainMode.Off, amp = 6.0206f) // x2
		assertTrue(p.isActive)
		val out = run(p, shortArrayOf(0, 100, -100, 20_000, -20_000, Short.MAX_VALUE, Short.MIN_VALUE))
		val expected = shortArrayOf(0, 200, -200, Short.MAX_VALUE, Short.MIN_VALUE, Short.MAX_VALUE, Short.MIN_VALUE)
		assertContentEquals(expected, out)
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
