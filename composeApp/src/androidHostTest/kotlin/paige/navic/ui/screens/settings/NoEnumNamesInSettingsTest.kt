package paige.navic.ui.screens.settings

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
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

	/** The source with line comments dropped, so a comment cannot satisfy or break a check. */
	private fun code(name: String) = File(dir + name).readLines().joinToString("\n") { it.substringBefore("//") }

	@Test
	fun theThemePickersAreNeverGreyedByArtworkColours() {
		val themes = code("ThemesScreen.kt")
		val appearance = code("AppearanceScreen.kt")
		assertTrue("overriddenByCover" !in themes, "ThemesScreen.kt still greys the pickers")
		assertTrue("DisabledAlpha" !in themes, "ThemesScreen.kt still dims the pickers")
		assertTrue("themePickers(" in themes && "themePickers(" in appearance, "both pages must ask themePickers")
		// Whatever enables a row there must be the picker state (always on), never the artwork switch.
		for ((name, text) in listOf("ThemesScreen.kt" to themes, "AppearanceScreen.kt" to appearance)) {
			for (line in text.lines()) {
				if (Regex("\\benabled\\s*=").containsMatchIn(line)) {
					assertTrue("pickers.enabled" in line || Regex("enabled\\s*=\\s*true\\b").containsMatchIn(line), "$name enables a row by something other than the picker state: ${line.trim()}")
				}
				assertTrue(!Regex("enabled\\s*=.*dynamicTheming").containsMatchIn(line), "$name ties a row to Colours from artwork: ${line.trim()}")
			}
		}
		val enabledRows = { text: String -> Regex("enabled\\s*=\\s*pickers\\.enabled").findAll(text).count() }
		assertEquals(2, enabledRows(appearance), "Theme mode and Theme must both take pickers.enabled")
		assertEquals(1, enabledRows(themes), "the palette cards must take pickers.enabled")
	}

	@Test
	fun theArtworkNoteSitsWithThePickersOnBothPages() {
		assertTrue("ThemeArtworkNote(pickers)" in code("AppearanceScreen.kt"), "Appearance does not say where artwork wins")
		assertTrue("ThemeArtworkNote(pickers)" in code("ThemesScreen.kt"), "Themes does not say where artwork wins")
	}
}
