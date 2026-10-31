package paige.navic.ui.screens

import paige.navic.ui.screens.settings.aboutLinks
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Worbus settings N1: About sends a tester to this fork, never to upstream's repository, Codeberg
 * mirror or Discord. Reads the screen's own source too, so a link typed straight into it is caught.
 */
class AboutLinksTest {
	private val banned = listOf("ssalggnikool", "codeberg", "discord")

	@Test
	fun noAboutLinkPointsUpstream() {
		assertTrue(aboutLinks.isNotEmpty())
		for (url in aboutLinks) {
			for (word in banned) {
				assertTrue(word !in url.lowercase(), "$url names $word")
			}
		}
	}

	@Test
	fun theScreenSourceHoldsNoLinkOutsideTheList() {
		val source = File("src/commonMain/kotlin/paige/navic/ui/screens/settings/AboutScreen.kt").readText()
		val typed = Regex("\"(https?://[^\"]+)\"").findAll(source).map { it.groupValues[1] }.toList()
		for (url in typed) {
			assertTrue(url in aboutLinks, "AboutScreen.kt types $url, which is not in aboutLinks")
			for (word in banned) {
				assertTrue(word !in url.lowercase(), "$url names $word")
			}
		}
	}
}
