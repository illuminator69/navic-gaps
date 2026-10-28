package paige.navic.ui.util

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M1 (audit L1-03): what animates on the wall clock reads reduced motion. Every file under
 * `commonMain` outside `ui/util/` that calls `withFrameNanos`, `.crossfade(` or builds an
 * `infiniteRepeatable` mentions `rememberReducedMotion` or `crossfadeMsFor`, or is listed here with
 * the reason it needs neither. A new one fails until someone decides which it is.
 */
class ReducedMotionConsumersTest {

	private val srcRoot = File("src/commonMain/kotlin/paige/navic")

	private val reviewed = mapOf(
		// `withFrameNanos { }` waits one frame before claiming the gate; nothing moves.
		"ui/screens/home/HomeShelf.kt" to "one-frame wait",
		"ui/screens/search/SearchScreen.kt" to "one-frame wait",
		// An infiniteRepeatable through the animation clock stops at scale 0 by MotionDurationScale.
		"ui/components/common/Waveform.kt" to "kit clock",
		// The image loader's default for a request that sets none; every cover goes through CoverArt,
		// RemoteCoverArt, BlendBackground or the mini player, which set their own.
		"di/SingletonImageLoaderInit.kt" to "default only"
	)

	private fun offenders(): Set<String> = srcRoot.walkTopDown()
		.filter { it.isFile && it.extension == "kt" }
		.map { it.relativeTo(srcRoot).invariantSeparatorsPath to it.readText() }
		.filter { (path, _) -> !path.startsWith("ui/util/") }
		.filter { (_, text) -> "withFrameNanos" in text || ".crossfade(" in text || "infiniteRepeatable(" in text }
		.filter { (_, text) -> "rememberReducedMotion" !in text && "crossfadeMsFor" !in text }
		.map { it.first }
		.toSet()

	@Test
	fun everyWallClockAnimationReadsReducedMotionOrIsReviewed() {
		assertTrue(srcRoot.isDirectory, "${srcRoot.absolutePath} not found (host tests run from composeApp/)")
		assertEquals(reviewed.keys, offenders())
	}

	@Test
	fun aCoverFadesInTheKitsTimeByDefault() {
		for (path in listOf("ui/components/common/CoverArt.kt", "ui/components/common/RemoteCoverArt.kt")) {
			val text = File(srcRoot, path).readText()
			assertTrue(Regex("""crossfadeMs: Int = COVER_FADE_MS""").containsMatchIn(text), "$path: crossfadeMs default")
		}
	}
}
