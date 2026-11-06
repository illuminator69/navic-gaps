package paige.navic.util

/**
 * The crash screen's text and the log's (B-261, B-283, B-296).
 *
 * `android.util.Log.getStackTraceString` returns "" for any throwable whose cause chain holds an
 * `UnknownHostException` (a documented filter), and `Log.e(tag, msg, tr)` goes through the same
 * helper, so a login typo crashed to a blank screen with nothing in logcat. Kotlin's
 * `stackTraceToString()` prints through `printStackTrace` and filters nothing.
 *
 * The trace goes through the logs file's redaction rule ([redactLines], Worbus P4 N3): it is shown
 * on the crash screen, which offers Copy and Share, and a failed request's exception names its
 * URL, login query included.
 */
fun crashReportText(t: Throwable, appVersion: String? = null): String = buildString {
	append("Navic ").append(appVersion?.ifBlank { null } ?: "(version unknown)").append('\n')
	append(redactLines(t.stackTraceToString()))
}

/**
 * [text] cut into pieces logcat keeps whole (an entry is cut at about 4 KB), on line breaks where
 * a line allows it.
 */
fun crashLogChunks(text: String, maxChars: Int = 3000): List<String> {
	val chunks = mutableListOf<String>()
	val current = StringBuilder()
	for (line in text.lines()) {
		var rest = line
		while (rest.length > maxChars) {
			if (current.isNotEmpty()) {
				chunks += current.toString()
				current.clear()
			}
			chunks += rest.take(maxChars)
			rest = rest.drop(maxChars)
		}
		if (current.length + rest.length + 1 > maxChars && current.isNotEmpty()) {
			chunks += current.toString()
			current.clear()
		}
		if (current.isNotEmpty()) current.append('\n')
		current.append(rest)
	}
	if (current.isNotEmpty()) chunks += current.toString()
	return chunks
}
