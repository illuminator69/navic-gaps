package paige.navic.domain.manager

import kotlinx.coroutines.runBlocking
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/*
 * Worbus P2, lane N1: the app's own transport (Ktor over OkHttp) against a socket on 127.0.0.1 that
 * answers as the real servers do, so R6 is checked on what the client actually reads, not on a
 * hand-built reply. The bodies are the hubs' own: the P1 hub's 405 `{"error": "method not allowed"}`
 * (hub.py at 7efa17f, a POST outside its proxies), the P2 hub's sign-in answer and refusals
 * (7732e88), and a reverse proxy's HTML.
 */
class HubAuthOverHttpTest {
	private val server = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
	private val requests = CopyOnWriteArrayList<String>()

	@AfterTest
	fun close() {
		server.close()
	}

	private fun answerWith(status: String, contentType: String, body: String) {
		thread(isDaemon = true) {
			while (!server.isClosed) {
				val socket = runCatching { server.accept() }.getOrNull() ?: break
				socket.use {
					val input = it.getInputStream()
					val reader = input.bufferedReader()
					val head = StringBuilder()
					var length = 0
					while (true) {
						val line = reader.readLine() ?: break
						if (line.isEmpty()) break
						head.append(line).append('\n')
						if (line.lowercase().startsWith("content-length:")) length = line.substringAfter(':').trim().toInt()
					}
					val buf = CharArray(length)
					var read = 0
					while (read < length) {
						val n = reader.read(buf, read, length - read)
						if (n < 0) break
						read += n
					}
					requests += head.toString() + String(buf, 0, read)
					val bytes = body.toByteArray()
					it.getOutputStream().write(
						("HTTP/1.1 $status\r\nContent-Type: $contentType\r\nContent-Length: ${bytes.size}\r\n" +
							"Connection: close\r\n\r\n").toByteArray() + bytes
					)
					it.getOutputStream().flush()
				}
			}
		}
	}

	private fun signInThroughKtor(): SignInResult {
		val settings = MemorySettings()
		val secrets = testSecrets(settings)
		val prefs = PreferenceManager(settings, secrets).apply {
			hubEnabled = true
			hubUrl = "ws://127.0.0.1:${server.localPort}/connect"
		}
		val auth = HubAuth(prefs, secrets, { NavidromeAccount("bob", "hunter2") }, { "phone" }, KtorHubAuthTransport(log = {}), log = {})
		return runBlocking { auth.signIn() }
	}

	@Test
	fun old_hub_405_shows_token_field() {
		answerWith("405 Method Not Allowed", "application/json", """{"error": "method not allowed"}""")
		assertEquals(SignInResult.OldServer, signInThroughKtor())
		assertTrue(requests.single().startsWith("POST /hub/signin "), requests.single())
		assertTrue("hunter2" !in requests.single(), "the password is not on the wire")
	}

	@Test
	fun old_hub_404_shows_token_field() {
		answerWith("404 Not Found", "text/html", "<html><body><h1>404</h1></body></html>")
		assertEquals(SignInResult.OldServer, signInThroughKtor())
	}

	@Test
	fun old_hub_non_json_shows_token_field() {
		answerWith("200 OK", "text/html", "<html><body>a proxy's page</body></html>")
		assertEquals(SignInResult.OldServer, signInThroughKtor())
	}

	@Test
	fun a_granted_signin_reads_over_http() {
		answerWith(
			"200 OK", "application/json",
			"""{"deviceId": "0123456789abcdef", "deviceSecret": "abc_DEF-123", "user": {"name": "bob", "admin": false, "primary": false}}"""
		)
		assertEquals(SignInResult.SignedIn(HubUser("bob", false, false)), signInThroughKtor())
	}

	@Test
	fun a_refusal_reads_over_http() {
		answerWith("401 Unauthorized", "application/json", """{"code": "navidrome_refused"}""")
		assertEquals(SignInResult.Refused, signInThroughKtor())
	}

	@Test
	fun a_closed_port_is_unreachable_not_old_server() {
		server.close() // nothing listens on the port any more: connection refused
		assertEquals(SignInResult.Unreachable(navidrome = false), signInThroughKtor())
	}
}
