package paige.navic.ui.components.common

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/*
 * I-043: `BrowsingAmbient` keeps ONE composition structure whether or not the cover's colours have
 * resolved. It used to return early into a bare `NavicTheme` while unresolved and wrap the content
 * in a `Box` once resolved, so the page under it sat in a different place in the composition and
 * was thrown away and built again when its colours landed (measured: twice per cold start), losing
 * its scroll position and everything it remembered. One `NavicTheme(` call in the function is the
 * shape that cannot do that. Source text, as in WashedBrowsingDriftTest: run from composeApp/.
 */
class BrowsingAmbientStructureTest {
	private val file = File("src/commonMain/kotlin/paige/navic/ui/components/common/CoverAmbientBackground.kt")

	private fun browsingAmbientBody(): String {
		assertTrue(file.isFile, "${file.absolutePath} not found (host tests run from composeApp/)")
		val text = file.readText()
		val start = text.indexOf("fun BrowsingAmbient(")
		assertTrue(start >= 0, "BrowsingAmbient not found")
		val end = text.indexOf("\n}\n", start)
		assertTrue(end > start, "end of BrowsingAmbient not found")
		return text.substring(start, end)
	}

	@Test
	fun thePageKeepsOnePlaceWhetherOrNotColoursHaveLanded() {
		val body = browsingAmbientBody()
		assertEquals(1, Regex("""\bNavicTheme\(""").findAll(body).count(), "NavicTheme( calls in BrowsingAmbient")
		assertTrue(!Regex("""\breturn\b""").containsMatchIn(body), "an early return in BrowsingAmbient")
	}
}
