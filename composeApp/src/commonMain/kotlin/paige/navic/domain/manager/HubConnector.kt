package paige.navic.domain.manager

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import paige.navic.di.synchronized
import paige.navic.util.Logger
import kotlin.time.Clock

/**
 * Worbus P3, lane N2 (design §6.3, PROTOCOL §3.4): which address this device's sockets and HTTP calls
 * go to. [HubManager] owns the loop and the socket; this owns the choice.
 *
 * - [connectOnce] walks the record's order, probes, and dials the first address that passes, and
 *   nothing else: the dial is where the hello (and its credential) is sent.
 * - [activeAddress] is the address the live socket is on, null while none. Every caller of the Worbus
 *   Server reaches it through [route] ([wire] gives it to [HubAuth]); with no socket there is no route.
 * - A working socket is never moved: [kick] only cuts a backoff that is waiting, and a
 *   `welcome.server` ([onWelcomeServer]) updates the record without touching the socket.
 */
class HubConnector(
	private val preferenceManager: PreferenceManager,
	private val probe: HubInfoProbe = KtorHubInfoProbe(),
	private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
	private val log: (String) -> Unit = { Logger.i("HubConnector", it) }
) {
	val store: HubServerRecordStore = preferenceManager.serverRecordStore

	private val _activeAddress = MutableStateFlow<String?>(null)

	/** The base the live socket is on (normalised), null while there is none. */
	val activeAddress: StateFlow<String?> = _activeAddress.asStateFlow()

	@kotlin.concurrent.Volatile
	private var auth: HubAuth? = null

	/**
	 * Set by [HubManager]: the server changed ([HubServerRecordStore.onServerChange]) and its secret is
	 * gone; a socket held on the old server is stopped.
	 */
	@kotlin.concurrent.Volatile
	var afterServerChange: (() -> Unit)? = null

	/** OtherServer skips already logged, once per address and id (design §6.3 #3). */
	private val loggedOther = mutableSetOf<String>()

	/** Cuts the reconnect loop's wait short ([kick]); conflated. */
	val kicks = Channel<String>(Channel.CONFLATED)

	fun record(): HubServerRecord = store.read()

	/**
	 * "Is a Worbus Server set here": the switch is on and an address is typed or tapped. A gate, never
	 * where to dial ([activeAddress]). With none set nothing is dialled (review I2), whatever the
	 * record still holds.
	 */
	fun serverSet(): Boolean = preferenceManager.hubEnabled && hubAddressSet(preferenceManager)

	/** The first address the next connect would try, for wording a failure (its host). */
	fun firstAddress(): String? = record().connectOrder().firstOrNull()?.url

	/**
	 * Where HTTP calls carrying the credential go (controller ruling C2): the live socket's address,
	 * and nothing while there is none. A probe that passed is not a socket: it says nothing about the
	 * network the next call goes out on, so the callers fail soft ("can't reach") until a socket opens.
	 */
	fun route(): String? = _activeAddress.value

	/**
	 * Where a sign-in posts the Navidrome token pair, and where a sign-out with no captured address
	 * goes: the live socket's address, else the first address that passes a probe now, in the
	 * record's order ([chooseAddress]). Null when none does (nothing is sent).
	 */
	suspend fun signInAddress(): String? {
		_activeAddress.value?.let { return it }
		return record().chooseAddress(probe, ::noteSkip)?.address?.url
	}

	/** [HubAuth] reads its routes here, and drops its secret on a server change (C1). */
	fun wire(auth: HubAuth) {
		this.auth = auth
		auth.routeAddress = ::route
		auth.signInAddress = ::signInAddress
		store.onServerChange = { from, to -> serverChanged(from, to) }
	}

	/**
	 * Controller ruling C1: the record's id was cleared or replaced, or the record started over for a
	 * new server. The device secret the old server issued is not presented again (hello, bearer,
	 * sign-out) and nothing is sent about it; a sign-in is owed.
	 */
	private fun serverChanged(from: HubServerRecord, to: HubServerRecord) {
		log("the server changed (${from.serverId ?: "no id"} to ${to.serverId ?: "no id"}): its secret is not presented again, nothing sent")
		auth?.serverChanged()
		afterServerChange?.invoke()
	}

	/**
	 * One connect: choose, then [dial] the chosen address only. [dial] calls its `opened` argument once
	 * the socket is up (that is when the address becomes [activeAddress] and is stamped `lastOkAt`) and
	 * returns when the socket has ended. False when no address passed or no server is set (nothing was
	 * dialled).
	 */
	suspend fun connectOnce(dial: suspend (url: String, opened: () -> Unit) -> Unit): Boolean {
		if (!serverSet()) return false
		val chosen = record().chooseAddress(probe, ::noteSkip) ?: return false
		val url = chosen.address.url
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
	 * Something outside says the server may be reachable again (a network change, an address added).
	 * Only cuts a backoff that is waiting: false, and nothing sent, while a socket is open.
	 */
	fun kick(reason: String): Boolean {
		if (_activeAddress.value != null) return false
		kicks.trySend(reason)
		return true
	}

	/**
	 * `welcome.server` on the socket at [arrivedOn] (design §6.2, §6.5): the record learns the id,
	 * the name and the public address, in one step of the store. The socket is not touched here. On
	 * the first id (or a different one) only [arrivedOn] has passed with it. A different id than the
	 * record held is a server change (C1: the store tells [serverChanged]). Returns the record before
	 * and after, for re-keying.
	 */
	fun onWelcomeServer(arrivedOn: String?, id: String, name: String, publicUrl: String?): Pair<HubServerRecord, HubServerRecord> {
		var before = HubServerRecord()
		val after = store.update { held ->
			before = held
			var next = held.applyWelcomeServer(id, name, publicUrl)
			if (held.serverId != id) {
				next = next.copy(addresses = next.addresses.map { it.copy(lastOkAt = null) })
				if (arrivedOn != null) next = next.markOk(arrivedOn, now())
			}
			next
		}
		if (before.serverId == null) {
			// The first id (design §6.5): what was kept under the address moves to the id, nothing is wiped.
			val addressKey = preferenceManager.hubUrl.trim()
			rekeyStoredPerServer(preferenceManager, setOfNotNull(arrivedOn, addressKey), id)
			auth?.noteServerIdentified(addressKey, id)
		}
		if (before.serverId != null && before.serverId != id) log("the server at ${arrivedOn ?: "?"} is another one now")
		return before to after
	}

	private fun noteSkip(address: HubAddress, outcome: InfoOutcome) {
		if (outcome is InfoOutcome.OtherServer) {
			val first = synchronized(loggedOther) { loggedOther.add("${address.url}|${outcome.serverId}") }
			if (first) log("${address.url} answers as another Worbus Server: skipped, nothing sent")
		}
	}
}

/**
 * `welcome.server`: `{id, name, publicUrl?, version?}`, or null for an older hub or anything malformed.
 * [version] is the server's display version, `0.4.0 (abc1234)` (Worbus P4), null when the hub sends none.
 */
data class WelcomeServer(val id: String, val name: String, val publicUrl: String?, val version: String? = null)

fun welcomeServerOf(obj: kotlinx.serialization.json.JsonObject?): WelcomeServer? {
	if (obj == null) return null
	fun str(key: String) = (obj[key] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
	val id = str("id")?.takeIf { isServerId(it) } ?: return null
	return WelcomeServer(id, str("name").orEmpty(), str("publicUrl"), str("version")?.trim()?.ifEmpty { null })
}

/** No address of the record answered as this server: nothing was dialled (the status says "Can't reach"). */
class HubNoAddressAnswered : Exception("no address answered as this Worbus Server")
