package paige.navic.ui.components.common

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import paige.navic.ui.navigation.rememberCoveredBySheet

private const val BAR_COUNT = 5
private val BarWidth = 3.dp
private val BarGap = 2.dp

/**
 * The playing indicator: five pill-shaped bars rising and falling.
 *
 * The bars are drawn, not laid out (I-041): their heights are read in the DRAW phase, so the five
 * endless animations redraw this one node per frame instead of recomposing and remeasuring a Row
 * of five boxes per frame for as long as a playing row is on screen (the queue's playing row while
 * it scrolls). Under a full-screen sheet ([rememberCoveredBySheet]) the bars hold still, as last
 * drawn, so a hidden album page stops redrawing its window every frame.
 */
@Composable
fun Waveform(
	modifier: Modifier = Modifier,
	isPlaying: Boolean
) {
	val transition = rememberInfiniteTransition()
	val fractions = List(BAR_COUNT) { index ->
		transition.animateFloat(
			initialValue = 0.2f,
			targetValue = 1f,
			animationSpec = infiniteRepeatable(
				animation = tween(
					durationMillis = 400 + (index * 150),
					easing = FastOutSlowInEasing
				),
				repeatMode = RepeatMode.Reverse
			)
		)
	}
	val limit = animateFloatAsState(if (isPlaying) 1f else .2f)
	val color = MaterialTheme.colorScheme.onSurface
	val covered = rememberCoveredBySheet()
	val held = remember { FloatArray(BAR_COUNT) { 0.2f } }
	Spacer(
		modifier = modifier
			.height(18.dp)
			.width(BarWidth * BAR_COUNT + BarGap * (BAR_COUNT - 1))
			.drawBehind {
				val barWidth = BarWidth.toPx()
				val step = barWidth + BarGap.toPx()
				for (index in 0 until BAR_COUNT) {
					if (!covered) held[index] = fractions[index].value.coerceAtMost(limit.value)
					val barHeight = size.height * held[index]
					drawRoundRect(
						color = color,
						topLeft = Offset(index * step, (size.height - barHeight) / 2f),
						size = Size(barWidth, barHeight),
						cornerRadius = CornerRadius(barWidth / 2f)
					)
				}
			}
	)
}
