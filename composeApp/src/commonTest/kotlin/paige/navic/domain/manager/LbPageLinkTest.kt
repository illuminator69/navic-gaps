package paige.navic.domain.manager

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Worbus P4 N4: lb-bot's failed imports in Needs attention, the link to lb-bot's page, and the
 * server's version on the Server screen. The seven cases are the contract lane F (Feishin) tests too.
 */
class LbPageLinkTest {
	private fun answer(counts: String) =
		"""{"version":1,"serverTime":1.0,"counts":$counts,"albums":[],"folders":[]}"""

	@Test
	fun imports_counted_in_to_fix() {
		val counts = AttentionCounts(failedGaps = 1, failedRequests = 2, folders = 3, imports = 4)
		assertEquals(10, toFixCount(counts))
		// Absent is 0: an lb-bot without the key counts as before.
		val older = (attentionRead(200, answer("""{"failedGaps":1,"failedRequests":2,"folders":3}""")) as AttentionRead.Ok).attention
		assertEquals(0, older.counts!!.imports)
		assertEquals(6, toFixCount(older.counts!!))
		val page = attentionPage(attentionRead(200, answer("""{"failedGaps":1,"imports":4}""")), null, null, emptyList(), null)
		assertEquals(5, (page as AttentionPage.Rows).toFix)
	}

	@Test
	fun imports_row_only_when_positive() {
		fun rows(counts: String) =
			attentionPage(attentionRead(200, answer(counts)), null, null, emptyList(), null) as AttentionPage.Rows
		assertNull(importsRow(rows("""{"imports":0}"""), "https://lb.example.org/#/needs/imports"))
		assertNull(importsRow(rows("""{"failedGaps":2}"""), null))
		assertEquals(ImportsRow(3, "https://lb.example.org/#/needs/imports"), importsRow(rows("""{"imports":3}"""), "https://lb.example.org/#/needs/imports"))
		assertEquals(ImportsRow(1, null), importsRow(rows("""{"imports":1}"""), null))
	}

	@Test
	fun link_uses_web_url() {
		assertEquals(
			"https://lb.example.org/#/needs/imports",
			lbPageLink("https://lb.example.org", "ws://192.168.1.20:4790", ::isPrivateHubAddress)
		)
		assertEquals(
			"https://lb.example.org/#/needs/imports",
			lbPageLink("https://lb.example.org/", "wss://hub.example.org", ::isPrivateHubAddress)
		)
	}

	@Test
	fun link_from_private_hub_host() {
		assertEquals(
			"http://192.168.1.20:8899/#/needs/imports",
			lbPageLink("", "ws://192.168.1.20:4790", ::isPrivateHubAddress)
		)
		assertEquals(
			"http://192.168.1.20:8899/#/needs/imports",
			lbPageLink(null, "ws://192.168.1.20:4790", ::isPrivateHubAddress)
		)
	}

	@Test
	fun no_link_from_public_host() {
		assertNull(lbPageLink("", "wss://hub.example.org", ::isPrivateHubAddress))
		assertNull(lbPageLink("", null, ::isPrivateHubAddress))
	}

	@Test
	fun unknown_attention_keys_ignored() {
		val read = attentionRead(
			200,
			"""{"version":2,"serverTime":1.0,"counts":{"failedGaps":1,"imports":2,"somethingNew":7},"albums":[],"folders":[],"alsoNew":{"x":1}}"""
		)
		assertIs<AttentionRead.Ok>(read)
		assertEquals(2, read.attention.counts!!.imports)
		assertEquals(3, toFixCount(read.attention.counts!!))
	}

	@Test
	fun server_version_shown() {
		val withVersion = Json.parseToJsonElement(
			"""{"id":"0123456789abcdef0123456789abcdef","name":"Home","publicUrl":null,"version":"0.4.0 (678af87)"}"""
		).jsonObject
		assertEquals("0.4.0 (678af87)", serverVersionShown(welcomeServerOf(withVersion)?.version))
		val without = Json.parseToJsonElement("""{"id":"0123456789abcdef0123456789abcdef","name":"Home"}""").jsonObject
		assertNull(serverVersionShown(welcomeServerOf(without)?.version))
		assertNull(serverVersionShown(null))
	}
}
