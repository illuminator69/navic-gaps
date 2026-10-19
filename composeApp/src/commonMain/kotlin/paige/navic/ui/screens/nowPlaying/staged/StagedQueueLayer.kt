package paige.navic.ui.screens.nowPlaying.staged

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.snapTo
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import com.kyant.capsule.ContinuousCapsule
import com.kyant.capsule.ContinuousRoundedRectangle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import paige.navic.ui.navigation.LocalSheetDepth
import paige.navic.ui.navigation.SheetCover
import paige.navic.ui.theme.NavicTheme
import paige.navic.ui.util.rememberNowPlayingCoverAmbient
import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * navi-connect P2: the staged queue, a layer inside the player with three resting places (peek,
 * half, full) driven by one AnchoredDraggableState. The rules are pure (`QueueDetents.kt`,
 * `SheetScroll.kt`); this file is the state, the gesture and the surface.
 *
 * The one exception to "a sheet has one resting place" (navic-gaps CLAUDE.md §7), and only this.
 */

/** Peek to half: rises with a little overshoot (the storyboard's first curve). */
private val RiseEasing = CubicBezierEasing(0.2f, 0.9f, 0.3f, 1.04f)

/** Half to full: opens without overshoot (the storyboard's second curve). */
private val OpenEasing = CubicBezierEasing(0.3f, 0.7f, 0.2f, 1f)

private const val RISE_MS = 420
private const val OPEN_MS = 380

/** Where the layer is and how it moves. Created by [rememberStagedQueueState]. */
@Stable
class StagedQueueState internal constructor(
	initial: QueueDetent,
	private val scope: CoroutineScope
) {
	internal val draggable = AnchoredDraggableState(initial)

	/** The anchors last given ([rememberStagedQueueState] keeps them current). */
	internal var anchors: QueueAnchors? by mutableStateOf(null)
		private set

	private var settleJob: Job? = null

	/** Where the layer rests (the last place it settled at). */
	val detent: QueueDetent get() = draggable.settledValue

	/** Where it is going: the place a settle in flight is heading for, else where it rests. */
	val targetDetent: QueueDetent get() = draggable.targetValue

	/** The layer's top, in the player's coordinates; NaN until the anchors are known. */
	val offsetTop: Float get() = draggable.offset

	/** How far from peek to full the layer is: 0 at peek, 1 at full. */
	val fraction: Float
		get() {
			val a = anchors ?: return 0f
			val offset = draggable.offset
			return if (offset.isNaN()) 0f else a.fraction(offset)
		}

	internal fun updateAnchors(new: QueueAnchors) {
		anchors = new
		val target = draggable.targetValue.takeIf { new.top(it) != null } ?: QueueDetent.Peek
		draggable.updateAnchors(
			DraggableAnchors {
				QueueDetent.Peek at new.peekTop
				new.halfTop?.let { QueueDetent.Half at it }
				QueueDetent.Full at new.fullTop
			},
			target
		)
	}

	/** Moves the layer to [d] on the storyboard's curve for that move; snaps under reduced motion. */
	suspend fun goTo(d: QueueDetent) {
		val a = anchors ?: return
		val to = if (a.top(d) != null) d else QueueDetent.Peek
		val reduced = scope.coroutineContext[MotionDurationScale]?.scaleFactor == 0f
		if (reduced) {
			draggable.snapTo(to)
			return
		}
		val spec = when (detentCurve(draggable.settledValue, to)) {
			DetentCurve.Rise -> tween<Float>(RISE_MS, easing = RiseEasing)
			DetentCurve.Open -> tween(OPEN_MS, easing = OpenEasing)
		}
		draggable.animateTo(to, spec)
	}

	/** The place a release goes to ([releaseTarget]), so what is tested is what runs. */
	internal fun settle(velocityPxPerS: Float) {
		val a = anchors ?: return
		val offset = draggable.offset
		if (offset.isNaN()) return
		launchMove(releaseTarget(offset, velocityPxPerS, a))
	}

	internal fun launchMove(d: QueueDetent) {
		settleJob?.cancel()
		settleJob = scope.launch { goTo(d) }
	}

	/** A finger took the layer: whatever settle was running stops where it is. */
	internal fun stopMoving() {
		settleJob?.cancel()
		settleJob = null
	}

	/** Moves the layer by a raw drag delta (px, positive is down); returns what it took. */
	internal fun dragBy(delta: Float): Float = draggable.dispatchRawDelta(delta)
}

/**
 * The layer's state, its place saved with the player's entry. [anchors] is read whenever it changes
 * (the player measures its compact block, the window resizes, a cast starts or ends).
 */
@Composable
fun rememberStagedQueueState(anchors: () -> QueueAnchors?): StagedQueueState {
	val scope = rememberCoroutineScope()
	var saved by rememberSaveable { mutableStateOf(QueueDetent.Peek.name) }
	val state = remember { StagedQueueState(QueueDetent.valueOf(saved), scope) }
	val currentAnchors by rememberUpdatedState(anchors)
	LaunchedEffect(state) {
		snapshotFlow { currentAnchors() }.collect { a -> if (a != null) state.updateAnchors(a) }
	}
	LaunchedEffect(state) {
		snapshotFlow { state.detent }.collect { saved = it.name }
	}
	return state
}

/**
 * The staged queue's layer: the player's ambient surface with a 26 dp top corner, a handle, and
 * either the [peek] row (at peek) or the queue's [content] (composed from the first upward pixel,
 * and kept while the layer is anywhere but peek). Placed by its top at [StagedQueueState.offsetTop]
 * inside a box as tall as the player; drawn nothing until the anchors are known.
 */
@Composable
fun StagedQueueLayer(
	state: StagedQueueState,
	modifier: Modifier = Modifier,
	peek: @Composable () -> Unit,
	content: @Composable (QueueDetent) -> Unit
) {
	val anchors = state.anchors ?: return
	val ambient = rememberNowPlayingCoverAmbient()
	val depth = LocalSheetDepth.current

	// Back collapses one place at a time; at peek it is the player's again (it closes).
	NavigationBackHandler(
		state = rememberNavigationEventState(NavigationEventInfo.None),
		isBackEnabled = state.targetDetent != QueueDetent.Peek,
		onBackCompleted = {
			val a = state.anchors ?: return@NavigationBackHandler
			backTarget(state.targetDetent, a)?.let { state.launchMove(it) }
		}
	)

	// Settled at full, the layer hides the player under it as the queue's window did: the cover's
	// blur and the wave hold (SheetCover). Off the moment it moves.
	val covers by remember(state) {
		derivedStateOf {
			val a = state.anchors
			state.detent == QueueDetent.Full && !state.draggable.isAnimationRunning &&
				a != null && abs(state.offsetTop - a.fullTop) < 1f
		}
	}
	DisposableEffect(depth, covers) {
		SheetCover.report(depth + 1, covers)
		onDispose { SheetCover.report(depth + 1, false) }
	}

	val scrollConnection = remember(state) { LayerScrollConnection(state) }
	val composed by remember(state) {
		derivedStateOf {
			val a = state.anchors
			val offset = state.offsetTop
			a != null && !offset.isNaN() && offset < a.peekTop - 1f
		}
	}

	Box(
		modifier = modifier
			// As tall as at full, placed by its top: the layer slides and is never re-measured while
			// it moves (the offset is read in placement only).
			.layout { measurable, constraints ->
				val height = (constraints.maxHeight - anchors.fullTop.roundToInt()).coerceAtLeast(0)
				val placeable = measurable.measure(constraints.copy(minHeight = height, maxHeight = height))
				layout(placeable.width, constraints.maxHeight) {
					val top = state.offsetTop
					if (!top.isNaN()) placeable.place(0, top.roundToInt())
				}
			}
			.draggable(
				state = rememberDraggableState { state.dragBy(it) },
				orientation = Orientation.Vertical,
				onDragStarted = { state.stopMoving() },
				onDragStopped = { velocity -> state.settle(velocity) }
			)
			.nestedScroll(scrollConnection)
	) {
		NavicTheme(ambient.scheme, contentColor = ambient.onAmbient) {
			Column(
				modifier = Modifier
					.fillMaxSize()
					.graphicsLayer {
						shape = ContinuousRoundedRectangle(topStart = 26.dp, topEnd = 26.dp)
						clip = true
					}
					.background(Brush.verticalGradient(listOf(ambient.top, ambient.top, ambient.bottom)))
			) {
				LayerHandle(state)
				Box(Modifier.fillMaxWidth().weight(1f)) {
					// The peek row gives way over the first stretch of the rise, the queue arrives
					// over the next: the two never stand on each other.
					Box(
						Modifier
							.fillMaxWidth()
							.graphicsLayer { alpha = (1f - state.fraction * 8f).coerceIn(0f, 1f) }
					) {
						peek()
					}
					if (composed) {
						CompositionLocalProvider(LocalSheetDepth provides depth + 1) {
							Box(
								Modifier
									.fillMaxSize()
									.graphicsLayer { alpha = ((state.fraction - 0.06f) * 8f).coerceIn(0f, 1f) }
							) {
								content(state.targetDetent)
							}
						}
					}
				}
			}
		}
	}
}

/** The 32 x 4 dp handle in the primary colour: a button that moves one place, with expand and collapse for TalkBack. */
@Composable
private fun LayerHandle(state: StagedQueueState) {
	Box(
		modifier = Modifier
			.fillMaxWidth()
			.height(24.dp)
			.semantics {
				val a = state.anchors
				if (a != null && state.targetDetent != QueueDetent.Full) {
					expand { state.launchMove(handleTapTarget(state.targetDetent, a)); true }
				}
				if (a != null && state.targetDetent != QueueDetent.Peek) {
					collapse { backTarget(state.targetDetent, a)?.let { state.launchMove(it) }; true }
				}
			}
			.clickable(
				interactionSource = remember { MutableInteractionSource() },
				indication = null,
				role = Role.Button
			) {
				state.anchors?.let { state.launchMove(handleTapTarget(state.targetDetent, it)) }
			},
		contentAlignment = Alignment.Center
	) {
		Surface(color = MaterialTheme.colorScheme.primary, shape = ContinuousCapsule) {
			Box(Modifier.size(width = 32.dp, height = 4.dp))
		}
	}
}

/**
 * The list inside the layer and the layer share a vertical scroll by [layerShare]: up, the layer
 * first until full; down, the list first and then the layer until peek. What is left of a downward
 * scroll at peek is kept, so it never drags the player itself down. A fling ends on [releaseTarget].
 */
private class LayerScrollConnection(private val state: StagedQueueState) : NestedScrollConnection {
	override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
		val a = state.anchors ?: return Offset.Zero
		val offset = state.offsetTop
		if (source != NestedScrollSource.UserInput || offset.isNaN()) return Offset.Zero
		val take = layerShare(available.y, ScrollPhase.Pre, offset, a)
		if (take == 0f) return Offset.Zero
		state.stopMoving()
		return Offset(0f, state.dragBy(take))
	}

	override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
		val a = state.anchors ?: return Offset.Zero
		val offset = state.offsetTop
		if (source != NestedScrollSource.UserInput || offset.isNaN()) return Offset.Zero
		val take = layerShare(available.y, ScrollPhase.Post, offset, a)
		if (take != 0f) {
			state.stopMoving()
			state.dragBy(take)
		}
		// The rest of a downward scroll stays here: the player closes by its own drag, above the layer.
		return if (available.y > 0f) available else Offset(0f, take)
	}

	override suspend fun onPreFling(available: Velocity): Velocity {
		val a = state.anchors ?: return Velocity.Zero
		val offset = state.offsetTop
		// Between places, an upward fling is the layer's: it settles before the list flings.
		if (available.y < 0f && !offset.isNaN() && offset > a.fullTop + 0.5f) {
			state.settle(available.y)
			return available
		}
		return Velocity.Zero
	}

	override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
		val a = state.anchors ?: return Velocity.Zero
		val offset = state.offsetTop
		if (offset.isNaN()) return Velocity.Zero
		// Only when the layer is between places, or the list left a downward fling over.
		val resting = a.top(state.detent)?.let { abs(it - offset) < 0.5f } == true
		if (resting && available.y <= 0f) return Velocity.Zero
		state.settle(available.y)
		return available
	}
}
