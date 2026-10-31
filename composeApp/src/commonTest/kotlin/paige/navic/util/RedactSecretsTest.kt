package paige.navic.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/*
 * The bug run's review, finding 2: the login error's details and the crash screen show (and copy)
 * a stack trace, and a failed request's exception names its URL, Subsonic login query included.
 */
class RedactSecretsTest {
	@Test
	fun aKtorTimeoutLosesTheLoginAndKeepsTheRequest() {
		val message = "io.ktor.client.network.sockets.ConnectTimeoutException: Connect timeout has expired " +
			"[url=http://192.168.1.10:4533/rest/ping.view?u=sandbox&t=26719a1196d2a940705a59634eb18eab&s=c19b2d&v=1.16.1&c=Navic&f=json, " +
			"connect_timeout=15000 ms]"
		val shown = redactSecrets(message)
		assertEquals(
			"io.ktor.client.network.sockets.ConnectTimeoutException: Connect timeout has expired " +
				"[url=http://192.168.1.10:4533/rest/ping.view?u=<redacted>&t=<redacted>&s=<redacted>&v=1.16.1&c=Navic&f=json, " +
				"connect_timeout=15000 ms]",
			shown
		)
	}

	@Test
	fun theLegacyPasswordTheApiKeyAndABearerGoToo() {
		val shown = redactSecrets(
			"GET http://nas:4533/rest/getUser.view?p=enc:68756e74657232&u=sandbox&c=Navic\n" +
				"GET https://music.example.com/rest/ping.view?apiKey=abc123DEF&f=json\n" +
				"`http://nas/rest/stream.view?id=1&t=aaaa&s=bbbb`\n" +
				"Authorization: Bearer hub-token-123"
		)
		for (secret in listOf("68756e74657232", "enc:", "abc123DEF", "aaaa", "bbbb", "hub-token-123", "sandbox")) {
			assertFalse(secret in shown, "$secret left in: $shown")
		}
		assertTrue("p=<redacted>&u=<redacted>&c=Navic" in shown, shown)
		assertTrue("?apiKey=<redacted>&f=json" in shown, shown)
		assertTrue("?id=1&t=<redacted>&s=<redacted>`" in shown, shown)
		assertTrue("Bearer <redacted>" in shown, shown)
	}

	@Test
	fun textWithoutALoginIsUnchanged() {
		val trace = "java.net.UnknownHostException: Unable to resolve host \"nsa\"\n\tat okhttp3.Dns\$Companion.lookup(Dns.kt:1)\n" +
			"http://nas:4533/rest/getAlbum.view?id=al-1&size=128 sort=asc"
		assertEquals(trace, redactSecrets(trace))
	}
}
