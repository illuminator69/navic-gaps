package paige.navic.domain.manager

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/*
 * B-296, B-261, B-283, B-302: every shape of address a person types reaches a result, never an
 * exception, and an address with no scheme gets the one its host calls for.
 */
class NormalizeInstanceUrlTest {
	private fun ok(raw: String): String = assertIs<InstanceUrlResult.Ok>(normalizeInstanceUrl(raw), raw).url
	private fun invalid(raw: String): InstanceUrlProblem =
		assertIs<InstanceUrlResult.Invalid>(normalizeInstanceUrl(raw), raw).reason

	@Test
	fun nothingTypedIsBlank() {
		assertEquals(InstanceUrlProblem.BLANK, invalid(""))
		assertEquals(InstanceUrlProblem.BLANK, invalid("   "))
	}

	@Test
	fun aPortRunIntoOtherTypingIsNotAnAddress() {
		// B-296's field: the other fields' typing landed after the port.
		assertEquals(InstanceUrlProblem.MALFORMED, invalid("http://127.0.0.1:4534sandbox"))
		assertEquals(InstanceUrlProblem.MALFORMED, invalid("http://127.0.0.1:4534sandboxpassword"))
	}

	@Test
	fun anEmptyHostOrABadPortIsNotAnAddress() {
		assertEquals(InstanceUrlProblem.MALFORMED, invalid("http://:4533"))
		assertEquals(InstanceUrlProblem.MALFORMED, invalid("http://"))
		assertEquals(InstanceUrlProblem.MALFORMED, invalid("nas:"))
		assertEquals(InstanceUrlProblem.MALFORMED, invalid("nas:0"))
		assertEquals(InstanceUrlProblem.MALFORMED, invalid("nas:99999"))
		assertEquals(InstanceUrlProblem.MALFORMED, invalid("nas 4533"))
		assertEquals(InstanceUrlProblem.MALFORMED, invalid("ftp://nas:4533"))
		assertEquals(InstanceUrlProblem.MALFORMED, invalid("http://[::1"))
	}

	@Test
	fun aNameWithAPortIsNotReadAsASchemeAndGetsHttpOnThisNetwork() {
		// Ktor alone reads "nas:4533" as the scheme "nas".
		assertEquals("http://nas:4533", ok("nas:4533"))
		assertEquals("http://192.168.1.10:4533", ok("192.168.1.10:4533"))
		assertEquals("http://10.0.2.2:4533", ok("10.0.2.2:4533"))
		assertEquals("http://localhost:4533", ok("localhost:4533"))
		assertEquals("http://127.0.0.1:4533", ok("127.0.0.1:4533"))
		assertEquals("http://music.local:4533", ok("music.local:4533"))
	}

	@Test
	fun aPublicNameGetsHttps() {
		assertEquals("https://music.example.com", ok("music.example.com"))
		assertEquals("https://music.example.com:8443", ok("music.example.com:8443"))
	}

	@Test
	fun aTypedSchemeIsKept() {
		assertEquals("https://music.example.com", ok("https://music.example.com"))
		assertEquals("http://music.example.com", ok("http://music.example.com"))
		assertEquals("https://192.168.1.10:4533", ok("https://192.168.1.10:4533"))
	}

	@Test
	fun spacesAndATrailingSlashGoAndAPathStays() {
		assertEquals("http://10.0.2.2:4533", ok("  http://10.0.2.2:4533/  "))
		assertEquals("https://music.example.com/navidrome", ok("https://music.example.com/navidrome/"))
	}

	@Test
	fun ipv6LiteralsParse() {
		assertEquals("http://[::1]:4533", ok("http://[::1]:4533"))
		assertEquals("http://[fd00::1]:4533", ok("[fd00::1]:4533"))
		assertEquals("http://[fe80::1]", ok("fe80::1"))
		assertEquals("::1", assertIs<InstanceUrlResult.Ok>(normalizeInstanceUrl("http://[::1]:4533")).host)
	}
}
