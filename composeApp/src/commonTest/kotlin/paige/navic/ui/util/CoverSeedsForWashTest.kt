package paige.navic.ui.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/*
 * Audit L4a-03: the wash kept the previous cover's seeds as a bridge while the next cover's were on
 * their way, and also when there was no next cover at all (a cleared queue, a radio, a coverless
 * song) or its fetch failed, so a gone cover went on colouring the page. [seedsForWash] keeps the
 * bridge only while a cover is actually coming.
 */
class CoverSeedsForWashTest {

	@Test
	fun noCoverIsNoColourEvenWithAFetchedBridge() {
		assertNull(seedsForWash(paletteKey = null, cached = null, fetched = "old", fetchFailedFor = null))
	}

	@Test
	fun theStoredSeedsWin() {
		assertEquals("stored", seedsForWash("c2", cached = "stored", fetched = "old", fetchFailedFor = null))
	}

	@Test
	fun aFailedFetchLetsGoOfTheBridge() {
		assertNull(seedsForWash("c2", cached = null, fetched = "old", fetchFailedFor = "c2"))
	}

	@Test
	fun whileTheNewCoverIsComingTheOldOneBridges() {
		assertEquals("old", seedsForWash("c2", cached = null, fetched = "old", fetchFailedFor = "c1"))
	}
}
