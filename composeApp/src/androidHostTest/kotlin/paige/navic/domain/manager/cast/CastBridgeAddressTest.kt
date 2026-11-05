package paige.navic.domain.manager.cast

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
		assertTrue("awaitHubAddress = { hubManager.activeAddress.first { it != null } }" in manager, "a bridge waiting for an address hears it (review M11)")
		assertTrue("waitBeforeRedial(noAddress, backoffMs + jitter, awaitHubAddress)" in bridge)
	}

	@Test
	fun bridge_waiting_for_an_address_dials_when_it_appears() = runBlocking<Unit> {
		// Review M11: no main-socket address: the bridge waits for it, not out its whole backoff.
		val active = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
		val started = System.nanoTime()
		val waiting = async(Dispatchers.Default) {
			waitBeforeRedial(noAddress = true, waitMs = 30_000, awaitAddress = { active.first { it != null } }, jitterMs = { 10 })
		}
		kotlinx.coroutines.delay(50)
		active.value = "ws://192.168.1.20:4790"
		withTimeout(5_000) { waiting.await() }
		val ms = (System.nanoTime() - started) / 1_000_000
		assertTrue(ms < 3_000, "dialled soon after the address appeared, took $ms ms")
		// No address ever: the backoff is the bound.
		val none = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
		val t0 = System.nanoTime()
		waitBeforeRedial(noAddress = true, waitMs = 100, awaitAddress = { none.first { it != null } }, jitterMs = { 0 })
		assertTrue((System.nanoTime() - t0) / 1_000_000 >= 90, "waited the backoff out")
		// Any other failure waits the backoff, whatever the address does.
		val t1 = System.nanoTime()
		waitBeforeRedial(noAddress = false, waitMs = 100, awaitAddress = {}, jitterMs = { 0 })
		assertTrue((System.nanoTime() - t1) / 1_000_000 >= 90)
	}
}
