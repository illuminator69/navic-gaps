package paige.navic.ui.screens.settings

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Worbus settings N5 (R14): "Colours from artwork" is on by default, and used to grey out Theme mode
 * and the theme picker, so a tester could not choose dark mode out of the box. They stay enabled.
 */
class ThemePickersEnabledWithArtworkColoursTest {

	@Test
	fun theyAreEnabledWhetherOrNotArtworkColoursAreOn() {
		assertTrue(themePickers(dynamicTheming = true).enabled)
		assertTrue(themePickers(dynamicTheming = false).enabled)
	}

	@Test
	fun theNoteIsDrawnOnlyWhileArtworkColoursAreOn() {
		assertTrue(themePickers(dynamicTheming = true).noteShown)
		assertFalse(themePickers(dynamicTheming = false).noteShown)
	}
}
