package paige.navic.domain.manager

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/*
 * I-021: lb-bot's "matches to check" list, as the client reads it (MatchesToCheck.kt). The two
 * things that go wrong quietly: an lb-bot that predates the list sends no `toCheck` at all, which
 * must not read as "nothing to check"; and the wording, which has to be true of a row lb-bot
 * guessed an artist for AND of one it found none for, and must not call either a mistake.
 */
class MatchesToCheckTest {
	private val wire = Json { ignoreUnknownKeys = true; coerceInputValues = true }
	private val guess = "22222222-2222-4222-8222-222222222222"

	private fun unmatched(id: String, albums: Int = 3) =
		LbMatchToCheck(ndArtistId = id, name = "Artist $id", kind = "unmatched", mbid = guess, albums = albums)

	private fun unresolved(id: String, albums: Int = 2) =
		LbMatchToCheck(ndArtistId = id, name = "Artist $id", kind = "unresolved", mbid = "", albums = albums)

	// --- the wire --------------------------------------------------------------------------

	@Test
	fun theListIsReadOffTheWireBesideTheOverrides() {
		val body = wire.decodeFromString<LbOverrides>(
			"""{"artists":[],"toCheck":[{"ndArtistId":"ar-1","name":"A","kind":"unmatched","mbid":"$guess","albums":3},{"ndArtistId":"ar-2","name":"B","kind":"unresolved","mbid":"","albums":1}]}"""
		)
		assertEquals(
			listOf(
				LbMatchToCheck("ar-1", "A", "unmatched", guess, 3),
				LbMatchToCheck("ar-2", "B", "unresolved", "", 1)
			),
			body.toCheck
		)
	}

	@Test
	fun anOlderLbBotSendsNoListAndThatIsNotAnEmptyOne() {
		assertNull(wire.decodeFromString<LbOverrides>("""{"artists":[]}""").toCheck)
		assertEquals(emptyList(), wire.decodeFromString<LbOverrides>("""{"artists":[],"toCheck":[]}""").toCheck)
	}

	// --- matchesToShow ---------------------------------------------------------------------

	@Test
	fun absentStaysAbsentAndEmptyStaysEmpty() {
		assertNull(matchesToShow(null, emptyMap()))
		assertEquals(emptyList(), matchesToShow(emptyList(), emptyMap()))
	}

	@Test
	fun theRowsKeepLbBotsOrder() {
		val rows = listOf(unmatched("ar-9", albums = 12), unresolved("ar-1", albums = 5), unmatched("ar-3", albums = 1))
		assertEquals(rows, matchesToShow(rows, emptyMap()))
	}

	@Test
	fun anArtistWithAnOverrideLeavesTheListAtOnce() {
		val rows = listOf(unmatched("ar-1"), unresolved("ar-2"))
		val overrides = mapOf("ar-1" to LbArtistOverride(ndArtistId = "ar-1", mbid = guess))
		assertEquals(listOf(unresolved("ar-2")), matchesToShow(rows, overrides))
	}

	@Test
	fun aRowThatNamesNoArtistIsDropped() {
		assertEquals(listOf(unmatched("ar-1")), matchesToShow(listOf(unmatched(""), unmatched("ar-1")), emptyMap()))
	}

	// --- a row, in words -------------------------------------------------------------------

	@Test
	fun aGuessThatMatchesNothingSaysHowManyAlbumsItMissed() {
		assertEquals(
			"lb-bot guessed a MusicBrainz artist that matches none of your 3 albums",
			matchReason(unmatched("ar-1", albums = 3))
		)
		assertEquals(
			"lb-bot guessed a MusicBrainz artist that does not match your 1 album",
			matchReason(unmatched("ar-1", albums = 1))
		)
	}

	@Test
	fun noGuessAtAllSaysSo() {
		assertEquals("No MusicBrainz artist found", matchReason(unresolved("ar-2")))
		// A kind this build does not know is worded by what the row carries.
		assertEquals(
			"No MusicBrainz artist found",
			matchReason(LbMatchToCheck(ndArtistId = "ar-3", kind = "something-new", mbid = "", albums = 4))
		)
		assertEquals(
			"lb-bot guessed a MusicBrainz artist that matches none of your 4 albums",
			matchReason(LbMatchToCheck(ndArtistId = "ar-3", kind = "something-new", mbid = guess, albums = 4))
		)
	}

	@Test
	fun onlyAnUnmatchedRowWithAGuessCanBeKept() {
		assertTrue(matchCanKeep(unmatched("ar-1")))
		assertFalse(matchCanKeep(unresolved("ar-2")))
		assertFalse(matchCanKeep(unmatched("ar-1").copy(mbid = "")))
		assertFalse(matchCanKeep(LbMatchToCheck(ndArtistId = "ar-3", kind = "something-new", mbid = guess)))
	}

	@Test
	fun theDescriptionIsTrueOfBothKindsAndCallsNeitherWrong() {
		assertTrue(MATCHES_TO_CHECK_BLURB.contains("guess matches none of their albums"))
		assertTrue(MATCHES_TO_CHECK_BLURB.contains("found no artist at all"))
		assertTrue(MATCHES_TO_CHECK_BLURB.contains("Worth a look, not necessarily wrong"))
		assertTrue(MATCHES_TO_CHECK_BLURB.contains("not in Latin script"))
	}

	// --- whether the section exists, and what it says --------------------------------------

	@Test
	fun aLoadedBodyWithoutTheListShowsNoSectionAtAll() {
		assertFalse(matchesSectionShown(LbPanelData.CURRENT, null))
		assertFalse(matchesSectionShown(LbPanelData.STALE, null))
		assertTrue(matchesSectionShown(LbPanelData.CURRENT, emptyList()))
		assertTrue(matchesSectionShown(LbPanelData.STALE, listOf(unmatched("ar-1"))))
	}

	@Test
	fun withLbBotOutOfReachAndNothingLoadedTheSectionSaysSo() {
		// Not "nothing to check", and not "loading": lb-bot is needed and cannot be asked.
		assertTrue(matchesSectionShown(LbPanelData.UNREACHABLE, null))
		assertEquals(
			"lb-bot is needed for this and is not reachable right now.",
			matchesNote(LbPanelData.UNREACHABLE, 0)
		)
	}

	@Test
	fun whileTheFirstReadIsInFlightThereIsNoSectionYet() {
		// Whether lb-bot sends the list is seconds from being known; a heading that shows and
		// then vanishes on an older lb-bot would be the wrong thing to have said.
		assertFalse(matchesSectionShown(LbPanelData.READING, null))
		assertNull(matchesNote(LbPanelData.READING, 0))
	}

	@Test
	fun anOlderHubOrLbBotShowsNoSection() {
		assertFalse(matchesSectionShown(LbPanelData.UNSUPPORTED, null))
		assertFalse(matchesSectionShown(LbPanelData.UNSUPPORTED, listOf(unmatched("ar-1"))))
		assertNull(matchesNote(LbPanelData.UNSUPPORTED, 3))
	}

	@Test
	fun anEmptyListSaysNothingToCheckAndAFullOneSaysNothingExtra() {
		assertEquals("Nothing to check.", matchesNote(LbPanelData.CURRENT, 0))
		assertNull(matchesNote(LbPanelData.CURRENT, 5))
		assertEquals(
			"Can't reach lb-bot. This is the copy this device loaded last.",
			matchesNote(LbPanelData.STALE, 5)
		)
	}
}
