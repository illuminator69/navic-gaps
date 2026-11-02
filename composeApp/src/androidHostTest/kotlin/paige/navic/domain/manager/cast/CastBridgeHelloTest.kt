package paige.navic.domain.manager.cast

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.runBlocking
import paige.navic.domain.manager.HelloCredential
import paige.navic.domain.manager.HubAuth
import paige.navic.domain.manager.HubAuthReply
import paige.navic.domain.manager.HubAuthTransport
import paige.navic.domain.manager.HubUser
import paige.navic.domain.manager.MemorySettings
import paige.navic.domain.manager.NavidromeAccount
import paige.navic.domain.manager.PreferenceManager
import paige.navic.domain.manager.SignInResult
import paige.navic.domain.manager.testSecrets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/*
 * Worbus P2, lane N1: a cast bridge authenticates with the bridging device's own secret and says
 * whose it is with `bridgedBy` = that device's own hub id. The hub registers a `cast-` id only when
 * `bridgedBy` equals the secret's bound id (hub.py `_bind_device_id`); any other hello is treated as
 * the phone itself, which would evict its main socket.
 */
class CastBridgeHelloTest {
	@Test
	fun cast_bridge_hello_carries_bridgedBy() {
		// The whole chain, as the app runs it: this device had a locally known id, signs in, and the
		// hub answers with the id it bound the secret to (R4: possibly a fresh one).
		val settings = MemorySettings()
		val secrets = testSecrets(settings)
		val prefs = PreferenceManager(settings, secrets).apply {
			hubEnabled = true
			hubUrl = "ws://hub.lan:4790/connect"
			hubDeviceId = "locally-known-id"
		}
		val transport = HubAuthTransport { _, _, _ ->
			HubAuthReply.Answered(
				200,
				"""{"deviceId": "bound-by-hub-id", "deviceSecret": "sek", "user": {"name": "bob", "admin": false, "primary": false}}"""
			)
		}
		val auth = HubAuth(prefs, secrets, { NavidromeAccount("bob", "pw") }, { "phone" }, transport, log = {})
		assertEquals(SignInResult.SignedIn(HubUser("bob", false, false)), runBlocking { auth.signIn() })
		assertEquals("bound-by-hub-id", auth.helloDeviceId(), "the returned id is persisted (R32: for this account here)")

		// What CastBridgeManager hands each bridge: the credential, and the owner id, while the main
		// socket's live id is still the legacy session's (no welcome on the new secret yet).
		val credential = auth.helloCredential()
		val owner = castOwnerDeviceId(credential, live = "stale-live-id", saved = auth.helloDeviceId())
		val hello = castBridgeHello("cast-abc", "Kitchen", "speaker", credential, owner)!!

		assertEquals("hello", hello["t"]!!.jsonPrimitive.content)
		assertEquals("sek", (hello["auth"] as JsonObject)["deviceSecret"]!!.jsonPrimitive.content)
		assertFalse("token" in hello, "the secret, never the legacy token beside it")
		val device = hello["device"] as JsonObject
		assertEquals("cast-abc", device["id"]!!.jsonPrimitive.content)
		// Exactly the id /hub/signin returned: never the locally known one, never a stale live one.
		assertEquals("bound-by-hub-id", device["bridgedBy"]!!.jsonPrimitive.content)
		assertEquals("chromecast", device["platform"]!!.jsonPrimitive.content)
		assertEquals("speaker", device["kind"]!!.jsonPrimitive.content)
	}

	@Test
	fun a_signed_in_bridge_without_its_own_id_says_no_hello() {
		assertNull(castBridgeHello("cast-abc", "Kitchen", null, HelloCredential.DeviceSecret("sek"), null))
		assertNull(castBridgeHello("cast-abc", "Kitchen", null, HelloCredential.DeviceSecret("sek"), ""))
	}

	@Test
	fun a_legacy_bridge_is_as_before() {
		val hello = castBridgeHello("cast-abc", "Kitchen", null, HelloCredential.LegacyToken("tok"), null)!!
		assertEquals("tok", hello["token"]!!.jsonPrimitive.content)
		assertFalse("auth" in hello)
		assertFalse("bridgedBy" in (hello["device"] as JsonObject))
	}

	@Test
	fun the_owner_id_is_the_bound_one_while_a_secret_is_held() {
		// After a sign-in the live id can still be the legacy session's until the next welcome.
		assertEquals("bound", castOwnerDeviceId(HelloCredential.DeviceSecret("s"), live = "old-live", saved = "bound"))
		assertNull(castOwnerDeviceId(HelloCredential.DeviceSecret("s"), live = "old-live", saved = ""))
		assertEquals("live", castOwnerDeviceId(HelloCredential.LegacyToken("t"), live = "live", saved = "saved"))
		assertEquals("saved", castOwnerDeviceId(HelloCredential.LegacyToken("t"), live = null, saved = "saved"))
	}
}
