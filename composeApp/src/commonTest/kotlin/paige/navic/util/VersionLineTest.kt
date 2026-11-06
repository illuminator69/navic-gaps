package paige.navic.util

import paige.navic.ui.components.sheets.UPDATE_CHECK_URL
import kotlin.test.Test
import kotlin.test.assertEquals

/** Worbus P4 N2: one version everywhere, `0.4.0 (<commit>)`, and `-dev` on a build that is not a release. */
class VersionLineTest {
	@Test
	fun versionLine_formats() {
		assertEquals("0.4.0 (36a8ea5)", versionLine("0.4.0", "36a8ea5", release = true))
		assertEquals("0.4.0-dev (36a8ea5)", versionLine("0.4.0", "36a8ea5", release = false))
	}

	@Test
	fun versionLine_without_a_commit_says_unknown() {
		assertEquals("0.4.0 (unknown)", versionLine("0.4.0", "", release = true))
	}

	@Test
	fun updateCheck_points_at_fork() {
		assertEquals("https://api.github.com/repos/illuminator69/navic-gaps/releases/latest", UPDATE_CHECK_URL)
	}
}
