package paige.navic.ui.util

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/*
 * navi-connect P1: a block that appears or goes by rolling its height out, not by popping. The
 * siblings below it follow the height, so nothing jumps. Home's Continue listening pill and box use
 * it, and so does the avatar menu's groups; P2 to P4's source boxes will too.
 */

/** A fold's height time, and the time of a tall one (a box that holds a list). */
const val FOLD_MS = 300
const val FOLD_TALL_MS = 450

/** The content fades over the fold, a little faster than the height moves. */
const val FOLD_FADE_MS = 220

@Composable
fun Fold(
	visible: Boolean,
	modifier: Modifier = Modifier,
	tall: Boolean = false,
	content: @Composable () -> Unit
) {
	val heightMs = if (tall) FOLD_TALL_MS else FOLD_MS
	AnimatedVisibility(
		visible = visible,
		modifier = modifier,
		enter = expandVertically(tween(heightMs, easing = FoldEasing)) + fadeIn(tween(FOLD_FADE_MS)),
		exit = shrinkVertically(tween(heightMs, easing = FoldEasing)) + fadeOut(tween(FOLD_FADE_MS))
	) {
		content()
	}
}
