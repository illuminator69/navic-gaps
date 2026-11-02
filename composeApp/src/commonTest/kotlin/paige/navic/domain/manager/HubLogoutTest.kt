package paige.navic.domain.manager

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/*
 * Worbus P2, lane N4: the owner's ruling of 2026-11-02 (design §6.6 #1) — a Navidrome logout signs the
 * device out of the Worbus Server: `/hub/signout` (best effort), the secret and the legacy token
 * forgotten, the socket stopped, the saved-queue cache dropped. The AudioMuse token stays.
 */
class HubLogoutTest {
	private class Call(val url: String, val bearer: String?)

	private val calls = mutableListOf<Call>()
	private var reply: HubAuthReply = HubAuthReply.Answered(
		200, """{"deviceId": "dev-1", "deviceSecret": "the-secret", "user": {"name": "bob", "admin": false, "primary": false}}"""
	)
	private val settings = MemorySettings()
	private val secrets = testSecrets(settings)
	private val prefs = PreferenceManager(settings, secrets).apply {
		hubEnabled = true
		hubUrl = "ws://hub.lan:4790/connect"
	}
	private val auth = HubAuth(prefs, secrets, { NavidromeAccount("bob", "pw") }, { "phone" },
		HubAuthTransport { url, _, bearer -> calls += Call(url, bearer); reply }, log = {})

	private fun <T> run(block: suspend () -> T): T {
		var out: Result<T>? = null
		block.startCoroutine(Continuation(EmptyCoroutineContext) { out = it })
		return out!!.getOrThrow()
	}

	@Test
	fun logout_signs_out_of_hub_and_forgets_secret() {
		run { auth.signIn() }
		secrets.audioMuseToken = "am-tok"
		calls.clear()
		reply = HubAuthReply.Answered(200, "{}")

		val steps = mutableListOf<String>()
		run {
			leaveWorbusServer(
				stopSocket = { steps += "stop socket" },
				auth = auth,
				awaitSocketGone = {
					// By now nothing can dial with, or fall back to, any Worbus Server credential.
					assertNull(auth.bearer(), "forgotten before the socket is even gone")
					assertTrue(calls.isEmpty(), "the server is told after the socket is down")
					steps += "socket gone"
				},
				forgetSavedQueues = { steps += "saved queues forgotten" }
			)
		}
		assertEquals(listOf("stop socket", "socket gone", "saved queues forgotten"), steps)
		val call = calls.single()
		assertEquals("http://hub.lan:4790/hub/signout", call.url)
		assertEquals("the-secret", call.bearer, "signed out with the device's own secret")
		assertNull(auth.helloCredential())
		assertEquals("", secrets.hubDeviceSecret)
		assertEquals("", secrets.hubToken, "no legacy token left to fall back to")
		assertEquals("am-tok", secrets.audioMuseToken, "AudioMuse's token is not the Worbus Server's")
		assertEquals(HubAuthState.Idle, auth.state.value)
	}

	@Test
	fun a_server_that_does_not_answer_still_signs_the_device_out() {
		run { auth.signIn() }
		reply = HubAuthReply.NoAnswer
		run { auth.signOut() }
		assertNull(auth.bearer())
	}

	@Test
	fun a_legacy_token_alone_is_forgotten_too() {
		auth.setLegacyToken("legacy")
		run { leaveWorbusServer({}, auth, {}, {}) }
		assertNull(auth.bearer())
		assertTrue(calls.isEmpty(), "nothing to tell a server about a shared token")
	}

	@Test
	fun a_sign_in_answered_after_a_sign_out_keeps_nothing() {
		// A logout while the silent sign-in is out: the sign-out lands before the answer does.
		var self: HubAuth? = null
		val late = HubAuth(prefs, secrets, { NavidromeAccount("bob", "pw") }, { "phone" }, HubAuthTransport { _, _, _ ->
			self!!.beginSignOut()
			HubAuthReply.Answered(
				200, """{"deviceId": "dev-1", "deviceSecret": "too-late", "user": {"name": "bob", "admin": false, "primary": false}}"""
			)
		}, log = {})
		self = late
		run { late.signIn() }
		assertNull(late.bearer(), "the late secret is not kept")
		assertEquals("", secrets.hubDeviceSecret)
	}
}
