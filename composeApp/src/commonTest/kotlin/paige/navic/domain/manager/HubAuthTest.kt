package paige.navic.domain.manager

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.ByteString.Companion.encodeUtf8
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/*
 * Worbus P2, lane N1: signing in to the Worbus Server with the Navidrome account. The fake transport
 * answers with the bodies the hub at 7732e88 writes (`hub.py` `Hub.signin` / `Hub.signout`, and the
 * P1 hub's 405 `{"error": "method not allowed"}` for a POST outside its proxies), never suspends, and
 * records what was sent.
 */
class HubAuthTest {
	private class Call(val url: String, val body: String?, val bearer: String?)

	private class FakeTransport(var reply: HubAuthReply) : HubAuthTransport {
		val calls = mutableListOf<Call>()
		override suspend fun post(url: String, jsonBody: String?, bearer: String?): HubAuthReply {
			calls += Call(url, jsonBody, bearer)
			return reply
		}
	}

	/** A cipher that really changes the bytes, so "sealed" is checkable. */
	private class XorCipher : SecretCipher {
		override val encrypts = true
		override fun seal(plain: ByteArray) = byteArrayOf(7) + plain.map { (it.toInt() xor 0x5A).toByte() }
		override fun open(sealed: ByteArray) = sealed.copyOfRange(1, sealed.size).map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
	}

	private val settings = MemorySettings()
	private val vault = MemorySettings()
	private val secrets = SecretStore(settings, SecretVault(vault, XorCipher()), onError = { _, _ -> })
	private val prefs = PreferenceManager(settings, secrets).apply {
		hubEnabled = true
		hubUrl = "ws://hub.lan:4790/connect"
		hubDeviceName = "Pixel"
	}
	private var account: NavidromeAccount? = NavidromeAccount("bob", "hunter2-p4ss")
	private val transport = FakeTransport(granted())
	private val auth = HubAuth(prefs, secrets, { account }, { "phone" }, transport, log = {})

	private fun <T> run(block: suspend () -> T): T {
		var out: Result<T>? = null
		block.startCoroutine(Continuation(EmptyCoroutineContext) { out = it })
		return out!!.getOrThrow()
	}

	private fun granted(
		deviceId: String = "a1b2c3d4e5f60718",
		secret: String = "S3cr3t-0f-32-bytes_urlsafe-abcdefghijklmn",
		name: String = "bob",
		admin: Boolean = false,
		primary: Boolean = false
	) = HubAuthReply.Answered(
		200,
		"""{"deviceId": "$deviceId", "deviceSecret": "$secret", "user": {"name": "$name", "admin": $admin, "primary": $primary}}"""
	)

	private fun refused(status: Int, code: String) = HubAuthReply.Answered(status, """{"code": "$code"}""")

	@Test
	fun signin_mints_token_from_password_not_sending_it() {
		prefs.hubDeviceId = "old-device-id"
		val result = run { auth.signIn() }
		assertEquals(SignInResult.SignedIn(HubUser("bob", isAdmin = false, isPrimary = false)), result)

		val call = transport.calls.single()
		assertEquals("http://hub.lan:4790/hub/signin", call.url)
		assertNull(call.bearer, "a sign-in carries no credential")
		val body = Json.parseToJsonElement(call.body!!).jsonObject
		val salt = body["salt"]!!.jsonPrimitive.content
		val token = body["token"]!!.jsonPrimitive.content
		assertTrue(salt.length >= 6, "salt is at least 6 characters (hub SIGNIN_SALT_MIN)")
		assertEquals("hunter2-p4ss$salt".encodeUtf8().md5().hex(), token)
		assertTrue(Regex("^[0-9a-f]{32}$").matches(token), "lower-case md5 hex, as the hub checks")
		assertFalse("hunter2-p4ss" in call.body!!, "the password itself is never sent")
		assertEquals("bob", body["username"]!!.jsonPrimitive.content)
		assertEquals("old-device-id", body["deviceId"]!!.jsonPrimitive.content)
		assertEquals("Pixel", body["name"]!!.jsonPrimitive.content)
		assertEquals("android", body["platform"]!!.jsonPrimitive.content)
		assertEquals("phone", body["kind"]!!.jsonPrimitive.content)

		// A second sign-in mints a fresh salt.
		run { auth.signIn() }
		val salt2 = Json.parseToJsonElement(transport.calls[1].body!!).jsonObject["salt"]!!.jsonPrimitive.content
		assertTrue(salt != salt2, "a new salt each time")
	}

	@Test
	fun signin_adopts_and_persists_the_returned_device_id() {
		prefs.hubDeviceId = "owner-device-id"
		transport.reply = granted(deviceId = "freshly-minted-id")
		run { auth.signIn() }
		// R4: the server's answer is this device's id from now on (hello.device.id, bridgedBy).
		assertEquals("freshly-minted-id", prefs.hubDeviceId)
	}

	@Test
	fun bearer_prefers_secret_then_token() {
		assertNull(auth.bearer(), "neither held: no credential at all")
		assertNull(auth.httpBase(), "and no Worbus Server layer")
		auth.setLegacyToken("legacy-hub-token")
		assertEquals("legacy-hub-token", auth.bearer())
		assertEquals(HelloCredential.LegacyToken("legacy-hub-token"), auth.helloCredential())
		assertEquals("http://hub.lan:4790", auth.httpBase())

		transport.reply = granted(secret = "the-device-secret")
		run { auth.signIn() }
		assertEquals("the-device-secret", auth.bearer())
		assertEquals(HelloCredential.DeviceSecret("the-device-secret"), auth.helloCredential())
		// A server with a sign-in route makes the legacy token obsolete: nothing falls back to it.
		assertFalse(auth.hasLegacyToken)

		// The rule itself, both held.
		assertEquals(HelloCredential.DeviceSecret("s"), hubCredentialOf("s", "t"))
		assertEquals(HelloCredential.LegacyToken("t"), hubCredentialOf("", "t"))
		assertNull(hubCredentialOf("", ""))
	}

	@Test
	fun hello_sends_the_secret_in_auth_else_the_token() {
		val withSecret = buildJsonObject { putHelloCredential(HelloCredential.DeviceSecret("sek")) }
		assertEquals("sek", (withSecret["auth"] as JsonObject)["deviceSecret"]!!.jsonPrimitive.content)
		assertFalse("token" in withSecret)
		val withToken = buildJsonObject { putHelloCredential(HelloCredential.LegacyToken("tok")) }
		assertEquals("tok", withToken["token"]!!.jsonPrimitive.content)
		assertFalse("auth" in withToken)
	}

	@Test
	fun old_hub_405_shows_token_field() {
		// The P1 hub's answer to a POST outside its proxies (hub.py at 7efa17f).
		transport.reply = HubAuthReply.Answered(405, """{"error": "method not allowed"}""")
		assertFalse(auth.oldServerSeen)
		assertEquals(SignInResult.OldServer, run { auth.signIn() })
		assertTrue(auth.oldServerSeen, "the token field is offered for this address")
		assertNull(auth.bearer(), "nothing was granted")
		// Remembered for the address, not for another one.
		prefs.hubUrl = "ws://other:4790"
		assertFalse(auth.oldServerSeen)
	}

	@Test
	fun old_hub_404_shows_token_field() {
		transport.reply = HubAuthReply.Answered(404, "<html><body>Not Found</body></html>")
		assertEquals(SignInResult.OldServer, run { auth.signIn() })
		assertTrue(auth.oldServerSeen)
	}

	@Test
	fun old_hub_non_json_shows_token_field() {
		for (reply in listOf(
			HubAuthReply.Answered(200, "<html>hello</html>"),
			HubAuthReply.Answered(200, """{"ok": true}"""),
			HubAuthReply.Answered(500, "Internal Server Error"),
			HubAuthReply.Answered(401, """{"error": "unauthorized"}""")
		)) {
			assertEquals(SignInOutcome.NotGranted(SignInResult.OldServer), signInOutcomeOf(reply), "$reply")
		}
	}

	@Test
	fun no_answer_is_unreachable_never_old_server() {
		// R6: a refused connection, a timeout or a dropped connection.
		transport.reply = HubAuthReply.NoAnswer
		assertEquals(SignInResult.Unreachable(navidrome = false), run { auth.signIn() })
		assertFalse(auth.oldServerSeen)
	}

	@Test
	fun each_refusal_code_is_its_own_result() {
		assertEquals(SignInOutcome.NotGranted(SignInResult.Refused), signInOutcomeOf(refused(401, "navidrome_refused")))
		assertEquals(SignInOutcome.NotGranted(SignInResult.Unreachable(navidrome = true)), signInOutcomeOf(refused(503, "navidrome_unreachable")))
		assertEquals(SignInOutcome.NotGranted(SignInResult.TooManyAttempts), signInOutcomeOf(refused(429, "too_many_attempts")))
		assertEquals(SignInOutcome.NotGranted(SignInResult.Refused), signInOutcomeOf(refused(400, "bad_request")))
		// The hub's "hub starting" form of 503 carries the same code.
		assertEquals(
			SignInOutcome.NotGranted(SignInResult.Unreachable(navidrome = true)),
			signInOutcomeOf(HubAuthReply.Answered(503, """{"code": "navidrome_unreachable", "error": "hub starting"}"""))
		)
	}

	@Test
	fun signed_out_triggers_one_silent_signin() {
		transport.reply = granted(secret = "first")
		run { auth.signIn() }
		transport.calls.clear()

		// The hub closed with 4005: the secret is dropped and ONE sign-in is made.
		transport.reply = granted(secret = "second")
		assertTrue(run { auth.afterSignedOut() })
		assertEquals(1, transport.calls.size)
		assertEquals("second", auth.bearer())

		// Signed out again before any welcome: no second silent try, the state says so.
		assertFalse(run { auth.afterSignedOut() })
		assertEquals(1, transport.calls.size, "one silent sign-in per signed-out episode")
		assertEquals(HubAuthState.SignedOut, auth.state.value)
		assertNull(auth.bearer(), "the revoked secret is forgotten")

		// A welcome ends the episode: the next signed_out earns its one try again.
		auth.noteWelcome()
		transport.reply = refused(401, "navidrome_refused")
		assertFalse(run { auth.afterSignedOut() })
		assertEquals(2, transport.calls.size)
		assertEquals(HubAuthState.SignedOut, auth.state.value, "a failed silent sign-in reads as signed out")
	}

	@Test
	fun signed_out_is_close_4005_or_the_error_frame() {
		assertTrue(signedOutClose(4005, false))
		assertTrue(signedOutClose(null, true))
		assertTrue(signedOutClose(1000, true))
		for (code in listOf<Short?>(null, 1000, 1006, 4001, 4003, 4004)) {
			assertFalse(signedOutClose(code, false), "close $code")
		}
	}

	@Test
	fun secret_not_in_plain_prefs() {
		transport.reply = granted(secret = "the-device-secret-value")
		run { auth.signIn() }
		// Not in the settings file (which a device transfer carries) under any key.
		for (key in settings.keys) {
			assertFalse("the-device-secret-value" in settings.getString(key, ""), "settings[$key]")
		}
		// In the secrets file, sealed.
		val stored = vault.getString("hubDeviceSecret", "")
		assertTrue(stored.startsWith(SecretStore.PREFIX), "sealed")
		assertFalse("the-device-secret-value" in stored)
		// And readable after a restart.
		val again = SecretStore(settings, SecretVault(vault, XorCipher()), onError = { _, _ -> })
		assertEquals("the-device-secret-value", again.hubDeviceSecret)
		// The grant prints no secret.
		val grant = (signInOutcomeOf(granted(secret = "zzz-secret")) as SignInOutcome.Granted).grant
		assertFalse("zzz-secret" in grant.toString())
		assertFalse("zzz-secret" in HelloCredential.DeviceSecret("zzz-secret").toString())
	}

	@Test
	fun no_account_or_no_address_sends_nothing() {
		account = null
		assertEquals(SignInResult.Refused, run { auth.signIn() })
		account = NavidromeAccount("bob", "pw")
		prefs.hubUrl = ""
		assertEquals(SignInResult.Refused, run { auth.signIn() })
		assertTrue(transport.calls.isEmpty())
	}

	@Test
	fun the_user_record_is_read_strictly() {
		assertEquals(HubUser("alice", true, false), hubUserOf(Json.parseToJsonElement("""{"name":"alice","admin":true,"primary":false}""").jsonObject))
		assertNull(hubUserOf(null))
		assertNull(hubUserOf(Json.parseToJsonElement("""{"name":"alice"}""").jsonObject))
		assertNull(hubUserOf(Json.parseToJsonElement("""{"name":"alice","admin":"yes","primary":false}""").jsonObject))
	}
}
