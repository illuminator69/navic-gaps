package paige.navic.ui.screens.settings

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** Worbus settings N4: the "Count a play after" slider shows what is stored; only a move is snapped. */
class ScrobbleSliderShowsStoredValueTest {

	@Test
	fun theLabelAndThePositionReadTheStoredValueAndOnlyAMoveIsSnapped() {
		val source = File("src/commonMain/kotlin/paige/navic/ui/screens/settings/PlaybackScreen.kt").readText()
		val code = source.lines().joinToString("\n") { it.substringBefore("//") }
		assertTrue("scrobblePercentShown(" in code, "the label does not use the stored value")
		assertTrue(!Regex("snapToTenPercent\\(\\s*preferenceManager\\.scrobblePercentage").containsMatchIn(code), "the stored value is snapped before it is shown")
		assertTrue(Regex("scrobblePercentage\\s*=\\s*snapToTenPercent\\(it\\)").containsMatchIn(code), "a move of the slider is no longer snapped")
	}
}
