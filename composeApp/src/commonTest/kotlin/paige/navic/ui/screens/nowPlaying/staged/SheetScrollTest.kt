package paige.navic.ui.screens.nowPlaying.staged

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * How a vertical scroll is shared between the staged queue's layer and the list inside it (P2,
 * `SheetScroll.kt`): the layer takes an upward scroll before the list, until it is at full, and a
 * downward one after the list, once the list is at its top, until it is at peek.
 */
class SheetScrollTest {

	private val anchors = QueueAnchors(peekTop = 734f, halfTop = 440f, fullTop = 96f)

	@Test
	fun upwardScrollIsTheLayersFirstUntilFull() {
		assertEquals(-100f, layerShare(-100f, ScrollPhase.Pre, 440f, anchors))
		// It stops at the full line; the list scrolls the rest.
		assertEquals(-344f, layerShare(-500f, ScrollPhase.Pre, 440f, anchors))
		assertEquals(0f, layerShare(-100f, ScrollPhase.Pre, 96f, anchors))
	}

	@Test
	fun downwardScrollIsTheListsFirst() {
		assertEquals(0f, layerShare(80f, ScrollPhase.Pre, 96f, anchors))
		assertEquals(0f, layerShare(80f, ScrollPhase.Pre, 440f, anchors))
	}

	@Test
	fun whatTheListLeavesOfADownwardScrollMovesTheLayerUntilPeek() {
		assertEquals(80f, layerShare(80f, ScrollPhase.Post, 96f, anchors))
		assertEquals(638f, layerShare(900f, ScrollPhase.Post, 96f, anchors))
		assertEquals(0f, layerShare(80f, ScrollPhase.Post, 734f, anchors))
	}

	@Test
	fun upwardScrollLeftAfterTheListIsNotTheLayers() {
		assertEquals(0f, layerShare(-80f, ScrollPhase.Post, 440f, anchors))
	}
}
