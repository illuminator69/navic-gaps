package paige.navic.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/*
 * The bug run's review, finding 2: the login error's details and the crash screen show (and copy)
 * a stack trace, and a failed request's exception names its URL, Subsonic login query included.
 * Worbus P4 (lane N review M2): both now go through the one rule, [redactLines] (`[removed]`); the
 * old `redactSecrets` (`<redacted>`) is retired. Ruling R15: no `jwt=` parameter is added.
 */
class ErrorTraceRedactionTest {
	@Test
	fun aKtorTimeoutLosesTheLoginAndKeepsTheRequest() {
		val message = "io.ktor.client.network.sockets.ConnectTimeoutException: Connect timeout has expired " +
			"[url=http://192.168.1.10:4533/rest/ping.view?u=sandbox&t=26719a1196d2a940705a59634eb18eab&s=c19b2d&v=1.16.1&c=Navic&f=json, " +
			"connect_timeout=15000 ms]"
		val shown = redactLines(message)
		assertEquals(
			"io.ktor.client.network.sockets.ConnectTimeoutException: Connect timeout has expired " +
				"[url=http://192.168.1.10:4533/rest/ping.view?u=[removed]&t=[removed]&s=[removed]&v=1.16.1&c=Navic&f=json, " +
				"connect_timeout=15000 ms]",
			shown
		)
	}

	@Test
	fun theLegacyPasswordTheApiKeyAndABearerGoToo() {
		val shown = redactLines(
			"GET http://nas:4533/rest/getUser.view?p=enc:68756e74657232&u=sandbox&c=Navic\n" +
				"GET https://music.example.com/rest/ping.view?apiKey=abc123DEF&f=json\n" +
				"`http://nas/rest/stream.view?id=1&t=aaaa&s=bbbb`\n" +
				"Authorization: Bearer hub-token-123"
		)
		for (secret in listOf("68756e74657232", "enc:", "abc123DEF", "aaaa", "bbbb", "hub-token-123", "sandbox")) {
			assertFalse(secret in shown, "$secret left in: $shown")
		}
		assertTrue("p=[removed]&u=[removed]&c=Navic" in shown, shown)
		assertTrue("?apiKey=[removed]&f=json" in shown, shown)
		assertTrue("?id=1&t=[removed]&s=[removed]" in shown, shown)
		assertTrue("Authorization: [removed]" in shown, shown)
		assertEquals(4, shown.lines().size, "line breaks kept: $shown")
	}

	@Test
	fun textWithoutALoginIsUnchanged() {
		val trace = "java.net.UnknownHostException: Unable to resolve host \"nsa\"\n\tat okhttp3.Dns\$Companion.lookup(Dns.kt:1)\n" +
			"http://nas:4533/rest/getAlbum.view?id=al-1&size=128 sort=asc"
		assertEquals(trace, redactLines(trace))
	}
}
