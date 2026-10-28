package paige.navic.ui.components.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Waiting rows are grey on every page ([rowPlaceholderColor], the owner's call of 2026-10-28). */
class RowPlaceholdersTest {

	@Test
	fun aWaitingRowIsGreyOnADarkAndALightPage() {
		for (dark in listOf(true, false)) {
			val c = rowPlaceholderColor(dark)
			assertEquals(c.red, c.green)
			assertEquals(c.green, c.blue)
			assertTrue(c.alpha in 0.01f..0.2f, "a faint overlay, not a slab: ${c.alpha}")
		}
	}

	@Test
	fun itLightensADarkPageAndDarkensALightOne() {
		assertEquals(1f, rowPlaceholderColor(darkPage = true).red)
		assertEquals(0f, rowPlaceholderColor(darkPage = false).red)
	}
}
