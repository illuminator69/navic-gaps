package paige.navic.domain.repositories

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/*
 * B-069: `getArtists` is parsed leniently, so a Subsonic error envelope reads as "no artists",
 * and the artist sync pruned every row in Room against that answer.
 */
class ArtistListingTest {
	@Test
	fun anEmptyAnswerDoesNotReplaceALibraryThatHasArtists() {
		assertFalse(artistListMayReplace(listed = 0, held = 2474))
		assertFalse(artistListMayReplace(listed = 0, held = 1))
	}

	@Test
	fun anEmptyAnswerIsBelievedWhereNothingIsHeld() {
		// A new server, or the first sync after logging in: nothing to lose, and not a failure.
		assertTrue(artistListMayReplace(listed = 0, held = 0))
	}

	@Test
	fun anAnswerThatListsSomebodyReplacesTheList() {
		assertTrue(artistListMayReplace(listed = 2474, held = 0))
		assertTrue(artistListMayReplace(listed = 2473, held = 2474))
		// A library that shrank to one artist is still an answer.
		assertTrue(artistListMayReplace(listed = 1, held = 2474))
	}
}
