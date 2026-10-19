package paige.navic.ui.screens.nowPlaying.staged

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.blurEffect
import dev.chrisbanes.haze.hazeEffect
import paige.navic.ui.util.rememberFoldMotion
import kotlin.math.max
import kotlin.math.roundToInt

/*
 * navi-connect P2, the phone's notes (2026-10-20): the Save pill does not open a menu beside itself;
 * it BECOMES the menu. On a tap the pill grows, in place, into a glass panel that floats over the
 * buttons beside it, and shrinks back into the pill when it closes. One shape driven by one number
 * (the progress, 0 = the pill, 1 = the panel) on the motion of the animation style the app is set
 * to (`rememberFoldMotion`: the motion scheme's springs, whose expressive overshoot gives the panel
 * its little swell; Quick's short times), so it moves like the rest of Navic. Reduced motion snaps.
 *
 * Liquid-glass inspired, in Navic's terms: a blur of the player behind it (Haze, drawn in the
 * player's own layout because a popup window cannot blur another window), the pills' own fill, and a
 * thin light edge and sheen that grow in with the panel so the closed shape matches the pill exactly.
 */

/**
 * Where the open panel goes: centred on the [pill], as wide as its content but never narrower than
 * the pill, kept [edge] clear of either side of a [boundsWidth] wide player.
 */
fun saveMenuTarget(pill: Rect, contentWidth: Float, contentHeight: Float, boundsWidth: Float, edge: Float): Rect {
	val width = max(contentWidth, pill.width)
	val left = (pill.center.x - width / 2f).coerceIn(edge, max(edge, boundsWidth - edge - width))
	val top = pill.center.y - contentHeight / 2f
	return Rect(left, top, left + width, top + contentHeight)
}

private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

/** The shape between the pill ([progress] 0) and the panel (1); past 1 it swells on, as a spring does. */
fun morphRect(pill: Rect, panel: Rect, progress: Float): Rect = Rect(
	lerp(pill.left, panel.left, progress),
	lerp(pill.top, panel.top, progress),
	lerp(pill.right, panel.right, progress),
	lerp(pill.bottom, panel.bottom, progress)
)

/** The corner radius from the pill's capsule (half its height) to the panel's. */
fun morphCorner(pillHeight: Float, panelCorner: Float, progress: Float): Float =
	lerp(pillHeight / 2f, panelCorner, progress.coerceIn(0f, 1f))

/**
 * The morphing panel, drawn over the player in its layout ([anchor]: the pill's bounds in root
 * coordinates). [progress] is hoisted so the real pill can step aside while the shape stands in for
 * it. [face] is the pill's own content (icon and label), [rows] the menu. A tap outside or Back
 * closes it ([onDismiss]).
 */
@Composable
fun SaveMorph(
	open: Boolean,
	anchor: Rect?,
	haze: HazeState,
	progress: Animatable<Float, AnimationVector1D>,
	fill: Color,
	onDismiss: () -> Unit,
	face: @Composable () -> Unit,
	rows: @Composable ColumnScope.() -> Unit
) {
	val motion = rememberFoldMotion()
	LaunchedEffect(open) {
		progress.animateTo(if (open) 1f else 0f, motion.spatial())
	}
	NavigationBackHandler(
		state = rememberNavigationEventState(NavigationEventInfo.None),
		isBackEnabled = open,
		onBackCompleted = onDismiss
	)
	if (open) {
		Box(
			Modifier
				.fillMaxSize()
				.pointerInput(Unit) { detectTapGestures { onDismiss() } }
		)
	}
	val pill = anchor ?: return
	// Nothing to draw while closed and still: the real pill is showing.
	if (!open && progress.value == 0f && !progress.isRunning) return

	var origin by remember { mutableStateOf(Offset.Zero) }
	val density = LocalDensity.current
	val edge = with(density) { 16.dp.toPx() }
	val panelCorner = with(density) { 24.dp.toPx() }
	val edgeWidth = with(density) { 1.dp.toPx() }
	fun cornerNow(minDimension: Float) =
		morphCorner(pill.height, panelCorner, progress.value).coerceAtMost(minDimension / 2f)

	Layout(
		contents = listOf(
			{
				// The glass.
				Box(
					Modifier
						.graphicsLayer {
							shape = RoundedCornerShape(cornerNow(size.minDimension))
							clip = true
						}
						.hazeEffect(state = haze) { blurEffect { blurRadius = 24.dp } }
						.drawWithContent {
							val p = progress.value.coerceIn(0f, 1f)
							val r = CornerRadius(cornerNow(size.minDimension))
							// The pills' fill, a shade deeper as it opens, a sheen from the top, and a thin
							// light edge; the last two grow in with the panel, so closed it is the pill.
							drawRect(fill.copy(alpha = fill.alpha * (1f + 0.4f * p)))
							drawRect(
								Brush.verticalGradient(
									listOf(Color.White.copy(alpha = 0.10f * p), Color.Transparent),
									endY = size.height * 0.6f
								)
							)
							drawContent()
							drawRoundRect(
								brush = Brush.linearGradient(
									listOf(
										Color.White.copy(alpha = 0.45f),
										Color.White.copy(alpha = 0.05f),
										Color.White.copy(alpha = 0.22f)
									)
								),
								topLeft = Offset(edgeWidth / 2f, edgeWidth / 2f),
								size = Size(size.width - edgeWidth, size.height - edgeWidth),
								cornerRadius = r,
								alpha = p,
								style = Stroke(edgeWidth)
							)
						}
				)
			},
			{ face() },
			{
				Column(
					Modifier
						.width(IntrinsicSize.Max)
						.padding(vertical = 6.dp),
					content = rows
				)
			}
		),
		modifier = Modifier
			.fillMaxSize()
			.onGloballyPositioned { origin = it.positionInRoot() }
	) { (glassM, faceM, rowsM), constraints ->
		val p = progress.value
		val local = pill.translate(-origin.x, -origin.y)
		val rowsP = rowsM.first().measure(Constraints())
		val panel = saveMenuTarget(local, rowsP.width.toFloat(), rowsP.height.toFloat(), constraints.maxWidth.toFloat(), edge)
		val now = morphRect(local, panel, p)
		val glass = glassM.first().measure(
			Constraints.fixed(now.width.roundToInt().coerceAtLeast(1), now.height.roundToInt().coerceAtLeast(1))
		)
		val faceP = faceM.first().measure(
			Constraints.fixed(local.width.roundToInt().coerceAtLeast(1), local.height.roundToInt().coerceAtLeast(1))
		)
		layout(constraints.maxWidth, constraints.maxHeight) {
			glass.place(now.left.roundToInt(), now.top.roundToInt())
			// The pill's face stays centred in the shape and gives way over the first third.
			faceP.placeWithLayer(
				(now.center.x - faceP.width / 2f).roundToInt(),
				(now.center.y - faceP.height / 2f).roundToInt()
			) { alpha = (1f - p * 3f).coerceIn(0f, 1f) }
			// The rows arrive once the shape is mostly the panel's.
			rowsP.placeWithLayer(
				(now.center.x - rowsP.width / 2f).roundToInt(),
				(now.center.y - rowsP.height / 2f).roundToInt()
			) {
				val a = ((p - 0.4f) / 0.6f).coerceIn(0f, 1f)
				alpha = a
				scaleX = 0.94f + 0.06f * a
				scaleY = 0.94f + 0.06f * a
			}
		}
	}
}
