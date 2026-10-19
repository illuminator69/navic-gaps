package paige.navic.ui.util

import paige.navic.domain.models.settings.AnimationStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Quick animation style (I-042): the old two styles keep the timing Navic always had, and
 * Quick is shorter everywhere.
 */
class PageMotionTest {

	@Test
	fun expressiveAndStandardKeepTodaysNumbers() {
		val today = PageMotion(
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
		assertEquals(today, pageMotionOf(AnimationStyle.Expressive))
		assertEquals(today, pageMotionOf(AnimationStyle.Standard))
	}

	@Test
	fun everyQuickDurationIsShortAndShorterThanBefore() {
		val old = pageMotionOf(AnimationStyle.Expressive)
		val quick = pageMotionOf(AnimationStyle.Quick)
		val pairs = listOf(
			"enterFade" to (quick.enterFadeMs to old.enterFadeMs),
			"enterSlide" to (quick.enterSlideMs to old.enterSlideMs),
			"exitFade" to (quick.exitFadeMs to old.exitFadeMs),
			"exitSlide" to (quick.exitSlideMs to old.exitSlideMs),
			"sharedBounds" to (quick.sharedBoundsMs to old.sharedBoundsMs),
			"sheetShow" to (quick.sheetShowMs to old.sheetShowMs),
			"sheetHide" to (quick.sheetHideMs to old.sheetHideMs)
		)
		for ((name, durations) in pairs) {
			val (q, o) = durations
			assertTrue(q <= 350, "$name: $q ms > 350")
			assertTrue(q < o, "$name: $q ms is not below $o")
		}
		// A fixed, short travel instead of half the screen.
		assertTrue((quick.enterSlideDp ?: Int.MAX_VALUE) <= 48)
	}

	@Test
	fun aFoldFollowsTheStyle() {
		// Expressive and Standard have no number of their own: the fold takes the motion scheme's springs
		// (the scheme `NavicTheme` builds for the style), so the two differ as the rest of the app does.
		for (style in listOf(AnimationStyle.Expressive, AnimationStyle.Standard)) {
			assertNull(foldMs(pageMotionOf(style), tall = false), "$style")
			assertNull(foldMs(pageMotionOf(style), tall = true), "$style")
		}
		// Quick: short, a box that holds a list longer than a few lines, and both under the 350 the rest
		// of Quick keeps to.
		val quick = pageMotionOf(AnimationStyle.Quick)
		val short = foldMs(quick, tall = false)!!
		val tall = foldMs(quick, tall = true)!!
		assertTrue(short in 1..tall, "$short .. $tall")
		assertTrue(tall <= 350, "$tall ms > 350")
	}

	@Test
	fun theEnumIsAppendOnlyBecauseItIsStoredByOrdinal() {
		assertEquals(
			listOf(AnimationStyle.Expressive, AnimationStyle.Standard, AnimationStyle.Quick),
			AnimationStyle.entries.toList()
		)
		assertEquals(0, AnimationStyle.Expressive.ordinal)
		assertEquals(1, AnimationStyle.Standard.ordinal)
		assertEquals(2, AnimationStyle.Quick.ordinal)
	}
}
