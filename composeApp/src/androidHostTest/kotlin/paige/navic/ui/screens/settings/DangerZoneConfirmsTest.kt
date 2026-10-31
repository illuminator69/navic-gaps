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

	@Test
	fun everyDangerActionTheScreenNamesGoesThroughRequestDanger() {
		val source = File("src/commonMain/kotlin/paige/navic/ui/screens/settings/DataStorageScreen.kt").readText()
		val code = source.lines().map { it.substringBefore("//") }
		var named = 0
		code.forEachIndexed { index, line ->
			if (!Regex("DangerAction\\.\\w+").containsMatchIn(line)) return@forEachIndexed
			named++
			// A `when` branch (what runDanger does, which dialog text to show) is not a call; every other
			// mention must be inside requestDanger(...), the only way an action reaches a tap.
			val isBranch = Regex("^\\s*DangerAction\\.\\w+\\s*->").containsMatchIn(line)
			assertTrue(isBranch || "requestDanger(" in line, "line ${index + 1} names a DangerAction outside requestDanger: ${line.trim()}")
		}
		assertTrue(named >= 4, "found only $named DangerAction mentions")
		for ((index, line) in code.withIndex()) {
			for (call in Regex("runDanger\\(([^)]*)\\)").findAll(line)) {
				val argument = call.groupValues[1].trim()
				assertTrue(argument == "action" || argument.startsWith("action:"), "line ${index + 1} runs $argument without asking: ${line.trim()}")
			}
		}
	}
}
