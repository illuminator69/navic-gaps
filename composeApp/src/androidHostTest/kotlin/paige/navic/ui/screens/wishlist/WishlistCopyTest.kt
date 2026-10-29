package paige.navic.ui.screens.wishlist

import paige.navic.ui.screens.downloads.WISHLIST_OFF_STRINGS
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/*
 * Worbus P1 (N3): while lb-bot cannot download, nothing the wishlist says may talk about downloading,
 * sources, Soulseek, peers or how often it looked. The strings it says then are listed in code
 * (`WISHLIST_OFF_STRINGS`) and their English text is read from the resource file, so a reworded string
 * is checked too. JVM-only (it reads the tree), as `WashedBrowsingDriftTest` is.
 */
class WishlistCopyTest {
	private val strings: Map<String, String> = Regex("""<string name="([^"]+)">(.*?)</string>""")
		.findAll(File("src/commonMain/composeResources/values/strings.xml").readText())
		.associate { it.groupValues[1] to it.groupValues[2] }

	private val banned = listOf("download", "source", "soulseek", "peer", "keeps looking", "keep looking", "looked")

	@Test
	fun wishlist_copy_off_has_no_searching_words() {
		assertTrue(WISHLIST_OFF_STRINGS.size >= 3, "the OFF list is only ${WISHLIST_OFF_STRINGS.size} long")
		for (res in WISHLIST_OFF_STRINGS) {
			val text = strings[res.key]
			assertTrue(text != null, "${res.key} is not in values/strings.xml")
			val lower = text.lowercase()
			banned.forEach { word -> assertTrue(word !in lower, "${res.key} says \"$word\": $text") }
		}
	}
}
