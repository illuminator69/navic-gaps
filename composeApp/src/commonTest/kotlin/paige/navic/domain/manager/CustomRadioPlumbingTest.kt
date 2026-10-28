package paige.navic.domain.manager

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.add
import paige.navic.domain.models.DomainExplicitStatus
import paige.navic.domain.models.DomainSong
import paige.navic.domain.models.Mix
import paige.navic.domain.models.MixKind
import paige.navic.domain.models.isCustomRadio
import paige.navic.domain.models.mixArtistIds
import paige.navic.domain.models.settings.StationCharacter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/*
 * A custom radio's plumbing (P5, PLAN-navic-library-and-custom-radio Task 3): how a recipe of
 * several artists plays, what the hub is sent and read back, and the words Familiar / Blend /
 * Discover.
 */
class CustomRadioPlumbingTest {
	private fun song(id: String, album: String, plays: Int) = DomainSong(
		id = id, title = "Song $id", artistName = "A", artistId = "ar", albumTitle = "Album",
		albumId = album, parentId = null, comment = null, trackNumber = 1, discNumber = 1,
		isrc = emptyList(), year = null, genre = null, genres = emptyList(), moods = emptyList(),
		duration = 200.seconds, bpm = null, contributors = emptyList(), userRating = null,
		averageRating = null, bitRate = null, bitDepth = null, sampleRate = null,
		audioChannelCount = null, replayGain = null, fileSize = 0, fileExtension = null,
		mimeType = null, filePath = null, starredAt = null, coverArtId = null, musicBrainzId = null,
		explicitStatus = DomainExplicitStatus.Unknown, artists = emptyList(), albumArtists = emptyList(),
		playCount = plays
	)

	/** [n] songs of artist [a], each on its own album, the first the most played. */
	private fun artist(a: String, n: Int) = (0 until n).map { song("$a$it", "$a-al$it", 100 - it) }

	// ---- how the recipe plays ---------------------------------------------------------------- //

	@Test
	fun threeArtistsGiveFourSeedsEachDealtInTurn() {
		val lists = listOf(artist("a", 6), artist("b", 6), artist("c", 6))
		val plan = customRadioPlan(engine = true, name = "A, B, C", songsPerArtist = lists)
		val want = interleaveSeeds(lists.map { artistSeedSongs(it).take(seedsPerArtist(3)) })
		assertEquals(want.map { it.id }, plan.seeds.map { it.id })
		assertEquals(listOf("a0", "b0", "c0", "a1", "b1", "c1"), plan.seeds.take(6).map { it.id })
		assertEquals(12, plan.seeds.size)
		assertEquals(StationSeed(StationSeed.TRACKS, want.map { it.id }, "A, B, C"), plan.station)
	}

	@Test
	fun eightArtistsGiveOneSeedEach() {
		val plan = customRadioPlan(true, "x", (0 until 8).map { artist("x$it", 5) })
		assertEquals(8, plan.seeds.size)
		assertEquals((0 until 8).map { "x${it}0" }, plan.seeds.map { it.id })
	}

	@Test
	fun anArtistTheLibraryNoLongerHoldsIsSkippedAndTheRestPlay() {
		val plan = customRadioPlan(true, "A, B, C", listOf(artist("a", 6), emptyList(), artist("c", 6)))
		// Two held: five each, as for a two-artist radio.
		assertEquals(10, plan.seeds.size)
		assertTrue(plan.seeds.all { it.id.startsWith("a") || it.id.startsWith("c") })
	}

	@Test
	fun oneArtistIsExactlyTodaysArtistRadio() {
		val songs = artist("a", 9)
		assertEquals(artistRadioPlan(true, "A", songs), customRadioPlan(true, "A", listOf(songs)))
		assertEquals(artistRadioPlan(false, "A", songs), customRadioPlan(false, "A", listOf(songs)))
		// Only one of three still held: that one's radio.
		assertEquals(artistRadioPlan(true, "A", songs), customRadioPlan(true, "A", listOf(emptyList(), songs, emptyList())))
	}

	@Test
	fun withoutTheEngineThereIsNoStationButTheSameSeeds() {
		val lists = listOf(artist("a", 3), artist("b", 3))
		val plan = customRadioPlan(engine = false, name = "A, B", songsPerArtist = lists)
		assertNull(plan.station)
		assertEquals(customRadioPlan(true, "A, B", lists).seeds, plan.seeds)
	}

	@Test
	fun nothingHeldIsNoStation() {
		val plan = customRadioPlan(true, "A, B", listOf(emptyList(), emptyList()))
		assertNull(plan.station)
		assertTrue(plan.seeds.isEmpty())
	}

	@Test
	fun aMixPlaysItsSeedIdsElseItsSeedId() {
		val custom = Mix("m", "A, B", MixKind.ARTIST, seedId = "a", seedIds = listOf("a", "b", "a", ""))
		assertEquals(listOf("a", "b"), mixArtistIds(custom))
		assertTrue(isCustomRadio(custom))
		val old = Mix("m", "A", MixKind.ARTIST, seedId = "a")
		assertEquals(listOf("a"), mixArtistIds(old))
		assertFalse(isCustomRadio(old))
		assertFalse(isCustomRadio(Mix("m", "A", MixKind.ARTIST, seedId = "a", seedIds = listOf("a"))))
		assertFalse(isCustomRadio(Mix("m", "S", MixKind.STATION, seedIds = listOf("a", "b"))))
	}

	// ---- the wire ------------------------------------------------------------------------------ //

	@Test
	fun theRecordsSeedIdsAreReadAsStringsInOrder() {
		assertEquals(emptyList(), mixSeedIds(null))
		assertEquals(emptyList(), mixSeedIds(JsonPrimitive("a")))
		assertEquals(
			listOf("a", "b"),
			mixSeedIds(buildJsonArray { add("a"); add(7); add(""); add("b"); add("a") })
		)
	}

	private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

	@Test
	fun varietyIsSentOnlyWhenTheProbeListedIt() {
		val newHub = json.decodeFromString<StationCatalogue>(
			"""{"configured":true,"available":true,"engine":"tier2","default":"balanced","characters":[],
			"count":true,"varieties":["low","medium","high"],"filters":[]}"""
		)
		assertTrue(newHub.count)
		assertTrue(newHub.takesVariety("high"))
		assertFalse(newHub.takesVariety(""))
		assertFalse(newHub.takesVariety("wild"))
		val oldHub = json.decodeFromString<StationCatalogue>(
			"""{"configured":true,"available":true,"engine":"tier2","default":"balanced","characters":[]}"""
		)
		assertFalse(oldHub.count)
		assertFalse(oldHub.takesVariety("high"))
		assertFalse(StationCatalogue.BUNDLED.takesVariety("low"))
	}

	@Test
	fun aCountAnswerIsReadByItsStatusAndBody() {
		assertEquals(
			StationCountAnswer.Counted(431, partial = false),
			parseStationCount(200, """{"count":431,"character":"balanced","engine":"tier2","anchors":3,"unknown":[],"partial":false}""")
		)
		assertEquals(StationCountAnswer.Counted(12, partial = true), parseStationCount(200, """{"count":12,"partial":true}"""))
		assertEquals(StationCountAnswer.Counted(0, partial = false), parseStationCount(200, """{"count":0}"""))
		assertEquals(StationCountAnswer.Failed, parseStationCount(200, "{}"))
		assertEquals(StationCountAnswer.Failed, parseStationCount(200, """{"count":"12"}"""))
		assertEquals(StationCountAnswer.SeedUnknown, parseStationCount(404, """{"code":"seed_unknown"}"""))
		assertEquals(StationCountAnswer.Unavailable, parseStationCount(404, """{"error":"unknown route"}"""))
		assertEquals(StationCountAnswer.Unavailable, parseStationCount(503, """{"fallback":true}"""))
		assertEquals(StationCountAnswer.Failed, parseStationCount(400, """{"code":"bad_seed"}"""))
		assertEquals(StationCountAnswer.Failed, parseStationCount(500, null))
		assertEquals(StationCountAnswer.Failed, parseStationCount(null, null))
	}

	// ---- the words ----------------------------------------------------------------------------- //

	@Test
	fun theCharactersAreFamiliarBlendDiscover() {
		assertEquals(listOf("Familiar", "Blend", "Discover"), StationCharacter.entries.map { it.label })
		// What is stored does not move.
		assertEquals(listOf("close", "balanced", "explore"), StationCharacter.entries.map { it.wireId })
		assertEquals(listOf("EchoMatch", "SteadyVibes", "TransitionMaestro"), StationCharacter.entries.map { it.legacyName })
		assertEquals(StationCharacter.Close, StationCharacter.fromWire("close"))
	}

	@Test
	fun theBundledCatalogueAndAnOldHubsSayTheNewWords() {
		assertEquals(
			listOf("Familiar", "Blend", "Discover"),
			StationCharacter.entries.map { StationCatalogue.BUNDLED.infoFor(it).label }
		)
		val oldHub = StationCatalogue(
			characters = listOf(
				StationCharacterInfo("close", "Close", "d1", "EchoMatch"),
				StationCharacterInfo("balanced", "Balanced", "d2", "SteadyVibes"),
				StationCharacterInfo("explore", "Explore", "d3", "TransitionMaestro")
			)
		)
		assertEquals(
			listOf("Familiar", "Blend", "Discover"),
			StationCharacter.entries.map { oldHub.infoFor(it).label }
		)
		assertEquals("d2", oldHub.infoFor(StationCharacter.Balanced).description)
		// A hub's own new words win.
		val renamed = StationCatalogue(characters = listOf(StationCharacterInfo("close", "Near", "d", "EchoMatch")))
		assertEquals("Near", renamed.infoFor(StationCharacter.Close).label)
	}

	// Audit L4a-05: the hub's count may say how far the radio reaches; an older one does not.
	@Test
	fun theCountBodyReadsReachWhenItIsThere() {
		assertEquals(StationCountAnswer.Counted(22, false, reach = 338), parseStationCount(200, """{"count":22,"reach":338}"""))
		assertEquals(StationCountAnswer.Counted(22, false, reach = null), parseStationCount(200, """{"count":22}"""))
		assertEquals(StationCountAnswer.Counted(22, true, reach = null), parseStationCount(200, """{"count":22,"partial":true,"reach":"x"}"""))
	}
}
