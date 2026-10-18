package paige.navic.ui.util

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.PathEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp

private val pathForAnimation =
	Path().apply {
		moveTo(0f, 0f)
		cubicTo(0.05f, 0f, 0.133333f, 0.06f, 0.166666f, 0.4f)
		cubicTo(0.208333f, 0.82f, 0.25f, 1f, 1f, 1f)
	}

const val DurationMedium1 = 250
const val DurationMedium2 = 300
const val DurationLong1 = 450
const val DurationLong2 = 500
val EmphasizedEasing = PathEasing(pathForAnimation)
val EmphasizedAccelerateEasing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
val EmphasizedDecelerateEasing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

val SheetShowMotionSpec = tween<Float>(
	durationMillis = 400,
	easing = EmphasizedDecelerateEasing
)
val SheetHideMotionSpec = tween<Float>(
	durationMillis = 600,
	easing = EmphasizedAccelerateEasing
)

object Material3Transitions {
	// navi-connect I-042: the four specs are built from a PageMotion (PageMotion.kt), so the
	// Quick style can shorten them. The vals are the Expressive/Standard timing, unchanged.
	fun enter(motion: PageMotion): (Density) -> EnterTransition = { density ->
		pageEnter(motion, density, sign = 1)
	}

	fun popEnter(motion: PageMotion): (Density) -> EnterTransition = { density ->
		pageEnter(motion, density, sign = -1)
	}

	fun exit(motion: PageMotion): (Density) -> ExitTransition = { density ->
		pageExit(motion, density, sign = -1)
	}

	fun popExit(motion: PageMotion): (Density) -> ExitTransition = { density ->
		pageExit(motion, density, sign = 1)
	}

	val SharedXAxisEnterTransition: (Density) -> EnterTransition = enter(DefaultPageMotion)

	val SharedXAxisPopEnterTransition: (Density) -> EnterTransition = popEnter(DefaultPageMotion)

	val SharedXAxisExitTransition: (Density) -> ExitTransition = exit(DefaultPageMotion)

	val SharedXAxisPopExitTransition: (Density) -> ExitTransition = popExit(DefaultPageMotion)

	private fun pageEnter(motion: PageMotion, density: Density, sign: Int): EnterTransition {
		val easing = when (motion.enterEasing) {
			PageEasing.Emphasized -> EmphasizedEasing
			PageEasing.EmphasizedDecelerate -> EmphasizedDecelerateEasing
		}
		return fadeIn(
			animationSpec = tween(durationMillis = motion.enterFadeMs, easing = easing)
		) +
			slideInHorizontally(
				animationSpec = tween(durationMillis = motion.enterSlideMs, easing = easing)
			) {
				val travel = motion.enterSlideDp?.let { dp -> with(density) { dp.dp.roundToPx() } }
					?: (it / 2)
				sign * travel
			}
	}

	private fun pageExit(motion: PageMotion, density: Density, sign: Int): ExitTransition =
		fadeOut(
			animationSpec = tween(
				durationMillis = motion.exitFadeMs,
				easing = EmphasizedAccelerateEasing
			)
		) +
			slideOutHorizontally(
				animationSpec = tween(
					durationMillis = motion.exitSlideMs,
					easing = EmphasizedAccelerateEasing
				)
			) {
				sign * with(density) { motion.exitSlideDp.dp.roundToPx() }
			}
}
