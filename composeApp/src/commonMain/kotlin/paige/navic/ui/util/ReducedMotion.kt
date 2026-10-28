package paige.navic.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.MotionDurationScale
import androidx.lifecycle.compose.LifecycleResumeEffect

/*
 * Reduced motion is the system animation scale ("Remove animations" sets it to 0). The kit's
 * animations honour it on their own through [MotionDurationScale]; what times itself on the wall
 * clock does not, and reads this flag instead (audit L1-03): Coil's cover cross-fade, the Now
 * Playing blurred background's rotation, the marquee and the mini player's thumbnail.
 */

/**
 * True when the system animation scale is 0. Read the way the arrival kit reads it, from the
 * composition's coroutine context, and again each time the screen resumes (the setting is changed in
 * the system's settings, so the app is resumed after it).
 */
@Composable
fun rememberReducedMotion(): Boolean {
	val scope = rememberCoroutineScope()
	var reduced by remember { mutableStateOf(scope.coroutineContext[MotionDurationScale]?.scaleFactor == 0f) }
	LifecycleResumeEffect(scope) {
		reduced = scope.coroutineContext[MotionDurationScale]?.scaleFactor == 0f
		onPauseOrDispose { }
	}
	return reduced
}

/** A wall-clock cross-fade's length: none under reduced motion. */
fun crossfadeMsFor(reduced: Boolean, ms: Int): Int = if (reduced) 0 else ms
