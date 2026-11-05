package paige.navic.domain.manager

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/*
 * Worbus P3, lane N1: the address model (design §6.3, PROTOCOL §3.4). The probes here are fakes that
 * answer at once; the rule under test is which address may carry this device's credential.
 */
class HubAddressesTest {
	private val idA = "a".repeat(32)
	private val idB = "b".repeat(32)

	private fun <T> run(block: suspend () -> T): T {
		var out: Result<T>? = null
		block.startCoroutine(Continuation(EmptyCoroutineContext) { out = it })
		return out!!.getOrThrow()
	}

	/** Answers by address; records every address asked. */
	private class FakeProbe(val answers: Map<String, InfoOutcome>) : HubInfoProbe {
		val asked = mutableListOf<Pair<String, Long>>()
		override suspend fun probe(base: String, timeoutMs: Long): InfoOutcome {
			asked += base to timeoutMs
			return answers[base] ?: InfoOutcome.Unreachable
		}
	}

	private fun typed(url: String, ok: Long? = null) = HubAddress(url, HubAddress.Source.Typed, ok)
	private fun found(url: String, ok: Long? = null) = HubAddress(url, HubAddress.Source.Found, ok)
	private fun learned(url: String, ok: Long? = null) = HubAddress(url, HubAddress.Source.Learned, ok)

	@Test
	fun normalise_adds_ws_keeps_path_drops_connect() {
		assertEquals("ws://192.168.1.20:4790", normaliseHubAddress("192.168.1.20:4790"))
		assertEquals("ws://192.168.1.20:4790", normaliseHubAddress("  ws://192.168.1.20:4790/connect  "))
		assertEquals("ws://192.168.1.20:4790", normaliseHubAddress("http://192.168.1.20:4790/"))
		assertEquals("wss://hub.example.com", normaliseHubAddress("https://hub.example.com/connect"))
		assertEquals("wss://hub.example.com", normaliseHubAddress("WSS://Hub.Example.com/"))
		// The controller's ruling: `/connect/` is the socket's path too.
		assertEquals("ws://h:4790", normaliseHubAddress("ws://h:4790/connect/"))
		assertEquals("ws://h:4790", normaliseHubAddress("ws://h:4790//connect//"))
		// A path is kept, so a hub behind a reverse proxy at a subpath keeps working.
		assertEquals("wss://example.com/worbus", normaliseHubAddress("https://example.com/worbus/connect"))
		assertEquals("ws://nas:4790/a/connected", normaliseHubAddress("nas:4790/a/connected/"))
		assertEquals("ws://[fe80::1]:4790", normaliseHubAddress("[fe80::1]:4790"))
		assertNull(normaliseHubAddress(""))
		assertNull(normaliseHubAddress("   "))
		assertNull(normaliseHubAddress("ftp://nas:4790"))
		assertNull(normaliseHubAddress("ws://"))
		assertNull(normaliseHubAddress("ws://:4790"))
		assertNull(normaliseHubAddress("192.168.1.20 4790"))
		// Review M1 (parity 7a): a query and a fragment are dropped, never carried into `/hub/info`.
		assertEquals("ws://h:4790", normaliseHubAddress("ws://h:4790?x=1"))
		assertEquals("ws://h:4790", normaliseHubAddress("ws://h:4790/connect?token=t#frag"))
		assertEquals("wss://example.com/worbus", normaliseHubAddress("https://example.com/worbus/?a=b"))
		assertEquals("ws://h:4790", normaliseHubAddress("h:4790#x"))
		assertEquals("http://h:4790/hub/info", hubInfoUrl("ws://h:4790?x=1"))
		// And `user:pass@` is stripped: no password is kept in the record or written to a log.
		assertEquals("ws://h:4790", normaliseHubAddress("ws://user:pass@h:4790"))
		assertEquals("wss://hub.example.com", normaliseHubAddress("https://u@Hub.Example.com/connect"))
		assertNull(normaliseHubAddress("ws://user:pass@"))
		assertNull(normaliseHubAddress("ws://?x=1"))
		// Every derived URL appends to the base.
		assertEquals("http://h:4790/hub/info", hubInfoUrl("ws://h:4790/connect/"))
		assertEquals("https://example.com/worbus/hub/info", hubInfoUrl("wss://example.com/worbus"))
		assertEquals("http://192.168.1.20:4790", hubHttpBaseOf("192.168.1.20:4790"))
	}

	@Test
	fun classify_private_tailscale_public() {
		val private = listOf(
			"127.0.0.1", "127.255.255.254", "10.0.0.1", "10.255.255.255", "172.16.0.1", "172.31.255.255",
			"192.168.0.1", "192.168.255.255", "169.254.0.1", "169.254.255.255", "[::1]", "[fc00::1]",
			"[fdff:ffff::1]", "[fe80::1]", "[febf::1]", "localhost", "nas.local", "NAS.LOCAL"
		)
		for (h in private) assertEquals(AddressClass.Private, classifyHubAddress("ws://$h:4790"), h)
		for (h in listOf("100.64.0.1", "100.127.255.255", "box.tail1234.ts.net")) {
			assertEquals(AddressClass.Tailscale, classifyHubAddress("ws://$h:4790"), h)
		}
		// One just outside each range, and any other DNS name.
		val public = listOf(
			"128.0.0.1", "126.255.255.255", "9.255.255.255", "11.0.0.1", "172.15.255.255", "172.32.0.1",
			"192.167.255.255", "192.169.0.1", "169.253.255.255", "169.255.0.1", "[::2]", "[fbff::1]",
			"[fec0::1]", "localhost.example.com", "local", "nas", "notlocal",
			"100.63.255.255", "100.128.0.1", "ts.net.example.com", "music.example.com"
		)
		for (h in public) assertEquals(AddressClass.Public, classifyHubAddress("ws://$h:4790"), h)
		assertEquals(PROBE_TIMEOUT_PRIVATE_MS, probeTimeoutMs("ws://192.168.1.20:4790"))
		assertEquals(PROBE_TIMEOUT_PRIVATE_MS, probeTimeoutMs("ws://100.64.0.2:4790"))
		assertEquals(PROBE_TIMEOUT_PUBLIC_MS, probeTimeoutMs("wss://hub.example.com"))
	}

	@Test
	fun default_address_not_migrated() {
		assertEquals(HubServerRecord(), HubServerRecord.migrateFrom(""))
		assertEquals(HubServerRecord(), HubServerRecord.migrateFrom("   "))
		// Through the store, as a never-configured install starts.
		val settings = MemorySettings()
		val prefs = PreferenceManager(settings, testSecrets(settings))
		val record = HubServerRecordStore(prefs).read()
		assertTrue(record.addresses.isEmpty())
		assertEquals("", prefs.hubUrl, "the legacy key is left as it was")
	}

	@Test
	fun typed_address_migrated() {
		val record = HubServerRecord.migrateFrom("192.168.1.20:4790/connect")
		assertEquals(listOf(typed("ws://192.168.1.20:4790")), record.addresses)
		assertNull(record.serverId)
		val settings = MemorySettings()
		val prefs = PreferenceManager(settings, testSecrets(settings)).apply { hubUrl = "ws://192.168.1.20:4790/connect" }
		val store = HubServerRecordStore(prefs)
		assertEquals(record, store.read())
		assertEquals("ws://192.168.1.20:4790/connect", prefs.hubUrl, "hubUrl is never rewritten")
		assertEquals(record, HubServerRecord.fromJson(prefs.hubServerRecord), "the migration is stored")
		// A rolled-back build that changed hubUrl: the address joins the record as typed.
		prefs.hubUrl = "ws://10.0.0.5:4790"
		assertEquals(listOf("ws://192.168.1.20:4790", "ws://10.0.0.5:4790"), store.read().addresses.map { it.url })
	}

	@Test
	fun record_json_round_trips_with_the_constraint_keys() {
		val record = HubServerRecord(
			serverId = idA, name = "Worbus Server",
			addresses = listOf(typed("ws://192.168.1.20:4790", 5L), learned("wss://hub.example.com")),
			learnedOff = true, promptDismissed = listOf(idB)
		)
		val text = record.toJson()
		for (key in listOf("\"serverId\"", "\"name\"", "\"addresses\"", "\"url\"", "\"source\"", "\"lastOkAt\"", "\"learnedOff\"", "\"promptDismissed\"", "\"typed\"", "\"learned\"")) {
			assertTrue(key in text, key)
		}
		assertEquals(record, HubServerRecord.fromJson(text))
		assertEquals(HubServerRecord(), HubServerRecord.fromJson("""{"serverId": null, "name": null, "addresses": [], "learnedOff": false, "promptDismissed": []}"""))
		assertNull(HubServerRecord.fromJson("not json"))
	}

	@Test
	fun order_private_first_then_newest() {
		val record = HubServerRecord(
			serverId = idA,
			addresses = listOf(
				learned("wss://hub.example.com", 900L),
				typed("ws://192.168.1.20:4790", 100L),
				found("ws://100.70.1.2:4790", 300L),
				typed("wss://other.example.org", null),
				found("ws://nas.local:4790", null)
			)
		)
		assertEquals(
			listOf("ws://100.70.1.2:4790", "ws://192.168.1.20:4790", "ws://nas.local:4790", "wss://hub.example.com", "wss://other.example.org"),
			record.connectOrder().map { it.url }
		)
	}

	@Test
	fun learned_ws_rejected() {
		val record = HubServerRecord(addresses = listOf(typed("ws://192.168.1.20:4790")))
		val plain = record.applyWelcomeServer(idA, "Worbus Server", "ws://hub.example.com")
		assertNull(plain.learned, "a learned ws:// is never stored")
		assertEquals(idA, plain.serverId)
		assertEquals("Worbus Server", plain.name)
		assertNull(record.applyWelcomeServer(idA, "W", "http://hub.example.com").learned)
		assertNull(record.applyWelcomeServer(idA, "W", "ws://192.168.1.30:4790").learned, "not even a private one")
		assertNull(record.withAddress("ws://hub.example.com", HubAddress.Source.Learned).learned)
		assertNull(HubServerRecord.fromJson("""{"addresses":[{"url":"ws://hub.example.com","source":"learned","lastOkAt":null}]}""")!!.learned)
		// https is wss.
		assertEquals("wss://hub.example.com", record.applyWelcomeServer(idA, "W", "https://hub.example.com/").learned?.url)
		// A found plain-text address on a public host is not kept either.
		assertTrue(record.withAddress("ws://music.example.com:4790", HubAddress.Source.Found).find("ws://music.example.com:4790") == null)
	}

	@Test
	fun learned_replaced_and_removed() {
		val start = HubServerRecord(addresses = listOf(typed("ws://192.168.1.20:4790")))
		val one = start.applyWelcomeServer(idA, "W", "wss://hub.example.com").markOk("wss://hub.example.com", 7L)
		assertEquals(learned("wss://hub.example.com", 7L), one.learned)
		// The same address again keeps its entry and its lastOkAt.
		assertEquals(learned("wss://hub.example.com", 7L), one.applyWelcomeServer(idA, "W", "wss://hub.example.com").learned)
		val two = one.applyWelcomeServer(idA, "W", "wss://away.example.com")
		assertEquals(learned("wss://away.example.com", null), two.learned)
		assertEquals(1, two.addresses.count { it.source == HubAddress.Source.Learned })
		val none = two.applyWelcomeServer(idA, "W", null)
		assertNull(none.learned)
		assertEquals(listOf(typed("ws://192.168.1.20:4790")), none.addresses)
		// A public address the person already typed is not repeated as learned.
		val typedWss = HubServerRecord(addresses = listOf(typed("wss://hub.example.com")))
		assertEquals(listOf(typed("wss://hub.example.com")), typedWss.applyWelcomeServer(idA, "W", "wss://hub.example.com").addresses)
	}

	@Test
	fun learned_off_excluded() {
		val record = HubServerRecord(serverId = idA, addresses = listOf(typed("ws://192.168.1.20:4790"), learned("wss://hub.example.com", 9L)))
		assertEquals(2, record.connectOrder().size)
		val off = record.copy(learnedOff = true)
		assertEquals(listOf("ws://192.168.1.20:4790"), off.connectOrder().map { it.url })
		// Still listed (the switch shows) and kept up to date, never dialled.
		val moved = off.applyWelcomeServer(idA, "W", "wss://away.example.com")
		assertEquals("wss://away.example.com", moved.learned?.url)
		assertTrue(moved.learnedOff)
		assertEquals(listOf("ws://192.168.1.20:4790"), moved.connectOrder().map { it.url })
		// Removing a learned address is switching it off: `without` leaves it.
		assertNotNull(record.without("wss://hub.example.com").learned)
	}

	@Test
	fun other_server_id_gets_no_secret() {
		val record = HubServerRecord(serverId = idA, addresses = listOf(typed("ws://192.168.1.20:4790", 5L)))
		val probe = FakeProbe(mapOf("ws://192.168.1.20:4790" to InfoOutcome.Match(idB, "Friend's server")))
		val skipped = mutableListOf<InfoOutcome>()
		assertNull(run { record.chooseAddress(probe) { _, o -> skipped += o } }, "nothing to dial")
		assertEquals(listOf<InfoOutcome>(InfoOutcome.OtherServer(idB)), skipped)
		assertEquals(DialVerdict.Skip(InfoOutcome.OtherServer(idB)), record.dialVerdict(record.addresses[0], InfoOutcome.OtherServer(idB)))
		// Skipped, never removed.
		assertEquals(1, record.addresses.size)
		// The same address answering A is dialled; with a learned address after it, B there moves on.
		val two = record.copy(addresses = record.addresses + learned("wss://hub.example.com"))
		val next = FakeProbe(mapOf(
			"ws://192.168.1.20:4790" to InfoOutcome.Match(idB, "Friend's server"),
			"wss://hub.example.com" to InfoOutcome.Match(idA, "Worbus Server")
		))
		assertEquals("wss://hub.example.com", run { two.chooseAddress(next) }?.address?.url)
		// With no id held yet, any Worbus answer passes (the first welcome fills it).
		assertNotNull(run { record.copy(serverId = null).chooseAddress(probe) })
	}

	@Test
	fun older_answer_uses_typed_never_learned() {
		val record = HubServerRecord(
			serverId = idA,
			addresses = listOf(
				typed("ws://192.168.1.20:4790", 5L),
				found("ws://192.168.1.21:4790", 6L),
				typed("ws://192.168.1.22:4790", null),
				learned("wss://hub.example.com", 9L)
			)
		)
		val (t, f, never, l) = record.addresses
		assertEquals(DialVerdict.Dial(checked = false), record.dialVerdict(t, InfoOutcome.Older))
		assertEquals(DialVerdict.Dial(checked = false), record.dialVerdict(f, InfoOutcome.Older))
		assertEquals(DialVerdict.Skip(InfoOutcome.Older), record.dialVerdict(never, InfoOutcome.Older), "never passed with this id")
		assertEquals(DialVerdict.Skip(InfoOutcome.Older), record.dialVerdict(l, InfoOutcome.Older), "a learned one never")
		// Through the walk: only the learned address left answering Older dials nothing.
		val onlyLearned = record.copy(addresses = listOf(l, never))
		assertNull(run { onlyLearned.chooseAddress(FakeProbe(mapOf(l.url to InfoOutcome.Older, never.url to InfoOutcome.Older))) })
		// An older server with no id held: the typed address is used as before.
		val older = HubServerRecord(addresses = listOf(typed("ws://192.168.1.20:4790")))
		assertEquals(DialVerdict.Dial(checked = false), older.dialVerdict(older.addresses[0], InfoOutcome.Older))
		// A 404 (review M4) connects as an older server does.
		assertEquals(DialVerdict.Dial(checked = false), older.dialVerdict(older.addresses[0], InfoOutcome.NoInfo))
		assertEquals(DialVerdict.Dial(checked = false), record.dialVerdict(t, InfoOutcome.NoInfo))
		assertEquals(DialVerdict.Skip(InfoOutcome.NoInfo), record.dialVerdict(l, InfoOutcome.NoInfo))
		// Review I3 (parity 6): any other answer at a typed address with no id known is dialled as
		// before P3 (a project 2 hub behind a proxy answering a page, a 403 or a 502)...
		assertEquals(DialVerdict.Dial(checked = false), older.dialVerdict(older.addresses[0], InfoOutcome.NotWorbus))
		// ...never at a found or learned one...
		val foundOnly = HubServerRecord(addresses = listOf(found("ws://192.168.1.21:4790")))
		assertEquals(DialVerdict.Skip(InfoOutcome.NotWorbus), foundOnly.dialVerdict(foundOnly.addresses[0], InfoOutcome.NotWorbus))
		val learnedNoId = HubServerRecord(addresses = listOf(learned("wss://hub.example.com")))
		assertEquals(DialVerdict.Skip(InfoOutcome.NotWorbus), learnedNoId.dialVerdict(learnedNoId.addresses[0], InfoOutcome.NotWorbus))
		// ...and never once an id is known, even at a typed address that passed with it.
		assertEquals(DialVerdict.Skip(InfoOutcome.NotWorbus), record.dialVerdict(t, InfoOutcome.NotWorbus))
		// Unreachable is skipped.
		assertEquals(DialVerdict.Skip(InfoOutcome.Unreachable), older.dialVerdict(older.addresses[0], InfoOutcome.Unreachable))
	}

	@Test
	fun info_answers_read_as_the_constraints_say() {
		assertEquals(InfoOutcome.Match(idA, "Worbus Server"), infoOutcomeOf(200, """{"service":"worbus","serverId":"$idA","name":"Worbus Server","signin":true}"""))
		assertEquals(InfoOutcome.Older, infoOutcomeOf(426, "Upgrade Required"))
		assertEquals(InfoOutcome.NoInfo, infoOutcomeOf(404, ""), "a 404 is any HTTP server on that port (review M4)")
		assertEquals(InfoOutcome.NotWorbus, infoOutcomeOf(405, """{"error":"method not allowed"}"""))
		assertEquals(InfoOutcome.NotWorbus, infoOutcomeOf(200, "<html></html>"))
		assertEquals(InfoOutcome.NotWorbus, infoOutcomeOf(200, """{"service":"other","serverId":"$idA"}"""))
		assertEquals(InfoOutcome.NotWorbus, infoOutcomeOf(200, """{"service":"worbus","serverId":"ABC"}"""))
		assertEquals(InfoOutcome.NotWorbus, infoOutcomeOf(502, ""))
	}

	@Test
	fun record_never_holds_a_secret() {
		// A sign-in as the app runs it, then everything the record learns around it.
		val settings = MemorySettings()
		val secrets = testSecrets(settings)
		val prefs = PreferenceManager(settings, secrets).apply {
			hubEnabled = true
			hubUrl = "ws://192.168.1.20:4790"
		}
		val secret = "S3cr3t-0f-32-bytes_urlsafe-abcdefghijklmn"
		val password = "hunter2-p4ss"
		var sentBody = ""
		val transport = HubAuthTransport { _, body, _ ->
			sentBody = body.orEmpty()
			HubAuthReply.Answered(200, """{"deviceId": "dev-1", "deviceSecret": "$secret", "user": {"name": "bob", "admin": false, "primary": false}}""")
		}
		secrets.hubToken = "legacy-token-value"
		val auth = HubAuth(prefs, secrets, { NavidromeAccount("bob", password) }, { "phone" }, transport, log = {})
		val store = HubServerRecordStore(prefs)
		store.read()
		assertTrue(run { auth.signIn() } is SignInResult.SignedIn)
		store.update {
			it.applyWelcomeServer(idA, "Worbus Server", "wss://hub.example.com")
				.markOk("ws://192.168.1.20:4790", 10L)
				.withAddress("ws://192.168.1.21:4790", HubAddress.Source.Found)
				.dismissPrompt(idA)
		}
		val salt = Regex("\"salt\":\"([^\"]+)\"").find(sentBody)!!.groupValues[1]
		val token = Regex("\"token\":\"([^\"]+)\"").find(sentBody)!!.groupValues[1]
		val stored = prefs.hubServerRecord
		assertTrue(stored.contains(idA), "the record was written: $stored")
		for (s in listOf(secret, password, salt, token, "legacy-token-value", "bob")) {
			assertFalse(s in stored, "the record holds no ${s.take(4)}…")
		}
	}

	@Test
	fun store_reports_every_server_change() {
		val settings = MemorySettings()
		val prefs = PreferenceManager(settings, testSecrets(settings)).apply { hubUrl = "ws://192.168.1.20:4790" }
		val store = prefs.serverRecordStore
		assertTrue(store === prefs.serverRecordStore, "one store per preference set (review M5)")
		val heard = mutableListOf<Pair<String?, String?>>()
		store.write(HubServerRecord(serverId = idA, addresses = listOf(typed("ws://192.168.1.20:4790"))))
		// Heard by the first listener, even when it changed before anyone listened.
		store.write(HubServerRecord(serverId = idB, addresses = listOf(typed("ws://192.168.1.20:4790"))))
		store.onServerChange = { from, to -> heard += from.serverId to to.serverId }
		assertEquals(listOf<Pair<String?, String?>>(idA to idB), heard, "a pending change is handed over")
		// The first id is not a change; the same id is not; a cleared one is.
		heard.clear()
		store.update { it.applyWelcomeServer(idB, "W", null) }
		store.update { it.copy(serverId = null) }
		assertEquals(listOf<Pair<String?, String?>>(idB to null), heard)
		store.update { it.copy(serverId = idA) }
		assertEquals(1, heard.size, "an id where none was held is the first welcome, not a change")
		// Starting over for a new server always is, with `hubUrl` written in the same step.
		heard.clear()
		store.startOver(HubServerRecord().withAddress("ws://192.168.1.9:4790", HubAddress.Source.Typed), hubUrl = "ws://192.168.1.9:4790")
		assertEquals(listOf<Pair<String?, String?>>(idA to null), heard)
		assertEquals("ws://192.168.1.9:4790", prefs.hubUrl)
		store.startOver(HubServerRecord().withAddress("ws://192.168.1.8:4790", HubAddress.Source.Typed), hubUrl = "ws://192.168.1.8:4790")
		assertEquals(2, heard.size, "a new server even when no id was held")
	}

	@Test
	fun rollback_then_upgrade_follows_the_latest_typed_address() {
		// Review M9: a rolled-back build typed Z (another server) while the record knew server A at X.
		val settings = MemorySettings()
		val prefs = PreferenceManager(settings, testSecrets(settings)).apply { hubUrl = "ws://192.168.1.20:4790" }
		val store = prefs.serverRecordStore
		store.write(
			HubServerRecord(
				serverId = idA,
				addresses = listOf(typed("ws://192.168.1.20:4790", 5L), learned("wss://hub.example.com")),
				promptDismissed = listOf(idB)
			)
		)
		val heard = mutableListOf<Pair<String?, String?>>()
		store.onServerChange = { from, to -> heard += from.serverId to to.serverId }
		prefs.hubUrl = "192.168.1.30:4790"
		val record = store.read()
		assertEquals(null, record.serverId, "the record starts over with the latest choice")
		assertEquals(listOf(typed("ws://192.168.1.30:4790")), record.addresses)
		assertEquals(listOf(idB), record.promptDismissed, "Not now answers are kept")
		assertEquals(listOf<Pair<String?, String?>>(idA to null), heard, "a server change (C1)")
		// With no id held, the rolled-back build's address joins as typed, as before.
		prefs.hubUrl = "192.168.1.31:4790"
		assertEquals(2, store.read().addresses.size)
		assertEquals(1, heard.size)
	}
}
