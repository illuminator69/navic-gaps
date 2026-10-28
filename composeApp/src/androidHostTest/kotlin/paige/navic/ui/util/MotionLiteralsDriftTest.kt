package paige.navic.ui.util

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * M6 (audit L1-10): the kit's durations and curves are written once, in `ui/util/`. Outside it, no
 * `tween(300`, `tween(450`, `tween(280`, `tween(240` (predictive pop, the wash, a cover's fade, an
 * arrival) and no bare `fadeIn() togetherWith fadeOut()` (MediumLow springs, about three times the
 * kit's effects spring). An intentional one is listed here with its reason.
 */
class MotionLiteralsDriftTest {

	private val srcRoot = File("src/commonMain/kotlin/paige/navic")
	private val literal = Regex("""tween\((300|450|280|240)\b|fadeIn\(\) togetherWith fadeOut\(\)""")

	private val reviewed = emptyMap<String, String>()

	@Test
	fun kitNumbersAreWrittenOnlyInUiUtil() {
		val found = srcRoot.walkTopDown()
			.filter { it.isFile && it.extension == "kt" }
			.map { it.relativeTo(srcRoot).invariantSeparatorsPath to it.readText() }
			.filter { (path, text) -> !path.startsWith("ui/util/") && literal.containsMatchIn(text) }
			.map { it.first }
			.toSet()
		assertEquals(reviewed.keys, found)
	}
}
