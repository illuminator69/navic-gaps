package paige.navic.ui.screens.home

import kotlin.test.Test
import kotlin.test.assertNotEquals

/** M3 (audit L1-09): an album on two shelves is two lazy items and two arrivals. */
class ShelfKeyTest {
	@Test
	fun theSameAlbumOnTwoShelvesHasTwoKeys() {
		assertNotEquals(shelfKey("played", "al-1"), shelfKey("frequent", "al-1"))
	}
}
