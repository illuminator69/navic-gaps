package paige.navic.ui.screens.search

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/*
 * Audit L2-05 (W-03, ran): Search's field asked for focus each time the page was composed again, so
 * Back from a result reopened the keyboard over the results. It takes the keyboard when Search arrives,
 * once per entry.
 */
class SearchFocusTest {

	@Test
	fun theFieldTakesTheKeyboardOncePerEntry() {
		assertTrue(shouldRequestSearchFocus(alreadyFocusedOnThisEntry = false))
		assertFalse(shouldRequestSearchFocus(alreadyFocusedOnThisEntry = true))
	}
}
