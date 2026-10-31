package paige.navic.util

/**
 * The one place text leaves the app's own hands with its credentials removed (the bug run's
 * review, finding 2): the login error's details (shown and copied) and the crash screen's text.
 *
 * Every Subsonic request this client makes carries its login in the query: `SubsonicAuth.Token`
 * puts `u`, `t` (the salted token) and `s` (the salt) on each URL, `SessionManager`'s own calls do
 * the same by hand, the API-key form uses `apiKey`, and the legacy form `p` (`enc:…` is only
 * hex). Ktor names the request URL in its timeout and "no transformation" exceptions, so a
 * stack trace of a failed login holds a `t` + `s` pair that logs in again as that user.
 *
 * What is removed: the value of any of those query parameters (and `token`, `jwt`,
 * `access_token`), and a `Bearer` header value (the hub's and AudioMuse's token). The names stay,
 * so the text still says which request failed.
 */
fun redactSecrets(text: String): String =
	text.replace(SECRET_QUERY_VALUE) { it.groupValues[1] + REDACTED }
		.replace(BEARER_VALUE) { it.groupValues[1] + REDACTED }

const val REDACTED = "<redacted>"

/** `?t=…` / `&s=…`: the value runs to the next `&`, a space, or a character that closes a quote. */
private val SECRET_QUERY_VALUE = Regex(
	"""([?&](?:u|p|t|s|token|apikey|jwt|access_token)=)[^&\s\]\)"'`#<>]+""",
	RegexOption.IGNORE_CASE
)

private val BEARER_VALUE = Regex("""(Bearer\s+)[A-Za-z0-9._~+/=-]+""", RegexOption.IGNORE_CASE)
