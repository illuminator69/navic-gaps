package paige.navic.ui.screens.library

import paige.navic.ui.screens.library.components.OVERVIEW_PRIMARY_MAX
import paige.navic.ui.screens.library.components.libraryMatchesOffered
import paige.navic.ui.screens.library.components.overviewButtonCount
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/*
 * I-006 added a fifth possible button to the library home (My Listening, Stats' since I-039). The
 * home is a two-column grid, so the count must stay even, and a fifth primary must add a row rather
 * than push the last one — Random, which nothing else offers — off the page.
 */
class OverviewButtonCountTest {
	@Test
	fun upToFourPrimariesKeepTheFourButtonHome() {
		(1..4).forEach { assertEquals(4, overviewButtonCount(it)) }
	}

	@Test
	fun aFifthPrimaryAddsARowInsteadOfDroppingOne() {
		assertEquals(6, overviewButtonCount(5))
	}

	@Test
	fun aSixthPrimaryFillsTheSecondRowAndPushesNothingOff() {
		// Library Matches (I-026) was the sixth: six primaries, six buttons, Random still there.
		assertEquals(6, overviewButtonCount(6))
	}

	@Test
	fun theLongestHomeIsThreeRowsWithNoFiller() {
		// I-039: Downloads joined and My Listening left (it is Stats' now, reached by the home's
		// own card): six primaries at most, three rows of two, Random still there.
		assertEquals(6, OVERVIEW_PRIMARY_MAX)
		assertEquals(6, overviewButtonCount(OVERVIEW_PRIMARY_MAX))
		// A seventh would add a row with one filler rather than push Random off.
		assertEquals(8, overviewButtonCount(7))
	}

	@Test
	fun libraryMatchesIsOfferedWhereverTheLbBotLayerIsOn() {
		// As the settings section it replaced: reachable on a cold start before the hub has
		// spoken, with lb-bot out of reach, and against an lb-bot older than the override
		// routes. The page is where "the copy loaded last", "not reachable" and "older lb-bot"
		// are said; the override routes gate its writes, not the way in.
		assertEquals(true, libraryMatchesOffered(lbConfigured = true, overridesSupported = false))
		assertEquals(true, libraryMatchesOffered(lbConfigured = true, overridesSupported = true))
		assertEquals(false, libraryMatchesOffered(lbConfigured = false, overridesSupported = false))
		assertEquals(false, libraryMatchesOffered(lbConfigured = false, overridesSupported = true))
	}

	@Test
	fun theCountIsAlwaysEvenAndNeverFewerThanThePrimaries() {
		(1..OVERVIEW_PRIMARY_MAX).forEach { primary ->
			val count = overviewButtonCount(primary)
			assertTrue(count % 2 == 0)
			assertTrue(count >= primary)
		}
	}
}
