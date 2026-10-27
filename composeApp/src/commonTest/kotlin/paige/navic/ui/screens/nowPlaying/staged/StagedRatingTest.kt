package paige.navic.ui.screens.nowPlaying.staged

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The player's rating control (P2 notes, 2026-10-19, `StagedRating.kt`): the star opens into five,
 * a rated song shows them open, and a tap on the rating already set clears it.
 */
class StagedRatingTest {

	@Test
	fun aTapSetsTheRatingAndATapOnTheSameStarClearsIt() {
		assertEquals(3, ratingAfterTap(current = 0, tapped = 3))
		assertEquals(5, ratingAfterTap(current = 3, tapped = 5))
		assertEquals(1, ratingAfterTap(current = 4, tapped = 1))
		assertEquals(0, ratingAfterTap(current = 3, tapped = 3))
	}

	@Test
	fun aRatedSongIsShownOpenAndAnUnratedOneOnlyWhenOpened() {
		assertTrue(ratingShownOpen(rating = 2, opened = false))
		assertTrue(ratingShownOpen(rating = 0, opened = true))
		assertFalse(ratingShownOpen(rating = 0, opened = false))
	}

	@Test
	fun theStarsUpToTheRatingAreFilled() {
		assertEquals(listOf(true, true, true, false, false), (1..5).map { starFilled(it, rating = 3) })
		assertEquals(List(5) { false }, (1..5).map { starFilled(it, rating = 0) })
	}

	// Fix round P6: an ext: preview is not a Navidrome song, so it cannot be saved or rated.
	@Test
	fun aPreviewIsNeitherSavedNorRated() {
		assertTrue(canSaveOrRate("so-1"))
		assertFalse(canSaveOrRate("ext:deezer:123"))
		assertFalse(canSaveOrRate(null))
	}
}
