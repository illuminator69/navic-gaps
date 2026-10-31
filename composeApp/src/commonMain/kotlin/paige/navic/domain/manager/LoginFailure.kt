package paige.navic.domain.manager

import dev.zt64.subsonic.api.model.SubsonicErrorCode
import dev.zt64.subsonic.api.model.SubsonicException

/**
 * Which of the login's failures happened (B-302). Each kind has one sentence on the login page;
 * they all used to read "Something went wrong.", so a wrong password and a typo in the address
 * looked the same.
 */
enum class LoginFailureKind {
	/** Subsonic error 40: the server answered, and refused this name and password. */
	WRONG_CREDENTIALS,

	/** Nothing answered: the name did not resolve, the connection was refused or timed out. */
	UNREACHABLE,

	/** An https handshake met a server that speaks plain http (Navidrome's default on 4533). */
	TLS_TO_PLAIN_HTTP,

	/** Something answered, and it was not a Subsonic answer (a web page, a 404, another service). */
	NOT_SUBSONIC,

	/** Anything else: the old "Something went wrong.", with the exception's class named. */
	OTHER
}

/**
 * A Subsonic error envelope that a hand-rolled call read (`status: failed`, B-101). The bundled
 * client throws [SubsonicException] for its own calls; this is the same answer for the calls
 * `SessionManager` parses itself.
 */
class SubsonicStatusException(val code: Int, message: String) : Exception(message)

private const val MAX_CAUSE_DEPTH = 16

/** The exception and its causes, outermost first, stopping at a cycle. */
internal fun causeChain(t: Throwable): List<Throwable> {
	val chain = mutableListOf<Throwable>()
	var current: Throwable? = t
	while (current != null && chain.size < MAX_CAUSE_DEPTH && chain.none { it === current }) {
		chain += current
		current = current.cause
	}
	return chain
}

private fun Throwable.simpleName(): String = this::class.simpleName ?: ""

/** The handshake answered by a plain-http server: BoringSSL's and the JDK's words for it. */
private val PLAIN_HTTP_HINTS = listOf("wrong_version_number", "unrecognized ssl message", "plaintext connection")

private val UNREACHABLE_NAMES = setOf(
	"UnknownHostException",
	"UnresolvedAddressException",
	"ConnectException",
	"NoRouteToHostException",
	"PortUnreachableException",
	"SocketTimeoutException",
	"ConnectTimeoutException",
	"HttpRequestTimeoutException"
)

/** What a body that is not Subsonic's JSON fails with, in Ktor and kotlinx.serialization. */
private val NOT_SUBSONIC_NAMES = setOf(
	"NoTransformationFoundException",
	"JsonConvertException",
	"ContentConvertException",
	"SerializationException",
	"JsonDecodingException",
	"MissingFieldException",
	"ClientRequestException"
)

/**
 * The kind of [t], read off its cause chain. Pure, and it names classes by their simple names,
 * so it compiles for iOS and reads the JVM's `java.net` / `javax.net.ssl` exceptions alike.
 *
 * Order matters: a refusal from the server is checked first (it is the most specific answer),
 * then the handshake, then "nothing answered", then "something else answered".
 */
fun loginFailureKind(t: Throwable): LoginFailureKind {
	val chain = causeChain(t)
	if (chain.any { it.isWrongCredentials() }) return LoginFailureKind.WRONG_CREDENTIALS
	if (chain.any { it.isPlainHttpHandshake() }) return LoginFailureKind.TLS_TO_PLAIN_HTTP
	if (chain.any { it.simpleName() in UNREACHABLE_NAMES }) return LoginFailureKind.UNREACHABLE
	if (chain.any { it.simpleName() in NOT_SUBSONIC_NAMES }) return LoginFailureKind.NOT_SUBSONIC
	return LoginFailureKind.OTHER
}

private fun Throwable.isWrongCredentials(): Boolean = when (this) {
	is SubsonicException -> code == SubsonicErrorCode.WRONG_USERNAME_OR_PASSWORD
	is SubsonicStatusException -> code == 40
	else -> false
}

private fun Throwable.isPlainHttpHandshake(): Boolean {
	if (!simpleName().startsWith("SSL")) return false
	val text = (message ?: "").lowercase()
	return PLAIN_HTTP_HINTS.any { it in text }
}

/**
 * The class to name under "Something went wrong." for [LoginFailureKind.OTHER]: the innermost
 * cause, which is the one that says what happened (the outer one is `SessionManager.login`'s own
 * generic wrapper).
 */
fun loginFailureClassName(t: Throwable): String =
	causeChain(t).lastOrNull()?.simpleName()?.ifBlank { null } ?: "Exception"
