package paige.navic.domain.manager.cast

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import paige.navic.domain.manager.HelloCredential
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
		val hello = castBridgeHello("cast-abc", "Kitchen", "speaker", HelloCredential.DeviceSecret("sek"), "phone-id")!!
		assertEquals("hello", hello["t"]!!.jsonPrimitive.content)
		assertEquals("sek", (hello["auth"] as JsonObject)["deviceSecret"]!!.jsonPrimitive.content)
		assertFalse("token" in hello, "the secret, never the legacy token beside it")
		val device = hello["device"] as JsonObject
		assertEquals("cast-abc", device["id"]!!.jsonPrimitive.content)
		assertEquals("phone-id", device["bridgedBy"]!!.jsonPrimitive.content)
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
