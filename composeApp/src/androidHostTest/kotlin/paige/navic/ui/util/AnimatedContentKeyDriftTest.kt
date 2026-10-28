package paige.navic.ui.util

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * M2 (audit L1-01): an `AnimatedContent` whose target is a sealed state with a payload replays its
 * transition whenever the state is replaced by another of the same kind: the artist page, backdrop
 * included, cross-dissolved three times as its data landed. Such a target takes
 * `contentKey = { it::class }`. Every `AnimatedContent(` without a `contentKey` is listed here with
 * the reviewed reason it needs none; a new one fails until someone reviews it.
 */
class AnimatedContentKeyDriftTest {

	private val srcRoot = File("src/commonMain/kotlin/paige/navic")

	/** File to the number of keyless `AnimatedContent`s in it, each reviewed. */
	private val reviewed = mapOf(
		// An Int (the tab, 0 when the queue is empty) and the shown tab: its own key.
		"ui/screens/queue/QueueScreen.kt" to 2,
		// Booleans (a scroll trigger, the loading flag): their own key.
		"ui/screens/mixes/MixListScreen.kt" to 1,
		"ui/screens/playlist/PlaylistListScreen.kt" to 1,
		"ui/screens/radio/RadioListScreen.kt" to 1,
		"ui/screens/nowPlaying/components/rows/ButtonsRow.kt" to 1,
		// An Int step, an enum chip, an enum section, an Int icon state: their own key.
		"ui/screens/radio/custom/CustomRadioScreen.kt" to 1,
		"ui/screens/library/LibraryScreen.kt" to 1,
		"ui/screens/sourcehistory/SourceHistoryScreen.kt" to 1,
		"ui/components/sheets/AddToPlaylistSheet.kt" to 1,
		// A new value is new content and the cross-fade is the point: another song's lyrics, the
		// generator line's words changing, another error message.
		"ui/screens/lyrics/LyricsScreen.kt" to 1,
		"ui/screens/queue/components/QueueGeneratorLine.kt" to 1,
		"ui/screens/login/pages/Error.kt" to 1
	)

	/** The argument list of each `AnimatedContent(` call in [text], by matching parentheses. */
	private fun calls(text: String): List<String> = Regex("""\bAnimatedContent\(""").findAll(text).map { m ->
		var depth = 1
		var i = m.range.last + 1
		while (depth > 0 && i < text.length) {
			when (text[i]) {
				'(' -> depth++
				')' -> depth--
			}
			i++
		}
		text.substring(m.range.last + 1, i - 1)
	}.toList()

	@Test
	fun everyKeylessAnimatedContentIsReviewed() {
		val keyless = srcRoot.walkTopDown()
			.filter { it.isFile && it.extension == "kt" }
			.associate { it.relativeTo(srcRoot).invariantSeparatorsPath to calls(it.readText()).count { args -> "contentKey" !in args } }
			.filterValues { it > 0 }
		assertEquals(reviewed, keyless)
	}
}
