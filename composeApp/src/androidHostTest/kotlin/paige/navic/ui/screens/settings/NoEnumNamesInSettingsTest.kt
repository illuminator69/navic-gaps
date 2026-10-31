package paige.navic.ui.screens.settings

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Worbus settings N5: no settings row prints an enum's Kotlin name. The rows that did are named in
 * the audit; this reads them, and the other pages' dialogs, for `.name` (SettingsLabelsTest holds
 * the labels that replaced them to one resource each).
 */
class NoEnumNamesInSettingsTest {
	private val dir = "src/commonMain/kotlin/paige/navic/ui/screens/settings/"

	@Test
	fun noScreenOrDialogPrintsAnEnumName() {
		val files = listOf(
			"AppearanceScreen.kt", "AppIconScreen.kt", "dialogs/ArtworkShapeDialog.kt", "NowPlayingScreen.kt",
			"BottomBarScreen.kt", "ThemesScreen.kt", "PlaybackScreen.kt", "AudioEffectsScreen.kt", "QualityDataScreen.kt"
		)
		for (name in files) {
			val lines = File(dir + name).readLines()
			lines.forEachIndexed { index, line ->
				val code = line.substringBefore("//")
				assertTrue(!Regex("\\.name\\b").containsMatchIn(code), "$name:${index + 1} prints a Kotlin name: ${line.trim()}")
			}
		}
	}

	@Test
	fun theThemePickersAreNeverGreyedByArtworkColours() {
		val themes = File(dir + "ThemesScreen.kt").readText()
		val appearance = File(dir + "AppearanceScreen.kt").readText()
		assertTrue("overriddenByCover" !in themes, "ThemesScreen.kt still greys the pickers")
		assertTrue("DisabledAlpha" !in themes, "ThemesScreen.kt still dims the pickers")
		assertTrue("themePickers(" in themes && "themePickers(" in appearance, "both pages must ask themePickers")
	}
}
