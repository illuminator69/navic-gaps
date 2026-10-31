package paige.navic.util

import java.net.UnknownHostException
import kotlin.test.Test
import kotlin.test.assertTrue

/*
 * B-296, B-261, B-283: the crash screen showed nothing, because android.util.Log's stack trace
 * helper prints "" for any chain that holds an UnknownHostException.
 */
class CrashReportTest {
	@Test
	fun anUnknownHostChainStillGivesTheWholeTrace() {
		val crash = IllegalStateException("login", UnknownHostException("music.exmaple.com"))
		val text = crashReportText(crash, appVersion = "1.0.0-alpha59-fork")
		assertTrue("IllegalStateException" in text, text)
		assertTrue("UnknownHostException" in text, text)
		assertTrue("music.exmaple.com" in text, text)
		assertTrue(text.startsWith("Navic 1.0.0-alpha59-fork"), text)
		assertTrue(crashReportText(crash).startsWith("Navic (version unknown)"))
	}

	@Test
	fun theLogPiecesAreShortEnoughAndLoseNothing() {
		var deep: Throwable = UnknownHostException("h")
		repeat(40) { deep = RuntimeException("level $it", deep) }
		val text = crashReportText(deep, "1")
		val chunks = crashLogChunks(text, maxChars = 3000)
		assertTrue(chunks.size > 1, "a 40-deep trace should take several entries")
		assertTrue(chunks.all { it.length <= 3000 })
		assertTrue(chunks.joinToString("\n") == text)
	}
}
