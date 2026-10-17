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
import paige.navic.ui.screens.mixes.viewmodels.MixSeedOption
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
 * One spec for both clients: the form offers Radio, Your mix, Genre, Artist in that order (Your mix
 * only with the hub's station engine), a character for all but Genre; a genre recipe holds the
 * genre's NAME; an artist recipe plays as the artist page's radio, never a shuffle; Your mix and
 * the legacy kinds that play as it wear a person. The words themselves are `MixFormWordsTest`'s.
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

	// ---- The form's choices ---------------------------------------------------------------- //

	@Test
	fun withTheEngineTheFormOffersRadioYourMixGenreArtistInThatOrder() {
		assertEquals(
			listOf(MixFormKind.Radio, MixFormKind.YourMix, MixFormKind.Genre, MixFormKind.Artist),
			mixFormKinds(stationEngine = true)
		)
	}

	@Test
	fun withoutTheEngineYourMixIsHiddenAndTheOtherThreeStay() {
		assertEquals(
			listOf(MixFormKind.Radio, MixFormKind.Genre, MixFormKind.Artist),
			mixFormKinds(stationEngine = false)
		)
	}

	@Test
	fun aCharacterForRadioYourMixAndArtistButNotGenre() {
		assertEquals(
			mapOf(
				MixFormKind.Radio to true,
				MixFormKind.YourMix to true,
				MixFormKind.Genre to false,
				MixFormKind.Artist to true
			),
			MixFormKind.entries.associateWith { it.takesCharacter }
		)
	}

	@Test
	fun eachChoiceTakesItsOwnSeed() {
		assertEquals(
			mapOf(
				MixFormKind.Radio to MixFormSeed.PlayingSong,
				MixFormKind.YourMix to MixFormSeed.None,
				MixFormKind.Genre to MixFormSeed.Genre,
				MixFormKind.Artist to MixFormSeed.Artist
			),
			MixFormKind.entries.associateWith { it.seed }
		)
	}

	@Test
	fun theChoicesNameTheirWordsByTheseKeys() {
		assertEquals(
			listOf(
				"mix_kind_station" to "mix_line_radio",
				"queue_your_mix" to "mix_your_mix_line",
				"mix_kind_genre" to "mix_line_genre",
				"mix_kind_artist" to "mix_line_artist"
			),
			MixFormKind.entries.map { it.label.key to it.line.key }
		)
	}

	// ---- What each choice saves ------------------------------------------------------------ //

	@Test
	fun aRadioIsAStationOnThePlayingSongWithItsCharacter() {
		val mix = mixRecipe("Cinnamon Girl radio", MixFormKind.Radio, song("s1", title = "Cinnamon Girl"), null, StationCharacter.Explore, 50)
		assertEquals(MixKind.STATION, mix.kind)
		assertEquals("s1", mix.seedId)
		assertEquals("Cinnamon Girl", mix.seedName)
		assertEquals("explore", mix.character)
		assertEquals("TransitionMaestro", mix.moodCharacter)
		assertEquals("al-s1", mix.coverArtId)
		assertEquals("", mix.id)
		assertEquals(50, mix.count)
	}

	@Test
	fun yourMixIsASeedlessStationWithItsCharacter() {
		val mix = mixRecipe("Your mix", MixFormKind.YourMix, song("s1"), null, StationCharacter.Close, 25)
		assertEquals(MixKind.STATION, mix.kind)
		assertEquals("", mix.seedId)
		assertEquals("", mix.seedName)
		assertEquals("close", mix.character)
		assertEquals("EchoMatch", mix.moodCharacter)
		assertEquals("", mix.coverArtId)
	}

	@Test
	fun aGenreHoldsTheGenreNameInBothFieldsAndNoCharacter() {
		val mix = mixRecipe("Shoegaze", MixFormKind.Genre, song("s1"), MixSeedOption(id = "Shoegaze", name = "Shoegaze"), StationCharacter.Explore, 100)
		assertEquals(MixKind.GENRE, mix.kind)
		assertEquals("Shoegaze", mix.seedId)
		assertEquals("Shoegaze", mix.seedName)
		assertEquals("", mix.character)
		assertEquals("", mix.moodCharacter)
	}

	@Test
	fun aGenreIsSavedByNameEvenIfTheOptionCarriedAnId() {
		val mix = mixRecipe("Shoegaze", MixFormKind.Genre, null, MixSeedOption(id = "g-42", name = "Shoegaze"), StationCharacter.Balanced, 50)
		assertEquals("Shoegaze", mix.seedId)
	}

	@Test
	fun anArtistIsAnArtistRecipeByIdWithItsNameCoverAndCharacter() {
		val mix = mixRecipe("Neil Young", MixFormKind.Artist, song("s1"), MixSeedOption(id = "ar1", name = "Neil Young", coverArtId = "ar-ar1"), StationCharacter.Balanced, 50)
		assertEquals(MixKind.ARTIST, mix.kind)
		assertEquals("ar1", mix.seedId)
		assertEquals("Neil Young", mix.seedName)
		assertEquals("balanced", mix.character)
		assertEquals("SteadyVibes", mix.moodCharacter)
		assertEquals("ar-ar1", mix.coverArtId)
	}

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
