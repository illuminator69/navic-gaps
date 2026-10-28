package paige.navic.ui.a11y

import kotlin.test.Test
import kotlin.test.assertEquals

/** M7 (audit L1-12): the sizes the audit measured, each taken up to 48 dp. */
class TouchTargetTest {
	@Test
	fun aSmallTargetGrowsTo48() {
		assertEquals(48f to 48f, touchTarget(30f, 32f)) // a rating star
		assertEquals(48f to 48f, touchTarget(44f, 44f)) // the Tune step's "+"
		assertEquals(320f to 48f, touchTarget(320f, 44f)) // an avatar menu row in a fold
	}

	@Test
	fun aLargeEnoughTargetIsLeftAlone() {
		assertEquals(120f to 56f, touchTarget(120f, 56f))
	}
}
