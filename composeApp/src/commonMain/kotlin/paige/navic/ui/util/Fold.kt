package paige.navic.ui.util

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntSize
import org.koin.compose.koinInject
import paige.navic.domain.manager.PreferenceManager

/*
 * navi-connect P1: a block that appears or goes by rolling its height out, not by popping. The
 * siblings below it follow the height, so nothing jumps. Home's Continue listening pill and box use
 * it, and so does the avatar menu's groups; P2 to P4's source boxes will too.
 *
 * Its motion follows the animation style (I-042), as the rest of the app does: the springs of the
 * motion scheme `NavicTheme` sets for the style (Expressive's, Standard's) and, under Quick, the short
 * times of `PageMotion.foldMs`. It was a fixed 300 ms tween while the pill sat among components that
 * follow the style, which is what made it look as if it did not belong.
 */

/** What a fold is moved by, and whatever else moves with it (the capsule's corners, a chevron). */
@Immutable
class FoldMotion internal constructor(private val ms: Int?, private val scheme: MotionScheme) {
	/** For the height, a size, a corner, a turn: where something goes. */
	fun <T> spatial(): FiniteAnimationSpec<T> =
		if (ms != null) tween(ms, easing = FoldEasing) else scheme.defaultSpatialSpec()

	/** For a fade: how it looks while it goes, a little quicker than the height. */
	fun <T> effects(): FiniteAnimationSpec<T> =
		if (ms != null) tween(ms * 2 / 3) else scheme.defaultEffectsSpec()
}

/** [tall]: a block that holds a list, which moves further than a few lines do. */
@Composable
fun rememberFoldMotion(tall: Boolean = false): FoldMotion {
	val style = koinInject<PreferenceManager>().animationStyle
	val ms = foldMs(pageMotionOf(style), tall)
	val scheme = MaterialTheme.motionScheme
	return remember(ms, scheme) { FoldMotion(ms, scheme) }
}

@Composable
fun Fold(
	visible: Boolean,
	modifier: Modifier = Modifier,
	tall: Boolean = false,
	content: @Composable () -> Unit
) {
	val motion = rememberFoldMotion(tall)
	AnimatedVisibility(
		visible = visible,
		modifier = modifier,
		enter = expandVertically(motion.spatial<IntSize>()) + fadeIn(motion.effects()),
		exit = shrinkVertically(motion.spatial<IntSize>()) + fadeOut(motion.effects())
	) {
		content()
	}
}
