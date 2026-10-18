package paige.navic.ui.util

import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.PathEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.koin.compose.koinInject
import paige.navic.domain.manager.PreferenceManager

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

// navi-connect I-042: a sheet's opening and closing times follow the animation style
// (PageMotion.kt); the vals are the Expressive/Standard timing, unchanged.
fun sheetShowMotionSpec(motion: PageMotion) = tween<Float>(
	durationMillis = motion.sheetShowMs,
	easing = EmphasizedDecelerateEasing
)
fun sheetHideMotionSpec(motion: PageMotion) = tween<Float>(
	durationMillis = motion.sheetHideMs,
	easing = EmphasizedAccelerateEasing
)
val SheetShowMotionSpec = sheetShowMotionSpec(DefaultPageMotion)
val SheetHideMotionSpec = sheetHideMotionSpec(DefaultPageMotion)

/**
 * navi-connect I-042: a cover's shared-element flight between a list and its page, timed by the
 * animation style so it lands with the page (PageMotion.sharedBoundsMs).
 */
@Composable
fun rememberCoverBoundsTransform(): BoundsTransform {
	val durationMs = pageMotionOf(koinInject<PreferenceManager>().animationStyle).sharedBoundsMs
	return remember(durationMs) {
		BoundsTransform { _, _ ->
			tween(
				durationMillis = durationMs,
				easing = EmphasizedDecelerateEasing
			)
		}
	}
}

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
