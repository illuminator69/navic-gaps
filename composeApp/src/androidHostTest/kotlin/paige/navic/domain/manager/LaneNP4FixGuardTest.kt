package paige.navic.domain.manager

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/*
 * Worbus P4, lane N's review fixes: the wiring no host test can run (HubManager is not constructible
 * here, and there is no Compose UI test harness), held at the source. The behaviour is tested as pure
 * functions: KnownServerVersion in LbPageLinkTest, logLineShown and redactLines in LogRedactionTest
 * and ErrorTraceRedactionTest.
 */
class LaneNP4FixGuardTest {
	private val sources: List<File> = listOf("src/commonMain", "src/androidMain")
		.map(::File)
		.flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }

	private fun text(name: String) = sources.single { it.name == name }.readText()

	/** A member function's text, from its signature to the closing brace at [indent]. */
	private fun member(file: String, signature: String, indent: String = "\t"): String {
		val all = text(file)
		val start = all.indexOf(signature)
		assertTrue(start >= 0, "$file: $signature")
		val end = all.indexOf("\n$indent}\n", start)
		assertTrue(end > start, "$file: end of $signature")
		return all.substring(start, end)
	}

	/** I1 / ruling R16: the version is forgotten when the socket closes, on stop and on restart. */
	@Test
	fun server_version_forgotten_on_close_stop_and_restart() {
		assertTrue("knownServerVersion.forget()" in member("HubManager.kt", "\tfun stop() {"), "stop()")
		assertTrue("knownServerVersion.forget()" in member("HubManager.kt", "\tfun restart() {"), "restart()")
		val loop = member("HubManager.kt", "\tprivate suspend fun runLoop() {")
		val close = loop.indexOf("backoff.onLinkEnded(")
		assertTrue(close >= 0, "the run loop's socket-close")
		assertTrue("knownServerVersion.forget()" in loop.substring(close, close + 400), "forgotten beside the socket-close")
		assertTrue("_serverVersion" !in text("HubManager.kt"), "one holder, no second state")
	}

	/** I2: the Logs screen's rows draw the redacted text, inside its SelectionContainer. */
	@Test
	fun logs_screen_draws_redacted_lines() {
		val screen = text("LogsScreen.kt")
		val row = screen.substring(screen.indexOf("private fun LogLineRow("))
		assertTrue("logLineShown(line)" in row, "the row draws logLineShown")
		assertTrue("text = line.text" !in row, "no raw line drawn")
		assertTrue("internal fun logLineShown(line: LogLine): String = redact(line.text)" in screen)
		val list = screen.substring(screen.indexOf("SelectionContainer {"))
		assertTrue(list.indexOf("LogLineRow(line = line)") in 0..200, "the selectable list draws LogLineRow")
	}

	/** M2: one redaction rule in the app; the old `<redacted>` rule is gone. */
	@Test
	fun one_redaction_rule() {
		assertTrue("redactLines(error.stackTraceToString())" in text("ErrorCodeBlock.kt"))
		val old = sources.filter { "redactSecrets(" in it.readText() || "\"<redacted>\"" in it.readText() }.map { it.name }
		assertEquals(emptyList(), old)
	}

	/** M3: with no link the imports row is the non-clickable item: no onClick, no button. */
	@Test
	fun linkless_imports_row_is_not_clickable() {
		val item = member("AttentionScreen.kt", "private fun ImportsRowItem(", indent = "")
		val noLink = item.substring(item.indexOf("if (link == null) {"), item.indexOf("\t\treturn\n"))
		assertTrue("SegmentedListItem(" in noLink, noLink)
		assertTrue("onClick" !in noLink, "no click without a link: $noLink")
		assertTrue("TextButton" !in noLink, "no button without a link: $noLink")
		assertTrue("attention_imports_home_only" in noLink, "its line says where to fix them")
	}
}
