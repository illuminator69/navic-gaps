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

	/** Every socket a test made, released when it ends, so a failed assertion cannot leave a dial hanging. */
	private val sockets = mutableListOf<FakeSocket>()

	private fun socket() = FakeSocket().also { sockets += it }

	private fun live(block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit) = runBlocking<Unit> {
		try {
			// A dial that never ends is a failure here, never a hung suite.
			withTimeout(20_000) { block() }
		} finally {
			sockets.forEach { it.hold.complete(Unit) }
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
	fun dials_first_matching_address() = live {
		val probe = FakeProbe(mutableMapOf(home to InfoOutcome.Match(idA, "W"), away to InfoOutcome.Match(idA, "W")))
		val c = connector(probe, record(learned(away, 50L), typed(home)))
		val socket = socket()
		socket.hold.complete(Unit)
		assertTrue(c.connectOnce(socket::dial))
		assertEquals(listOf(home), socket.dials, "the private address first, and only it")
		assertEquals(listOf(home), probe.asked, "no probe after the first that passed")
		assertEquals(1_000L, c.record().find(home)?.lastOkAt, "stamped when the socket opened")
		assertNull(c.activeAddress.value, "the socket ended")
	}

	@Test
	fun skips_other_server_then_dials_next() = live {
		val probe = FakeProbe(mutableMapOf(home to InfoOutcome.Match(idB, "Friend"), away to InfoOutcome.Match(idA, "W")))
		val c = connector(probe, record(typed(home, 10L), learned(away)))
		val socket = socket()
		socket.hold.complete(Unit)
		assertTrue(c.connectOnce(socket::dial))
		assertEquals(listOf(away), socket.dials)
		assertEquals(listOf(home, away), probe.asked)
		assertEquals(2, c.record().addresses.size, "the other server's address is skipped, never removed")
	}

	@Test
	fun no_hello_after_other_server() = live {
		val probe = FakeProbe(mutableMapOf(home to InfoOutcome.Match(idB, "Friend")))
		val c = connector(probe, record(typed(home, 10L)))
		val socket = socket()
		socket.hold.complete(Unit)
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
	fun network_change_keeps_live_socket() = live {
		val probe = FakeProbe(mutableMapOf(home to InfoOutcome.Match(idA, "W"), away to InfoOutcome.Match(idA, "W")))
		// Set up through the public address (a sibling name): no home address yet.
		prefs.hubUrl = away
		val c = connector(probe, record(typed(away, 50L)))
		val socket = socket()
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
		val next = socket().also { it.hold.complete(Unit) }
		c.connectOnce(next::dial)
		assertEquals(listOf(home), next.dials)
	}

	@Test
	fun learned_update_keeps_live_socket() = live {
		val probe = FakeProbe(mutableMapOf(home to InfoOutcome.Match(idA, "W")))
		val c = connector(probe, record(typed(home)))
		val socket = socket()
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
	fun http_base_follows_active_address() = live {
		val probe = FakeProbe(mutableMapOf(home to InfoOutcome.Match(idA, "W"), away to InfoOutcome.Match(idA, "W")))
		val c = connector(probe, record(typed(home), learned(away)))
		secrets.hubDeviceSecret = "device-secret"
		val auth = HubAuth(prefs, secrets, { NavidromeAccount("bob", "pw") }, { "phone" }, { _, _, _ -> HubAuthReply.NoAnswer }, log = {})
		c.wire(auth)
		assertNull(auth.httpBase(), "an id is known and nothing has passed yet: no route")
		val first = socket()
		val loop = async(Dispatchers.Default) { c.connectOnce(first::dial) }
		withTimeout(5_000) { first.opened.await() }
		assertEquals("http://192.168.1.20:4790", auth.httpBase())
		assertEquals("http://192.168.1.20:4790", hubHttpBase(auth))
		first.hold.complete(Unit)
		loop.await()
		// Controller ruling C2: a probe that passed is not a socket; with none there is no route.
		assertNull(auth.httpBase(), "no live socket: no bearer call anywhere")
		// Home stops answering: the next socket is the public one, and HTTP follows it.
		probe.answers[home] = InfoOutcome.Unreachable
		val second = socket()
		val loop2 = async(Dispatchers.Default) { c.connectOnce(second::dial) }
		withTimeout(5_000) { second.opened.await() }
		assertEquals("https://hub.example.com", auth.httpBase())
		second.hold.complete(Unit)
		loop2.await()
		// A record with no id yet (an older server, before the first welcome): nothing without a socket either.
		c.store.write(record(typed(home), id = null))
		val fresh = HubConnector(prefs, probe, log = {}).also { it.wire(auth) }
		assertNull(fresh.route())
	}

	// ---- The fix round (lane N review C1, C2, I2, M8) ---------------------------------------- //

	private class Post(val url: String, val bearer: String?)

	private fun authRecording(posts: MutableList<Post>, reply: (String) -> HubAuthReply = { HubAuthReply.NoAnswer }) =
		HubAuth(prefs, secrets, { NavidromeAccount("bob", "pw") }, { "phone" }, { url, _, bearer -> posts += Post(url, bearer); reply(url) }, log = {})

	/** What HubManager's loop does: dial only with a credential, the hello carrying [HubAuth.helloCredential]. */
	private suspend fun loopOnce(c: HubConnector, auth: HubAuth, hellos: MutableList<Pair<String, HelloCredential?>>) {
		if (!auth.hasCredential) return
		c.connectOnce { url, opened -> hellos += url to auth.helloCredential(); opened() }
	}

	private val y = "ws://192.168.1.30:4790"
	private val secretOfA = "secret-of-a"

	private fun assertNothingOfA(auth: HubAuth, hellos: List<Pair<String, HelloCredential?>>, posts: List<Post>) {
		assertTrue(hellos.none { it.second == HelloCredential.DeviceSecret(secretOfA) }, "no hello carried A's secret: $hellos")
		assertNull(auth.helloCredential(), "no hello credential is held")
		assertNull(auth.bearer(), "no bearer")
		assertNull(auth.httpBase(), "no HTTP base")
		assertFalse(auth.signedIn, "not signed in: a sign-in is owed")
		assertTrue(posts.none { it.bearer == secretOfA }, "no request carried A's secret")
	}

	@Test
	fun no_secret_after_record_starts_over() = live {
		val probe = FakeProbe(mutableMapOf(home to InfoOutcome.Match(idA, "W"), y to InfoOutcome.Match(idB, "Friend")))
		val posts = mutableListOf<Post>()
		val auth = authRecording(posts)
		val c = connector(probe, record(typed(home, 10L)))
		c.wire(auth)
		secrets.hubDeviceSecret = secretOfA
		assertEquals(secretOfA, auth.bearer())
		// The review's route: remove every address (no server set), then Type an address for Y = B.
		removeServerAddress(prefs, c.store, home)
		assertEquals("", prefs.hubUrl)
		typeNewServer(prefs, c.store, y)
		val hellos = mutableListOf<Pair<String, HelloCredential?>>()
		loopOnce(c, auth, hellos)
		auth.finishSignOut(auth.beginSignOut(c.activeAddress.value))
		assertNothingOfA(auth, hellos, posts)
		assertEquals(emptyList(), posts.map { it.url }, "nothing sent at all: no sign-out to B")
	}

	@Test
	fun no_secret_after_type_an_address_alone() = live {
		// Type an address with the old id still held (a record left so by an older build): starting
		// over is a server change by itself.
		val probe = FakeProbe(mutableMapOf(y to InfoOutcome.Match(idB, "Friend")))
		val posts = mutableListOf<Post>()
		val auth = authRecording(posts)
		val c = connector(probe, record(typed(home, 10L)))
		c.wire(auth)
		secrets.hubDeviceSecret = secretOfA
		prefs.hubUrl = ""
		typeNewServer(prefs, c.store, y)
		val hellos = mutableListOf<Pair<String, HelloCredential?>>()
		loopOnce(c, auth, hellos)
		auth.finishSignOut(auth.beginSignOut(null))
		assertNothingOfA(auth, hellos, posts)
	}

	@Test
	fun no_secret_after_sign_in_to_found_server() = live {
		val probe = FakeProbe(mutableMapOf(y to InfoOutcome.Match(idB, "Friend")))
		val posts = mutableListOf<Post>()
		// B's sign-in answers 429: the device stays without B's secret, and must not fall back to A's.
		val auth = authRecording(posts) { url ->
			if (url.endsWith(HubAuth.SIGNIN_PATH)) HubAuthReply.Answered(429, """{"code": "too_many_attempts"}""") else HubAuthReply.NoAnswer
		}
		val c = connector(probe, record(typed(home, 10L)))
		c.wire(auth)
		secrets.hubDeviceSecret = secretOfA
		prefs.hubUrl = ""
		var tapped = 0
		signInToFound(prefs, c.store, Found.Server(idB, "Friend", listOf(y)), y) { tapped++ }
		assertEquals(1, tapped)
		assertEquals(SignInResult.TooManyAttempts, auth.signIn())
		assertEquals(listOf("http://192.168.1.30:4790/hub/signin"), posts.map { it.url }, "the tapped server's sign-in only")
		assertNull(posts.single().bearer, "a sign-in carries no credential")
		val hellos = mutableListOf<Pair<String, HelloCredential?>>()
		loopOnce(c, auth, hellos)
		auth.finishSignOut(auth.beginSignOut(c.activeAddress.value))
		assertNothingOfA(auth, hellos, posts)
		assertEquals(1, posts.size, "no sign-out sent")
	}

	@Test
	fun welcome_from_another_server_drops_the_secret() = live {
		val probe = FakeProbe(mutableMapOf(home to InfoOutcome.Match(idA, "W")))
		val auth = authRecording(mutableListOf())
		val c = connector(probe, record(typed(home, 10L)))
		var stopped = 0
		c.afterServerChange = { stopped++ }
		c.wire(auth)
		secrets.hubDeviceSecret = secretOfA
		c.onWelcomeServer(home, idA, "W", null)
		assertEquals(secretOfA, auth.bearer(), "the same server: kept")
		assertEquals(0, stopped)
		c.onWelcomeServer(home, idB, "Friend", null)
		assertNull(auth.bearer(), "another id: the secret goes, a sign-in is owed")
		assertEquals(1, stopped, "HubManager stops the socket")
	}

	@Test
	fun bearer_calls_need_live_socket() = live {
		val probe = FakeProbe(mutableMapOf(home to InfoOutcome.Match(idA, "W")))
		val c = connector(probe, record(typed(home)))
		secrets.hubDeviceSecret = "device-secret"
		val auth = authRecording(mutableListOf())
		c.wire(auth)
		val first = socket()
		val loop = async(Dispatchers.Default) { c.connectOnce(first::dial) }
		withTimeout(5_000) { first.opened.await() }
		assertEquals("http://192.168.1.20:4790", auth.httpBase())
		first.hold.complete(Unit)
		loop.await()
		assertNull(auth.httpBase(), "the socket closed: no base")
		assertNull(hubHttpBase(auth))
		// A sign-in probe that passed is not a socket either.
		assertEquals(home, c.signInAddress())
		assertNull(auth.httpBase(), "a passed probe gives no route")
		// No id known (an older server): still nothing without a socket.
		c.store.write(record(typed(home), id = null))
		secrets.hubDeviceSecret = "device-secret"
		assertNull(c.route())
		assertNull(auth.httpBase())
		// Before the wiring (a manager made ahead of HubManager): no socket exists, so no route.
		assertNull(HubAuth(prefs, secrets, { null }, { "phone" }, { _, _, _ -> HubAuthReply.NoAnswer }, log = {}).httpBase())
	}

	@Test
	fun signout_goes_only_to_an_admitted_address() = live {
		val probe = FakeProbe(mutableMapOf(home to InfoOutcome.Match(idA, "W"), away to InfoOutcome.Match(idA, "W")))
		val c = connector(probe, record(typed(home), learned(away)))
		val posts = mutableListOf<Post>()
		val auth = authRecording(posts) { HubAuthReply.Answered(200, "{}") }
		c.wire(auth)
		secrets.hubDeviceSecret = secretOfA
		val first = socket().also { it.hold.complete(Unit) }
		c.connectOnce(first::dial)
		assertEquals(listOf(home), first.dials)
		// The phone joins a friend's Wi-Fi, where the same private address is their server B.
		probe.answers[home] = InfoOutcome.Match(idB, "Friend")
		auth.finishSignOut(auth.beginSignOut(c.activeAddress.value))
		assertEquals(listOf("https://hub.example.com/hub/signout"), posts.map { it.url }, "only the address that answers as A")
		assertEquals(secretOfA, posts.single().bearer)
		// With a socket up, the captured address is used and nothing is probed.
		secrets.hubDeviceSecret = secretOfA
		posts.clear()
		probe.asked.clear()
		auth.finishSignOut(auth.beginSignOut(home))
		assertEquals(listOf("http://192.168.1.20:4790/hub/signout"), posts.map { it.url })
		assertEquals(emptyList(), probe.asked)
		// Nothing answers as A: nothing is sent.
		secrets.hubDeviceSecret = secretOfA
		posts.clear()
		probe.answers[away] = InfoOutcome.Unreachable
		auth.finishSignOut(auth.beginSignOut(null))
		assertEquals(emptyList(), posts.map { it.url })
	}

	@Test
	fun signin_with_no_socket_posts_only_to_admitted() = live {
		val probe = FakeProbe(mutableMapOf(home to InfoOutcome.Match(idB, "Friend"), away to InfoOutcome.Match(idA, "W")))
		val c = connector(probe, record(typed(home, 10L), learned(away)))
		val posts = mutableListOf<Post>()
		val auth = authRecording(posts) {
			HubAuthReply.Answered(200, """{"deviceId": "d", "deviceSecret": "new-secret", "user": {"name": "bob", "admin": false, "primary": false}}""")
		}
		c.wire(auth)
		assertTrue(auth.signIn() is SignInResult.SignedIn)
		assertEquals(listOf("https://hub.example.com/hub/signin"), posts.map { it.url }, "the private address answers as B: skipped")
	}

	@Test
	fun no_dial_when_not_set() = live {
		val probe = FakeProbe(mutableMapOf(away to InfoOutcome.Match(idA, "W")))
		prefs.hubUrl = ""
		val c = connector(probe, record(learned(away, 5L)))
		secrets.hubDeviceSecret = "device-secret"
		val socket = socket().also { it.hold.complete(Unit) }
		assertFalse(c.connectOnce(socket::dial), "no server set: nothing dialled")
		assertEquals(emptyList(), socket.dials)
		assertEquals(emptyList(), probe.asked, "not even probed")
		// Connect off is not set either.
		prefs.hubUrl = away
		prefs.hubEnabled = false
		assertFalse(c.connectOnce(socket::dial))
		assertEquals(emptyList(), socket.dials)
	}

	@Test
	fun pass_before_the_id_does_not_count_after_it() = live {
		// Parity 5 (review M8): typed X passes with no id; a welcome on Y names A; X answering
		// as an older server is then skipped: its pass was not as A.
		val x = home
		val probe = FakeProbe(mutableMapOf(x to InfoOutcome.Older))
		val c = connector(probe, record(typed(x), typed(y), id = null))
		val s1 = socket().also { it.hold.complete(Unit) }
		assertTrue(c.connectOnce(s1::dial))
		assertEquals(listOf(x), s1.dials)
		assertEquals(1_000L, c.record().find(x)?.lastOkAt, "stamped with no id held")
		probe.answers[x] = InfoOutcome.Unreachable
		probe.answers[y] = InfoOutcome.Match(idA, "W")
		val s2 = socket().also { it.hold.complete(Unit) }
		clock = 2_000L
		assertTrue(c.connectOnce(s2::dial))
		assertEquals(listOf(y), s2.dials)
		c.onWelcomeServer(y, idA, "W", null)
		assertNull(c.record().find(x)?.lastOkAt, "X's pass was not as A")
		probe.answers[x] = InfoOutcome.Older
		probe.answers[y] = InfoOutcome.Unreachable
		val s3 = socket().also { it.hold.complete(Unit) }
		assertFalse(c.connectOnce(s3::dial))
		assertEquals(emptyList(), s3.dials, "X is not dialled without the check")
	}
}
