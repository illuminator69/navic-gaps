package paige.navic.ui.screens.nowPlaying.staged

/*
 * navi-connect P2: how a vertical scroll inside the staged queue is shared between the layer and
 * its list (`SheetScrollTest`), the Material bottom sheet's rule: the layer takes an upward scroll
 * BEFORE the list (it rises to full first, then the list scrolls), and a downward one AFTER the list
 * (the list scrolls back to its top first, then the layer descends to peek). A drag a row's handle
 * started never reaches here: the reorder consumes it.
 */

/** [Pre]: before the list has scrolled ([androidx.compose.ui.input.nestedscroll.NestedScrollConnection.onPreScroll]); [Post]: what it left. */
enum class ScrollPhase { Pre, Post }

/**
 * The part of [available] (a vertical scroll delta; negative is the finger moving up) the layer
 * takes in [phase], with its top at [offsetTop]: never past full going up, never past peek going down.
 */
fun layerShare(available: Float, phase: ScrollPhase, offsetTop: Float, anchors: QueueAnchors): Float = when {
	phase == ScrollPhase.Pre && available < 0f -> {
		val room = offsetTop - anchors.fullTop
		if (room <= 0f) 0f else maxOf(available, -room)
	}

	phase == ScrollPhase.Post && available > 0f -> {
		val room = anchors.peekTop - offsetTop
		if (room <= 0f) 0f else minOf(available, room)
	}

	else -> 0f
}
