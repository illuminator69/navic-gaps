package paige.navic.domain.manager

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/*
 * Worbus P3, lane N3 (design §6.5): per-server values follow the server's id, not its address. The
 * values are the ones Navic keeps per server: the device id a sign-in bound (R32), lb-bot's downloads
 * mode (P1), the sign-in verdict (R33) and the route signature the pages re-read on.
 */
class HubServerKeyTest {
	private val idA = "a".repeat(32)
	private val idB = "b".repeat(32)
	private val home = "ws://192.168.1.20:4790"
	private val away = "wss://hub.example.com"

	private fun <T> run(block: suspend () -> T): T {
		var out: Result<T>? = null
		block.startCoroutine(Continuation(EmptyCoroutineContext) { out = it })
		return out!!.getOrThrow()
	}

	private val settings = MemorySettings()
	private val secrets = testSecrets(settings)
	private val prefs = PreferenceManager(settings, secrets).apply {
		hubEnabled = true
		hubUrl = "$home/connect"
	}
	private val transport = HubAuthTransport { _, _, _ ->
		HubAuthReply.Answered(200, """{"deviceId": "bound-1", "deviceSecret": "sek", "user": {"name": "bob", "admin": false, "primary": false}}""")
	}
	private val auth = HubAuth(prefs, secrets, { NavidromeAccount("bob", "pw") }, { "phone" }, transport, log = {})
	private val connector = HubConnector(prefs, { _, _ -> InfoOutcome.Match(idA, "W") }, now = { 5L }, log = {}).also { it.wire(auth) }

	/** As LbBotManager builds it. */
	private fun downloads() = LbDownloadsState(
		memory = object : LbDownloadsMemory {
			override fun read(): String = prefs.lbDownloadsByHub
			override fun write(value: String) { prefs.lbDownloadsByHub = value }
		},
		hubKey = { perServerKey(prefs) }
	)

	private fun signature() = hubRouteSignature(prefs, auth)

	@Test
	fun address_change_keeps_every_key() {
		connector.store.write(HubServerRecord(serverId = idA, addresses = listOf(
			HubAddress(home, HubAddress.Source.Typed, 1L),
			HubAddress(away, HubAddress.Source.Learned, null)
		)))
		assertTrue(run { auth.signIn() } is SignInResult.SignedIn)
		downloads().newConnectionAt(LbDownloads.OFF)
		val id = auth.helloDeviceId()
		val sig = signature()
		assertEquals("bound-1", id)
		// The phone moves away: the person removes the home address, so the typed one is the public one,
		// and the socket is on it.
		prefs.hubUrl = away
		connector.store.update { it.without(home).withAddress(away, HubAddress.Source.Typed) }
		assertEquals(id, auth.helloDeviceId(), "the device id")
		assertEquals(LbDownloads.OFF, downloads().current, "the downloads mode")
		assertEquals(sig, signature(), "the route signature")
		auth.noteNewConnect()
		assertTrue(auth.state.value is HubAuthState.Answered, "the sign-in verdict")
	}

	@Test
	fun first_welcome_rekeys_not_wipes() {
		// Before P3, or before the first welcome: everything kept under the typed address.
		connector.store.read()
		assertTrue(run { auth.signIn() } is SignInResult.SignedIn)
		downloads().newConnectionAt(LbDownloads.ON)
		val keptIds = prefs.hubDeviceIds
		val keptModes = prefs.lbDownloadsByHub
		assertTrue("$home/connect|bob" in keptIds, keptIds)
		val sig = signature()
		connector.onWelcomeServer(home, idA, "Worbus Server", null)
		assertEquals(idA, connector.record().serverId)
		assertEquals("bound-1", auth.helloDeviceId(), "the device id moved to the id")
		assertEquals(LbDownloads.ON, downloads().current, "the downloads mode moved to the id")
		auth.noteNewConnect()
		assertTrue(auth.state.value is HubAuthState.Answered, "the sign-in verdict is not dropped")
		// Nothing wiped: the address entries are still there for a rolled-back build.
		assertTrue("$home/connect|bob" in prefs.hubDeviceIds)
		assertTrue("\"$home/connect\"" in prefs.lbDownloadsByHub)
		assertTrue(prefs.hubDeviceIds.length > keptIds.length && prefs.lbDownloadsByHub.length > keptModes.length)
		// The signature is the server's now; it moves once, then never with the address.
		val idSig = signature()
		assertNotEquals(sig, idSig)
		prefs.hubUrl = away
		assertEquals(idSig, signature())
	}

	@Test
	fun other_id_still_wipes() {
		connector.store.write(HubServerRecord(serverId = idA, addresses = listOf(HubAddress(home, HubAddress.Source.Typed, 1L))))
		assertTrue(run { auth.signIn() } is SignInResult.SignedIn)
		downloads().newConnectionAt(LbDownloads.OFF)
		val sig = signature()
		// The same address now answers with another server's id (its state was deleted: a new server).
		connector.onWelcomeServer(home, idB, "Worbus Server", null)
		assertEquals(idB, connector.record().serverId)
		assertNotEquals("bound-1", auth.helloDeviceId(), "the other server's device id is not offered")
		assertEquals(LbDownloads.UNKNOWN, downloads().current, "the downloads mode starts over")
		assertNotEquals(sig, signature(), "the pages re-read")
		auth.noteNewConnect()
		assertEquals(HubAuthState.Idle, auth.state.value, "the verdict is dropped, as an address change dropped it")
	}
}

private fun LbDownloadsState.newConnectionAt(mode: LbDownloads) = applyAdvert(mode, newConnection = true)
