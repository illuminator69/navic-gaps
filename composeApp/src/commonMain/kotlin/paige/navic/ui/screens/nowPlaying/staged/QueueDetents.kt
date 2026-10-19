package paige.navic.ui.screens.nowPlaying.staged

import kotlin.math.abs
import kotlin.math.max

/*
 * navi-connect P2: the staged queue's resting places and how far the player behind it has
 * compacted, every rule a pure function (`QueueDetentsTest`). The queue is a layer inside the
 * player (portrait phones only) with three places: peek (a strip naming the next song), half (the
 * player compacts above it and keeps its controls) and full (the player is a mini header). Every
 * offset here is the layer's TOP in the player's own coordinates, so a smaller number is higher up.
 *
 * Numbers from the boards (dp on an 844 dp phone): peek shows 110 dp of layer; the half line is at
 * 52 % of the height or just below the compact player, whichever is lower; full is 96 dp from the
 * top. Half is skipped when the screen is too short to leave the queue any room at it.
 */

enum class QueueDetent { Peek, Half, Full }

/** Where the layer's top rests at each place. [halfTop] is null on a screen too short for half. */
data class QueueAnchors(val peekTop: Float, val halfTop: Float?, val fullTop: Float) {
	fun top(d: QueueDetent): Float? = when (d) {
		QueueDetent.Peek -> peekTop
		QueueDetent.Half -> halfTop
		QueueDetent.Full -> fullTop
	}

	/** How far up from peek to full the half line is, or null with no half. */
	val halfFraction: Float?
		get() = halfTop?.let { (peekTop - it) / (peekTop - fullTop) }

	/** How far up from peek to full [offsetTop] is, 0 at peek and 1 at full. */
	fun fraction(offsetTop: Float): Float =
		if (peekTop <= fullTop) 0f else ((peekTop - offsetTop) / (peekTop - fullTop)).coerceIn(0f, 1f)
}

/** Where along a gesture the half line falls when a screen has none: the compaction still has a midpoint. */
const val NO_HALF_FRACTION = 0.5f

/** The share of the height the half line sits at on a phone with room for it (the board's 440 of 844). */
const val HALF_LINE_SHARE = 0.52f

/**
 * The anchors for a player [containerPx] tall: peek leaves [peekPx] of layer showing, full is at
 * [fullTopPx], and half sits [gapPx] below the compact player's measured bottom ([compactBottomPx])
 * or at [HALF_LINE_SHARE] of the height, whichever is lower, and only if that leaves the list at
 * least [minHalfListPx] above peek. The compact block is measured, so a big font moves the half line
 * down rather than overlapping the controls.
 */
fun queueAnchors(
	containerPx: Float,
	peekPx: Float,
	fullTopPx: Float,
	compactBottomPx: Float,
	gapPx: Float,
	minHalfListPx: Float
): QueueAnchors {
	val peekTop = containerPx - peekPx
	val half = max(containerPx * HALF_LINE_SHARE, compactBottomPx + gapPx)
	return QueueAnchors(
		peekTop = peekTop,
		halfTop = half.takeIf { peekTop - it >= minHalfListPx && it > fullTopPx },
		fullTop = fullTopPx
	)
}

/** How far along its velocity a release is projected before the nearest place is taken, in seconds. */
const val FLING_PROJECTION_S = 0.15f

/**
 * Where a release goes: the offset projected [FLING_PROJECTION_S] along the velocity (px/s,
 * negative is upward), then the nearest place. A quick flick from peek passes half; a slow one
 * stops there.
 */
fun releaseTarget(offsetTop: Float, velocityPxPerS: Float, anchors: QueueAnchors): QueueDetent {
	val projected = offsetTop + velocityPxPerS * FLING_PROJECTION_S
	return QueueDetent.entries
		.mapNotNull { d -> anchors.top(d)?.let { d to abs(it - projected) } }
		.minBy { it.second }
		.first
}

/** A tap on the handle moves one place: peek to half to full, and full back to half. */
fun handleTapTarget(current: QueueDetent, anchors: QueueAnchors): QueueDetent {
	val hasHalf = anchors.halfTop != null
	return when (current) {
		QueueDetent.Peek -> if (hasHalf) QueueDetent.Half else QueueDetent.Full
		QueueDetent.Half -> QueueDetent.Full
		QueueDetent.Full -> if (hasHalf) QueueDetent.Half else QueueDetent.Peek
	}
}

/** Back collapses one place at a time; null at peek, where Back closes the player. */
fun backTarget(current: QueueDetent, anchors: QueueAnchors): QueueDetent? = when (current) {
	QueueDetent.Full -> if (anchors.halfTop != null) QueueDetent.Half else QueueDetent.Peek
	QueueDetent.Half -> QueueDetent.Peek
	QueueDetent.Peek -> null
}

/** The storyboard's two curves: peek to half rises with a little overshoot, half to full opens without. */
enum class DetentCurve { Rise, Open }

/** Which curve a settle from [from] to [to] runs on: the one of the half the layer lands in. */
fun detentCurve(from: QueueDetent, to: QueueDetent): DetentCurve = when (to) {
	QueueDetent.Full -> DetentCurve.Open
	QueueDetent.Peek -> DetentCurve.Rise
	QueueDetent.Half -> if (from == QueueDetent.Full) DetentCurve.Open else DetentCurve.Rise
}

/** How far the player has compacted: [toHalf] from rest to the half line, then [toFull] from there to full. */
data class Compaction(val toHalf: Float, val toFull: Float)

/** The compaction at [fraction] of the way from peek to full, the half line at [halfFraction]. */
fun compaction(fraction: Float, halfFraction: Float): Compaction {
	val f = fraction.coerceIn(0f, 1f)
	val h = halfFraction.coerceIn(0.01f, 0.99f)
	return Compaction(
		toHalf = (f / h).coerceIn(0f, 1f),
		toFull = ((f - h) / (1f - h)).coerceIn(0f, 1f)
	)
}

private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

/** One value at rest, at the half line and at full, linear between. */
private fun staged(rest: Float, half: Float, full: Float, c: Compaction) =
	lerp(lerp(rest, half, c.toHalf), full, c.toFull)

/** The cover: 342 dp at rest, 0.445 (152 dp) at half, 0.3 at full, where it has gone. */
fun coverScale(c: Compaction): Float = staged(1f, 0.445f, 0.3f, c)

fun coverAlpha(c: Compaction): Float = staged(1f, 1f, 0f, c)

/** The header (chevron, "Playing from", more): through half, gone at full. */
fun headerAlpha(c: Compaction): Float = staged(1f, 1f, 0f, c)

/** The mini header that replaces the player at full: only on the way from half to full. */
fun miniHeaderAlpha(c: Compaction): Float = staged(0f, 0f, 1f, c)

/** The action row and the durations: gone halfway to the half line. */
fun actionsAlpha(c: Compaction): Float = (1f - c.toHalf * 2f).coerceIn(0f, 1f)

/** Title, progress and controls: kept at half, gone at full. */
fun playbackAlpha(c: Compaction): Float = staged(1f, 1f, 0f, c)
