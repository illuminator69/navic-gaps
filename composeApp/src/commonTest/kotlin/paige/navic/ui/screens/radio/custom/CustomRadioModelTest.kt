package paige.navic.ui.screens.radio.custom

import paige.navic.domain.manager.StationCountAnswer
import paige.navic.domain.manager.interleaveSeeds
import paige.navic.domain.manager.seedsPerArtist
import paige.navic.domain.models.DomainExplicitStatus
import paige.navic.domain.models.DomainSong
import paige.navic.domain.models.MixKind
import paige.navic.domain.models.settings.StationCharacter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/* The custom radio's rules (P5, PLAN-navic-library-and-custom-radio Task 2). */
class CustomRadioModelTest {
	private fun song(id: String) = DomainSong(
		id = id, title = "Song $id", artistName = "A", artistId = "ar", albumTitle = "Album",
		albumId = "al", parentId = null, comment = null, trackNumber = 1, discNumber = 1,
		isrc = emptyList(), year = null, genre = null, genres = emptyList(), moods = emptyList(),
		duration = 200.seconds, bpm = null, contributors = emptyList(), userRating = null,
		averageRating = null, bitRate = null, bitDepth = null, sampleRate = null,
		audioChannelCount = null, replayGain = null, fileSize = 0, fileExtension = null,
		mimeType = null, filePath = null, starredAt = null, coverArtId = null, musicBrainzId = null,
		explicitStatus = DomainExplicitStatus.Unknown, artists = emptyList(), albumArtists = emptyList()
	)

	private val moth = PickedArtist("ar-moth", "Moth Season", "cv-moth")
	private val ida = PickedArtist("ar-ida", "Ida Verrall", "cv-ida")
	private val hollow = PickedArtist("ar-hollow", "Hollow Orchard", "cv-hollow")

	// ---- the title ------------------------------------------------------------------------- //

	@Test
	fun oneNameIsThatName() = assertEquals("Moth Season", radioTitle(listOf(moth)))

	@Test
	fun threeNamesAreJoinedInPickOrder() =
		assertEquals("Moth Season, Ida Verrall, Hollow Orchard", radioTitle(listOf(moth, ida, hollow)))

	@Test
	fun noArtistIsNoTitle() = assertEquals("", radioTitle(emptyList()))

	// ---- the seeds ------------------------------------------------------------------------- //

	@Test
	fun twelveSeedsAreSharedOneToFiveEach() {
		assertEquals(
			listOf(0, 5, 5, 4, 3, 2, 2, 1, 1),
			(0..8).map { seedsPerArtist(it) }
		)
	}

	@Test
	fun theSeedsTakeOneFromEachArtistInTurn() {
		val (a1, a2) = song("a1") to song("a2")
		val b1 = song("b1")
		val (c1, c2, c3) = Triple(song("c1"), song("c2"), song("c3"))
		assertEquals(
			listOf("a1", "b1", "c1", "a2", "c2", "c3"),
			interleaveSeeds(listOf(listOf(a1, a2), listOf(b1), listOf(c1, c2, c3))).map { it.id }
		)
	}

	@Test
	fun aSongInTwoArtistsListsIsASeedOnce() {
		val duet = song("duet")
		assertEquals(
			listOf("duet", "b1", "a2"),
			interleaveSeeds(listOf(listOf(duet, song("a2")), listOf(song("b1"), duet))).map { it.id }
		)
	}

	// ---- the count ------------------------------------------------------------------------- //

	private val key = CountKey.of(listOf("ar-moth", "ar-ida"), StationCharacter.Balanced)

	@Test
	fun theKeyIgnoresTheOrderTheArtistsWerePickedIn() =
		assertEquals(key, CountKey.of(listOf("ar-ida", "ar-moth"), StationCharacter.Balanced))

	@Test
	fun countingKeepsTheNumberItReplaces() {
		assertEquals(CountState.Counting(186), reduceCount(CountState.Known(186), key, key, null))
		assertEquals(CountState.Counting(186), reduceCount(CountState.Counting(186), key, key, null))
		assertEquals(CountState.Counting(null), reduceCount(CountState.Idle, key, key, null))
		assertEquals(CountState.Counting(null), reduceCount(CountState.Failed, key, key, null))
	}

	@Test
	fun anAnswerForAKeyThatIsNoLongerOnScreenChangesNothing() {
		val stale = CountKey.of(listOf("ar-moth"), StationCharacter.Balanced)
		val shown = CountState.Counting(40)
		assertSame(shown, reduceCount(shown, stale, key, StationCountAnswer.Counted(12, partial = false)))
		val otherCharacter = CountKey.of(listOf("ar-moth", "ar-ida"), StationCharacter.Explore)
		assertSame(shown, reduceCount(shown, otherCharacter, key, StationCountAnswer.Counted(900, false)))
		assertSame(shown, reduceCount(shown, stale, key, null))
	}

	@Test
	fun eachAnswerHasItsOwnState() {
		val c = CountState.Counting(10)
		assertEquals(CountState.Known(0), reduceCount(c, key, key, StationCountAnswer.Counted(0, false)))
		assertEquals(CountState.Known(431, partial = true), reduceCount(c, key, key, StationCountAnswer.Counted(431, true)))
		assertEquals(CountState.Unknown, reduceCount(c, key, key, StationCountAnswer.SeedUnknown))
		assertEquals(CountState.Unavailable, reduceCount(c, key, key, StationCountAnswer.Unavailable))
		assertEquals(CountState.Failed, reduceCount(c, key, key, StationCountAnswer.Failed))
	}

	@Test
	fun doneNeedsAnArtist() {
		assertFalse(canFinish(0, CountState.Known(500)))
		assertFalse(canFinish(0, CountState.Idle))
	}

	@Test
	fun doneStaysEnabledWhenTheCountIsOutOrMissing() {
		for (state in listOf(
			CountState.Idle, CountState.Counting(null), CountState.Counting(3),
			CountState.Unavailable, CountState.Failed, CountState.Known(1), CountState.Known(4, partial = true)
		)) assertTrue(canFinish(1, state), "$state")
	}

	@Test
	fun doneIsDisabledOnlyByNothingToDrawFrom() {
		assertFalse(canFinish(3, CountState.Known(0)))
		assertFalse(canFinish(3, CountState.Unknown))
	}

	// ---- the recipe ------------------------------------------------------------------------ //

	@Test
	fun aCustomRadioIsAnArtistMixWithEveryArtistAndTheFirstAsItsSeed() {
		val mix = customRadioRecipe(
			"mx_1_abc", "Moth Season, Ida Verrall, Hollow Orchard", listOf(moth, ida, hollow),
			StationCharacter.Explore, Variety.High
		)
		assertEquals(MixKind.ARTIST, mix.kind)
		assertEquals("mx_1_abc", mix.id)
		assertEquals("ar-moth", mix.seedId)
		assertEquals("Moth Season", mix.seedName)
		assertEquals(listOf("ar-moth", "ar-ida", "ar-hollow"), mix.seedIds)
		assertEquals("high", mix.variety)
		assertEquals("explore", mix.character)
		assertEquals("TransitionMaestro", mix.moodCharacter)
		assertEquals("cv-moth", mix.coverArtId)
		assertEquals(50, mix.count)
	}

	@Test
	fun theVarietyWireValuesAreTheHubs() =
		assertEquals(listOf("low", "medium", "high"), Variety.entries.map { it.wire })

	// ---- P7: the mood filter ------------------------------------------------------------------ //

	private val moods = paige.navic.domain.manager.MoodCatalogue(
		configured = true, available = true, ready = true,
		moods = listOf(
			paige.navic.domain.manager.MoodEntry("relax", "Relax", filter = true),
			paige.navic.domain.manager.MoodEntry("sleep", "Sleep", filter = false),
			paige.navic.domain.manager.MoodEntry("energize", "Energize", filter = true),
			paige.navic.domain.manager.MoodEntry("feel-good", "Feel good", filter = true),
			paige.navic.domain.manager.MoodEntry("sad", "Sad", filter = true),
			paige.navic.domain.manager.MoodEntry("focus", "Focus", filter = true),
			paige.navic.domain.manager.MoodEntry("pump-up", "Pump-up", "extra", filter = true),
			paige.navic.domain.manager.MoodEntry("upbeat", "Upbeat", "extra", filter = true),
			paige.navic.domain.manager.MoodEntry("downbeat", "Downbeat", "extra", filter = true)
		)
	)
	private val probe = paige.navic.domain.manager.StationCatalogue(filters = listOf("mood"), moods = true)

	@Test
	fun aMoodFilterIsACountKey() {
		val plain = CountKey.of(listOf("b", "a"), StationCharacter.Balanced)
		val filtered = CountKey.of(listOf("a", "b"), StationCharacter.Balanced, "pump-up")
		kotlin.test.assertNotEquals(plain, filtered)
		assertEquals(filtered, CountKey.of(listOf("b", "a"), StationCharacter.Balanced, "pump-up"))
		// An answer for the key before the filter changed is dropped.
		val shown = reduceCount(CountState.Known(400), plain, filtered, StationCountAnswer.Counted(400, false))
		assertEquals(CountState.Known(400), shown)
		assertEquals(CountState.Known(25), reduceCount(CountState.Counting(400), filtered, filtered, StationCountAnswer.Counted(25, false)))
	}

	@Test
	fun oneFilterAtATimeAndTappingItClearsIt() {
		assertEquals("relax", nextMoodFilter(null, "relax"))
		kotlin.test.assertNull(nextMoodFilter("relax", "relax"))
		assertEquals("upbeat", nextMoodFilter("relax", "upbeat"))
	}

	@Test
	fun theRecipeCarriesTheFilterOnlyWhenSet() {
		val with = customRadioRecipe("mx_1", "Moth Season", listOf(moth), StationCharacter.Close, Variety.Medium, moodFilter = "pump-up")
		assertEquals("pump-up", with.moodFilter)
		val without = customRadioRecipe("mx_2", "Moth Season", listOf(moth), StationCharacter.Close, Variety.Medium)
		assertEquals("", without.moodFilter)
	}

	@Test
	fun theFilterWordsAreHomesFiveThenTheExtraThree() {
		val words = filterWords(moods, probe)
		// The board's order, not the catalogue's (Relax, Energize, ... in the hub).
		assertEquals(listOf("Energize", "Relax", "Feel good", "Sad", "Focus"), words.home.map { it.label })
		assertEquals(listOf("Pump-up", "Upbeat", "Downbeat"), words.extra.map { it.label })
		assertTrue(filterWords(moods, probe.copy(filters = emptyList())).isEmpty)
		assertTrue(filterWords(moods.copy(available = false), probe).isEmpty)
	}

	@Test
	fun underThirtyTheFooterWarns() {
		assertTrue(filterWarning(CountState.Known(29)))
		assertFalse(filterWarning(CountState.Known(30)))
		assertFalse(filterWarning(CountState.Counting(10)))
		assertFalse(filterWarning(CountState.Unknown))
		assertFalse(filterWarning(CountState.Unavailable))
		// Done stays enabled with a low count; 0 is P5's rule.
		assertTrue(canFinish(1, CountState.Known(12)))
	}

	@Test
	fun aMixRoundTripsItsMoodFilter() {
		val act = paige.navic.domain.manager.saveMixAct(
			"Moth Season", MixKind.ARTIST, "mx_1", "ar-moth", "Moth Season", "EchoMatch", 50, "", "close",
			listOf("ar-moth", "ar-b"), "", "pump-up"
		)
		assertEquals("\"pump-up\"", act["moodFilter"].toString())
		val record = kotlinx.serialization.json.JsonObject(act.filterKeys { it != "t" && it != "action" })
		assertEquals("pump-up", paige.navic.domain.manager.mixFromRecord(record)?.moodFilter)
		val plain = paige.navic.domain.manager.saveMixAct(
			"X", MixKind.ARTIST, "mx_2", "ar", "X", "", 50, "", "", emptyList(), "", ""
		)
		assertFalse("moodFilter" in plain)
		assertEquals("", paige.navic.domain.manager.mixFromRecord(kotlinx.serialization.json.JsonObject(plain))?.moodFilter)
	}

	// Fix round P6: the hint under the dials named Artist variety on a hub that draws no variety control.
	@Test
	fun theHintNamesVarietyOnlyWhenItIsOffered() {
		assertEquals(TuneHint.WithVariety, tuneHint(varietyOffered = true))
		assertEquals(TuneHint.WithoutVariety, tuneHint(varietyOffered = false))
	}
}
