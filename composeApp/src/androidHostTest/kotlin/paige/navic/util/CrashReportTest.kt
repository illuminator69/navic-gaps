package paige.navic.util

import java.net.UnknownHostException
import kotlin.test.Test
import kotlin.test.assertFalse
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
	fun crashReport_names_commit() {
		val text = crashReportText(IllegalStateException("boom"), versionLine("0.4.0", "36a8ea5", release = true))
		assertTrue(text.startsWith("Navic 0.4.0 (36a8ea5)\n"), text)
	}

	@Test
	fun theCrashTextCarriesNoLogin() {
		// Ktor 3's timeout names the request URL, and every Subsonic URL carries u, t and s.
		val timeout = RuntimeException(
			"Request timeout has expired [url=http://10.0.2.2:4533/rest/getAlbumList2.view?u=sandbox&t=8a1f0c2e9b7d4a3f5e6c7b8a9d0e1f2a&s=k3j9x2&v=1.16.1&c=Navic&f=json, request_timeout=120000 ms]"
		)
		val text = crashReportText(IllegalStateException("sync", timeout), "1")
		assertFalse("8a1f0c2e9b7d4a3f5e6c7b8a9d0e1f2a" in text, text)
		assertFalse("k3j9x2" in text, text)
		assertFalse("u=sandbox" in text, text)
		assertTrue("getAlbumList2.view?u=<redacted>&t=<redacted>&s=<redacted>&v=1.16.1&c=Navic&f=json" in text, text)
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
