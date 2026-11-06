package paige.navic.domain.manager

import dev.zt64.subsonic.client.SubsonicAuth
import dev.zt64.subsonic.client.SubsonicClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import kotlinx.coroutines.runBlocking
import paige.navic.util.redactLines
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/*
 * The login's failures as the real client meets them (the bug run's review, findings 2 and 6): the
 * bundled Subsonic client over OkHttp, against a socket on 127.0.0.1 that answers as a reverse
 * proxy does while Navidrome behind it is down.
 */
class LoginFailureOverHttpTest {
	private val server = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
	private val requestLines = CopyOnWriteArrayList<String>()

	@AfterTest
	fun close() {
		server.close()
	}

	/** Answer every request on [server] with [status] and an HTML page, the way nginx does. */
	private fun answerWith(status: String) {
		thread(isDaemon = true) {
			while (!server.isClosed) {
				val socket = runCatching { server.accept() }.getOrNull() ?: break
				socket.use {
					val reader = it.getInputStream().bufferedReader()
					val first = reader.readLine() ?: return@use
					requestLines += first
					while (true) {
						val line = reader.readLine() ?: break
						if (line.isEmpty()) break
					}
					val body = "<html><body><h1>$status</h1></body></html>"
					it.getOutputStream().write(
						("HTTP/1.1 $status\r\nContent-Type: text/html\r\nContent-Length: ${body.length}\r\n" +
							"Connection: close\r\n\r\n$body").toByteArray()
					)
					it.getOutputStream().flush()
				}
			}
		}
	}

	private val base get() = "http://127.0.0.1:${server.localPort}"

	private fun pingFailure(): Throwable {
		val client = SubsonicClient(
			baseUrl = base,
			auth = SubsonicAuth.Token(username = "sandbox", password = "hunter2"),
			client = "Navic",
			engine = OkHttp.create(),
			clientConfig = {
				install(HttpTimeout) {
					connectTimeoutMillis = 5_000
					requestTimeoutMillis = 10_000
					socketTimeoutMillis = 5_000
				}
			}
		)
		val thrown = runCatching { runBlocking { client.ping() } }.exceptionOrNull()
		checkNotNull(thrown) { "the ping should fail against a $base that answers HTML" }
		// As SessionManager.login wraps it.
		return Exception("Failed to connect to the instance. Please check your credentials and try again.", thrown)
	}

	@Test
	fun aProxysBadGatewayIsUnreachableNotAnotherKindOfServer() {
		answerWith("502 Bad Gateway")
		val e = pingFailure()
		assertEquals(LoginFailureKind.UNREACHABLE, loginFailureKind(e), e.stackTraceToString())
	}

	@Test
	fun aProxysServiceUnavailableIsUnreachable() {
		answerWith("503 Service Unavailable")
		val e = pingFailure()
		assertEquals(LoginFailureKind.UNREACHABLE, loginFailureKind(e), e.stackTraceToString())
	}

	@Test
	fun anHtmlPageThatIsNotAnErrorIsStillNotSubsonic() {
		answerWith("200 OK")
		val e = pingFailure()
		assertEquals(LoginFailureKind.NOT_SUBSONIC, loginFailureKind(e), e.stackTraceToString())
	}

	@Test
	fun aStatusThrownAsAStatusIsReadToo() {
		// Ktor's own ServerResponseException, from a client that has expectSuccess on.
		answerWith("504 Gateway Timeout")
		val thrown = runCatching {
			runBlocking { HttpClient(OkHttp) { expectSuccess = true }.use { it.get("$base/rest/ping.view") } }
		}.exceptionOrNull()
		checkNotNull(thrown)
		assertEquals("ServerResponseException", thrown::class.simpleName)
		assertEquals(LoginFailureKind.UNREACHABLE, loginFailureKind(Exception("wrap", thrown)))
	}

	@Test
	fun theFailuresTextNamesTheLoginAndTheRedactedTextDoesNot() {
		answerWith("502 Bad Gateway")
		val trace = pingFailure().stackTraceToString()
		// The request the client really sent carries the salted token and its salt.
		val query = requestLines.first().substringAfter('?').substringBefore(' ')
		val params = query.split('&').associate { it.substringBefore('=') to it.substringAfter('=') }
		val token = params.getValue("t")
		val salt = params.getValue("s")
		assertTrue(token.length >= 16 && salt.isNotEmpty(), query)
		// Ktor names that URL in the exception, so the details box showed it.
		assertTrue(token in trace, "the unredacted trace was expected to carry t: $trace")

		val shown = redactLines(trace)
		assertFalse(token in shown, shown)
		assertFalse("s=$salt" in shown, shown)
		assertFalse("u=sandbox" in shown, shown)
		assertTrue("t=[removed]" in shown, shown)
		assertTrue("ping.view" in shown, "the request is still named: $shown")
	}
}
