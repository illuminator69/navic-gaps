package paige.navic.ui.navigation

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Which content a full-screen sheet hides ([SheetCover], I-041): only what lies UNDER it, never the
 * sheet itself or one opened on top of it.
 */
class SheetCoverTest {

	@AfterTest
	fun clear() {
		(0..4).forEach { SheetCover.report(it, false) }
	}

	@Test
	fun theQueueOverThePlayerHidesThePlayerAndThePageButNotItself() {
		// Library page (main window, depth 0) < player (1) < queue (2).
		SheetCover.report(depth = 1, covers = true)
		SheetCover.report(depth = 2, covers = true)
		assertTrue(SheetCover.coveredAbove(0))
		assertTrue(SheetCover.coveredAbove(1))
		assertFalse(SheetCover.coveredAbove(2))
	}

	@Test
	fun aSheetThatStopsCoveringUncoversWhatItHid() {
		SheetCover.report(depth = 1, covers = true)
		SheetCover.report(depth = 2, covers = true)
		// The queue is dragged down: the player shows again, the page is still under the player.
		SheetCover.report(depth = 2, covers = false)
		assertFalse(SheetCover.coveredAbove(1))
		assertTrue(SheetCover.coveredAbove(0))
		SheetCover.report(depth = 1, covers = false)
		assertFalse(SheetCover.coveredAbove(0))
	}

	@Test
	fun aShortSheetOverThePlayerHidesNothing() {
		// The player is open; the playback-speed sheet over it never reports covering.
		SheetCover.report(depth = 1, covers = true)
		assertFalse(SheetCover.coveredAbove(1))
		assertTrue(SheetCover.coveredAbove(0))
	}
}
