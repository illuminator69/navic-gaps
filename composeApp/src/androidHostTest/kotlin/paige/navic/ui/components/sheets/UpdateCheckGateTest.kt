package paige.navic.ui.components.sheets

import kotlinx.coroutines.runBlocking
import paige.navic.di.PlatformType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Worbus settings N1: a release build never asks GitHub for a newer version (the only release on
 * offer would be upstream's, with this app's applicationId and another key); a debug build still can.
 */
class UpdateCheckGateTest {

	private val newer = GitHubRelease(tag = "v1.0.0-alpha60", url = "https://example.test/r", body = "")

	@Test
	fun aReleaseBuildNeverOffersTheCheck() {
		assertFalse(updateChecksOffered(PlatformType.Android, fdroid = false, debugBuild = false))
		assertFalse(updateChecksOffered(PlatformType.Android, fdroid = true, debugBuild = false))
	}

	@Test
	fun aDebugBuildOffersItExceptOnFdroid() {
		assertTrue(updateChecksOffered(PlatformType.Android, fdroid = false, debugBuild = true))
		assertFalse(updateChecksOffered(PlatformType.Android, fdroid = true, debugBuild = true))
	}

	@Test
	fun aCheckThatIsNotOfferedNeverCallsOut() = runBlocking {
		var calls = 0
		val checker = UpdateChecker(offered = false) { calls++; newer }
		assertNull(checker.check("v1.0.0-alpha59"))
		assertEquals(0, calls)
	}

	@Test
	fun aCheckThatIsOfferedCallsOutOnceAndReturnsTheNewerRelease() = runBlocking {
		var calls = 0
		val checker = UpdateChecker(offered = true) { calls++; newer }
		assertEquals(newer, checker.check("v1.0.0-alpha59"))
		assertEquals(1, calls)
	}

	@Test
	fun aReleaseNoNewerThanThisBuildIsNotOffered() = runBlocking {
		val checker = UpdateChecker(offered = true) { newer }
		assertNull(checker.check("v1.0.0-alpha60"))
	}
}
