package paige.navic.ui.screens.wishlist

import navic.composeapp.generated.resources.Res
import navic.composeapp.generated.resources.attention_missing_off
import navic.composeapp.generated.resources.info_external_album_index_failed_off
import navic.composeapp.generated.resources.request_downloads_off
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

	/** The lane N review's OFF strings: Needs attention's pill and line, and the missing album's index note. */
	@Test
	fun review_off_copy_has_no_searching_words() {
		for (res in listOf(Res.string.attention_missing_off, Res.string.request_downloads_off, Res.string.info_external_album_index_failed_off)) {
			val text = strings[res.key]
			assertTrue(text != null, "${res.key} is not in values/strings.xml")
			val lower = text.lowercase()
			banned.forEach { word -> assertTrue(word !in lower, "${res.key} says \"$word\": $text") }
		}
	}
}
