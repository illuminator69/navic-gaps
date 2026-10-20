package paige.navic.ui.screens.mixes

import paige.navic.domain.manager.SEED_TRACKS_MAX
import paige.navic.domain.manager.StationSeed
import paige.navic.domain.manager.artistRadioPlan
import paige.navic.domain.models.DomainExplicitStatus
import paige.navic.domain.models.DomainSong
import paige.navic.domain.models.Mix
import paige.navic.domain.models.MixKind
import paige.navic.domain.models.genreSeedNames
import paige.navic.domain.models.settings.StationCharacter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/*
 * Mixed for You in step with Feishin (B-149, B-150, 2026-10-17): "mixed for you in navic has the
 * same icon for your mix and it does not really make sense for it to be that icon. mix choices are
 * different from those in feishin when they should be the same."
 *
 * One spec for both clients: a genre recipe holds the genre's NAME; an artist recipe plays as the
 * artist page's radio, never a shuffle; Your mix and the legacy kinds that play as it wear a
 * person. (The new mix form these tests also covered was retired by the custom radio, P5.)
 */
class MixRecipesTest {
	private fun song(id: String, album: String? = "al", plays: Int = 0, title: String = "Song $id") = DomainSong(
		id = id, title = title, artistName = "Neil Young", artistId = "ar1", albumTitle = "Album",
		albumId = album, parentId = null, comment = null, trackNumber = 1, discNumber = 1,
		isrc = emptyList(), year = null, genre = null, genres = emptyList(), moods = emptyList(),
		duration = 200.seconds, bpm = null, contributors = emptyList(), userRating = null,
		averageRating = null, bitRate = null, bitDepth = null, sampleRate = null,
		audioChannelCount = null, replayGain = null, fileSize = 0, fileExtension = null,
		mimeType = null, filePath = null, starredAt = null, coverArtId = "al-$id", musicBrainzId = null,
		explicitStatus = DomainExplicitStatus.Unknown, artists = emptyList(), albumArtists = emptyList(),
		playCount = plays
	)

	// ---- How a recipe plays ---------------------------------------------------------------- //

	@Test
	fun withTheEngineEveryKindButGenrePlaysAsAStation() {
		val asStation = MixKind.ALL.filter { MixKind.playsAsStation(it, engine = true) }
		assertEquals(
			setOf(MixKind.STATION, MixKind.SIMILAR, MixKind.FINGERPRINT, MixKind.ADAPTIVE, MixKind.ARTIST),
			asStation.toSet()
		)
		assertFalse(MixKind.playsAsStation(MixKind.GENRE, engine = true))
	}

	@Test
	fun withoutTheEngineNothingPlaysAsAStation() {
		assertTrue(MixKind.ALL.none { MixKind.playsAsStation(it, engine = false) })
	}

	@Test
	fun anArtistMixWithTheEngineIsTheArtistRadioStation() {
		// Two albums: the most played song of each first, then the next most played, five at most.
		val songs = listOf(
			song("a1", "A", plays = 9), song("a2", "A", plays = 8), song("a3", "A", plays = 7),
			song("b1", "B", plays = 5), song("b2", "B", plays = 4), song("b3", "B", plays = 1),
			song("a4", "A", plays = 0)
		)
		val plan = artistRadioPlan(engine = true, artistName = "Neil Young", songs = songs)
		assertEquals(listOf("a1", "b1", "a2", "a3", "b2"), plan.seeds.map { it.id })
		assertEquals(StationSeed(StationSeed.TRACKS, listOf("a1", "b1", "a2", "a3", "b2"), "Neil Young"), plan.station)
		assertEquals(SEED_TRACKS_MAX, plan.seeds.size)
	}

	@Test
	fun anArtistMixWithoutTheEngineHasNoStationAndKeepsTheSameSeeds() {
		val songs = listOf(song("a1", "A", plays = 3), song("b1", "B", plays = 2))
		val plan = artistRadioPlan(engine = false, artistName = "Neil Young", songs = songs)
		assertNull(plan.station)
		// The seeds still head the queue; Navidrome's similar songs follow (RadioManager).
		assertEquals(listOf("a1", "b1"), plan.seeds.map { it.id })
	}

	@Test
	fun anArtistWithNoSongsHereGetsNoStationEvenWithTheEngine() {
		val plan = artistRadioPlan(engine = true, artistName = "Neil Young", songs = emptyList())
		assertNull(plan.station)
		assertTrue(plan.seeds.isEmpty())
	}

	@Test
	fun aBlankArtistNameNamesNoSeed() {
		val plan = artistRadioPlan(engine = true, artistName = "", songs = listOf(song("a1")))
		assertNull(plan.station?.name)
	}

	// ---- A genre by name, leniently -------------------------------------------------------- //

	@Test
	fun aGenreRecipeIsDrawnByTheNameItHolds() {
		assertEquals(listOf("Shoegaze"), genreSeedNames(Mix(id = "m", name = "x", kind = MixKind.GENRE, seedId = "Shoegaze", seedName = "Shoegaze")))
	}

	@Test
	fun anOlderRecipeHoldingTheGenresIdFallsBackToItsName() {
		// Feishin's form wrote Navidrome's genre id as the seed before B-150.
		assertEquals(
			listOf("9b1f0c3e-genre-id", "Shoegaze"),
			genreSeedNames(Mix(id = "m", name = "x", kind = MixKind.GENRE, seedId = "9b1f0c3e-genre-id", seedName = "Shoegaze"))
		)
	}

	@Test
	fun aGenreRecipeWithOnlyOneFieldUsesThatOne() {
		assertEquals(listOf("Jazz"), genreSeedNames(Mix(id = "m", name = "x", kind = MixKind.GENRE, seedId = "Jazz")))
		assertEquals(listOf("Jazz"), genreSeedNames(Mix(id = "m", name = "x", kind = MixKind.GENRE, seedName = " Jazz ")))
		assertTrue(genreSeedNames(Mix(id = "m", name = "x", kind = MixKind.GENRE)).isEmpty())
	}

	// ---- The face of a mix ----------------------------------------------------------------- //

	@Test
	fun yourMixAndTheKindsThatPlayAsItWearAPerson() {
		assertEquals(MixGlyph.Person, mixGlyph(MixKind.STATION, seedId = ""))
		assertEquals(MixGlyph.Person, mixGlyph(MixKind.FINGERPRINT, seedId = ""))
		assertEquals(MixGlyph.Person, mixGlyph(MixKind.ADAPTIVE, seedId = ""))
	}

	@Test
	fun theOtherKindsKeepTheirGlyphs() {
		assertEquals(MixGlyph.Radio, mixGlyph(MixKind.STATION, seedId = "s1"))
		assertEquals(MixGlyph.Note, mixGlyph(MixKind.SIMILAR, seedId = "s1"))
		assertEquals(MixGlyph.Genre, mixGlyph(MixKind.GENRE, seedId = "Jazz"))
		assertEquals(MixGlyph.Artist, mixGlyph(MixKind.ARTIST, seedId = "ar1"))
		assertEquals(MixGlyph.Unknown, mixGlyph("journey", seedId = ""))
	}
}
