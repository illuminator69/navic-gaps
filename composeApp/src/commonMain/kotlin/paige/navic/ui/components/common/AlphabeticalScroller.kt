package paige.navic.ui.components.common

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import paige.navic.domain.manager.PreferenceManager
import kotlin.math.roundToInt

/**
 * Builds the letter -> grid-item-index map an [AlphabeticalScroller] needs from an
 * already alphabetically-sorted list. [leadingItems] accounts for any non-item cells
 * placed before the list (e.g. a count header). [key] extracts the grouping character.
 * Only the first occurrence of each letter is kept, so tapping a letter jumps to the
 * first matching entry.
 */
fun <T> alphabeticalHeaders(
	items: List<T>,
	leadingItems: Int = 0,
	key: (T) -> Char
): ImmutableList<Pair<String, Int>> {
	val seen = LinkedHashMap<Char, Int>()
	items.forEachIndexed { index, item ->
		val c = key(item)
		if (c !in seen) seen[c] = index + leadingItems
	}
	return seen.map { (letter, idx) -> letter.toString() to idx }.toImmutableList()
}

/**
 * The alphabetical lists' scrollbar (P5 round 2, the owner's note: a scrollbar that shows the
 * letter beside it while dragged, in place of the column of letters). A thumb on the right edge
 * that follows the list, shows while it scrolls and fades a moment after; dragging it scrolls the
 * list through its whole length, with the letter of the place under it in a bubble to its left and
 * a tick as the letter changes. Hidden, it takes no touches, so the list's own scroll is never
 * caught at the edge.
 *
 * [headers] are each letter's first item index ([alphabeticalHeaders]); [bottomPadding] keeps the
 * track clear of what is drawn over the list's bottom (the bars, the mini player).
 */
@Composable
fun AlphabeticalScroller(
	modifier: Modifier = Modifier,
	state: LazyGridState,
	headers: ImmutableList<Pair<String, Int>>,
	bottomPadding: Dp = 0.dp
) {
	val preferenceManager = koinInject<PreferenceManager>()
	if (!preferenceManager.alphabeticalScroll || headers.isEmpty()) return
	val haptic = LocalHapticFeedback.current
	val scope = rememberCoroutineScope()
	val density = LocalDensity.current

	val total by remember { derivedStateOf { state.layoutInfo.totalItemsCount } }
	// Where the list is, 0 to 1: the first visible item over the last first-visible item there can be.
	val listFraction by remember {
		derivedStateOf {
			val info = state.layoutInfo
			val last = (info.totalItemsCount - info.visibleItemsInfo.size).coerceAtLeast(1)
			(state.firstVisibleItemIndex.toFloat() / last).coerceIn(0f, 1f)
		}
	}
	var dragging by remember { mutableStateOf(false) }
	var dragFraction by remember { mutableFloatStateOf(0f) }
	var letter by remember { mutableStateOf(headers.first().first) }

	// Shown while the list moves or the thumb is held, and for a moment after.
	val moving = dragging || state.isScrollInProgress
	var shown by remember { mutableStateOf(false) }
	LaunchedEffect(moving) {
		if (moving) shown = true
		else {
			delay(SCROLLER_HOLD_MS)
			shown = false
		}
	}
	val alpha by animateFloatAsState(if (shown) 1f else 0f, tween(if (shown) 120 else 300), label = "scrollerAlpha")
	// A list that fits on screen has nothing to scroll through.
	if (total == 0 || (!state.canScrollForward && !state.canScrollBackward)) return

	BoxWithConstraints(
		modifier
			.fillMaxHeight()
			.width(SCROLLER_WIDTH)
			.padding(top = 8.dp, bottom = bottomPadding + 8.dp)
	) {
		val trackPx = constraints.maxHeight.toFloat()
		val thumbPx = with(density) { THUMB_HEIGHT.toPx() }
		val fraction = if (dragging) dragFraction else listFraction
		val thumbY = fraction * (trackPx - thumbPx).coerceAtLeast(0f)

		fun dragTo(y: Float) {
			dragFraction = scrollerFraction(y, thumbPx, trackPx)
			val target = scrollerTarget(dragFraction, total)
			val now = letterAt(headers, target)
			if (now != letter) {
				letter = now
				haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
			}
			scope.launch { state.scrollToItem(target) }
		}

		Box(
			Modifier
				.fillMaxSize()
				.then(
					if (!shown) Modifier
					else Modifier.pointerInput(headers, total, trackPx) {
						detectVerticalDragGestures(
							onDragStart = { offset ->
								dragging = true
								dragTo(offset.y)
							},
							onVerticalDrag = { change, _ ->
								change.consume()
								dragTo(change.position.y)
							},
							onDragEnd = { dragging = false },
							onDragCancel = { dragging = false }
						)
					}
				)
		) {
			Box(
				Modifier
					.align(Alignment.TopEnd)
					.offset { IntOffset(0, thumbY.roundToInt()) }
					.padding(end = 4.dp)
					.size(width = if (dragging) 8.dp else 6.dp, height = THUMB_HEIGHT)
					.alpha(alpha)
					.background(
						if (dragging) MaterialTheme.colorScheme.primary
						else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
						RoundedCornerShape(50)
					)
			)
		}

		// The letter, beside the thumb, while it is held. Outside this box (to its left), so it is
		// drawn over the list and takes no touches.
		if (dragging) {
			val bubblePx = with(density) { BUBBLE_SIZE.toPx() }
			Box(
				Modifier
					.align(Alignment.TopEnd)
					.offset {
						IntOffset(
							-(SCROLLER_WIDTH + 8.dp).roundToPx(),
							(thumbY + thumbPx / 2f - bubblePx / 2f).roundToInt()
						)
					}
					.requiredSize(BUBBLE_SIZE)
					.background(
						MaterialTheme.colorScheme.primaryContainer,
						RoundedCornerShape(topStartPercent = 50, topEndPercent = 50, bottomStartPercent = 50, bottomEndPercent = 8)
					),
				contentAlignment = Alignment.Center
			) {
				Text(
					letter,
					style = MaterialTheme.typography.headlineMedium,
					color = MaterialTheme.colorScheme.onPrimaryContainer
				)
			}
		}
	}
}

/** Where on the track a finger at [y] holds the thumb's middle, 0 to 1. */
internal fun scrollerFraction(y: Float, thumbPx: Float, trackPx: Float): Float =
	((y - thumbPx / 2f) / (trackPx - thumbPx).coerceAtLeast(1f)).coerceIn(0f, 1f)

/** The item a thumb at [fraction] stands for, in a list of [total] items. */
internal fun scrollerTarget(fraction: Float, total: Int): Int =
	(fraction.coerceIn(0f, 1f) * (total - 1).coerceAtLeast(0)).roundToInt()

/** The letter of the group [index] is in: the last header at or before it (the first before any). */
internal fun letterAt(headers: List<Pair<String, Int>>, index: Int): String =
	headers.lastOrNull { it.second <= index }?.first ?: headers.firstOrNull()?.first.orEmpty()

private val SCROLLER_WIDTH = 28.dp
private val THUMB_HEIGHT = 48.dp
private val BUBBLE_SIZE = 64.dp
private const val SCROLLER_HOLD_MS = 1_200L
