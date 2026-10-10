package paige.navic.ui.screens.library

import paige.navic.ui.screens.library.components.OVERVIEW_PRIMARY_MAX
import paige.navic.ui.screens.library.components.overviewButtonCount
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/*
 * I-006 added a fifth possible button to the library home (My Listening). The home is a two-column
 * grid, so the count must stay even, and a fifth primary must add a row rather than push the last
 * one — Random, which nothing else offers — off the page.
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
		// Library Matches (I-026) is the sixth: six primaries, six buttons, Random still there.
		assertEquals(6, overviewButtonCount(OVERVIEW_PRIMARY_MAX))
		assertEquals(6, OVERVIEW_PRIMARY_MAX)
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
