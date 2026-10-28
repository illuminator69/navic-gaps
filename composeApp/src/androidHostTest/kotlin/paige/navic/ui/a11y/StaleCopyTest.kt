package paige.navic.ui.a11y

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** M8 (audit L4b-03): no string names a screen that has moved. */
class StaleCopyTest {
	private val strings = File("src/commonMain/composeResources/values/strings.xml").readText()

	@Test
	fun noStringNamesAScreenThatMoved() {
		for (stale in listOf("on the library's home page", "also shows in Downloads", "also on the library's home page")) {
			assertTrue(stale !in strings, "strings.xml still says \"$stale\"")
		}
	}
}
