package paige.navic.ui.util

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamicColorScheme
import com.materialkolor.dynamiccolor.ColorSpec
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The scheme's `primary` (the Play button) a cover's accent gives on its page, through the same
 * `dynamicColorScheme` call `rememberCoverColorScheme` makes ([forPage]). Host-only because materialkolor
 * is exercised whole here, not because it reads files.
 */
class AccentPrimaryTest {

	private fun primaryOf(accent: Int, isDark: Boolean): Color = dynamicColorScheme(
		seedColor = Color(accent).boostedAccent().forPage(isDark),
		isDark = isDark,
		style = PaletteStyle.Content,
		neutral = Color(0xFF111111),
		neutralVariant = Color(0xFF111111),
		specVersion = ColorSpec.SpecVersion.SPEC_2021
	).primary

	private fun Color.lab() = convert(ColorSpaces.CieLab)

	@Test
	fun aPaleAccentOnADarkPageKeepsItsColour() {
		// The Bends: the cream vivid swatch on its black page. Uncapped, primary was #fffdff.
		val primary = primaryOf(0xFFF0E8B0.toInt(), isDark = true).lab()
		assertTrue(hypot(primary.green, primary.blue) >= 30f, "primary chroma ${hypot(primary.green, primary.blue)}")
		assertTrue(primary.red in 75f..85f, "primary L* ${primary.red}")
	}

	@Test
	fun aDarkerAccentIsUnchanged() {
		// The Red Shoes' beige and Loss of Life's orange were already below the cap.
		for (accent in listOf(0xFFC0B080.toInt(), 0xFFF0A040.toInt())) {
			val seed = Color(accent).boostedAccent()
			assertEquals(seed, seed.forPage(isDark = true))
		}
	}

	@Test
	fun aLightPagesAccentIsNotTouched() {
		val seed = Color(0xFFF0E8B0.toInt()).boostedAccent()
		assertEquals(seed, seed.forPage(isDark = false))
	}
}
