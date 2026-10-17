package paige.navic.ui.screens.mixes

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/*
 * B-150: the new mix form says what Feishin's says, word for word. The words are string
 * resources, which a host test cannot resolve, so this reads them from the source tree
 * (`values/strings.xml`) by the keys `MixFormKind` names. Gradle runs host tests from the module
 * directory, composeApp/.
 */
class MixFormWordsTest {
	private val strings: Map<String, String> by lazy {
		val file = File("src/commonMain/composeResources/values/strings.xml")
		assertTrue(file.isFile, "${file.absolutePath} not found (host tests run from composeApp/)")
		Regex("""<string name="([^"]+)">([^<]*)</string>""")
			.findAll(file.readText())
			.associate { it.groupValues[1] to it.groupValues[2] }
	}

	private fun words(key: String): String = strings[key] ?: error("no string $key")

	@Test
	fun eachChoiceIsNamedAndDescribedInTheSharedWords() {
		assertEquals(
			listOf(
				"Radio" to "Around a song, steered by your skips and likes",
				"Your mix" to "Built from what you play most",
				"Genre" to "A random run through one genre",
				"Artist" to "More like one artist"
			),
			MixFormKind.entries.map { words(it.label.key) to words(it.line.key) }
		)
	}
}
