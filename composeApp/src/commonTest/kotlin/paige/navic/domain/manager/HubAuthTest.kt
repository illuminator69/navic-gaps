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
			HubAuthReply.Answered(401, """{"error": "unauthorized"}"""),
			HubAuthReply.Answered(403, "<html>Forbidden</html>")
		)) {
			assertEquals(SignInOutcome.NotGranted(SignInResult.OldServer), signInOutcomeOf(reply), "$reply")
		}
	}

	@Test
	fun any_5xx_is_unreachable_never_old_server() {
		// The R6 clarification: a 5xx is a gateway or server failure whatever its body (a reverse
		// proxy's 502 HTML while the hub is down), never "an older server" with its token field.
		for (reply in listOf(
			HubAuthReply.Answered(500, "Internal Server Error"),
			HubAuthReply.Answered(502, "<html><body>502 Bad Gateway</body></html>"),
			HubAuthReply.Answered(503, "Service Unavailable"),
			HubAuthReply.Answered(504, """{"error": "timeout"}"""),
			HubAuthReply.Answered(500, """{"code": "something_else"}""")
		)) {
			assertEquals(SignInOutcome.NotGranted(SignInResult.Unreachable(navidrome = false)), signInOutcomeOf(reply), "$reply")
		}
		transport.reply = HubAuthReply.Answered(502, "<html>Bad Gateway</html>")
		run { auth.signIn() }
		assertFalse(auth.oldServerSeen, "no token field for a proxy's 502")
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
		assertEquals(SignInResult.NoAccount, run { auth.signIn() })
		account = NavidromeAccount("bob", "pw")
		prefs.hubUrl = ""
		assertEquals(SignInResult.NoAccount, run { auth.signIn() })
		assertTrue(transport.calls.isEmpty())
	}

	@Test
	fun no_account_is_not_the_servers_refusal() {
		// Review M-3: no account to sign in with (an unreadable password, B-279) is not the server's
		// Navidrome refusing one: the page must not say "doesn't know this account".
		auth.setLegacyToken("legacy")
		account = null
		assertEquals(SignInResult.NoAccount, run { auth.upgradeLegacyTokenIfDue() })
		assertEquals(HubAuthState.Idle, auth.state.value, "no answer to show")
		assertTrue(auth.hasLegacyToken, "nobody refused it: the token is kept")
		assertEquals("legacy", auth.bearer())
		assertTrue(transport.calls.isEmpty())
		// After a sign-out, the one silent sign-in that has nothing to sign in with says SignedOut.
		transport.reply = granted(secret = "sek")
		account = NavidromeAccount("bob", "pw")
		run { auth.signIn() }
		account = null
		assertFalse(run { auth.afterSignedOut() })
		assertEquals(HubAuthState.SignedOut, auth.state.value)
	}

	@Test
	fun the_user_record_is_read_strictly() {
		assertEquals(HubUser("alice", true, false), hubUserOf(Json.parseToJsonElement("""{"name":"alice","admin":true,"primary":false}""").jsonObject))
		assertNull(hubUserOf(null))
		assertNull(hubUserOf(Json.parseToJsonElement("""{"name":"alice"}""").jsonObject))
		assertNull(hubUserOf(Json.parseToJsonElement("""{"name":"alice","admin":"yes","primary":false}""").jsonObject))
	}

	// ---- R23: the legacy token is an upgrade bridge, not a fallback -------------------------- //

	@Test
	fun legacy_token_upgrades_once_on_connect() {
		auth.setLegacyToken("legacy")
		transport.reply = granted(secret = "upgraded")
		assertEquals(SignInResult.SignedIn(HubUser("bob", false, false)), run { auth.upgradeLegacyTokenIfDue() })
		assertEquals("upgraded", auth.bearer())
		assertFalse(auth.hasLegacyToken, "success clears the token")
		// Nothing left to upgrade: no second sign-in, now or at the next connect.
		assertNull(run { auth.upgradeLegacyTokenIfDue() })
		auth.noteNewConnect()
		assertNull(run { auth.upgradeLegacyTokenIfDue() })
		assertEquals(1, transport.calls.size)
	}

	@Test
	fun refused_upgrade_does_not_use_the_token() {
		auth.setLegacyToken("legacy")
		transport.reply = refused(401, "navidrome_refused")
		assertEquals(SignInResult.Refused, run { auth.upgradeLegacyTokenIfDue() })
		assertNull(auth.bearer(), "the token would make this account the primary user")
		assertFalse(auth.hasCredential)
		assertNull(auth.httpBase(), "no Worbus Server layer on it either")
		// The R23 clarification: a navidrome_refused answer retires the token, durably.
		assertFalse(auth.hasLegacyToken, "retired, not only unsent")
	}

	@Test
	fun refused_then_429_never_uses_the_token() {
		// Review I-3 (a): refused, then the hub's 429 on a later attempt (the sixth refusal in 600 s).
		auth.setLegacyToken("legacy")
		transport.reply = refused(401, "navidrome_refused")
		run { auth.upgradeLegacyTokenIfDue() }
		assertNull(auth.bearer())
		transport.reply = refused(429, "too_many_attempts")
		auth.noteNewConnect()
		run { auth.upgradeLegacyTokenIfDue() }
		assertEquals(SignInResult.TooManyAttempts, run { auth.signIn() }, "a tap on Sign in as")
		assertNull(auth.bearer(), "a 429 does not bring the token back")
		assertNull(auth.helloCredential())
		assertFalse(auth.hasCredential)
	}

	@Test
	fun refused_token_is_not_sent_after_restart() {
		// Review I-3 (b): a new process over the same store, before any sign-in has been answered.
		auth.setLegacyToken("legacy")
		transport.reply = refused(401, "navidrome_refused")
		run { auth.upgradeLegacyTokenIfDue() }
		val again = HubAuth(prefs, SecretStore(settings, SecretVault(vault, XorCipher()), onError = { _, _ -> }),
			{ account }, { "phone" }, transport, log = {})
		assertNull(again.bearer(), "nothing for LbBotManager's resumed watches to send as the primary user")
		assertNull(again.helloCredential())
		assertNull(again.httpBase())
	}

	@Test
	fun a_refusal_while_signed_in_keeps_the_secret() {
		// Only the upgrade (a token held, no secret) retires a token; a signed-in device holds none.
		transport.reply = granted(secret = "sek")
		run { auth.signIn() }
		transport.reply = refused(401, "navidrome_refused")
		run { auth.signIn() }
		assertEquals("sek", auth.bearer(), "a refused re-tap does not drop a working secret")
	}

	// ---- Review M-5: a credential change is announced (the cast bridges rebuild on it) ------- //

	@Test
	fun credential_changes_are_announced() {
		var seen = auth.credentialGeneration.value
		fun moved(what: String) {
			val now = auth.credentialGeneration.value
			assertTrue(now != seen, "$what moves the credential generation")
			seen = now
		}
		auth.setLegacyToken("legacy"); moved("a typed token")
		transport.reply = granted(secret = "sek")
		run { auth.signIn() }; moved("a sign-in")
		transport.reply = refused(401, "navidrome_refused")
		run { auth.afterSignedOut() }; moved("a 4005")
		auth.setLegacyToken("legacy")
		seen = auth.credentialGeneration.value
		run { auth.signIn() }; moved("a refused upgrade retiring the token")
		transport.reply = granted(secret = "sek2")
		run { auth.signIn() }
		seen = auth.credentialGeneration.value
		auth.noteOldServerRefusedSecret(); moved("R24")
		auth.setLegacyToken("t2"); seen = auth.credentialGeneration.value
		auth.beginSignOut(); moved("a sign-out")
	}

	// ---- Review M-12: no direct fingerprint for a device a Worbus Server signed in or out --- //

	@Test
	fun signed_out_or_refused_device_reads_no_fingerprint_directly() {
		assertFalse(auth.boundToWorbusServer, "never signed in anywhere: AudioMuse direct, as before")
		auth.setLegacyToken("legacy")
		assertFalse(auth.boundToWorbusServer, "the legacy token is today's primary user")
		transport.reply = granted(secret = "sek")
		run { auth.signIn() }
		assertTrue(auth.boundToWorbusServer)
		// Signed out, and the silent sign-in failed: no secret, but this device belongs to a server.
		transport.reply = refused(401, "navidrome_refused")
		run { auth.afterSignedOut() }
		assertEquals(HubAuthState.SignedOut, auth.state.value)
		assertTrue(auth.boundToWorbusServer)
		assertFalse(fingerprintRouteAllowed(viaHub = false, signedIn = auth.boundToWorbusServer))
		// A refused upgrade (the token retired) too.
		val fresh = HubAuth(prefs, SecretStore(MemorySettings(), SecretVault(MemorySettings(), XorCipher()), onError = { _, _ -> }),
			{ account }, { "phone" }, transport, log = {})
		fresh.setLegacyToken("legacy")
		run { fresh.upgradeLegacyTokenIfDue() }
		assertTrue(fresh.boundToWorbusServer)
	}

	// ---- Review M-2: a hub 401 while a credential is held kicks the socket ------------------ //

	@Test
	fun http_401_with_a_credential_kicks_the_socket() {
		var kicks = 0
		auth.onUnauthorized = { kicks++ }
		auth.noteHttpUnauthorized()
		assertEquals(0, kicks, "no credential: nothing to find out")
		transport.reply = granted(secret = "sek")
		run { auth.signIn() }
		auth.noteHttpUnauthorized()
		assertEquals(1, kicks, "the socket's hello then meets 4005 and the one silent sign-in")
		assertEquals("sek", auth.bearer(), "HTTP never signs in or forgets the secret itself")
		assertTrue(transport.calls.size == 1)
	}

	@Test
	fun old_server_or_no_answer_keeps_the_token() {
		auth.setLegacyToken("legacy")
		transport.reply = HubAuthReply.Answered(405, """{"error": "method not allowed"}""")
		assertEquals(SignInResult.OldServer, run { auth.upgradeLegacyTokenIfDue() })
		assertEquals("legacy", auth.bearer())
		assertNull(run { auth.upgradeLegacyTokenIfDue() }, "an older server is not asked again on this connect")

		for (reply in listOf(HubAuthReply.NoAnswer, refused(429, "too_many_attempts"), refused(503, "navidrome_unreachable"))) {
			auth.noteNewConnect()
			transport.reply = reply
			assertTrue(run { auth.upgradeLegacyTokenIfDue() } != null)
			assertEquals("legacy", auth.bearer(), "$reply: an upgrade must not cost a connection")
			// …and the next connect tries again.
			transport.calls.clear()
			run { auth.upgradeLegacyTokenIfDue() }
			assertEquals(1, transport.calls.size, "$reply: retried at the next connect")
		}
	}

	@Test
	fun after_a_signin_the_token_is_never_used_again() {
		auth.setLegacyToken("legacy")
		run { auth.signIn() }
		// The hub signs the device out and the silent sign-in fails: nothing to fall back to.
		transport.reply = refused(401, "navidrome_refused")
		assertFalse(run { auth.afterSignedOut() })
		assertNull(auth.bearer())
		assertEquals(false, legacyTokenUsableAfter(SignInResult.Refused))
		assertEquals(false, legacyTokenUsableAfter(SignInResult.SignedIn(HubUser("bob", false, false))))
		assertEquals(true, legacyTokenUsableAfter(null))
	}

	// ---- R24: 4001 to a secret hello is an older server --------------------------------------- //

	@Test
	fun secret_hello_closed_4001_is_old_server() {
		assertTrue(secretHelloRefusedByOldServer(helloSentSecret = true, closeCode = 4001, authErrorSeen = false))
		assertTrue(secretHelloRefusedByOldServer(true, null, authErrorSeen = true))
		assertFalse(secretHelloRefusedByOldServer(helloSentSecret = false, closeCode = 4001, authErrorSeen = true), "a refused token is Refused, as before")
		assertFalse(secretHelloRefusedByOldServer(true, 4005, false), "a new server says signed out with 4005")

		transport.reply = granted(secret = "sek")
		run { auth.signIn() }
		auth.noteOldServerRefusedSecret()
		assertNull(auth.bearer(), "the secret means nothing to a server from before sign-in")
		assertTrue(auth.oldServerSeen, "the token field is offered")
		assertEquals(HubAuthState.Answered(SignInResult.OldServer), auth.state.value)
		auth.setLegacyToken("typed")
		assertEquals("typed", auth.bearer())
	}

	// ---- Feishin review C1's pattern: "the hub is set up" never means "a token is typed" ---- //

	@Test
	fun signed_in_device_without_token_has_the_hub_layer() {
		transport.reply = granted(secret = "sek")
		run { auth.signIn() }
		assertFalse(auth.hasLegacyToken)
		assertTrue(auth.hasCredential)
		assertEquals("http://hub.lan:4790", auth.httpBase(), "lb-bot, the index mirror, previews, stations, moods")
		assertEquals("http://hub.lan:4790", hubHttpBase(auth))
		assertTrue(hubRouteSignature(prefs, auth) != "true|ws://hub.lan:4790/connect|0", "a re-probe key that moved")
	}
}
