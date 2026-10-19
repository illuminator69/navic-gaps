package paige.navic.ui.components.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Which cover a browsing page wears ([washCoverArtId]): on a cold start, the last one until the player has restored. */
class WashCoverIdTest {

	@Test
	fun nowPlayingWins() {
		assertEquals("a", washCoverArtId("a", false, "b"))
	}

	@Test
	fun lastWashIsWornOnlyBeforeRestore() {
		assertEquals("b", washCoverArtId(null, false, "b"))
	}

	@Test
	fun nothingAfterRestoreWhenNothingPlays() {
		assertNull(washCoverArtId(null, true, "b"))
	}

	@Test
	fun aBlankLastWashIsNothing() {
		assertNull(washCoverArtId(null, false, ""))
	}
}
