package paige.navic.domain.manager

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/*
 * Worbus P3, lane N5 (design §6.4, §6.6): the Server screen's rules, which the screen only draws:
 * the route its status line names, the rows while no server is set, what a Sign in tap saves, the
 * post-sign-in sheet's once-per-server rule, and the address list's edits.
 */
class ServerScreenRulesTest {
	private val idA = "a".repeat(32)

	private fun <T> run(block: suspend () -> T): T {
		var out: Result<T>? = null
		block.startCoroutine(Continuation(EmptyCoroutineContext) { out = it })
		return out!!.getOrThrow()
	}

	private val settings = MemorySettings()
	private val prefs = PreferenceManager(settings, testSecrets(settings))
	private val store = HubServerRecordStore(prefs)

	@Test
	fun status_names_route_for_each_class() {
		assertEquals(RouteNote(ServerRoute.Home, false), routeNote("ws://192.168.1.20:4790"))
		assertEquals(RouteNote(ServerRoute.Home, false), routeNote("ws://nas.local:4790"))
		assertEquals(RouteNote(ServerRoute.Tailscale, false), routeNote("ws://100.101.1.2:4790"))
		assertEquals(RouteNote(ServerRoute.Tailscale, false), routeNote("ws://box.tail1.ts.net:4790"))
		assertEquals(RouteNote(ServerRoute.Via("hub.example.com"), false), routeNote("wss://hub.example.com"))
		// A typed public ws:// is the person's own choice: it works, and says "not encrypted".
		assertEquals(RouteNote(ServerRoute.Via("music.example.com"), true), routeNote("ws://music.example.com:4790"))
		assertNull(routeNote(null), "no live socket: nothing named")
		assertEquals("192.168.1.20:4790", shownAddress("ws://192.168.1.20:4790/connect"))
		assertEquals("hub.example.com/worbus", shownAddress("wss://hub.example.com/worbus"))
	}

	@Test
	fun not_set_rows_for_each_finder_state() {
		val actions = listOf(NotSetRow.LookOnNetwork, NotSetRow.TypeAddress)
		assertEquals(actions, notSetRows(FinderState.Idle))
		assertEquals(listOf(NotSetRow.Sentence(NotSetSentence.Looking)) + actions, notSetRows(FinderState.Looking))
		assertEquals(listOf(NotSetRow.Sentence(NotSetSentence.NothingBeside)) + actions, notSetRows(FinderState.NothingBeside))
		assertEquals(listOf(NotSetRow.Sentence(NotSetSentence.NothingOnNetwork)) + actions, notSetRows(FinderState.NothingOnNetwork))
		assertEquals(listOf(NotSetRow.Sentence(NotSetSentence.NotOnWifi)) + actions, notSetRows(FinderState.NotOnWifi))
		assertEquals(
			listOf(NotSetRow.Sentence(NotSetSentence.NeedsPermission), NotSetRow.OpenSettings) + actions,
			notSetRows(FinderState.NeedsPermission)
		)
		val server = Found.Server(idA, "Worbus Server", listOf("ws://192.168.1.20:4790"))
		val older = Found.OlderAt("ws://192.168.1.20:4790")
		val plain = Found.NotEncrypted("ws://music.example.com:4790")
		assertEquals(
			listOf(NotSetRow.Server(server), NotSetRow.Older(older), NotSetRow.NotEncrypted(plain.address)) + actions,
			notSetRows(FinderState.Results(listOf(server, older, plain)))
		)
	}

	@Test
	fun sign_in_tap_saves_found_sets_url_and_enables() {
		prefs.hubEnabled = false
		store.write(HubServerRecord(addresses = listOf(HubAddress("ws://10.0.0.9:4790", HubAddress.Source.Typed, null)), promptDismissed = listOf("x")))
		prefs.hubUrl = ""
		val found = Found.Server(idA, "Worbus Server", listOf("ws://192.168.1.20:4790", "wss://hub.example.com"))
		var signedIn = 0
		signInToFound(prefs, store, found, "wss://hub.example.com", signIn = { signedIn++ })
		val record = store.read()
		assertEquals(listOf("wss://hub.example.com", "ws://192.168.1.20:4790"), record.addresses.map { it.url })
		assertTrue(record.addresses.all { it.source == HubAddress.Source.Found })
		assertEquals("Worbus Server", record.name)
		assertNull(record.serverId, "filled by the first welcome")
		assertEquals(listOf("x"), record.promptDismissed)
		assertEquals("wss://hub.example.com", prefs.hubUrl, "the tapped address")
		assertTrue(prefs.hubEnabled, "Connect is on")
		assertEquals(1, signedIn, "one tap: the sign-in runs")
		// An older server's Use this address: the one address, the same way.
		signInToFound(prefs, store, Found.OlderAt("ws://192.168.1.30:4790"), "ws://192.168.1.30:4790", signIn = { signedIn++ })
		assertEquals(listOf("ws://192.168.1.30:4790"), store.read().addresses.map { it.url })
		assertEquals(2, signedIn)
		// A plain-text public answer is never signed in to.
		signInToFound(prefs, store, Found.NotEncrypted("ws://music.example.com:4790"), "ws://music.example.com:4790", signIn = { signedIn++ })
		assertEquals(2, signedIn)
		assertEquals("ws://192.168.1.30:4790", prefs.hubUrl)
		// Review I1, belt and braces: an "older server" at a public plain-text address (which the
		// finder no longer reports) changes nothing either: not laundered into hubUrl as typed.
		prefs.hubUrl = ""
		prefs.hubEnabled = false
		val before = store.read()
		signInToFound(prefs, store, Found.OlderAt("ws://music.example.com:4790"), "ws://music.example.com:4790", signIn = { signedIn++ })
		assertEquals(2, signedIn, "no sign-in")
		assertEquals("", prefs.hubUrl, "hubUrl stays blank")
		assertFalse(prefs.hubEnabled)
		assertEquals(before, store.read(), "the record is untouched")
	}

	@Test
	fun sheet_once_per_server() {
		val found = Found.Server(idA, "Worbus Server", listOf("ws://192.168.1.20:4533".replace("4533", "4790")))
		val finder = HubFinder(
			probe = { _, _ -> InfoOutcome.Match(idA, "Worbus Server") },
			localSubnet = { "192.168.1.57" },
			localNetworkGranted = { true },
			navidromeUrl = { "http://192.168.1.20:4533" },
			serverSet = { false },
			store = store,
			log = {}
		)
		run { finder.autoLook(afterLogin = true) }
		assertEquals(found, finder.prompt.value)
		finder.answerPrompt(dismissed = true)
		assertNull(finder.prompt.value)
		assertEquals(listOf(idA), store.read().promptDismissed, "remembered by id")
		run { finder.autoLook(afterLogin = true) }
		assertNull(finder.prompt.value, "asked once")
		// The Server screen's own look offers no sheet.
		store.write(HubServerRecord())
		run { finder.autoLook(afterLogin = false) }
		assertNull(finder.prompt.value)
		// An older server is remembered by its address; two servers found offer no sheet.
		assertEquals(Found.OlderAt("ws://h:4790"), promptFor(listOf(Found.OlderAt("ws://h:4790")), emptyList()))
		assertNull(promptFor(listOf(Found.OlderAt("ws://h:4790")), listOf("ws://h:4790")))
		assertNull(promptFor(listOf(found, found.copy(serverId = "b".repeat(32))), emptyList()))
		assertNull(promptFor(listOf(Found.NotEncrypted("ws://music.example.com:4790")), emptyList()))
	}

	@Test
	fun remove_and_learned_switch_persist() {
		prefs.hubUrl = "ws://192.168.1.20:4790"
		store.write(HubServerRecord(serverId = idA, addresses = listOf(
			HubAddress("ws://192.168.1.20:4790", HubAddress.Source.Typed, 3L),
			HubAddress("ws://100.70.1.2:4790", HubAddress.Source.Found, null),
			HubAddress("wss://hub.example.com", HubAddress.Source.Learned, 2L)
		)))
		assertTrue(removeServerAddress(prefs, store, "192.168.1.20:4790"), "hubUrl held it")
		assertEquals("ws://100.70.1.2:4790", prefs.hubUrl, "the next typed or found one")
		assertEquals(listOf("ws://100.70.1.2:4790", "wss://hub.example.com"), HubServerRecordStore(prefs).read().addresses.map { it.url })
		setUseAwayFromHome(store, on = false)
		assertTrue(HubServerRecordStore(prefs).read().learnedOff, "persisted")
		assertEquals(listOf("ws://100.70.1.2:4790"), store.read().connectOrder().map { it.url })
		assertEquals(emptyList(), otherAddresses(store.read(), "ws://100.70.1.2:4790"), "a switched-off learned address is not listed as another way in")
		setUseAwayFromHome(store, on = true)
		assertFalse(store.read().learnedOff)
		// A learned address cannot be removed, only switched off.
		assertFalse(removeServerAddress(prefs, store, "wss://hub.example.com"))
		assertTrue(store.read().learned != null)
		// The last typed or found one: no server set, and (review I2) no server either: the id and the
		// learned address go with it, what was answered Not now stays.
		store.update { it.dismissPrompt("x") }
		removeServerAddress(prefs, store, "ws://100.70.1.2:4790")
		assertEquals("", prefs.hubUrl)
		assertEquals(HubServerRecord(promptDismissed = listOf("x")), store.read())
		// Typing adds one, normalised, and sets it when nothing is set.
		assertEquals("ws://192.168.1.21:4790", addTypedAddress(prefs, store, "192.168.1.21:4790/connect/"))
		assertEquals("ws://192.168.1.21:4790", prefs.hubUrl)
		assertNull(addTypedAddress(prefs, store, "not an address"))
		// A second one while set joins the list and leaves hubUrl alone.
		assertEquals("ws://192.168.1.22:4790", addTypedAddress(prefs, store, "192.168.1.22:4790"))
		assertEquals("ws://192.168.1.21:4790", prefs.hubUrl)
		assertEquals(listOf("ws://192.168.1.21:4790", "ws://192.168.1.22:4790"), store.read().addresses.map { it.url })
		// Review M6: Add with nothing set (an old id still held) is a new server, as Type an address is.
		store.write(HubServerRecord(serverId = idA, addresses = listOf(HubAddress("ws://192.168.1.21:4790", HubAddress.Source.Typed, 3L))))
		prefs.hubUrl = ""
		assertEquals("ws://192.168.1.40:4790", addTypedAddress(prefs, store, "192.168.1.40:4790"))
		assertEquals(HubServerRecord(addresses = listOf(HubAddress("ws://192.168.1.40:4790", HubAddress.Source.Typed, null))), store.read())
		assertEquals("ws://192.168.1.40:4790", prefs.hubUrl)
		// "Type an address" while nothing is set is a new server: the old one's id and addresses go.
		prefs.hubUrl = ""
		assertEquals("ws://10.1.1.1:4790", typeNewServer(prefs, store, "10.1.1.1:4790"))
		val fresh = store.read()
		assertNull(fresh.serverId)
		assertEquals(listOf("ws://10.1.1.1:4790"), fresh.addresses.map { it.url })
		assertEquals("ws://10.1.1.1:4790", prefs.hubUrl)
	}

	/**
	 * N6 walk (found on the AVD): "Type an address" while no server was set left Connect off, so the
	 * Sign in that followed signed in and nothing connected ("Off" under Signed in). Typing is the other
	 * way on from the not-set rows, and turns Connect on as a found tap does.
	 */
	@Test
	fun type_an_address_turns_connect_on() {
		prefs.hubEnabled = false
		prefs.hubUrl = ""
		assertEquals("ws://127.0.0.1:4799", typeNewServer(prefs, store, "127.0.0.1:4799"))
		assertTrue(prefs.hubEnabled, "Connect is on")
		// The list's Add with nothing set goes the same way (M6).
		prefs.hubEnabled = false
		prefs.hubUrl = ""
		addTypedAddress(prefs, store, "10.0.0.5:4790")
		assertTrue(prefs.hubEnabled)
		// With a server set, adding another address leaves the switch as the person left it.
		prefs.hubEnabled = false
		addTypedAddress(prefs, store, "10.0.0.6:4790")
		assertFalse(prefs.hubEnabled)
	}
}
