package paige.navic.domain.manager

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import paige.navic.util.Logger
import kotlin.time.Clock

/**
 * Worbus P3, lane N2 (design §6.3, PROTOCOL §3.4): which address this device's sockets and HTTP calls
 * go to. [HubManager] owns the loop and the socket; this owns the choice.
 *
 * - [connectOnce] walks the record's order, probes, and dials the first address that passes, and
 *   nothing else: the dial is where the hello (and its credential) is sent.
 * - [activeAddress] is the address the live socket is on, null while none. Every caller of the Worbus
 *   Server reaches it through [route] ([wire] gives it to [HubAuth]).
 * - A working socket is never moved: [kick] only cuts a backoff that is waiting, and a
 *   `welcome.server` ([onWelcomeServer]) updates the record without touching the socket.
 */
class HubConnector(
	private val preferenceManager: PreferenceManager,
	private val probe: HubInfoProbe = KtorHubInfoProbe(),
	private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
	private val log: (String) -> Unit = { Logger.i("HubConnector", it) }
) {
	val store = HubServerRecordStore(preferenceManager)

	private val _activeAddress = MutableStateFlow<String?>(null)

	/** The base the live socket is on (normalised), null while there is none. */
	val activeAddress: StateFlow<String?> = _activeAddress.asStateFlow()

	/**
	 * The address that last passed a probe in this process, so HTTP keeps its route across a brief
	 * reconnect. Only ever an address that answered as this server (or the older-server rules).
	 */
	@kotlin.concurrent.Volatile
	private var lastPassed: String? = null

	/** OtherServer skips already logged, once per address and id (design §6.3 #3). */
	private val loggedOther = mutableSetOf<String>()

	/** Cuts the reconnect loop's wait short ([kick]); conflated. */
	val kicks = Channel<String>(Channel.CONFLATED)

	fun record(): HubServerRecord = store.read()

	/** The first address the next connect would try, for wording a failure (its host). */
	fun firstAddress(): String? = record().connectOrder().firstOrNull()?.url

	/**
	 * Where HTTP calls and the sign-out go: the live socket's address, else the address that last
	 * passed in this process while the record still holds it, else, for a record with no id yet (an
	 * older server, or before the first welcome), the typed address as before P3. Null otherwise: a
	 * known server's credential goes nowhere that has not answered as it.
	 */
	fun route(): String? {
		_activeAddress.value?.let { return it }
		val record = record()
		lastPassed?.let { url -> if (record.connectOrder().any { it.url == url }) return url }
		if (record.serverId == null) return normaliseHubAddress(preferenceManager.hubUrl)
		return null
	}

	/**
	 * Where a sign-in posts the Navidrome token pair: the live socket's address, else the first
	 * address that passes a probe now. Null when none does (nothing is sent).
	 */
	suspend fun signInAddress(): String? {
		_activeAddress.value?.let { return it }
		val chosen = record().chooseAddress(probe, ::noteSkip) ?: return null
		lastPassed = chosen.address.url
		return chosen.address.url
	}

	/** [HubAuth] reads its routes here. */
	fun wire(auth: HubAuth) {
		auth.routeAddress = ::route
		auth.signInAddress = ::signInAddress
	}

	/**
	 * One connect: choose, then [dial] the chosen address only. [dial] calls its `opened` argument once
	 * the socket is up (that is when the address becomes [activeAddress] and is stamped `lastOkAt`) and
	 * returns when the socket has ended. False when no address passed (nothing was dialled).
	 */
	suspend fun connectOnce(dial: suspend (url: String, opened: () -> Unit) -> Unit): Boolean {
		val chosen = record().chooseAddress(probe, ::noteSkip) ?: return false
		val url = chosen.address.url
		lastPassed = url
		if (!(chosen.outcome is InfoOutcome.Match)) log("dialling $url without the check (${chosen.outcome::class.simpleName})")
		try {
			dial(url) {
				_activeAddress.value = url
				store.update { it.markOk(url, now()) }
			}
		} finally {
			if (_activeAddress.value == url) _activeAddress.value = null
		}
		return true
	}

	/**
	 * Something outside says the server may be reachable again (a network change). Only cuts a
	 * backoff that is waiting: false, and nothing sent, while a socket is open.
	 */
	fun kick(reason: String): Boolean {
		if (_activeAddress.value != null) return false
		kicks.trySend(reason)
		return true
	}

	/**
	 * `welcome.server` on the socket at [arrivedOn] (design §6.2, §6.5): the record learns the id,
	 * the name and the public address. The socket is not touched. On the first id (or a different
	 * one) only [arrivedOn] has passed with it. Returns the record before and after, for re-keying.
	 */
	fun onWelcomeServer(arrivedOn: String?, id: String, name: String, publicUrl: String?): Pair<HubServerRecord, HubServerRecord> {
		val before = record()
		var after = before.applyWelcomeServer(id, name, publicUrl)
		if (before.serverId != id) {
			after = after.copy(addresses = after.addresses.map { it.copy(lastOkAt = null) })
			if (arrivedOn != null) after = after.markOk(arrivedOn, now())
		}
		if (after != before) store.write(after)
		if (before.serverId != null && before.serverId != id) log("the server at ${arrivedOn ?: "?"} is another one now")
		return before to after
	}

	private fun noteSkip(address: HubAddress, outcome: InfoOutcome) {
		if (outcome is InfoOutcome.OtherServer) {
			if (loggedOther.add("${address.url}|${outcome.serverId}")) {
				log("${address.url} answers as another Worbus Server: skipped, nothing sent")
			}
		}
	}
}

/** `welcome.server`: `{id, name, publicUrl?}`, or null for an older hub or anything malformed. */
data class WelcomeServer(val id: String, val name: String, val publicUrl: String?)

fun welcomeServerOf(obj: kotlinx.serialization.json.JsonObject?): WelcomeServer? {
	if (obj == null) return null
	fun str(key: String) = (obj[key] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
	val id = str("id")?.takeIf { isServerId(it) } ?: return null
	return WelcomeServer(id, str("name").orEmpty(), str("publicUrl"))
}

/** No address of the record answered as this server: nothing was dialled (the status says "Can't reach"). */
class HubNoAddressAnswered : Exception("no address answered as this Worbus Server")
