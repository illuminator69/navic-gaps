package paige.navic.domain.manager

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import paige.navic.util.Logger

/*
 * Worbus P3, lane N4 (design §6.4): finding the Worbus Server without typing. Only `GET /hub/info` is
 * ever sent, by [HubInfoProbe], and only to: Navidrome's host on port 4790; `hub.` and `worbus.` beside
 * a Navidrome name of three labels or more; and, on a tap only, this device's own /24 on port 4790.
 */

/** This device's own private IPv4 address on Wi-Fi or Ethernet, or null (Android: `LocalSubnet.android.kt`). */
fun interface LocalSubnet {
	fun ownPrivateIpv4(): String?
}

/** What looking found. */
sealed interface Found {
	/** A Worbus Server, by its id, at every candidate it answered (normalised). */
	data class Server(val serverId: String, val name: String, val addresses: List<String>) : Found

	/** An older Worbus Server (426) at Navidrome's host, port 4790, on a private host; offered by address. */
	data class OlderAt(val address: String) : Found

	/**
	 * A Worbus Server (or an older one, review I1) answering plain `http://` on a public host: not
	 * offered (design §6.3), so no tap sends the Navidrome token pair over the internet in plain text.
	 */
	data class NotEncrypted(val address: String) : Found
}

/** Where a look stands, for the Server screen's sentences (design §6.6). */
sealed interface FinderState {
	data object Idle : FinderState
	data object Looking : FinderState
	data class Results(val found: List<Found>) : FinderState
	data object NothingBeside : FinderState
	data object NothingOnNetwork : FinderState
	data object NeedsPermission : FinderState
	data object NotOnWifi : FinderState
}

/**
 * Design §6.4's "no server set": no address is typed or tapped (`hubUrl` blank), whatever the Connect
 * switch says (review M2: a typed address with Connect off is a set server, and the automatic look
 * would otherwise offer the sheet, whose Sign in starts the record over).
 */
fun hubAddressSet(preferenceManager: PreferenceManager): Boolean = preferenceManager.hubUrl.isNotBlank()

/** The port every candidate is asked on. */
const val HUB_PORT = 4790

/** The sweep's numbers (Global Constraints): 32 at a time, 700 ms each, 10 s in all. */
data class SweepLimits(val parallel: Int = 32, val perProbeMs: Long = 700, val capMs: Long = 10_000)

/**
 * The candidates beside Navidrome, in order: its host on port 4790 over `http://`, then
 * `https://hub.<parent>` and `https://worbus.<parent>` when the host is a DNS name of at least three
 * labels (`music.example.com` → `example.com`). Empty for an address with no host.
 */
fun candidatesFor(navidromeUrl: String): List<String> {
	val host = hostOfUrl(navidromeUrl.trim())?.lowercase()?.removeSuffix(".") ?: return emptyList()
	val hostPart = if (host.contains(':')) "[$host]" else host
	val first = "http://$hostPart:$HUB_PORT"
	val labels = host.split('.')
	val isName = !host.contains(':') && labels.size >= 3 && !labels.all { it.all(Char::isDigit) }
	if (!isName) return listOf(first)
	val parent = labels.drop(1).joinToString(".")
	return listOf(first, "https://hub.$parent", "https://worbus.$parent")
}

/** The 254 hosts of [ownIpv4]'s /24, the device's own included, as `http://a.b.c.n:4790`; empty for anything else. */
fun sweepTargets(ownIpv4: String): List<String> {
	val parts = ownIpv4.trim().split('.')
	if (parts.size != 4 || parts.any { p -> p.isEmpty() || p.length > 3 || !p.all(Char::isDigit) || p.toInt() > 255 }) return emptyList()
	val prefix = parts.take(3).joinToString(".")
	return (1..254).map { "http://$prefix.$it:$HUB_PORT" }
}

/**
 * Group what the candidates answered (design §6.4): one result per server id, with every address it
 * answered at; an older server (a 426, never a 404: review M4) only from the FIRST candidate
 * (Navidrome's host), and only when [olderCounts]; a Worbus or older answer over plain text on a
 * public host is [Found.NotEncrypted], never offered (review I1). Sweep results never carry the last
 * two ([olderCounts] false, and every sweep host is private).
 */
fun groupFound(answers: List<Pair<String, InfoOutcome>>, olderCounts: Boolean): List<Found> {
	val servers = linkedMapOf<String, Found.Server>()
	val others = mutableListOf<Found>()
	answers.forEachIndexed { i, (candidate, outcome) ->
		val address = normaliseHubAddress(candidate) ?: return@forEachIndexed
		when (outcome) {
			is InfoOutcome.Match -> {
				if (!address.startsWith("wss://") && !isPrivateHubAddress(address)) {
					others += Found.NotEncrypted(address)
				} else {
					val held = servers[outcome.serverId]
					servers[outcome.serverId] = held?.copy(addresses = (held.addresses + address).distinct())
						?: Found.Server(outcome.serverId, outcome.name, listOf(address))
				}
			}
			InfoOutcome.Older -> if (olderCounts && i == 0) {
				others += if (!address.startsWith("wss://") && !isPrivateHubAddress(address)) Found.NotEncrypted(address)
				else Found.OlderAt(address)
			}
			else -> Unit
		}
	}
	return servers.values.toList() + others
}

/** The one found server the post-sign-in sheet offers, or null (none, several, or answered Not now). */
fun promptFor(found: List<Found>, dismissed: List<String>): Found? {
	val offerable = found.filter { it is Found.Server || it is Found.OlderAt }
	val one = offerable.singleOrNull() ?: return null
	val key = promptKey(one) ?: return null
	return one.takeIf { key !in dismissed }
}

/** What Not now remembers: a server's id, an older server's address. */
fun promptKey(found: Found): String? = when (found) {
	is Found.Server -> found.serverId
	is Found.OlderAt -> found.address
	is Found.NotEncrypted -> null
}

/**
 * Looking for the Worbus Server (design §6.4).
 *
 * - [lookBeside]: the candidates beside Navidrome; automatically once after a login and on each Server
 *   screen opening while no server is set ([autoLook]).
 * - [sweep]: this device's /24, on a tap only, on Wi-Fi or Ethernet, with the local network permission.
 * - [findHomeAddress]: the sweep, keeping only the record's own server.
 * - [lookForHomeQuietly]: while a set server has no home address, Navidrome's host is asked on each
 *   reconnect over Wi-Fi or Ethernet, and a match with the same id is kept as found.
 */
class HubFinder(
	private val probe: HubInfoProbe,
	private val localSubnet: LocalSubnet,
	private val localNetworkGranted: () -> Boolean,
	private val navidromeUrl: () -> String,
	/** "An address is typed or tapped" (design §6.4: no server set means a blank address, review M2). */
	private val serverSet: () -> Boolean,
	private val store: HubServerRecordStore,
	private val limits: SweepLimits = SweepLimits(),
	/** A home address was kept: a reconnect that is waiting may go now (review M10). */
	private val onAddressAdded: () -> Unit = {},
	private val log: (String) -> Unit = { Logger.i("HubFinder", it) }
) {
	/** The app's: wired to the session, the hub and the platform. Its quiet look runs before each reconnect. */
	constructor(
		preferenceManager: PreferenceManager,
		sessionManager: SessionManager,
		hubManager: HubManager,
		localNetwork: LocalNetworkAccess,
		localSubnet: LocalSubnet
	) : this(
		probe = KtorHubInfoProbe(),
		localSubnet = localSubnet,
		localNetworkGranted = { localNetwork.granted() },
		navidromeUrl = { sessionManager.instanceUrl() },
		serverSet = { hubAddressSet(preferenceManager) },
		store = hubManager.serverRecordStore,
		onAddressAdded = { hubManager.kickReconnect("home address found") }
	) {
		hubManager.beforeReconnect = { lookForHomeQuietly() }
	}

	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

	private val _state = MutableStateFlow<FinderState>(FinderState.Idle)
	val state: StateFlow<FinderState> = _state.asStateFlow()

	private val _prompt = MutableStateFlow<Found?>(null)

	/** The one server the post-sign-in sheet offers, until it is answered ([answerPrompt]). */
	val prompt: StateFlow<Found?> = _prompt.asStateFlow()

	private var job: Job? = null

	/** Candidates 1 and 2 only. Results, or NothingBeside. */
	suspend fun lookBeside(): List<Found> {
		_state.value = FinderState.Looking
		val candidates = candidatesFor(navidromeUrl())
		var skippedForPermission = false
		val answers = candidates.mapNotNull { candidate ->
			val address = normaliseHubAddress(candidate) ?: return@mapNotNull null
			if (isPrivateHubAddress(address) && !localNetworkGranted()) {
				skippedForPermission = true
				return@mapNotNull candidate to InfoOutcome.Unreachable
			}
			candidate to probe.probe(address, probeTimeoutMs(address))
		}
		val found = groupFound(answers, olderCounts = true)
		_state.value = when {
			found.isNotEmpty() -> FinderState.Results(found)
			skippedForPermission -> FinderState.NeedsPermission
			else -> FinderState.NothingBeside
		}
		log("looked beside Navidrome: ${candidates.size} candidates, ${found.size} found")
		return found
	}

	/** Candidate 3, on a tap: the own /24 (Global Constraints' numbers). Results, NothingOnNetwork, NotOnWifi or NeedsPermission. */
	suspend fun sweep(): List<Found> {
		val own = localSubnet.ownPrivateIpv4()?.takeIf { classifyHubAddress("ws://$it") == AddressClass.Private }
		if (own == null) {
			_state.value = FinderState.NotOnWifi
			return emptyList()
		}
		if (!localNetworkGranted()) {
			_state.value = FinderState.NeedsPermission
			return emptyList()
		}
		_state.value = FinderState.Looking
		val targets = sweepTargets(own)
		val answers = arrayOfNulls<InfoOutcome>(targets.size)
		val gate = Semaphore(limits.parallel)
		withTimeoutOrNull(limits.capMs) {
			coroutineScope {
				targets.mapIndexed { i, target ->
					async {
						gate.withPermit {
							val address = normaliseHubAddress(target)!!
							answers[i] = withTimeoutOrNull(limits.perProbeMs) { probe.probe(address, limits.perProbeMs) }
								?: InfoOutcome.Unreachable
						}
					}
				}.awaitAll()
			}
		}
		val found = groupFound(targets.indices.mapNotNull { i -> answers[i]?.let { targets[i] to it } }, olderCounts = false)
			.filterIsInstance<Found.Server>()
		_state.value = if (found.isEmpty()) FinderState.NothingOnNetwork else FinderState.Results(found)
		log("swept ${targets.size} hosts of the own /24: ${found.size} found")
		return found
	}

	/** The sweep, keeping only [serverId]'s answer; its first address is kept as found. */
	suspend fun findHomeAddress(serverId: String): String? {
		val mine = sweep().filterIsInstance<Found.Server>().firstOrNull { it.serverId == serverId }
		val address = mine?.addresses?.firstOrNull()
		if (address != null) {
			store.update { it.withAddress(address, HubAddress.Source.Found) }
			onAddressAdded()
		}
		if (mine == null && _state.value is FinderState.Results) _state.value = FinderState.NothingOnNetwork
		return address
	}

	/**
	 * Design §6.4's automatic look: candidates 1 and 2, only while no server is set. [afterLogin] also
	 * offers the one server found (not answered Not now before) to the sheet.
	 */
	suspend fun autoLook(afterLogin: Boolean = false): List<Found> {
		if (serverSet()) return emptyList()
		val found = lookBeside()
		if (afterLogin) _prompt.value = promptFor(found, store.read().promptDismissed)
		return found
	}

	/** Where the last quiet look asked (own address, candidate, server id): asked once per network (review M12). */
	@kotlin.concurrent.Volatile
	private var quietLookedAt: String? = null

	/**
	 * Before a reconnect (design §6.4): a set server with an id and no home address, on Wi-Fi or Ethernet,
	 * asks Navidrome's host on port 4790. The same id there is kept as found; nothing else is. Once per
	 * network (this device's own address), not before every attempt: a Wi-Fi without the server would
	 * otherwise add up to 1.5 s to each reconnect (review M12).
	 */
	suspend fun lookForHomeQuietly() {
		if (!serverSet()) return
		val record = store.read()
		val id = record.serverId ?: return
		if (record.hasPrivateAddress) return
		val own = localSubnet.ownPrivateIpv4() ?: return
		if (!localNetworkGranted()) return
		val candidate = candidatesFor(navidromeUrl()).firstOrNull()?.let { normaliseHubAddress(it) } ?: return
		if (!isPrivateHubAddress(candidate)) return
		val at = "$own|$candidate|$id"
		if (quietLookedAt == at) return
		quietLookedAt = at
		val outcome = probe.probe(candidate, probeTimeoutMs(candidate))
		if (outcome is InfoOutcome.Match && outcome.serverId == id) {
			store.update { it.withAddress(candidate, HubAddress.Source.Found) }
			log("found the home address beside Navidrome")
		}
	}

	/** Not now (or Sign in, which also ends the offer): the sheet is not offered again for it. */
	fun answerPrompt(dismissed: Boolean) {
		val found = _prompt.value ?: return
		if (dismissed) promptKey(found)?.let { key -> store.update { it.dismissPrompt(key) } }
		_prompt.value = null
	}

	// Launchers for the screens, on this finder's scope: one look at a time, a new one replaces it.
	fun startAutoLook(afterLogin: Boolean = false) = launchLook { autoLook(afterLogin) }
	fun startSweep() = launchLook { sweep() }
	fun startFindHomeAddress(serverId: String) = launchLook { findHomeAddress(serverId) }

	/** A tap on Sign in during a look stops it (design §7's RACES). */
	fun stopLooking() {
		job?.cancel()
		if (_state.value == FinderState.Looking) _state.value = FinderState.Idle
	}

	/**
	 * Review M3: what an earlier look said goes (a login's look beside Navidrome, an old sweep), so the
	 * Find home address row never shows a sentence about a look it did not run. A look under way is
	 * left alone. Called when the Server screen opens with a server set, and after a sign-in tap.
	 */
	fun forgetResults() {
		if (_state.value != FinderState.Looking) _state.value = FinderState.Idle
	}

	private fun launchLook(block: suspend () -> Unit) {
		job?.cancel()
		job = scope.launch { block() }
	}
}
