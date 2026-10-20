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
}
