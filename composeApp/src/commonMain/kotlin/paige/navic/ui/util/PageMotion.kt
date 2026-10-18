package paige.navic.ui.util

import paige.navic.domain.models.settings.AnimationStyle

/**
 * navi-connect I-042: the timing of page pushes and pops, the cover's shared-element flight and
 * bottom sheets, per [AnimationStyle]. Pure values and no Compose imports, so a host test can lock
 * them; [Material3Transitions], the three cover `BoundsTransform` sites and `ModalBottomSheet`
 * build their specs from it.
 *
 * Expressive and Standard are the numbers these sites used before Quick existed — they must not
 * move. Quick's live in [QuickPageMotion]: retuning it is a one-number edit there.
 */
data class PageMotion(
	val enterFadeMs: Int,
	val enterSlideMs: Int,
	/** How far the incoming page travels; null is half the screen width. */
	val enterSlideDp: Int?,
	val enterEasing: PageEasing,
	val exitFadeMs: Int,
	val exitSlideMs: Int,
	val exitSlideDp: Int,
	val sharedBoundsMs: Int,
	val sheetShowMs: Int,
	val sheetHideMs: Int
)

/** Which of [Material3Transitions]' easings the incoming page uses. */
enum class PageEasing { Emphasized, EmphasizedDecelerate }

/** Expressive and Standard: what Navic has always done (450/500 ms from half the width). */
val DefaultPageMotion = PageMotion(
	enterFadeMs = 450,
	enterSlideMs = 500,
	enterSlideDp = null,
	enterEasing = PageEasing.Emphasized,
	exitFadeMs = 250,
	exitSlideMs = 300,
	exitSlideDp = 30,
	sharedBoundsMs = 500,
	sheetShowMs = 400,
	sheetHideMs = 600
)

/** Quick: a starting proposal (lb-bot's web UI enters in 220 ms over 18 px), tuned on the phone. */
val QuickPageMotion = PageMotion(
	enterFadeMs = 220,
	enterSlideMs = 250,
	enterSlideDp = 30,
	enterEasing = PageEasing.EmphasizedDecelerate,
	exitFadeMs = 100,
	exitSlideMs = 200,
	exitSlideDp = 30,
	sharedBoundsMs = 350,
	sheetShowMs = 300,
	sheetHideMs = 250
)

fun pageMotionOf(style: AnimationStyle): PageMotion = when (style) {
	AnimationStyle.Expressive, AnimationStyle.Standard -> DefaultPageMotion
	AnimationStyle.Quick -> QuickPageMotion
}
