package paige.navic.domain.manager

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/*
 * Worbus P3, lane N4 (design §6.4): looking for the Worbus Server. The probes are fakes that record
 * what was asked; the last test puts the app's own probe (Ktor over OkHttp) in front of a socket on
 * 127.0.0.1 and reads the request off the wire.
 */
class HubFinderTest {
	private val idA = "a".repeat(32)
	private val idB = "b".repeat(32)

	private class FakeProbe(val answer: suspend (String) -> InfoOutcome) : HubInfoProbe {
		val asked: MutableList<String> = Collections.synchronizedList(mutableListOf())
		override suspend fun probe(base: String, timeoutMs: Long): InfoOutcome {
			asked += base
			return answer(base)
		}
	}

	private val settings = MemorySettings()
	private val prefs = PreferenceManager(settings, testSecrets(settings))
	private val store = HubServerRecordStore(prefs)
	private var navidrome = "http://192.168.1.20:4533"
	private var granted = true
	private var ownIp: String? = "192.168.1.57"
	private var set = false

	private var added = 0

	private fun finder(probe: HubInfoProbe, limits: SweepLimits = SweepLimits(), serverSet: () -> Boolean = { set }) = HubFinder(
		probe = probe,
		localSubnet = { ownIp },
		localNetworkGranted = { granted },
		navidromeUrl = { navidrome },
		serverSet = serverSet,
		store = store,
		limits = limits,
		onAddressAdded = { added++ },
		log = {}
	)

	@Test
	fun candidates_for_ip_host() {
		assertEquals(listOf("http://192.168.1.20:4790"), candidatesFor("http://192.168.1.20:4533"))
		assertEquals(listOf("http://10.0.0.2:4790"), candidatesFor("https://10.0.0.2/navidrome"))
		assertEquals(listOf("http://[fd00::5]:4790"), candidatesFor("http://[fd00::5]:4533"))
		assertEquals(emptyList(), candidatesFor(""))
	}

	@Test
	fun candidates_for_three_label_name() {
		assertEquals(
			listOf("http://music.example.com:4790", "https://hub.example.com", "https://worbus.example.com"),
			candidatesFor("https://music.example.com")
		)
		assertEquals(
			listOf("http://nd.home.example.org:4790", "https://hub.home.example.org", "https://worbus.home.example.org"),
			candidatesFor("https://ND.home.example.org:443/")
		)
	}

	@Test
	fun no_siblings_for_two_labels() {
		assertEquals(listOf("http://example.com:4790"), candidatesFor("https://example.com"))
		assertEquals(listOf("http://nas:4790"), candidatesFor("http://nas:4533"))
		assertEquals(listOf("http://nas.local:4790"), candidatesFor("http://nas.local:4533"))
	}

	@Test
	fun results_grouped_by_server_id() = runBlocking<Unit> {
		navidrome = "https://music.example.com"
		val probe = FakeProbe { base ->
			when (base) {
				"wss://hub.example.com" -> InfoOutcome.Match(idA, "Worbus Server")
				"wss://worbus.example.com" -> InfoOutcome.Match(idA, "Worbus Server")
				else -> InfoOutcome.Unreachable
			}
		}
		val f = finder(probe)
		val found = f.lookBeside()
		assertEquals(listOf<Found>(Found.Server(idA, "Worbus Server", listOf("wss://hub.example.com", "wss://worbus.example.com"))), found)
		assertEquals(FinderState.Results(found), f.state.value)
		// Two servers are two results.
		val two = groupFound(listOf("http://10.0.0.2:4790" to InfoOutcome.Match(idA, "A"), "https://hub.x.org" to InfoOutcome.Match(idB, "B")), olderCounts = true)
		assertEquals(2, two.size)
		// Nothing: its own state.
		val none = finder(FakeProbe { InfoOutcome.Unreachable })
		assertEquals(emptyList(), none.lookBeside())
		assertEquals(FinderState.NothingBeside, none.state.value)
	}

	@Test
	fun older_only_at_navidrome_host() = runBlocking<Unit> {
		navidrome = "https://music.example.com"
		val f = finder(FakeProbe { base -> if (base == "wss://hub.example.com") InfoOutcome.Older else InfoOutcome.Unreachable })
		assertEquals(emptyList(), f.lookBeside(), "a 426 at a sibling name is any WebSocket server")
		navidrome = "http://192.168.1.20:4533"
		val g = finder(FakeProbe { InfoOutcome.Older })
		assertEquals(listOf<Found>(Found.OlderAt("ws://192.168.1.20:4790")), g.lookBeside())
		// Review M4: a 404 there is any HTTP server on that port, not an older Worbus Server.
		val notFound = finder(FakeProbe { InfoOutcome.NoInfo })
		assertEquals(emptyList(), notFound.lookBeside())
		assertEquals(FinderState.NothingBeside, notFound.state.value)
		// The sweep never reports one.
		val h = finder(FakeProbe { InfoOutcome.Older })
		assertEquals(emptyList(), h.sweep())
		assertEquals(FinderState.NothingOnNetwork, h.state.value)
	}

	@Test
	fun public_http_found_not_offered() = runBlocking<Unit> {
		navidrome = "https://music.example.com"
		val f = finder(FakeProbe { base -> if (base == "ws://music.example.com:4790") InfoOutcome.Match(idA, "Worbus Server") else InfoOutcome.Unreachable })
		val found = f.lookBeside()
		assertEquals(listOf<Found>(Found.NotEncrypted("ws://music.example.com:4790")), found)
		assertFalse(found.any { it is Found.Server })
		// Not offered by the sheet either, and never kept as found.
		f.autoLook(afterLogin = true)
		assertNull(f.prompt.value)
		assertNull(HubServerRecord().withAddress("ws://music.example.com:4790", HubAddress.Source.Found).find("ws://music.example.com:4790"))
		// Review I1 (parity 3): a project 2 hub (426) port-forwarded at the public Navidrome host is
		// not encrypted either: never "Use this address", never the sheet.
		val older = finder(FakeProbe { base -> if (base == "ws://music.example.com:4790") InfoOutcome.Older else InfoOutcome.Unreachable })
		val olderFound = older.lookBeside()
		assertEquals(listOf<Found>(Found.NotEncrypted("ws://music.example.com:4790")), olderFound)
		assertFalse(olderFound.any { it is Found.OlderAt })
		assertNull(promptFor(olderFound, emptyList()))
		assertEquals(listOf(NotSetRow.NotEncrypted("ws://music.example.com:4790"), NotSetRow.LookOnNetwork, NotSetRow.TypeAddress), notSetRows(older.state.value))
		older.autoLook(afterLogin = true)
		assertNull(older.prompt.value)
		// And a tap on one (were it ever offered) leaves hubUrl blank.
		signInToFound(prefs, store, Found.OlderAt("ws://music.example.com:4790"), "ws://music.example.com:4790") { error("no sign-in") }
		assertEquals("", prefs.hubUrl)
		// A private host keeps its Use this address.
		navidrome = "http://192.168.1.20:4533"
		assertEquals(listOf<Found>(Found.OlderAt("ws://192.168.1.20:4790")), finder(FakeProbe { InfoOutcome.Older }).lookBeside())
	}

	@Test
	fun sweep_bounded_to_own_24() = runBlocking<Unit> {
		assertEquals(SweepLimits(parallel = 32, perProbeMs = 700, capMs = 10_000), SweepLimits())
		val inFlight = AtomicInteger(0)
		val most = AtomicInteger(0)
		val timeouts = CopyOnWriteArrayList<Long>()
		val probe = object : HubInfoProbe {
			val asked: MutableList<String> = Collections.synchronizedList(mutableListOf())
			override suspend fun probe(base: String, timeoutMs: Long): InfoOutcome {
				asked += base
				timeouts += timeoutMs
				most.accumulateAndGet(inFlight.incrementAndGet(), ::maxOf)
				delay(5)
				inFlight.decrementAndGet()
				return if (base == "ws://192.168.1.20:4790") InfoOutcome.Match(idA, "Worbus Server") else InfoOutcome.Unreachable
			}
		}
		val found = finder(probe).sweep()
		assertEquals(listOf<Found>(Found.Server(idA, "Worbus Server", listOf("ws://192.168.1.20:4790"))), found)
		assertEquals((1..254).map { "ws://192.168.1.$it:4790" }.toSet(), probe.asked.toSet(), "exactly the own /24, port 4790")
		assertEquals(254, probe.asked.size, "each once, the device's own address included")
		assertTrue(most.get() <= 32, "at most 32 in flight, saw ${most.get()}")
		assertTrue(timeouts.all { it == 700L })
		// The cap: probes that never answer are stopped when it runs out, not after each times out.
		val hang = FakeProbe { awaitCancellation() }
		val started = System.nanoTime()
		val capped = finder(hang, SweepLimits(perProbeMs = 60_000, capMs = 300)).sweep()
		val ms = (System.nanoTime() - started) / 1_000_000
		assertEquals(emptyList(), capped)
		assertTrue(ms < 3_000, "stopped at the cap, took $ms ms")
		assertTrue(hang.asked.size <= 32, "nothing beyond the first 32 started while they hung")
	}

	@Test
	fun sweep_needs_wifi_and_permission() = runBlocking<Unit> {
		val probe = FakeProbe { InfoOutcome.Match(idA, "W") }
		ownIp = null
		val f = finder(probe)
		assertEquals(emptyList(), f.sweep())
		assertEquals(FinderState.NotOnWifi, f.state.value)
		ownIp = "100.70.1.2" // a Tailscale address is not a home network to sweep
		f.sweep()
		assertEquals(FinderState.NotOnWifi, f.state.value)
		ownIp = "192.168.1.57"
		granted = false
		assertEquals(emptyList(), f.sweep())
		assertEquals(FinderState.NeedsPermission, f.state.value)
		assertEquals(emptyList(), probe.asked, "nothing was probed")
	}

	@Test
	fun autolook_runs_when_no_server_set() = runBlocking<Unit> {
		val probe = FakeProbe { InfoOutcome.Match(idA, "Worbus Server") }
		val f = finder(probe)
		set = false
		f.autoLook(afterLogin = true)
		assertEquals(listOf("ws://192.168.1.20:4790"), probe.asked)
		assertEquals(Found.Server(idA, "Worbus Server", listOf("ws://192.168.1.20:4790")), f.prompt.value)
		// And not when one is set.
		set = true
		probe.asked.clear()
		assertEquals(emptyList(), f.autoLook(afterLogin = true))
		assertEquals(emptyList(), probe.asked)
		// Review M2: the app's gate is a typed or tapped address, whatever Connect says (Navic's
		// default is Connect off): a typed address with Connect off is a set server, no look.
		prefs.hubEnabled = false
		prefs.hubUrl = "ws://192.168.1.20:4790"
		val app = finder(probe, serverSet = { hubAddressSet(prefs) })
		assertEquals(emptyList(), app.autoLook(afterLogin = true))
		assertEquals(emptyList(), probe.asked)
		assertNull(app.prompt.value)
		prefs.hubUrl = ""
		app.autoLook(afterLogin = true)
		assertEquals(listOf("ws://192.168.1.20:4790"), probe.asked, "blank: looks")
		val source = java.io.File("src/commonMain/kotlin/paige/navic/domain/manager/HubFinder.kt").readText()
		assertTrue("serverSet = { hubAddressSet(preferenceManager) }" in source, "the app's finder gates on the address alone")
	}

	@Test
	fun quiet_look_once_per_network() = runBlocking<Unit> {
		// Review M12: a Wi-Fi without the server is asked once, not before every reconnect.
		store.write(HubServerRecord(serverId = idA, addresses = listOf(HubAddress("wss://hub.example.com", HubAddress.Source.Typed, 1L))))
		set = true
		val probe = FakeProbe { InfoOutcome.Unreachable }
		val f = finder(probe)
		f.lookForHomeQuietly()
		f.lookForHomeQuietly()
		f.lookForHomeQuietly()
		assertEquals(listOf("ws://192.168.1.20:4790"), probe.asked, "once on this network")
		ownIp = "192.168.7.3"
		f.lookForHomeQuietly()
		assertEquals(2, probe.asked.size, "again on another network")
	}

	@Test
	fun results_forgotten_and_home_found_kicks() = runBlocking<Unit> {
		// Review M3: a login's results are not shown later as a home look's.
		val f = finder(FakeProbe { InfoOutcome.Match(idA, "W") })
		f.lookBeside()
		assertTrue(f.state.value is FinderState.Results)
		f.forgetResults()
		assertEquals(FinderState.Idle, f.state.value)
		// Review M10: a home address kept tells the hub manager (a waiting reconnect may go now).
		store.write(HubServerRecord(serverId = idA, addresses = listOf(HubAddress("wss://hub.example.com", HubAddress.Source.Typed, 1L))))
		prefs.hubUrl = "wss://hub.example.com"
		assertEquals("ws://192.168.1.1:4790", f.findHomeAddress(idA))
		assertEquals(1, added)
	}

	@Test
	fun home_address_only_same_id() = runBlocking<Unit> {
		store.write(HubServerRecord(serverId = idA, addresses = listOf(HubAddress("wss://hub.example.com", HubAddress.Source.Typed, 1L))))
		prefs.hubEnabled = true
		prefs.hubUrl = "wss://hub.example.com"
		set = true
		val probe = FakeProbe { base ->
			when (base) {
				"ws://192.168.1.5:4790" -> InfoOutcome.Match(idB, "Someone else's")
				"ws://192.168.1.7:4790" -> InfoOutcome.Match(idA, "Worbus Server")
				else -> InfoOutcome.Unreachable
			}
		}
		assertEquals("ws://192.168.1.7:4790", finder(probe).findHomeAddress(idA))
		val record = store.read()
		assertEquals(HubAddress.Source.Found, record.find("ws://192.168.1.7:4790")?.source)
		assertNull(record.find("ws://192.168.1.5:4790"), "another server's address is not kept")
		// The quiet look before a reconnect: Navidrome's host answers as another server: nothing kept.
		store.write(record.without("ws://192.168.1.7:4790"))
		navidrome = "http://192.168.1.5:4533"
		finder(probe).lookForHomeQuietly()
		assertFalse(store.read().hasPrivateAddress)
		navidrome = "http://192.168.1.7:4533"
		finder(probe).lookForHomeQuietly()
		assertTrue(store.read().hasPrivateAddress)
	}

	// ---- The app's probe on the wire ---------------------------------------------------------- //

	private val server = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
	private val requests = CopyOnWriteArrayList<String>()

	@AfterTest
	fun close() {
		server.close()
	}

	private fun answer(status: String, body: String) {
		thread(isDaemon = true) {
			while (!server.isClosed) {
				val socket = runCatching { server.accept() }.getOrNull() ?: break
				socket.use {
					val reader = it.getInputStream().bufferedReader()
					val head = StringBuilder()
					var length = 0
					while (true) {
						val line = reader.readLine() ?: break
						if (line.isEmpty()) break
						head.append(line).append('\n')
						if (line.lowercase().startsWith("content-length:")) length = line.substringAfter(':').trim().toInt()
					}
					val buf = CharArray(length)
					var read = 0
					while (read < length) {
						val n = reader.read(buf, read, length - read)
						if (n < 0) break
						read += n
					}
					requests += head.toString() + "\n" + String(buf, 0, read)
					val bytes = body.toByteArray()
					it.getOutputStream().write(
						("HTTP/1.1 $status\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray() + bytes
					)
					it.getOutputStream().flush()
				}
			}
		}
	}

	@Test
	fun probe_sends_no_credentials() = runBlocking<Unit> {
		answer("200 OK", """{"service":"worbus","serverId":"$idA","name":"Worbus Server","signin":true}""")
		// A device that holds every secret there is: none of it may be on the probe.
		val secrets = testSecrets(settings)
		secrets.navidromePassword = "hunter2-p4ss"
		secrets.hubToken = "legacy-token-value"
		secrets.hubDeviceSecret = "device-secret-value"
		navidrome = "http://127.0.0.1:${server.localPort}"
		val outcome = KtorHubInfoProbe().probe("ws://127.0.0.1:${server.localPort}/connect", 1_500)
		assertEquals(InfoOutcome.Match(idA, "Worbus Server"), outcome)
		val request = requests.single()
		val requestLine = request.lineSequence().first()
		assertEquals("GET /hub/info HTTP/1.1", requestLine, "no query at all, so no u/t/s")
		assertFalse("authorization" in request.lowercase(), request)
		assertFalse("cookie" in request.lowercase(), request)
		assertTrue(request.endsWith("\n\n"), "no body")
		for (s in listOf("hunter2", "legacy-token-value", "device-secret-value")) assertFalse(s in request)
	}
}
