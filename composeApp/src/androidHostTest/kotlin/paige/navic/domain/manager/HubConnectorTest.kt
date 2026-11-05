package paige.navic.domain.manager

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/*
 * Worbus P3, lane N2: connecting through the address that answers as this server. [HubConnector] is
 * what HubManager's loop calls for each connect: the fake dial here stands in for the socket (it is
 * where the hello and its credential would be written), so a dial it never records is a hello never
 * sent.
 */
class HubConnectorTest {
	private val idA = "a".repeat(32)
	private val idB = "b".repeat(32)
	private val home = "ws://192.168.1.20:4790"
	private val away = "wss://hub.example.com"

	private class FakeProbe(val answers: MutableMap<String, InfoOutcome>) : HubInfoProbe {
		val asked = mutableListOf<String>()
		override suspend fun probe(base: String, timeoutMs: Long): InfoOutcome {
			asked += base
			return answers[base] ?: InfoOutcome.Unreachable
		}
	}

	/** Records every dial; [hold] keeps the socket open until it completes. */
	private class FakeSocket {
		val dials = mutableListOf<String>()
		val hold = CompletableDeferred<Unit>()
		val opened = CompletableDeferred<String>()
		suspend fun dial(url: String, opened: () -> Unit) {
			dials += url
			opened()
			this.opened.complete(url)
			hold.await()
		}
	}

	private val settings = MemorySettings()
	private val secrets = testSecrets(settings)
	private val prefs = PreferenceManager(settings, secrets).apply {
		hubEnabled = true
		hubUrl = home
	}
	private var clock = 1_000L

	private fun connector(probe: HubInfoProbe, record: HubServerRecord): HubConnector {
		val c = HubConnector(prefs, probe, now = { clock }, log = {})
		c.store.write(record)
		return c
	}

	private fun record(vararg addresses: HubAddress, id: String? = idA) = HubServerRecord(serverId = id, addresses = addresses.toList())
	private fun typed(url: String, ok: Long? = null) = HubAddress(url, HubAddress.Source.Typed, ok)
	private fun learned(url: String, ok: Long? = null) = HubAddress(url, HubAddress.Source.Learned, ok)

	@Test
	fun dials_first_matching_address() = runBlocking<Unit> {
		val probe = FakeProbe(mutableMapOf(home to InfoOutcome.Match(idA, "W"), away to InfoOutcome.Match(idA, "W")))
		val c = connector(probe, record(learned(away, 50L), typed(home)))
		val socket = FakeSocket()
		socket.hold.complete(Unit)
		assertTrue(c.connectOnce(socket::dial))
		assertEquals(listOf(home), socket.dials, "the private address first, and only it")
		assertEquals(listOf(home), probe.asked, "no probe after the first that passed")
		assertEquals(1_000L, c.record().find(home)?.lastOkAt, "stamped when the socket opened")
		assertNull(c.activeAddress.value, "the socket ended")
	}

	@Test
	fun skips_other_server_then_dials_next() = runBlocking<Unit> {
		val probe = FakeProbe(mutableMapOf(home to InfoOutcome.Match(idB, "Friend"), away to InfoOutcome.Match(idA, "W")))
		val c = connector(probe, record(typed(home, 10L), learned(away)))
		val socket = FakeSocket()
		socket.hold.complete(Unit)
		assertTrue(c.connectOnce(socket::dial))
		assertEquals(listOf(away), socket.dials)
		assertEquals(listOf(home, away), probe.asked)
		assertEquals(2, c.record().addresses.size, "the other server's address is skipped, never removed")
	}

	@Test
	fun no_hello_after_other_server() = runBlocking<Unit> {
		val probe = FakeProbe(mutableMapOf(home to InfoOutcome.Match(idB, "Friend")))
		val c = connector(probe, record(typed(home, 10L)))
		val socket = FakeSocket()
		assertFalse(c.connectOnce(socket::dial), "nothing passed")
		assertEquals(emptyList(), socket.dials, "zero dials to the other server: no hello, no secret")
		// Nor does any HTTP call carrying the credential have a route there.
		val auth = HubAuth(prefs, secrets, { NavidromeAccount("bob", "pw") }, { "phone" }, { _, _, _ -> HubAuthReply.NoAnswer }, log = {})
		secrets.hubDeviceSecret = "device-secret"
		c.wire(auth)
		assertTrue(auth.serverSet, "still a set server (its mirror still shows)")
		assertNull(auth.httpBase(), "no base: the secret goes nowhere")
		// And a sign-in posts nothing there either.
		var posted = 0
		val signer = HubAuth(prefs, secrets, { NavidromeAccount("bob", "pw") }, { "phone" }, { _, _, _ -> posted++; HubAuthReply.NoAnswer }, log = {})
		c.wire(signer)
		secrets.hubDeviceSecret = ""
		assertEquals(SignInResult.Unreachable(navidrome = false), signer.signIn())
		assertEquals(0, posted, "no token pair to an address that answered as another server")
	}

	@Test
	fun network_change_keeps_live_socket() = runBlocking<Unit> {
		val probe = FakeProbe(mutableMapOf(home to InfoOutcome.Match(idA, "W"), away to InfoOutcome.Match(idA, "W")))
		// Set up through the public address (a sibling name): no home address yet.
		prefs.hubUrl = away
		val c = connector(probe, record(typed(away, 50L)))
		val socket = FakeSocket()
		val loop = async(Dispatchers.Default) { c.connectOnce(socket::dial) }
		assertEquals(away, withTimeout(5_000) { socket.opened.await() })
		assertEquals(away, c.activeAddress.value)
		// Home Wi-Fi comes back, and a typed home address answers now.
		c.store.update { it.withAddress(home, HubAddress.Source.Typed) }
		assertFalse(c.kick("network available"), "a kick is refused while a socket is open")
		assertNull(c.kicks.tryReceive().getOrNull(), "nothing reaches the loop's wait")
		assertTrue(loop.isActive, "the live socket is not closed")
		assertEquals(listOf(away), socket.dials)
		assertEquals(away, c.activeAddress.value)
		socket.hold.complete(Unit)
		loop.await()
		// Once it has dropped, the kick cuts the wait and the next connect goes home.
		assertTrue(c.kick("network available"))
		assertEquals("network available", c.kicks.tryReceive().getOrNull())
		val next = FakeSocket().also { it.hold.complete(Unit) }
		c.connectOnce(next::dial)
		assertEquals(listOf(home), next.dials)
	}

	@Test
	fun learned_update_keeps_live_socket() = runBlocking<Unit> {
		val probe = FakeProbe(mutableMapOf(home to InfoOutcome.Match(idA, "W")))
		val c = connector(probe, record(typed(home)))
		val socket = FakeSocket()
		val loop = async(Dispatchers.Default) { c.connectOnce(socket::dial) }
		withTimeout(5_000) { socket.opened.await() }
		val (_, after) = c.onWelcomeServer(c.activeAddress.value, idA, "Worbus Server", "wss://hub.example.com")
		assertEquals("wss://hub.example.com", after.learned?.url)
		assertEquals(after, c.record(), "persisted")
		c.onWelcomeServer(c.activeAddress.value, idA, "Worbus Server", "wss://away.example.com")
		assertEquals("wss://away.example.com", c.record().learned?.url)
		assertTrue(loop.isActive, "no reconnect")
		assertEquals(home, c.activeAddress.value)
		assertEquals(listOf(home), socket.dials)
		socket.hold.complete(Unit)
		loop.await()
	}

	@Test
	fun http_base_follows_active_address() = runBlocking<Unit> {
		val probe = FakeProbe(mutableMapOf(home to InfoOutcome.Match(idA, "W"), away to InfoOutcome.Match(idA, "W")))
		val c = connector(probe, record(typed(home), learned(away)))
		secrets.hubDeviceSecret = "device-secret"
		val auth = HubAuth(prefs, secrets, { NavidromeAccount("bob", "pw") }, { "phone" }, { _, _, _ -> HubAuthReply.NoAnswer }, log = {})
		c.wire(auth)
		assertNull(auth.httpBase(), "an id is known and nothing has passed yet: no route")
		val first = FakeSocket()
		val loop = async(Dispatchers.Default) { c.connectOnce(first::dial) }
		withTimeout(5_000) { first.opened.await() }
		assertEquals("http://192.168.1.20:4790", auth.httpBase())
		assertEquals("http://192.168.1.20:4790", hubHttpBase(auth))
		first.hold.complete(Unit)
		loop.await()
		assertEquals("http://192.168.1.20:4790", auth.httpBase(), "the address that last passed, across a reconnect")
		// Home stops answering: the next socket is the public one, and HTTP follows it.
		probe.answers[home] = InfoOutcome.Unreachable
		val second = FakeSocket()
		val loop2 = async(Dispatchers.Default) { c.connectOnce(second::dial) }
		withTimeout(5_000) { second.opened.await() }
		assertEquals("https://hub.example.com", auth.httpBase())
		second.hold.complete(Unit)
		loop2.await()
		// A record with no id yet (an older server, before the first welcome): the typed address, as before.
		c.store.write(record(typed(home), id = null))
		val fresh = HubConnector(prefs, probe, log = {}).also { it.wire(auth) }
		assertEquals("ws://192.168.1.20:4790", fresh.route())
	}
}
