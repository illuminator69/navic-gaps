package paige.navic.ui.components.sheets

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.contentColorFor
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import paige.navic.ui.theme.NavicTheme
import paige.navic.ui.util.CoverAmbient
import paige.navic.ui.util.SheetHideMotionSpec
import paige.navic.ui.util.SheetShowMotionSpec

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModalBottomSheet(
	onDismissRequest: () -> Unit,
	modifier: Modifier = Modifier,
	// **One resting place: fully shown.** Material's own default also enables a half-way
	// place (`PartiallyExpanded`), which the opening heads for and which exists only while the
	// sheet is taller than half the screen. A sheet that reads after it opens starts shorter
	// than that — so it shows whole — and when its content lands and passes half the screen
	// the half-way place comes into being and the sheet goes THERE: it opened whole, then
	// dropped until its last control was cut in half (the missing-album sheet's "Find
	// sources", seen on the phone). With only Hidden and Expanded there is nowhere to stop
	// short: the sheet rests at its content's height, and content taller than the screen
	// scrolls inside it. This is the state upstream's own sheets already pass (Share, Sort,
	// Account, Sleep timer); a caller that wants the half-way stop passes its own.
	sheetState: SheetState = rememberBottomSheetState(
		initialValue = SheetValue.Hidden,
		enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)
	),
	sheetMaxWidth: Dp = BottomSheetDefaults.SheetMaxWidth,
	sheetGesturesEnabled: Boolean = true,
	shape: Shape = BottomSheetDefaults.ExpandedShape,
	containerColor: Color = BottomSheetDefaults.ContainerColor,
	contentColor: Color = contentColorFor(containerColor),
	tonalElevation: Dp = 0.dp,
	scrimColor: Color = BottomSheetDefaults.ScrimColor,
	dragHandle: @Composable (() -> Unit)? = { BottomSheetDefaults.DragHandle() },
	contentWindowInsets: @Composable () -> WindowInsets = { BottomSheetDefaults.modalWindowInsets },
	properties: ModalBottomSheetProperties = ModalBottomSheetProperties(),
	// When set, the sheet carries an Apple-Music-style dominant-colour wash
	// (Haze can't backdrop a ModalBottomSheet — separate window). The container is
	// the opaque muted top colour so the WHOLE surface (incl. the drag-handle
	// notch) blends; a top→bottom gradient is drawn over the content starting from
	// that same top colour (seamless), and the content is themed with the cover
	// scheme so its text/cards adapt. The Material surface still clips it to [shape].
	ambient: CoverAmbient? = null,
	sheetTitle: String? = null,
	// **A sheet opens in one motion, whatever arrives after its first frame.** Material rests a
	// sheet at `screen height − sheet height` and works that out again on every change of the
	// sheet's size; the opening animation picks its target ONCE, from the first frame, and is
	// restarted from where it is — a fresh tween, fast at the start — each time the anchors move
	// under it. Upstream's sheets never notice: their content is all there on the first frame.
	// The fork's sheets read from lb-bot after they open, so the first frame is a spinner, the
	// sheet starts for a resting place a hand high, and every answer that lands (the album, its
	// editions, its covers, the search results) throws it at a new one: a series of jerks where
	// there should be one slide. With this on, a change of the content's height is eased, so the
	// sheet's size — and the resting place Material derives from it — moves smoothly, and the
	// one opening animation follows it up. A sheet whose content is static is unaffected.
	// **Never while a finger is on the sheet** (B-121): near the top of the screen Material gives
	// the content more room as the sheet is dragged down (the status bar's share of the inset
	// shrinks), so a drag changes the content's height every frame; eased, that height lags, the
	// resting place moves under the drag, and the sheet snapped back to the top instead of
	// following the finger — a full-height sheet could not be swiped away at all. Measured on the
	// emulator: with easing during the drag the offset bounced between 3 and 124 px over a 1200 px
	// swipe; without it the offset followed the finger to the bottom and the sheet dismissed.
	// Off for the two navigation scenes, which host whole pages (the player, the queue) and
	// are left exactly as they were.
	animateContentHeight: Boolean = true,
	content: @Composable ColumnScope.() -> Unit,
) {
	// Whether a finger is down anywhere on the sheet (handle or content). Observed on the
	// Initial pass and never consumed, so the sheet's own drag and the content's scrolling see
	// every event as before.
	var touched by remember { mutableStateOf(false) }
	androidx.compose.material3.ModalBottomSheet(
		onDismissRequest = onDismissRequest,
		modifier = modifier.pointerInput(Unit) {
			awaitPointerEventScope {
				while (true) {
					val event = awaitPointerEvent(PointerEventPass.Initial)
					touched = event.changes.any { it.pressed }
				}
			}
		}.semantics {
			sheetTitle?.let { sheetTitle ->
				paneTitle = sheetTitle
			}
		},
		sheetState = sheetState,
		sheetMaxWidth = sheetMaxWidth,
		sheetGesturesEnabled = sheetGesturesEnabled,
		shape = shape,
		containerColor = ambient?.top ?: containerColor,
		// Drive the implicit content colour from the cover scheme too, so elements
		// that rely on LocalContentColor (not an explicit MaterialTheme colour) stay
		// legible on the tinted surface.
		contentColor = ambient?.onAmbient ?: contentColor,
		tonalElevation = tonalElevation,
		scrimColor = scrimColor,
		dragHandle = dragHandle,
		// For the ambient wash, M3 insets only the TOP: the drag handle then sits below the
		// status bar, as on every Material sheet. With no inset at all (as before B-121) a sheet
		// tall enough to reach the top drew its handle inside the status-bar band, where a pull
		// opened the notification shade instead of moving the sheet. The container is the
		// gradient's own top colour, so that band shows no seam. The bottom and sides are
		// re-applied INSIDE the gradient below, so it reaches the true bottom edge and no
		// container-coloured frame shows there.
		contentWindowInsets = if (ambient != null) ({ contentWindowInsets().only(WindowInsetsSides.Top) }) else contentWindowInsets,
		properties = properties,
		content = {
			// INSIDE the window insets (below), not around them: the keyboard's inset changes
			// every frame as it slides, and easing that too would let the keyboard cover the
			// field it was opened for. The eased size is clamped to what the sheet is given,
			// so a shrinking viewport is obeyed at once.
			val eased = if (animateContentHeight && !touched) Modifier.animateContentSize() else Modifier
			if (ambient != null) {
				val insets = contentWindowInsets()
				NavicTheme(ambient.scheme, contentColor = ambient.onAmbient) {
					Column(
						modifier = Modifier
							.fillMaxWidth()
							// `weight(fill = false)` bounds this wrapper to the space the
							// sheet actually has. Without it a plain Column measures its
							// children with INFINITE max height, so any content using
							// `verticalScroll` gets a viewport as tall as its own content
							// — nothing to scroll — and the overflow is simply clipped at
							// the screen edge. `AboutSheet` is exactly that shape, and it
							// silently lost the ability to scroll the moment it started
							// passing an ambient. The non-ambient branch below never had
							// the problem because the sheet's own column bounds it.
							.weight(1f, fill = false)
							.background(
								Brush.verticalGradient(
									listOf(ambient.top, ambient.top, ambient.bottom)
								)
							)
							// Apply the rest of the content insets here, so the gradient reaches the
							// true bottom/side edges and no container-coloured border shows.
							.windowInsetsPadding(insets.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
							.then(eased),
						content = content
					)
				}
			} else if (animateContentHeight) {
				// The same bounded column the ambient branch has (`weight(fill = false)`: see
				// there), so the content is measured exactly as before and only its size eases.
				Column(
					modifier = Modifier
						.fillMaxWidth()
						.weight(1f, fill = false)
						.then(eased),
					content = content
				)
			} else {
				content()
			}
		},
	)

	@Suppress("INVISIBLE_REFERENCE")
	LaunchedEffect(Unit) {
		sheetState.showMotionSpec = SheetShowMotionSpec
		sheetState.hideMotionSpec = SheetHideMotionSpec
	}
}
