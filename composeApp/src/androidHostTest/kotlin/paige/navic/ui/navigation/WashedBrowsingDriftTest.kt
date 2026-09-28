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

	/** App.kt's `entryProvider`, one block of text per `entry<Screen.X>`, keyed on `X`. */
	private fun entryBlocks(): Map<String, String> {
		val app = source("App.kt")
		val start = app.indexOf("private fun entryProvider(")
		val end = app.indexOf("\n}\n", start)
		assertTrue(start >= 0 && end > start, "entryProvider not found in App.kt")
		val body = app.substring(start, end)
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
		val wrappedInApp = entryBlocks().filterValues { "Washed {" in it }.keys
		assertTrue(
			selfWashing.values.none { it in wrappedInApp },
			"a self-washing screen is also wrapped in App.kt, i.e. washed twice"
		)
		assertEquals(wrappedInApp + selfWashing.values, WashedBrowsingScreens.names())
	}

	@Test
	fun onlyTheKnownScreensWashThemselves() {
		val callers = srcRoot.walkTopDown()
			.filter { it.isFile && it.extension == "kt" }
			.filter { file ->
				file.readLines().any { raw ->
					val line = raw.trim()
					!line.startsWith("//") && !line.startsWith("*") && !line.startsWith("/*") &&
						!line.contains("fun BrowsingAmbient") &&
						(line.contains("BrowsingAmbient {") || line.contains("BrowsingAmbient("))
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
		val sheets = entryBlocks().filterValues { sheetScene.containsMatchIn(it) }.keys
		assertEquals(sheets, PageOverlayScreens.names())
	}
}
