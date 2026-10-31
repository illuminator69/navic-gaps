package paige.navic.domain.manager

import dev.zt64.subsonic.api.model.SubsonicErrorCode
import dev.zt64.subsonic.api.model.SubsonicException
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.serialization.JsonConvertException
import kotlinx.serialization.SerializationException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.channels.UnresolvedAddressException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLProtocolException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/*
 * B-302: the login's failures, told apart on the real JVM exceptions the session's client throws,
 * each wrapped the way SessionManager.login wraps them.
 */
class LoginFailureKindTest {
	private fun wrapped(cause: Throwable) =
		Exception("Failed to connect to the instance. Please check your credentials and try again.", cause)

	@Test
	fun wrongCredentials() {
		val refused = SubsonicException("Wrong username or password", SubsonicErrorCode.WRONG_USERNAME_OR_PASSWORD)
		assertEquals(LoginFailureKind.WRONG_CREDENTIALS, loginFailureKind(wrapped(refused)))
		assertEquals(LoginFailureKind.WRONG_CREDENTIALS, loginFailureKind(SubsonicStatusException(40, "Wrong username or password")))
		// Another Subsonic refusal is not "wrong password".
		val other = SubsonicException("Incompatible", SubsonicErrorCode.INCOMPATIBLE_SERVER_VERSION)
		assertEquals(LoginFailureKind.OTHER, loginFailureKind(wrapped(other)))
	}

	@Test
	fun unreachable() {
		assertEquals(LoginFailureKind.UNREACHABLE, loginFailureKind(wrapped(UnknownHostException("music.exmaple.com"))))
		assertEquals(LoginFailureKind.UNREACHABLE, loginFailureKind(wrapped(ConnectException("Failed to connect to /192.168.1.10:4533"))))
		assertEquals(
			LoginFailureKind.UNREACHABLE,
			loginFailureKind(wrapped(SocketTimeoutException("failed to connect to /10.0.2.2 (port 4533) from /10.0.2.16 (port 41234) after 10000ms")))
		)
		assertEquals(LoginFailureKind.UNREACHABLE, loginFailureKind(wrapped(ConnectTimeoutException("Connect timeout has expired"))))
		// The server went away mid-talk (the review's finding 6).
		assertEquals(LoginFailureKind.UNREACHABLE, loginFailureKind(wrapped(SocketException("Connection reset"))))
		assertEquals(LoginFailureKind.UNREACHABLE, loginFailureKind(wrapped(SocketException("socket failed: EPERM (Operation not permitted)"))))
	}

	/*
	 * The review's finding 1: a name that never resolved is a wrong address, and saying "Android is
	 * blocking Navic from your local network" with Open settings would send the person to a page that
	 * cannot help. A dial to a private host that was refused or timed out still says it.
	 */
	@Test
	fun aNameThatNeverResolvedIsNotPutDownToTheLocalNetworkPermission() {
		val typo = wrapped(UnknownHostException("Unable to resolve host \"nsa\": No address associated with hostname"))
		assertEquals(LoginFailureKind.UNREACHABLE, loginFailureKind(typo))
		assertFalse(failureLooksLocalNetworkBlocked(typo, sdk = 37, granted = false, host = "nsa"))
		assertFalse(failureLooksLocalNetworkBlocked(wrapped(UnresolvedAddressException()), sdk = 37, granted = false, host = "towr"))
		assertFalse(failureLooksLocalNetworkBlocked(RuntimeException("ws", typo), sdk = 37, granted = false, host = "192.168.1.10"))

		val refused = wrapped(ConnectException("Failed to connect to /192.168.1.10:4533"))
		assertTrue(failureLooksLocalNetworkBlocked(refused, sdk = 37, granted = false, host = "192.168.1.10"))
		val timedOut = wrapped(ConnectTimeoutException("Connect timeout has expired"))
		assertTrue(failureLooksLocalNetworkBlocked(timedOut, sdk = 37, granted = false, host = "nas"))
		// The rest of the rule is localNetworkBlocked's (PrivateHostTest).
		assertFalse(failureLooksLocalNetworkBlocked(refused, sdk = 37, granted = true, host = "192.168.1.10"))
		assertFalse(failureLooksLocalNetworkBlocked(refused, sdk = 36, granted = false, host = "192.168.1.10"))
	}

	@Test
	fun tlsToAPlainHttpServer() {
		// BoringSSL (Android) and the JDK, answered by Navidrome's plain http on 4533.
		val android = SSLProtocolException(
			"Read error: ssl=0x7b3a: Failure in SSL library, usually a protocol error\n" +
				"error:100000f7:SSL routines:OPENSSL_internal:WRONG_VERSION_NUMBER"
		)
		assertEquals(LoginFailureKind.TLS_TO_PLAIN_HTTP, loginFailureKind(wrapped(android)))
		assertEquals(LoginFailureKind.TLS_TO_PLAIN_HTTP, loginFailureKind(wrapped(SSLException("Unsupported or unrecognized SSL message"))))
		// Conscrypt on Android 17, seen on the AVD (API 37) against Navidrome's plain http (the N9 walk).
		assertEquals(LoginFailureKind.TLS_TO_PLAIN_HTTP, loginFailureKind(wrapped(SSLException("Unable to parse TLS packet header"))))
		// A certificate the device does not trust is not "this server doesn't use https".
		val cert = SSLHandshakeException("java.security.cert.CertPathValidatorException: Trust anchor for certification path not found.")
		assertEquals(LoginFailureKind.OTHER, loginFailureKind(wrapped(cert)))
	}

	@Test
	fun notASubsonicAnswer() {
		assertEquals(LoginFailureKind.NOT_SUBSONIC, loginFailureKind(wrapped(SerializationException("Unexpected JSON token at offset 0"))))
		assertEquals(LoginFailureKind.NOT_SUBSONIC, loginFailureKind(wrapped(JsonConvertException("Illegal input: <html>"))))
	}

	@Test
	fun anythingElseIsOtherAndNamesTheInnermostClass() {
		val e = wrapped(IllegalStateException("something unforeseen"))
		assertEquals(LoginFailureKind.OTHER, loginFailureKind(e))
		assertEquals("IllegalStateException", loginFailureClassName(e))
		assertEquals("Exception", loginFailureClassName(Exception("bare")))
	}
}
