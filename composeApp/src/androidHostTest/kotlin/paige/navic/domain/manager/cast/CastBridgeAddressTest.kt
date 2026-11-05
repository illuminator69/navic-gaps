package paige.navic.domain.manager.cast

import kotlinx.coroutines.runBlocking
import paige.navic.domain.manager.HubNoAddressAnswered
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/*
 * Worbus P3, lane N2: a cast bridge's socket goes where the main socket went (design §6.3), read at
 * each dial, never the typed address and never anything while the main socket has no address.
 */
class CastBridgeAddressTest {
	@Test
	fun cast_bridge_dials_active_address() = runBlocking<Unit> {
		var active: String? = "ws://192.168.1.20:4790"
		val dialled = mutableListOf<String>()
		dialActiveAddress({ active }) { dialled += it }
		// The main socket moved (it dropped away from home and came back on the public address).
		active = "wss://hub.example.com"
		dialActiveAddress({ active }) { dialled += it }
		assertEquals(listOf("ws://192.168.1.20:4790", "wss://hub.example.com"), dialled)
		// No main socket: no dial, the bridge's own backoff waits.
		active = null
		assertFailsWith<HubNoAddressAnswered> { dialActiveAddress({ active }) { dialled += it } }
		assertEquals(2, dialled.size)
	}

	@Test
	fun the_bridges_are_wired_to_the_main_socket() {
		val cast = File("src/androidMain/kotlin/paige/navic/domain/manager/cast")
		val bridge = File(cast, "CastDeviceBridge.kt").readText()
		val manager = File(cast, "CastBridgeManager.kt").readText()
		assertTrue("dialActiveAddress(hubAddress) { url -> client.webSocket(url)" in bridge, "the bridge dials through the supplier")
		assertTrue(Regex("""client\.webSocket\((?!url\))""").find(bridge) == null, "no other dial in the bridge")
		assertTrue("hubAddress = { hubManager.activeAddress.value }" in manager, "the supplier is the main socket's address")
		assertTrue("preferenceManager.hubUrl" !in manager && "preferenceManager.hubUrl" !in bridge, "the typed address is not read")
	}
}
