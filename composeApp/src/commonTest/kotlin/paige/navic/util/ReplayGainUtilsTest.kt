package paige.navic.util

import paige.navic.domain.models.DomainReplayGain
import paige.navic.domain.models.settings.ReplayGainMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/*
 * B-278: gains are dB and 0 dB is unity. ReplayGain that does not apply is null, not 0 dB, so a
 * song without tags plays at the plain amplifier and the ReplayGain pre-amp stays out of it.
 */
class ReplayGainUtilsTest {
	private fun rg(track: Float? = null, album: Float? = null, fallback: Float? = null, base: Float? = null) =
		DomainReplayGain(albumGain = album, albumPeak = null, trackGain = track, trackPeak = null, baseGain = base, fallbackGain = fallback)

	@Test
	fun offAppliesNoReplayGain() {
		assertNull(rg(track = -6f, album = -4f).effectiveGain(ReplayGainMode.Off))
	}

	@Test
	fun aSongWithoutTagsHasNoReplayGainRatherThanZeroDb() {
		for (mode in ReplayGainMode.entries) assertNull(rg().effectiveGain(mode), "$mode")
	}

	@Test
	fun eachModePrefersItsOwnGainThenFallsBack() {
		val both = rg(track = -6f, album = -4f, fallback = -2f, base = -1f)
		assertEquals(-6f, both.effectiveGain(ReplayGainMode.Track))
		assertEquals(-6f, both.effectiveGain(ReplayGainMode.Dynamic))
		assertEquals(-4f, both.effectiveGain(ReplayGainMode.Album))
		assertEquals(-4f, rg(album = -4f).effectiveGain(ReplayGainMode.Track))
		assertEquals(-6f, rg(track = -6f).effectiveGain(ReplayGainMode.Album))
		assertEquals(-2f, rg(fallback = -2f, base = -1f).effectiveGain(ReplayGainMode.Album))
		assertEquals(-1f, rg(base = -1f).effectiveGain(ReplayGainMode.Track))
		// A tag that says 0 dB is ReplayGain at 0 dB, not "no ReplayGain".
		assertEquals(0f, rg(track = 0f).effectiveGain(ReplayGainMode.Track))
	}

	@Test
	fun noReplayGainAndNoAmpIsUnity() {
		val db = outputGainDb(replayGainDb = null, rgAmpDb = 0f, ampDb = 0f)
		assertEquals(0f, db)
		assertEquals(1f, db.decibelsToLinear())
		assertTrue(isUnityGain(db.decibelsToLinear()))
	}

	@Test
	fun theReplayGainPreAmpAppliesOnlyWithReplayGain() {
		assertEquals(-3f, outputGainDb(replayGainDb = -6f, rgAmpDb = 3f, ampDb = 5f))
		assertEquals(5f, outputGainDb(replayGainDb = null, rgAmpDb = 3f, ampDb = 5f))
		// ReplayGain of 0 dB still takes the pre-amp, and never the plain amp.
		assertEquals(3f, outputGainDb(replayGainDb = 0f, rgAmpDb = 3f, ampDb = 5f))
	}

	@Test
	fun unityIsWithinATenThousandth() {
		assertTrue(isUnityGain(1f))
		assertTrue(isUnityGain(1.00005f))
		assertFalse(isUnityGain(1f.decibelsToLinear()))
		assertFalse(isUnityGain(0.999f))
	}
}
