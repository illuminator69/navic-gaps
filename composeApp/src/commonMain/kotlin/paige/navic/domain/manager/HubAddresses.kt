package paige.navic.domain.manager

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import paige.navic.di.synchronized
import paige.navic.util.Logger

/*
 * Worbus P3, lane N1 (design §6.3, PROTOCOL §3.4): the Worbus Server's addresses. One record per
 * server, every address normalised, each with where it came from; a probe (`GET <base>/hub/info`)
 * that asks only who answers; and the one rule that decides which address may carry this device's
 * credential. Nothing here sends a credential: a probe carries no header, no query and no body.
 */

// ---- Normalising ---------------------------------------------------------------------------- //

/**
 * The ONE normaliser (PROTOCOL §3.4): trim; no scheme becomes `ws://`; `http://` becomes `ws://`
 * and `https://` becomes `wss://`; trailing `/` are stripped, then a trailing `/connect`, then
 * trailing `/` again (the controller's ruling: `…/connect/` is the socket's path too, and kept, its
 * `/connect/hub/info` would get the socket's 426 and read a P3 hub as older for good). The path is
 * kept; a `?query`, a `#fragment` and `user:pass@` are dropped. Scheme and host are lower-cased.
 * Null for a blank address, one with no host, whitespace inside, or any other scheme.
 */
fun normaliseHubAddress(raw: String): String? {
	val text = raw.trim()
	if (text.isEmpty() || text.any { it.isWhitespace() }) return null
	val schemeEnd = text.indexOf("://")
	val (scheme, rest) = if (schemeEnd < 0) "ws" to text else text.substring(0, schemeEnd).lowercase() to text.substring(schemeEnd + 3)
	val wsScheme = when (scheme) {
		"ws", "http" -> "ws"
		"wss", "https" -> "wss"
		else -> return null
	}
	// Review M1: a `?query` or `#fragment` is dropped (it would end up inside `<base>/hub/info`), and
	// `user:pass@` is stripped, so no password is ever kept in the record or written to a log line.
	val cut = rest.indexOfFirst { it == '?' || it == '#' }
	val bare = if (cut < 0) rest else rest.substring(0, cut)
	val slash = bare.indexOf('/')
	val authority = (if (slash < 0) bare else bare.substring(0, slash)).lowercase().substringAfterLast('@')
	val path = if (slash < 0) "" else bare.substring(slash)
	if (hostOfAuthority(authority).isNullOrEmpty()) return null
	val trimmedPath = path.trimEnd('/').removeSuffix("/connect").trimEnd('/')
	return "$wsScheme://$authority$trimmedPath"
}

/** The host of `user@host:port` (IPv6 without its brackets), or null. */
private fun hostOfAuthority(authority: String): String? {
	val hostPort = authority.substringAfterLast('@')
	if (hostPort.startsWith("[")) {
		val end = hostPort.indexOf(']')
		if (end < 0) return null
		return hostPort.substring(1, end)
	}
	return hostPort.substringBefore(':').takeIf { it.isNotEmpty() }
}

/** The host of a (normalised or not) address, or null. */
fun hubAddressHost(url: String): String? {
	val normalised = normaliseHubAddress(url) ?: return null
	val rest = normalised.substringAfter("://")
	return hostOfAuthority(rest.substringBefore('/'))?.removeSuffix(".")
}

/** `ws://` or `wss://` → `http://` or `https://`, path kept. Null when it does not normalise. */
fun httpBaseOfAddress(url: String): String? {
	val normalised = normaliseHubAddress(url) ?: return null
	return when {
		normalised.startsWith("wss://") -> "https://" + normalised.removePrefix("wss://")
		else -> "http://" + normalised.removePrefix("ws://")
	}
}

// ---- The private test ------------------------------------------------------------------------ //

enum class AddressClass { Private, Tailscale, Public }

/**
 * Global Constraints' private test, on the normalised address's host. Private: IPv4 127/8, 10/8,
 * 172.16/12, 192.168/16, 169.254/16; IPv6 `::1`, fc00::/7, fe80::/10; `localhost` and `*.local`.
 * Tailscale (also safe for plain text): 100.64/10 and `*.ts.net`. Everything else is public, any
 * other DNS name included. It is a spelling test: nothing is resolved. Not [isPrivateHost], which
 * answers Android's permission question (a single-label name is private there, loopback is not).
 */
fun classifyHubAddress(url: String): AddressClass {
	val host = hubAddressHost(url)?.lowercase() ?: return AddressClass.Public
	if (host == "localhost" || host.endsWith(".local")) return AddressClass.Private
	if (host.endsWith(".ts.net")) return AddressClass.Tailscale
	ipv4Of(host)?.let { o ->
		return when {
			o[0] == 127 || o[0] == 10 -> AddressClass.Private
			o[0] == 172 && o[1] in 16..31 -> AddressClass.Private
			o[0] == 192 && o[1] == 168 -> AddressClass.Private
			o[0] == 169 && o[1] == 254 -> AddressClass.Private
			o[0] == 100 && o[1] in 64..127 -> AddressClass.Tailscale
			else -> AddressClass.Public
		}
	}
	if (host.contains(':')) {
		val bare = host.substringBefore('%')
		if (bare == "::1" || bare == "0:0:0:0:0:0:0:1") return AddressClass.Private
		if (bare.startsWith("::")) return AddressClass.Public
		val first = bare.substringBefore(':').takeIf { it.length in 1..4 }?.toIntOrNull(16) ?: return AddressClass.Public
		if ((first and 0xFE00) == 0xFC00 || (first and 0xFFC0) == 0xFE80) return AddressClass.Private
	}
	return AddressClass.Public
}

/** Private or Tailscale: where plain `ws://` may carry a credential. */
fun isPrivateHubAddress(url: String): Boolean = classifyHubAddress(url) != AddressClass.Public

private fun ipv4Of(host: String): List<Int>? {
	val parts = host.split('.')
	if (parts.size != 4) return null
	val octets = parts.map { p -> if (p.isEmpty() || p.length > 3 || !p.all(Char::isDigit)) return null else p.toInt() }
	return octets.takeIf { o -> o.all { it in 0..255 } }
}

// ---- The record ------------------------------------------------------------------------------ //

/** One address of the server, normalised, with where it came from and when it last passed. */
data class HubAddress(val url: String, val source: Source, val lastOkAt: Long?) {
	/** `typed` and `found` are the person's; `learned` came from an authenticated `welcome.server`. */
	enum class Source(val wire: String) { Typed("typed"), Found("found"), Learned("learned") }
}

/**
 * The client's record of its Worbus Server (Global Constraints' JSON, verbatim keys). [serverId] is
 * null until the server has said who it is. [learnedOff]: the person switched "Use away from home"
 * off; the learned entry stays listed (so the switch shows) and is never dialled. [promptDismissed]:
 * the server ids (or, for an older server, the addresses) whose post-sign-in sheet was answered
 * Not now. `lastOkAt` means "last passed while the record held its current id".
 */
data class HubServerRecord(
	val serverId: String? = null,
	val name: String? = null,
	val addresses: List<HubAddress> = emptyList(),
	val learnedOff: Boolean = false,
	val promptDismissed: List<String> = emptyList()
) {
	fun toJson(): String = buildJsonObject {
		if (serverId != null) put("serverId", serverId) else put("serverId", JsonNull)
		if (name != null) put("name", name) else put("name", JsonNull)
		putJsonArray("addresses") {
			addresses.forEach { a ->
				addJsonObject {
					put("url", a.url)
					put("source", a.source.wire)
					val ok = a.lastOkAt
					if (ok != null) put("lastOkAt", ok) else put("lastOkAt", JsonNull)
				}
			}
		}
		put("learnedOff", learnedOff)
		putJsonArray("promptDismissed") { promptDismissed.forEach { add(it) } }
	}.toString()

	/** The address (normalised) of [url] in this record, or null. */
	fun find(url: String): HubAddress? {
		val n = normaliseHubAddress(url) ?: return null
		return addresses.firstOrNull { it.url == n }
	}

	/** True while some typed or found address is private (design §6.4's "home address"). */
	val hasPrivateAddress: Boolean
		get() = addresses.any { it.source != HubAddress.Source.Learned && isPrivateHubAddress(it.url) }

	/** The learned entry, if any. */
	val learned: HubAddress? get() = addresses.firstOrNull { it.source == HubAddress.Source.Learned }

	/**
	 * The order every (re)connect walks (design §6.3): private and Tailscale first, then public;
	 * within each, `lastOkAt` newest first (never-passed last, listed order otherwise). A learned
	 * address is left out while [learnedOff], and one that is not `wss://` never takes part.
	 */
	fun connectOrder(): List<HubAddress> {
		val usable = addresses.filter { a ->
			if (a.source != HubAddress.Source.Learned) true
			else !learnedOff && a.url.startsWith("wss://")
		}
		val byRecency = compareByDescending<HubAddress> { it.lastOkAt ?: Long.MIN_VALUE }
		val (home, away) = usable.partition { isPrivateHubAddress(it.url) }
		return home.sortedWith(byRecency) + away.sortedWith(byRecency)
	}

	/**
	 * A `welcome.server` (design §6.2): fills the id and name, and adds, replaces or removes the one
	 * learned entry to match [publicUrl]. Only a `wss://` address is ever added. A different id than
	 * the one held is a different server: its learned address goes, every `lastOkAt` is cleared
	 * (none passed with this id) and [learnedOff] starts over; typed and found addresses stay (a probe
	 * then skips the other server's, never removing them).
	 */
	fun applyWelcomeServer(id: String, name: String, publicUrl: String?): HubServerRecord {
		val base = if (serverId != null && serverId != id) {
			copy(
				addresses = addresses.filter { it.source != HubAddress.Source.Learned }.map { it.copy(lastOkAt = null) },
				learnedOff = false
			)
		} else this
		val wanted = publicUrl?.let { normaliseHubAddress(it) }?.takeIf { it.startsWith("wss://") }
		val current = base.learned
		val others = base.addresses.filter { it.source != HubAddress.Source.Learned }
		val next = when {
			wanted == null -> others
			others.any { it.url == wanted } -> others
			current != null && current.url == wanted -> base.addresses
			else -> others + HubAddress(wanted, HubAddress.Source.Learned, null)
		}
		return base.copy(serverId = id, name = name.ifBlank { base.name }, addresses = next)
	}

	/** [url] passed (with this record's id, or with none held): stamp it. */
	fun markOk(url: String, at: Long): HubServerRecord {
		val n = normaliseHubAddress(url) ?: return this
		return copy(addresses = addresses.map { if (it.url == n) it.copy(lastOkAt = at) else it })
	}

	/** Add [url] as [source] (normalised); an address already held keeps its entry. */
	fun withAddress(url: String, source: HubAddress.Source): HubServerRecord {
		val n = normaliseHubAddress(url) ?: return this
		if (addresses.any { it.url == n }) return this
		if (source == HubAddress.Source.Learned && !n.startsWith("wss://")) return this
		// A found plain-text address on a public host is never offered, so never kept (design §6.3).
		if (source == HubAddress.Source.Found && !n.startsWith("wss://") && !isPrivateHubAddress(n)) return this
		return copy(addresses = addresses + HubAddress(n, source, null))
	}

	/** Remove a typed or found [url]. A learned one is switched off instead ([learnedOff]). */
	fun without(url: String): HubServerRecord {
		val n = normaliseHubAddress(url) ?: return this
		return copy(addresses = addresses.filterNot { it.url == n && it.source != HubAddress.Source.Learned })
	}

	fun dismissPrompt(key: String): HubServerRecord =
		if (key in promptDismissed) this else copy(promptDismissed = promptDismissed + key)

	companion object {
		/**
		 * Today's typed address becomes the one `typed` entry; a blank one (never configured) gives an
		 * empty record, so looking runs (design §6.3).
		 */
		fun migrateFrom(hubUrl: String): HubServerRecord {
			val n = normaliseHubAddress(hubUrl) ?: return HubServerRecord()
			return HubServerRecord(addresses = listOf(HubAddress(n, HubAddress.Source.Typed, null)))
		}

		private val json = Json { ignoreUnknownKeys = true; isLenient = true }

		/** The stored JSON, or null when it is blank or does not read. Unknown sources are dropped. */
		fun fromJson(text: String): HubServerRecord? {
			if (text.isBlank()) return null
			val obj = try {
				json.parseToJsonElement(text).jsonObject
			} catch (e: Exception) {
				return null
			}
			fun str(o: JsonObject, key: String) = (o[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
			val addresses = (obj["addresses"] as? JsonArray).orEmpty().mapNotNull { el ->
				val o = el as? JsonObject ?: return@mapNotNull null
				val url = str(o, "url")?.let { normaliseHubAddress(it) } ?: return@mapNotNull null
				val source = HubAddress.Source.entries.firstOrNull { it.wire == str(o, "source") } ?: return@mapNotNull null
				if (source == HubAddress.Source.Learned && !url.startsWith("wss://")) return@mapNotNull null
				HubAddress(url, source, (o["lastOkAt"] as? JsonPrimitive)?.longOrNull)
			}.distinctBy { it.url }
			return HubServerRecord(
				serverId = str(obj, "serverId")?.takeIf { isServerId(it) },
				name = str(obj, "name"),
				addresses = addresses,
				learnedOff = (obj["learnedOff"] as? JsonPrimitive)?.booleanOrNull ?: false,
				promptDismissed = (obj["promptDismissed"] as? JsonArray).orEmpty()
					.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
			)
		}
	}
}

/** 32 lower-case hex characters (`secrets.token_hex(16)`). */
fun isServerId(text: String): Boolean = text.length == 32 && text.all { it in '0'..'9' || it in 'a'..'f' }

/**
 * The record as stored ([PreferenceManager.hubServerRecord]) kept in step with the legacy key: read
 * once and migrated from `hubUrl` when nothing is stored; a `hubUrl` the record does not hold (set by
 * a rolled-back build) joins it as typed, or, when the record already knows its server, starts it
 * over (review M9: the person's latest choice wins; the rolled-back build has already said hello
 * there). `hubUrl` itself is written only inside [update] and [startOver], under the same lock.
 *
 * One per [PreferenceManager] ([PreferenceManager.serverRecordStore], review M5); every
 * read-modify-write holds one process-wide lock, so a welcome, a quiet look and an edit on the
 * Server screen cannot lose one another's change. [onServerChange] hears every server change: the
 * record's id cleared or replaced, or a record started over for a new server ([startOver]).
 */
class HubServerRecordStore(private val preferenceManager: PreferenceManager) {
	/** The last record read, with the two stored strings it was read from (review M14: no parse per read). */
	@kotlin.concurrent.Volatile
	private var cached: Triple<String, String, HubServerRecord>? = null

	/** A server change that happened before anyone listened; handed to the first listener. */
	@kotlin.concurrent.Volatile
	private var pendingChange: Pair<HubServerRecord, HubServerRecord>? = null

	/**
	 * Controller ruling C1: called (outside the lock) with the record before and after, whenever the
	 * server this device may hold a credential for is no longer the record's. Set by [HubConnector.wire].
	 */
	@kotlin.concurrent.Volatile
	var onServerChange: ((from: HubServerRecord, to: HubServerRecord) -> Unit)? = null
		set(value) {
			field = value
			val pending = pendingChange
			if (value != null && pending != null) {
				pendingChange = null
				value(pending.first, pending.second)
			}
		}

	fun read(): HubServerRecord {
		var change: Pair<HubServerRecord, HubServerRecord>? = null
		val record = synchronized(LOCK) {
			val (current, synced) = currentLocked()
			change = synced
			current
		}
		change?.let { notify(it) }
		return record
	}

	fun write(record: HubServerRecord) {
		val changes = synchronized(LOCK) {
			val (current, synced) = currentLocked()
			listOfNotNull(synced, writeLocked(current, record))
		}
		changes.forEach { notify(it) }
	}

	/** Read, change and write as one step. [change] may also write `hubUrl`: it runs under the lock. */
	fun update(change: (HubServerRecord) -> HubServerRecord): HubServerRecord {
		var next: HubServerRecord? = null
		val changes = synchronized(LOCK) {
			val (current, synced) = currentLocked()
			val changed = change(current)
			next = changed
			listOfNotNull(synced, writeLocked(current, changed))
		}
		changes.forEach { notify(it) }
		return next!!
	}

	/**
	 * A new server (a tapped found one, "Type an address" while none is set): [record] replaces the
	 * stored one and `hubUrl` becomes [hubUrl], as one step. Always a server change (C1), whatever the
	 * old record held: the device's secret was issued by whatever server came before.
	 */
	fun startOver(record: HubServerRecord, hubUrl: String) {
		val changes = synchronized(LOCK) {
			val (current, synced) = currentLocked()
			preferenceManager.hubUrl = hubUrl
			writeLocked(current, record)
			listOfNotNull(synced, current to record)
		}
		changes.forEach { notify(it) }
	}

	/** The stored record, migrated or synced and written back if needed; the change that made, if any. */
	private fun currentLocked(): Pair<HubServerRecord, Pair<HubServerRecord, HubServerRecord>?> {
		val text = preferenceManager.hubServerRecord
		val legacy = preferenceManager.hubUrl
		cached?.let { (t, l, r) -> if (t == text && l == legacy) return r to null }
		val stored = HubServerRecord.fromJson(text)
		val synced = when {
			stored == null -> HubServerRecord.migrateFrom(legacy)
			legacy.isBlank() || normaliseHubAddress(legacy) == null || stored.find(legacy) != null -> stored
			stored.serverId != null -> HubServerRecord(promptDismissed = stored.promptDismissed).withAddress(legacy, HubAddress.Source.Typed)
			else -> stored.withAddress(legacy, HubAddress.Source.Typed)
		}
		var change: Pair<HubServerRecord, HubServerRecord>? = null
		if (stored == null || synced != stored) change = writeLocked(stored, synced)
		else cached = Triple(text, legacy, synced)
		return synced to change
	}

	/** Write [next]; the server change it is ([before]'s id cleared or replaced), if any. */
	private fun writeLocked(before: HubServerRecord?, next: HubServerRecord): Pair<HubServerRecord, HubServerRecord>? {
		val text = next.toJson()
		preferenceManager.hubServerRecord = text
		// The next read parses it once and checks it against `hubUrl` again.
		cached = null
		val was = before?.serverId ?: return null
		return if (next.serverId != was) before to next else null
	}

	private fun notify(change: Pair<HubServerRecord, HubServerRecord>) {
		val listener = onServerChange
		if (listener == null) pendingChange = change else listener(change.first, change.second)
	}

	private companion object {
		/** One for every store of the process (tests make their own stores over one preference set). */
		val LOCK = Any()
	}
}

// ---- Probing --------------------------------------------------------------------------------- //

/** What `GET <base>/hub/info` said (Global Constraints). */
sealed interface InfoOutcome {
	/** `service: "worbus"` with a well-formed id. */
	data class Match(val serverId: String, val name: String) : InfoOutcome

	/** A Worbus Server, but not the one the record holds ([judgedAgainst]). */
	data class OtherServer(val serverId: String) : InfoOutcome

	/** 426: a project 2 hub's handshake answer (the only answer the finder offers as an older server). */
	data object Older : InfoOutcome

	/**
	 * 404: a proxy that passes only upgrades, or any HTTP server on that port (review M4). The connect
	 * rule treats it as [Older]; the finder never offers it.
	 */
	data object NoInfo : InfoOutcome

	/** Any other answer. */
	data object NotWorbus : InfoOutcome

	/** No answer: refused, timed out, a name that did not resolve. */
	data object Unreachable : InfoOutcome
}

/** How `/hub/info` is asked. The app's is [KtorHubInfoProbe]; it sends nothing but the GET. */
fun interface HubInfoProbe {
	suspend fun probe(base: String, timeoutMs: Long): InfoOutcome
}

const val PROBE_TIMEOUT_PRIVATE_MS = 1_500L
const val PROBE_TIMEOUT_PUBLIC_MS = 5_000L

/** 1500 ms for a private (or Tailscale) address, 5000 ms for any other. */
fun probeTimeoutMs(url: String): Long =
	if (isPrivateHubAddress(url)) PROBE_TIMEOUT_PRIVATE_MS else PROBE_TIMEOUT_PUBLIC_MS

/** `<http base>/hub/info`, or null for an address that does not normalise. */
fun hubInfoUrl(base: String): String? = httpBaseOfAddress(base)?.let { "$it/hub/info" }

private val infoJson = Json { ignoreUnknownKeys = true; isLenient = true }

/** An answer read: the status and the body. A Worbus answer is a Match whatever its id ([judgedAgainst]). */
fun infoOutcomeOf(status: Int, body: String): InfoOutcome {
	if (status == 426) return InfoOutcome.Older
	if (status == 404) return InfoOutcome.NoInfo
	if (status != 200) return InfoOutcome.NotWorbus
	val obj = try {
		infoJson.parseToJsonElement(body).jsonObject
	} catch (e: Exception) {
		return InfoOutcome.NotWorbus
	}
	fun str(key: String) = (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
	if (str("service") != "worbus") return InfoOutcome.NotWorbus
	val id = str("serverId")?.takeIf { isServerId(it) } ?: return InfoOutcome.NotWorbus
	return InfoOutcome.Match(id, str("name").orEmpty())
}

/** A Match for another id than the record's is [InfoOutcome.OtherServer]; everything else as it is. */
fun InfoOutcome.judgedAgainst(record: HubServerRecord): InfoOutcome = when {
	this is InfoOutcome.Match && record.serverId != null && serverId != record.serverId -> InfoOutcome.OtherServer(serverId)
	else -> this
}

/** The probes' one client (review M14: not one per probe), made on first use. */
private val sharedProbeClient: HttpClient by lazy {
	HttpClient {
		install(UserAgent) { agent = "Navic" }
		install(HttpTimeout)
		expectSuccess = false
		followRedirects = false
	}
}

/** The app's probe: one GET, no header but the user agent, no query, no body; a bearer never. */
class KtorHubInfoProbe(
	private val client: HttpClient = sharedProbeClient
) : HubInfoProbe {
	override suspend fun probe(base: String, timeoutMs: Long): InfoOutcome {
		val url = hubInfoUrl(base) ?: return InfoOutcome.NotWorbus
		return try {
			val response = client.get(url) {
				timeout {
					connectTimeoutMillis = timeoutMs
					requestTimeoutMillis = timeoutMs
					socketTimeoutMillis = timeoutMs
				}
			}
			infoOutcomeOf(response.status.value, response.bodyAsText())
		} catch (e: CancellationException) {
			throw e
		} catch (e: Exception) {
			InfoOutcome.Unreachable
		}
	}
}

// ---- Which address may be dialled ------------------------------------------------------------ //

/** One address's verdict ([dialVerdict]). */
sealed interface DialVerdict {
	/** Dial it. [checked]: `/hub/info` matched; false for the older-server and rollback fallbacks. */
	data class Dial(val checked: Boolean) : DialVerdict

	/** Not this one; [outcome] says why. */
	data class Skip(val outcome: InfoOutcome) : DialVerdict
}

/**
 * The rule every connect applies to each address in [HubServerRecord.connectOrder] (design §6.3):
 * - a Match passes while the record holds no id or this same id; another id is skipped, never removed;
 * - Older or NoInfo (426, 404) while the record holds no id: a typed or found address is dialled as
 *   before (failover starts only once the server has said who it is);
 * - Older or NoInfo once the id is known (a hub rolled back, a proxy that passes only upgrades): a
 *   typed or found address that last passed with this id is dialled without the check; a learned
 *   one never;
 * - NotWorbus (any other answer) while the record holds no id: a TYPED address is dialled as before
 *   P3 (review I3, the controller's parity rule 6: a project 2 hub behind a proxy that answers
 *   unknown paths with a page, a 403 or a 502); a found or learned one never, and never once an id
 *   is known;
 * - Unreachable is skipped.
 */
fun HubServerRecord.dialVerdict(address: HubAddress, outcome: InfoOutcome): DialVerdict =
	when (val judged = outcome.judgedAgainst(this)) {
		is InfoOutcome.Match -> DialVerdict.Dial(checked = true)
		InfoOutcome.Older, InfoOutcome.NoInfo -> when {
			address.source == HubAddress.Source.Learned -> DialVerdict.Skip(judged)
			serverId == null -> DialVerdict.Dial(checked = false)
			address.lastOkAt != null -> DialVerdict.Dial(checked = false)
			else -> DialVerdict.Skip(judged)
		}
		InfoOutcome.NotWorbus ->
			if (serverId == null && address.source == HubAddress.Source.Typed) DialVerdict.Dial(checked = false)
			else DialVerdict.Skip(judged)
		else -> DialVerdict.Skip(judged)
	}

/** The address a connect dials, and what its probe said. */
data class ChosenAddress(val address: HubAddress, val outcome: InfoOutcome)

/**
 * Walk [HubServerRecord.connectOrder], probe each address in turn and return the first that passes
 * [dialVerdict], or null when none does. [onSkip] hears every address passed over (the caller logs an
 * OtherServer once). Only the probe is sent; the caller dials, with the credential, the address
 * returned and nothing else.
 */
suspend fun HubServerRecord.chooseAddress(
	probe: HubInfoProbe,
	onSkip: (HubAddress, InfoOutcome) -> Unit = { _, _ -> }
): ChosenAddress? {
	for (address in connectOrder()) {
		val outcome = probe.probe(address.url, probeTimeoutMs(address.url))
		when (val verdict = dialVerdict(address, outcome)) {
			is DialVerdict.Dial -> return ChosenAddress(address, outcome)
			is DialVerdict.Skip -> onSkip(address, verdict.outcome)
		}
	}
	return null
}

internal fun logSkippedAddress(address: HubAddress, outcome: InfoOutcome) {
	Logger.i("HubAddresses", "skipped ${address.url} (${address.source.wire}): ${outcome::class.simpleName}")
}

// ---- Per-server keys (lane N3, design §6.5) -------------------------------------------------- //

/** `id:<serverId>`, the key every per-server value is kept under once the server has said who it is. */
fun serverKey(id: String): String = "id:$id"

/**
 * The key this server's values are kept under: `id:<serverId>` once known, else [address] (an older
 * server, or before the first welcome), exactly the key such values were kept under before P3.
 */
fun serverKey(record: HubServerRecord, address: String): String =
	record.serverId?.let { serverKey(it) } ?: address

/** [serverKey] for the stored record and the typed address (`hubUrl`, trimmed, as before P3). */
fun perServerKey(preferenceManager: PreferenceManager): String =
	serverKey(preferenceManager.serverRecordStore.read(), preferenceManager.hubUrl.trim())

/**
 * The first `welcome` carrying the server's id (design §6.5): every value kept under one of
 * [addresses] (compared normalised) is copied to `id:<id>`, unless the id already has its own. The
 * address entries stay: a client rolled back to project 2 still finds them. Nothing is wiped.
 * `hubDeviceIds` is keyed `<address>|<account>`, `lbDownloadsByHub` `<address>`.
 */
fun rekeyStoredPerServer(preferenceManager: PreferenceManager, addresses: Set<String>, id: String) {
	val wanted = addresses.mapNotNull { normaliseHubAddress(it) }.toSet()
	if (wanted.isEmpty()) return
	fun matches(address: String) = normaliseHubAddress(address)?.let { it in wanted } == true
	val idKey = serverKey(id)
	preferenceManager.hubDeviceIds = rekeyJsonMap(preferenceManager.hubDeviceIds) { key ->
		val cut = key.lastIndexOf('|')
		if (cut <= 0 || !matches(key.substring(0, cut))) null else "$idKey${key.substring(cut)}"
	}
	preferenceManager.lbDownloadsByHub = rekeyJsonMap(preferenceManager.lbDownloadsByHub) { key ->
		if (matches(key)) idKey else null
	}
}

/** Copy each string entry whose key [newKeyOf] maps to a key not yet present. The text as it was if nothing moves. */
private fun rekeyJsonMap(text: String, newKeyOf: (String) -> String?): String {
	if (text.isBlank()) return text
	val obj = try {
		Json.parseToJsonElement(text) as? JsonObject
	} catch (e: Exception) {
		null
	} ?: return text
	val added = linkedMapOf<String, String>()
	for ((key, value) in obj) {
		val target = newKeyOf(key) ?: continue
		val v = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: continue
		if (target !in obj && target !in added) added[target] = v
	}
	if (added.isEmpty()) return text
	return buildJsonObject {
		for ((key, value) in obj) put(key, value)
		for ((key, value) in added) put(key, value)
	}.toString()
}
