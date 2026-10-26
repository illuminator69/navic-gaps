package paige.navic.ui.components.layouts

import kotlin.test.Test
import kotlin.test.assertEquals

/*
 * Audit W-05 (ran): at 448 dp tall (a phone in landscape) the rail's fourth item, New, sat below the
 * fold with nothing to say the rail scrolls. Collapsed items are 56 dp on a window shorter than
 * 480 dp, 64 dp otherwise; expanded items stay 48 dp.
 */
class RailMetricsTest {

	@Test
	fun itemHeights() {
		assertEquals(56f, railItemMinHeight(447.9f, expanded = false))
		assertEquals(64f, railItemMinHeight(480f, expanded = false))
		assertEquals(64f, railItemMinHeight(900f, expanded = false))
		assertEquals(48f, railItemMinHeight(300f, expanded = true))
	}
}
