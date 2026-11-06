package paige.navic.util

/**
 * Worbus P4 N3 (design §6.8): the ONE redaction rule for everything a tester sends — the Logs
 * screen's Share logs file and its copy, and the crash screen's report. Applied per line; each
 * secret's value becomes [LOG_REDACTION_MARK], its name stays so the line still says what it was.
 *
 * The rule and its vectors are shared with Feishin (the controller's `RULE.md` and
 * `redaction-vectors.json`, ruling R2; `LogRedactionTest` holds them verbatim). In order:
 *  a. a quoted JSON field whose name contains password, secret, token, apikey, key or
 *     authorization (any case): its string value (which may hold `\"`);
 *  b. the same names unquoted, `name=value` or `name: value`, not preceded by a name character or
 *     `"`: a quoted value, or an optional `Bearer `/`Basic ` plus one run up to space, `&`, `,`, `;`
 *     or a quote;
 *  c. a bare `bearer <credential>`, any case;
 *  d. the Subsonic login query parameters `u`, `t`, `s` and `p` (lower case) after `?` or `&`;
 *  e. the userinfo of a URL, `scheme://user:pass@`.
 *
 * Known limit: a credential split across two lines (`Authorization:` at the end of one, the token
 * on the next) is not caught by a per-line rule.
 */
fun redact(line: String): String {
	var s = QUOTED_FIELD.replace(line) { m ->
		"\"${m.groupValues[1]}\"${m.groupValues[2]}\"$LOG_REDACTION_MARK\""
	}
	s = UNQUOTED_FIELD.replace(s) { m ->
		val name = m.groupValues[1]
		val sep = m.groupValues[2]
		val quoted = m.value.substring(name.length + sep.length).startsWith("\"")
		name + sep + if (quoted) "\"$LOG_REDACTION_MARK\"" else LOG_REDACTION_MARK
	}
	s = BARE_BEARER.replace(s) { m -> m.groupValues[1] + " " + LOG_REDACTION_MARK }
	s = SUBSONIC_PARAM.replace(s) { m -> m.groupValues[1] + m.groupValues[2] + "=" + LOG_REDACTION_MARK }
	s = URL_USERINFO.replace(s) { m -> m.groupValues[1] + LOG_REDACTION_MARK + "@" }
	return s
}

/** The mark a removed value is replaced with, in every app of the stack. */
const val LOG_REDACTION_MARK = "[removed]"

private const val SENSITIVE = "(?:password|secret|token|apikey|key|authorization)"
private const val FIELD_NAME = "[A-Za-z0-9_.\\-]*$SENSITIVE[A-Za-z0-9_.\\-]*"

private val QUOTED_FIELD = Regex(
	"\"($FIELD_NAME)\"(\\s*:\\s*)\"(?:[^\"\\\\]|\\\\.)*\"",
	RegexOption.IGNORE_CASE
)

private val UNQUOTED_FIELD = Regex(
	"(?<![A-Za-z0-9_.\\-\"])($FIELD_NAME)(\\s*[:=]\\s*)" +
		"(?:\"(?:[^\"\\\\]|\\\\.)*\"|(?:(?:bearer|basic)\\s+)?[^\\s&,;\"']+)",
	RegexOption.IGNORE_CASE
)

private val BARE_BEARER = Regex("\\b(bearer)\\s+[A-Za-z0-9._~+/=\\-]+", RegexOption.IGNORE_CASE)

private val SUBSONIC_PARAM = Regex("([?&])([utsp])=([^&\\s\"'\\\\#<>]+)")

private val URL_USERINFO = Regex("(\\b[A-Za-z][A-Za-z0-9+.\\-]*://)[^/\\s:@\"]+:[^/\\s@\"]+@")

/** What heads a logs file: this app's version line, the phone, and the server's version when known. */
data class LogsHeader(
	/** `0.4.0 (abc1234)` ([versionLine]). */
	val appVersion: String,
	/** e.g. `Android 17 (API 37)`. */
	val androidVersion: String,
	/** e.g. `Google Pixel 9`. */
	val deviceModel: String,
	/** `welcome.server.version` from the Worbus Server, or null when this session has not heard it. */
	val serverVersion: String?
)

/** The text of `navic-logs-<stamp>.txt`: the header, a blank line, then [lines], each redacted. */
fun logsFileText(header: LogsHeader, lines: List<String>): String = buildString {
	append("Navic ").append(header.appVersion).append('\n')
	append(header.androidVersion).append('\n')
	append("Device: ").append(header.deviceModel).append('\n')
	append(header.serverVersion?.let { "Worbus Server $it" } ?: "Worbus Server: server version unknown").append('\n')
	append('\n')
	for (line in lines) append(redact(line)).append('\n')
}

/** [text] with every line redacted ([redact]); line breaks kept. */
fun redactLines(text: String): String = text.lines().joinToString("\n") { redact(it) }

/** `Google Pixel 9` from Android's manufacturer and model, without saying the maker twice. */
fun deviceModelName(manufacturer: String, model: String): String {
	val maker = manufacturer.trim().replaceFirstChar { it.uppercase() }
	val name = model.trim()
	return when {
		name.isEmpty() -> maker.ifEmpty { "unknown device" }
		maker.isEmpty() || name.startsWith(maker, ignoreCase = true) -> name
		else -> "$maker $name"
	}
}

/** `navic-logs-20261106-153012.txt`, from the local date and time the file was made. */
fun logsFileName(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int): String {
	fun two(n: Int) = n.toString().padStart(2, '0')
	return "navic-logs-$year${two(month)}${two(day)}-${two(hour)}${two(minute)}${two(second)}.txt"
}
