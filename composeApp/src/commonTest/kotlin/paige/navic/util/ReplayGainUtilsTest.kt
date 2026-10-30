package paige.navic.util

import paige.navic.domain.models.DomainExplicitStatus
import paige.navic.domain.models.DomainReplayGain
import paige.navic.domain.models.DomainSong
import paige.navic.domain.models.settings.ReplayGainMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

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

	private fun song(id: String, albumId: String?) = DomainSong(
		id = id, title = "Song $id", artistName = "Artist", artistId = "ar", albumTitle = "Album",
		albumId = albumId, parentId = null, comment = null, trackNumber = 1, discNumber = 1,
		isrc = emptyList(), year = null, genre = null, genres = emptyList(), moods = emptyList(),
		duration = 200.seconds, bpm = null, contributors = emptyList(), userRating = null,
		averageRating = null, bitRate = null, bitDepth = null, sampleRate = null,
		audioChannelCount = null, replayGain = null, fileSize = 0, fileExtension = null,
		mimeType = null, filePath = null, starredAt = null, coverArtId = null, musicBrainzId = null,
		explicitStatus = DomainExplicitStatus.Unknown, artists = emptyList(), albumArtists = emptyList()
	)

	@Test
	fun dynamicIsAlbumOnlyWhileTheWholeQueueIsTheSongsAlbum() {
		// B-278 review: the settings screen applied Dynamic as Track and the song's details showed
		// Track, while the player applied this rule. One resolver now, for all three.
		val a1 = song("1", "a")
		val album = listOf(a1, song("2", "a"), song("3", "a"))
		val mixed = album + song("4", "b")
		assertEquals(ReplayGainMode.Album, resolveGainMode(album, a1, ReplayGainMode.Dynamic))
		assertEquals(ReplayGainMode.Track, resolveGainMode(mixed, a1, ReplayGainMode.Dynamic))
		assertEquals(ReplayGainMode.Track, resolveGainMode(album, null, ReplayGainMode.Dynamic))
		assertEquals(ReplayGainMode.Track, resolveGainMode(album, song("9", "b"), ReplayGainMode.Dynamic))
	}

	@Test
	fun everyOtherModeIsItself() {
		val a1 = song("1", "a")
		val mixed = listOf(a1, song("2", "b"))
		for (mode in ReplayGainMode.entries - ReplayGainMode.Dynamic) {
			assertEquals(mode, resolveGainMode(mixed, a1, mode))
			assertEquals(mode, resolveGainMode(listOf(a1), a1, mode))
		}
	}
}
