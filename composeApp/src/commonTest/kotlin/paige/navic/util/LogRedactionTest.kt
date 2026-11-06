package paige.navic.util

import paige.navic.domain.parser.LogLine
import paige.navic.domain.parser.LogLineType
import paige.navic.ui.screens.settings.logLineShown
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Worbus P4 N3: the redaction rule every "send logs" path applies. The vectors are the controller's
 * shared contract (`redaction-vectors.json`, ruling R2; v2 by ruling R14), copied verbatim; Feishin
 * tests the same file.
 */
class LogRedactionTest {
	private data class Vector(val name: String, val input: String, val output: String)

	// redaction-vectors.json (contract v2, ruling R14), verbatim (27 cases).
	private val vectors = listOf(
		Vector("subsonic_params", """GET https://nd.example.org/rest/stream?u=bob&t=abc123&s=def456&v=1.16.1&c=navic&id=tr-1""", """GET https://nd.example.org/rest/stream?u=[removed]&t=[removed]&s=[removed]&v=1.16.1&c=navic&id=tr-1"""),
		Vector("subsonic_p_enc", """cover https://nd.example.org/rest/getCoverArt?id=al-9&p=enc:7365637265743132&u=alice&size=300""", """cover https://nd.example.org/rest/getCoverArt?id=al-9&p=[removed]&u=[removed]&size=300"""),
		Vector("bearer_header", """Authorization: Bearer eyJhbGciOi.payload.sig""", """Authorization: [removed]"""),
		Vector("bearer_header_lower", """authorization: bearer eyJhbGciOi.payload.sig""", """authorization: [removed]"""),
		Vector("bearer_bare_upper", """-> request with BEARER zzz999 attached""", """-> request with BEARER [removed] attached"""),
		Vector("bearer_json_header", """{"headers":{"Authorization":"Bearer abc.def"}}""", """{"headers":{"Authorization":"[removed]"}}"""),
		Vector("json_password", """{"username":"bob","password":"hunter2"}""", """{"username":"bob","password":"[removed]"}"""),
		Vector("json_password_escaped_quote", """{"password":"a\"b","x":1}""", """{"password":"[removed]","x":1}"""),
		Vector("json_device_secret", """{"deviceId":"d-1","deviceSecret":"ds-1234","name":"Pixel"}""", """{"deviceId":"d-1","deviceSecret":"[removed]","name":"Pixel"}"""),
		Vector("json_token", """hello {"t":"hello","token":"tok-abc","device":"d-1"}""", """hello {"t":"hello","token":"[removed]","device":"d-1"}"""),
		Vector("json_nested_secret", """{"hubAuth":{"deviceId":"d1","secret":"s3cr3t"}}""", """{"hubAuth":{"deviceId":"d1","secret":"[removed]"}}"""),
		Vector("json_spaced", """{ "apiKey" : "k-123" }""", """{ "apiKey" : "[removed]" }"""),
		Vector("kv_apikey", """audiomuse apiKey=sk-123 ok""", """audiomuse apiKey=[removed] ok"""),
		Vector("kv_env_style", """SLSKD_API_KEY=abc HUB_TOKEN=tok2 HUB_NAME=home""", """SLSKD_API_KEY=[removed] HUB_TOKEN=[removed] HUB_NAME=home"""),
		Vector("kv_colon", """HubManager: token: abc123""", """HubManager: token: [removed]"""),
		Vector("kv_quoted", """password="hunter 2" next""", """password="[removed]" next"""),
		Vector("query_apikey", """GET http://am.local:8000/api/x?apiKey=abc&q=1""", """GET http://am.local:8000/api/x?apiKey=[removed]&q=1"""),
		Vector("url_inside_json", """{"url":"https://nd.example.org/rest/stream?u=bob&t=abc&s=def&id=1"}""", """{"url":"https://nd.example.org/rest/stream?u=[removed]&t=[removed]&s=[removed]&id=1"}"""),
		Vector("url_inside_escaped_json", """payload={\"src\":\"https://nd/rest/stream?id=1&t=abc&s=def\"}""", """payload={\"src\":\"https://nd/rest/stream?id=1&t=[removed]&s=[removed]\"}"""),
		Vector("json_escaped_password", """body="{\"user\":\"bob\",\"password\":\"hunter2\"}"""", """body="{\"user\":\"bob\",\"password\":\"[removed]\"}""""),
		Vector("json_escaped_token_spaced", """frame {\"t\":\"hello\", \"token\" : \"tok-9\"}""", """frame {\"t\":\"hello\", \"token\" : \"[removed]\"}"""),
		Vector("url_userinfo", """fetch https://bob:hunter2@nd.example.org/rest/ping""", """fetch https://[removed]@nd.example.org/rest/ping"""),
		Vector("plain_ping", """GET /rest/ping?v=1.16.1&c=navic ok""", """GET /rest/ping?v=1.16.1&c=navic ok"""),
		Vector("plain_player", """Player: track changed to 3 of 12 (id=tr-1)""", """Player: track changed to 3 of 12 (id=tr-1)"""),
		Vector("plain_words", """Pressed the key 5 times; token refresh scheduled""", """Pressed the key 5 times; token refresh scheduled"""),
		Vector("plain_json", """{"t":"progress","positionMs":1200,"index":3}""", """{"t":"progress","positionMs":1200,"index":3}"""),
		Vector("plain_url", """https://hub.example.org:4790/hub/info?format=json""", """https://hub.example.org:4790/hub/info?format=json"""),
	)

	private fun check(vararg names: String) {
		for (name in names) {
			val v = vectors.single { it.name == name }
			assertEquals(v.output, redact(v.input), v.name)
		}
	}

	@Test
	fun redacts_subsonic_params() {
		check("subsonic_params", "subsonic_p_enc")
		assertEquals(
			"GET /rest/stream?u=[removed]&t=[removed]&s=[removed]&p=[removed]",
			redact("GET /rest/stream?u=bob&t=abc&s=def&p=enc:xyz")
		)
	}

	@Test
	fun redacts_bearer_any_case() {
		check("bearer_header", "bearer_header_lower", "bearer_bare_upper", "bearer_json_header")
	}

	@Test
	fun redacts_secret_fields() {
		check(
			"json_password", "json_password_escaped_quote", "json_device_secret", "json_token",
			"json_nested_secret", "json_spaced", "kv_apikey", "kv_env_style", "kv_colon", "kv_quoted",
			"query_apikey"
		)
	}

	@Test
	fun redacts_secret_fields_in_escaped_json() {
		check("json_escaped_password", "json_escaped_token_spaced")
	}

	@Test
	fun redacts_url_inside_json() {
		check("url_inside_json", "url_inside_escaped_json", "url_userinfo")
	}

	@Test
	fun leaves_plain_lines() {
		check("plain_ping", "plain_player", "plain_words", "plain_json", "plain_url")
	}

	@Test
	fun every_shared_vector_matches() {
		assertEquals(27, vectors.size)
		val wrong = vectors.filter { redact(it.input) != it.output }.map { it.name }
		assertEquals(emptyList(), wrong)
	}

	@Test
	fun header_has_versions() {
		val known = LogsHeader(
			appVersion = "0.4.0 (36a8ea5)",
			androidVersion = "Android 17 (API 37)",
			deviceModel = "Google Pixel 9",
			serverVersion = "0.4.0 (678af87)"
		)
		val text = logsFileText(known, emptyList())
		assertTrue("Navic 0.4.0 (36a8ea5)" in text, text)
		assertTrue("Android 17 (API 37)" in text, text)
		assertTrue("Google Pixel 9" in text, text)
		assertTrue("Worbus Server 0.4.0 (678af87)" in text, text)
		val unknown = logsFileText(known.copy(serverVersion = null), emptyList())
		assertTrue("server version unknown" in unknown, unknown)
		assertFalse("678af87" in unknown, unknown)
	}

	@Test
	fun device_name_and_file_name() {
		assertEquals("Google Pixel 9", deviceModelName("Google", "Pixel 9"))
		assertEquals("Samsung SM-S921B", deviceModelName("samsung", "SM-S921B"))
		assertEquals("Google Pixel 9", deviceModelName("Google", "Google Pixel 9"))
		assertEquals("Pixel 9", deviceModelName("", "Pixel 9"))
		assertEquals("navic-logs-20261106-090502.txt", logsFileName(2026, 11, 6, 9, 5, 2))
	}

	@Test
	fun logs_file_has_no_planted_secret() {
		val planted = listOf(
			"D/HubManager: hello {\"deviceSecret\":\"planted-ds-77\"}",
			"I/okhttp: --> GET https://nd.example.org/rest/ping?u=planted-user&t=planted-t&s=planted-s",
			"I/okhttp: Authorization: Bearer planted-bearer",
			"W/Login: password=planted-pw"
		)
		val header = LogsHeader("0.4.0 (36a8ea5)", "Android 17 (API 37)", "Google Pixel 9", null)
		val text = logsFileText(header, planted)
		for (secret in listOf("planted-ds-77", "planted-user", "planted-t", "planted-s", "planted-bearer", "planted-pw")) {
			assertFalse(secret in text, secret)
		}
		assertTrue(text.startsWith("Navic 0.4.0 (36a8ea5)\n"), text)
		assertTrue("Authorization: [removed]" in text, text)
	}

	/** Review I2: the Logs screen's row draws the line redacted, so selecting or screenshotting it gives no more than Share logs. */
	@Test
	fun log_row_shows_redacted_text() {
		val line = LogLine(
			id = 1,
			type = LogLineType.Info,
			text = "okhttp: --> GET https://nd.example.org/rest/ping?u=planted-user&t=planted-t&s=planted-s Authorization: Bearer planted-bearer",
			rawText = "I/okhttp: --> GET https://nd.example.org/rest/ping?u=planted-user&t=planted-t&s=planted-s Authorization: Bearer planted-bearer"
		)
		val shown = logLineShown(line)
		for (secret in listOf("planted-user", "planted-t", "planted-s", "planted-bearer")) {
			assertFalse(secret in shown, "$secret left in: $shown")
		}
		assertEquals(redact(line.text), shown)
	}
}
