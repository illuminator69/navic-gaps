package paige.navic.ui.screens.settings

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Worbus settings N6: nothing in the Danger zone deletes before it is confirmed. A tap on a row that
 * deletes something returns the action to ask about and runs nothing; only a cache runs at once.
 * The screen's source is held to the same rule, since the tap handlers are Compose.
 */
class DangerZoneConfirmsTest {

	@Test
	fun aDeletingActionRunsNothingUntilItIsConfirmed() {
		for (action in listOf(DangerAction.ClearPendingActions, DangerAction.ClearDownloads, DangerAction.RebuildDatabase)) {
			val ran = mutableListOf<DangerAction>()
			assertEquals(action, requestDanger(action) { ran += it }, "$action should ask")
			assertEquals(emptyList(), ran, "$action ran without being confirmed")
		}
	}

	@Test
	fun clearingTheImageCacheRunsAtOnce() {
		val ran = mutableListOf<DangerAction>()
		assertNull(requestDanger(DangerAction.ClearImageCache) { ran += it })
		assertEquals(listOf(DangerAction.ClearImageCache), ran)
	}

	@Test
	fun theScreenNeverBindsADeletionStraightToATap() {
		val source = File("src/commonMain/kotlin/paige/navic/ui/screens/settings/DataStorageScreen.kt").readText()
		for (call in listOf("clearAllDownloads", "removeAllActions", "rebuildDatabase")) {
			assertTrue(!Regex("onClick\\s*=\\s*(viewModel::|\\{\\s*viewModel\\.)$call\\b").containsMatchIn(source), "$call is bound to a tap")
			assertTrue(Regex("viewModel\\.$call\\(").containsMatchIn(source), "$call is never called, so nothing confirms it")
		}
		assertTrue("requestDanger(" in source, "the Danger zone does not go through requestDanger")
	}
}
