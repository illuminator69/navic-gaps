package paige.navic.ui.navigation

import java.io.File
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/*
 * B-008: the rail's predicate must not drift from the entry provider.
 *
 * `isWashedBrowsing` and App.kt's `Washed { }` call sites are two statements of one fact, and
 * nothing in the compiler ties them together: a new tab wrapped in `Washed` and left out of the
 * predicate gives a base rail beside a washed page, which is the gap B-008 closed. The call sites
 * are composable content, so the only place to observe them without Compose is the source text.
 * Hence JVM-only (androidHostTest, java.io): it reads the tree and has no meaning on a device.
 * Gradle runs host tests from the module directory, composeApp/.
 *
 * The overlay list is checked the same way, against the entries whose metadata asks for a sheet
 * scene: those are the ones that sit on top of a page without replacing it.
 */
class WashedBrowsingDriftTest {
	private val srcRoot = File("src/commonMain/kotlin/paige/navic")

	/**
	 * Screens that call `BrowsingAmbient` themselves instead of being wrapped by App.kt, by the file
	 * that does it. A new one fails [onlyTheKnownScreensWashThemselves] until it is added here and
	 * to [WashedBrowsingScreens].
	 */
	private val selfWashing = mapOf("ui/screens/library/LibraryScreen.kt" to "Library")

	private fun source(path: String): String {
		val file = File(srcRoot, path)
		assertTrue(file.isFile, "${file.absolutePath} not found (host tests run from composeApp/)")
		return file.readText()
	}

	/** App.kt's `entryProvider` function, from its signature to its closing brace. */
	private fun entryProviderBody(): String {
		val app = source("App.kt")
		val start = app.indexOf("private fun entryProvider(")
		val end = app.indexOf("\n}\n", start)
		assertTrue(start >= 0 && end > start, "entryProvider not found in App.kt")
		return app.substring(start, end)
	}

	/** App.kt's `entryProvider`, one block of text per `entry<Screen.X>`, keyed on `X`. */
	private fun entryBlocks(): Map<String, String> {
		val body = entryProviderBody()
		val heads = Regex("""entry<Screen\.([A-Za-z.]+)>""").findAll(body).toList()
		assertTrue(heads.size > 20, "only ${heads.size} entries parsed out of App.kt")
		return heads.mapIndexed { i, head ->
			val to = heads.getOrNull(i + 1)?.range?.first ?: body.length
			head.groupValues[1] to body.substring(head.range.first, to)
		}.toMap()
	}

	private fun Set<KClass<out Any>>.names() =
		map { it.qualifiedName!!.substringAfter("navigation.Screen.") }.toSet()

	@Test
	fun thePredicateIsExactlyTheWashedEntriesPlusTheSelfWashingScreens() {
		// `Washed {`, `Washed{` and `Washed(content = …)` alike.
		val washedCall = Regex("""\bWashed\s*[({]""")
		val wrappedInApp = entryBlocks().filterValues { washedCall.containsMatchIn(it) }.keys
		assertTrue(
			selfWashing.values.none { it in wrappedInApp },
			"a self-washing screen is also wrapped in App.kt, i.e. washed twice"
		)
		assertEquals(wrappedInApp + selfWashing.values, WashedBrowsingScreens.names())
	}

	@Test
	fun onlyTheKnownScreensWashThemselves() {
		val ambientCall = Regex("""\bBrowsingAmbient\s*[({]""")
		val callers = srcRoot.walkTopDown()
			.filter { it.isFile && it.extension == "kt" }
			.filter { file ->
				file.readLines().any { raw ->
					val line = raw.trim()
					!line.startsWith("//") && !line.startsWith("*") && !line.startsWith("/*") &&
						!line.contains("fun BrowsingAmbient") &&
						ambientCall.containsMatchIn(line)
				}
			}
			.map { it.relativeTo(srcRoot).invariantSeparatorsPath }
			.toSet()
		// App.kt's own call is the `Washed` helper, which the test above covers.
		assertEquals(selfWashing.keys + "App.kt", callers)
	}

	@Test
	fun theOverlayListIsExactlyTheSheetSceneEntries() {
		val sheetScene = Regex("""SceneStrategy\.(bottomSheet|dialog)\(""")
		// Metadata can be inline (`metadata = NowPlayingSceneStrategy.bottomSheet(…)`) or a name,
		// as `navtabMetadata` and `imageViewMetadata` are: a `val` in entryProvider's preamble. A
		// name is looked up there, and one declared anywhere else fails loudly rather than being
		// read as "not a sheet".
		val preamble = entryProviderBody().substringBefore("entryProvider {")
		val valHeads = Regex("""\n\tval (\w+)\s*=""").findAll(preamble).toList()
		val localVals = valHeads.mapIndexed { i, head ->
			val to = valHeads.getOrNull(i + 1)?.range?.first ?: preamble.length
			head.groupValues[1] to preamble.substring(head.range.last + 1, to)
		}.toMap()
		val namedMetadata = Regex("""metadata\s*=\s*([A-Za-z_]\w*)\s*\)""")
		val sheets = entryBlocks().filterValues { block ->
			sheetScene.containsMatchIn(block) || namedMetadata.find(block)?.groupValues?.get(1)?.let { name ->
				val initialiser = localVals[name]
				assertTrue(initialiser != null, "metadata `$name` is not a val of entryProvider; teach this test where it lives")
				sheetScene.containsMatchIn(initialiser)
			} == true
		}.keys
		assertTrue("navtabMetadata" in localVals && "imageViewMetadata" in localVals, "preamble vals not parsed")
		assertEquals(sheets, PageOverlayScreens.names())
	}
}
