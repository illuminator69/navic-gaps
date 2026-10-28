package paige.navic.ui.screens.home

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * M3 (audit L1-02): a lazy item's placement animation is read from the item's ROOT. Since P7 each
 * Home shelf's `animateItem()` sat inside `Fold`'s `AnimatedVisibility`, where it does nothing, so a
 * shelf appearing late made the shelves below it jump. Every `item(key = …) { Fold(` on Home passes
 * `animateItem` in Fold's own arguments, and nothing inside a Fold calls it again.
 */
class HomeAnimateItemPlacementTest {

	private val text = File("src/commonMain/kotlin/paige/navic/ui/screens/home/HomeScreen.kt").readText()

	/** The text between the bracket at [open] and its match. */
	private fun span(open: Int): String {
		val (o, c) = text[open] to if (text[open] == '(') ')' else '}'
		var depth = 1
		var i = open + 1
		while (depth > 0) {
			if (text[i] == o) depth++ else if (text[i] == c) depth--
			i++
		}
		return text.substring(open + 1, i - 1)
	}

	@Test
	fun everyShelfFoldAnimatesItsPlacementFromTheRoot() {
		val folds = Regex("""item\(key = "([^"]+)"\) \{\s*Fold\(""").findAll(text).toList()
		assertTrue(folds.size >= 6, "found ${folds.size} shelf folds")
		for (m in folds) {
			val argsOpen = m.range.last
			val args = span(argsOpen)
			val bodyOpen = text.indexOf('{', argsOpen + args.length + 1)
			val body = span(bodyOpen)
			val shelf = m.groupValues[1]
			assertTrue("animateItem(" in args, "$shelf: Fold does not take animateItem in its arguments")
			assertTrue("animateItem(" !in body, "$shelf: animateItem inside Fold is inert")
		}
	}
}
