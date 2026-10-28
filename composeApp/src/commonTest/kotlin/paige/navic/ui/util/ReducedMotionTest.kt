package paige.navic.ui.util

import kotlin.test.Test
import kotlin.test.assertEquals

/** M1 (audit L1-03): a wall-clock cross-fade has no length under reduced motion. */
class ReducedMotionTest {
	@Test
	fun aCrossfadeIsNoneUnderReducedMotion() {
		assertEquals(0, crossfadeMsFor(reduced = true, ms = COVER_FADE_MS))
		assertEquals(COVER_FADE_MS, crossfadeMsFor(reduced = false, ms = COVER_FADE_MS))
	}

	@Test
	fun aCoverFadesIn280() {
		assertEquals(280, COVER_FADE_MS)
	}
}
