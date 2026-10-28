package paige.navic.ui.screens.external.components

import kotlin.test.Test
import kotlin.test.assertEquals

/** B7 (audit L4b-06): a long discography draws its first rows per group, and Show all the rest. */
class ReleaseRowsLimitTest {

	@Test
	fun aLongGroupDrawsItsFirst40() {
		assertEquals(40, visibleReleases(500, expanded = false))
		assertEquals(RELEASE_ROWS_FIRST, visibleReleases(41, expanded = false))
	}

	@Test
	fun aShortGroupDrawsAll() {
		assertEquals(30, visibleReleases(30, expanded = false))
		assertEquals(0, visibleReleases(0, expanded = false))
	}

	@Test
	fun showAllDrawsEvery() {
		assertEquals(500, visibleReleases(500, expanded = true))
	}
}
