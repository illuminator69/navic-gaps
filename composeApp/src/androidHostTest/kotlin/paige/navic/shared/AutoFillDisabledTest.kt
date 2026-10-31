package paige.navic.shared

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Worbus settings N4: Auto-fill queue is gone. Its row is removed, `checkAndAutoFillQueue` no longer
 * exists, and nothing reads the stored `autoFillQueue` any more: a Play now with the old value true
 * adds nothing. The player cannot be built on the JVM (six app-level dependencies), so this holds
 * the source to it; it fails if a read of the preference, or the random top-up, comes back.
 */
class AutoFillDisabledTest {
	private val roots = listOf("src/commonMain", "src/androidMain", "src/iosMain").map(::File)

	private fun sources() = roots.flatMap { root -> root.walkTopDown().filter { it.extension == "kt" }.toList() }

	@Test
	fun nothingReadsTheStoredAutoFillPreference() {
		val readers = sources()
			.filter { it.name != "PreferenceManager.kt" }
			.filter { Regex("\\bautoFillQueue\\b").containsMatchIn(it.readText()) }
			.map { it.name }
		assertEquals(emptyList(), readers, "these files still read autoFillQueue")
	}

	@Test
	fun thePlayerNoLongerTopsTheQueueUpWithARandomSong() {
		val player = File("src/commonMain/kotlin/paige/navic/shared/MediaPlayer.kt").readText()
		assertTrue("checkAndAutoFillQueue" !in player, "MediaPlayer.kt still has checkAndAutoFillQueue")
		assertTrue("getRandomSongs(" !in player, "MediaPlayer.kt still asks for random songs")
	}
}
