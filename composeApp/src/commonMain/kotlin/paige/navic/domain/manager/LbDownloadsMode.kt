package paige.navic.domain.manager

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/*
 * Worbus P1 (N1): two facts about lb-bot that ride the hub's adverts, and the refusal that can
 * correct the first one.
 *
 * - **Whether lb-bot can download** (`downloads: "on" | "off"` on `welcome.lb`, the `lb` frame and
 *   `/lb/status`). lb-bot is "on" exactly when it has an slskd API key. A hub or an lb-bot older than
 *   the field sends none, and that is [LbDownloads.UNKNOWN], which every surface treats as on: today's
 *   behaviour. A 409 `{"code": "downloads_off"}` from any download route says "off" whatever the last
 *   advert said (an older hub in front of a newer lb-bot, a cached advert), and holds until an advert
 *   says otherwise ([downloadsAfterAdvert]).
 * - **How far lb-bot's index build has got** (`progress: {unindexed, total, current}` in
 *   `welcome.lbIndex` and the `index` frame). Absent, or malformed, is no bar.
 */

/** Whether lb-bot can download. [UNKNOWN] is an older peer and reads as [ON] everywhere ([isOn]). */
enum class LbDownloads {
	ON, OFF, UNKNOWN;

	val isOn: Boolean get() = this != OFF
}

/** lb-bot's index build: [unindexed] artists of [total] still to scan, [current] the one being scanned now. */
@Serializable
data class LbIndexProgress(val unindexed: Int, val total: Int, val current: String = "")

/** `welcome.lb`, an `lb` frame, as one value. [downloads] is [LbDownloads.UNKNOWN] when the hub said nothing. */
data class LbHubAdvert(
	val available: Boolean,
	val routes: List<String>,
	val webUrl: String,
	val downloads: LbDownloads,
	/** Worbus P2: `identity: true`, lb-bot reads who is asking (R17). Absent is false: an older lb-bot or hub. */
	val identity: Boolean = false
)

/** The wire word, or [LbDownloads.UNKNOWN] for anything else (absent included). */
fun lbDownloadsOf(value: String?): LbDownloads = when (value) {
	"on" -> LbDownloads.ON
	"off" -> LbDownloads.OFF
	else -> LbDownloads.UNKNOWN
}

/**
 * `{available, routes, webUrl, downloads?}` — `welcome.lb`, or an `lb` frame (the same object with its
 * `t`). Null when it carries no usable `available`, which applies nothing (as before P1).
 */
fun lbAdvertOf(lb: JsonObject): LbHubAdvert? {
	val available = (lb["available"] as? JsonPrimitive)?.booleanOrNull ?: return null
	val routes = (lb["routes"] as? JsonArray)
		?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
		?: emptyList()
	val webUrl = (lb["webUrl"] as? JsonPrimitive)?.contentOrNull.orEmpty()
	val downloads = (lb["downloads"] as? JsonPrimitive)?.takeIf { it.isString }?.content
	val identity = (lb["identity"] as? JsonPrimitive)?.booleanOrNull == true
	return LbHubAdvert(available, routes, webUrl, lbDownloadsOf(downloads), identity)
}

/**
 * What an advert does to the mode: a word replaces it, silence (an older hub) leaves it where it is — a
 * 409 `downloads_off` is not undone by an advert that cannot say. Except on a new connection
 * ([newConnection], a `welcome`): that hub, perhaps another one, says from scratch, and its silence is
 * [LbDownloads.UNKNOWN] (seen on the AVD: a hub swapped for an older one kept the last one's "off").
 */
fun downloadsAfterAdvert(current: LbDownloads, advert: LbDownloads, newConnection: Boolean = false): LbDownloads =
	if (newConnection || advert != LbDownloads.UNKNOWN) advert else current

/** The machine-readable refusal lb-bot sends from every download route while downloading is off. */
const val LB_CODE_DOWNLOADS_OFF = "downloads_off"

fun isDownloadsOffRefusal(error: LbBotManager.LbError): Boolean =
	error is LbBotManager.LbError.Rejected && error.status == 409 && error.code == LB_CODE_DOWNLOADS_OFF

/**
 * What a failed call does to the mode: only lb-bot's own `downloads_off` turns it off. The one rule
 * [LbDownloadsState.noteAnswer] applies to every non-2xx answer.
 */
fun downloadsAfterError(current: LbDownloads, error: LbBotManager.LbError): LbDownloads =
	if (isDownloadsOffRefusal(error)) LbDownloads.OFF else current

/** lb-bot's and the hub's refusal body: `{"error": "...", "code": "..."}`, either part optional. */
@Serializable
private data class LbRefusalBody(val error: String = "", val code: String = "")

/**
 * A non-2xx answer as an [LbBotManager.LbError]: 404 is a hub that does not proxy the route, 502 and 504
 * are lb-bot not answering in time, and anything else is a refusal in upstream's own words, its `code`
 * kept (P1: `downloads_off`, `signin_required`, …).
 */
fun lbFailureOf(status: Int, raw: String): LbBotManager.LbError {
	val body = try {
		lbWireJson.decodeFromString(LbRefusalBody.serializer(), raw)
	} catch (e: Exception) {
		LbRefusalBody()
	}
	return when (status) {
		404 -> LbBotManager.LbError.RouteUnknown
		502, 504 -> LbBotManager.LbError.Busy
		else -> LbBotManager.LbError.Rejected(status, body.error, body.code)
	}
}

/** [lbFailureOf] for an answer read raw: is it lb-bot's `downloads_off`? */
fun isDownloadsOffAnswer(status: Int, raw: String): Boolean =
	status == 409 && isDownloadsOffRefusal(lbFailureOf(status, raw))

/**
 * `progress` out of `welcome.lbIndex` or an `index` frame. Null when absent or malformed (a count that is
 * not a whole number, or below zero): no bar, never an error.
 */
fun lbIndexProgressOf(hint: JsonObject?): LbIndexProgress? {
	val progress = hint?.get("progress") as? JsonObject ?: return null
	val unindexed = (progress["unindexed"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull ?: return null
	val total = (progress["total"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull ?: return null
	if (unindexed < 0 || total < 0) return null
	val current = (progress["current"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
	return LbIndexProgress(unindexed, total, current)
}

/** `seq` and `epoch` of an index hint (`welcome.lbIndex`, an `index` frame); null where absent. */
fun lbIndexSeqOf(hint: JsonObject?): Pair<String?, Long?> =
	(hint?.get("epoch") as? JsonPrimitive)?.contentOrNull to
		(hint?.get("seq") as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull

/**
 * An `index` frame that only moved the build's progress: the same epoch and a seq the last hint already
 * had. lb-bot pushes one every few seconds while it builds, and the last frame with that seq already
 * asked for the pull (and the override re-read), so this one asks for neither.
 */
fun indexFrameIsProgressOnly(lastEpoch: String?, lastSeq: Long?, epoch: String?, seq: Long?, hasProgress: Boolean): Boolean =
	hasProgress && lastEpoch != null && lastSeq != null && seq != null && epoch == lastEpoch && seq <= lastSeq

/**
 * Where the last mode each hub reported is kept between launches: one string, written whole. In the
 * app a preference ([PreferenceManager.lbDownloadsByHub]); in tests a variable.
 */
interface LbDownloadsMemory {
	fun read(): String
	fun write(value: String)
}

private val modeMapSerializer = MapSerializer(String.serializer(), String.serializer())

/** The kept map (hub URL → "on" | "off"); anything unreadable is an empty map. */
internal fun lbDownloadsMapOf(text: String): Map<String, String> = if (text.isBlank()) emptyMap() else try {
	lbWireJson.decodeFromString(modeMapSerializer, text)
} catch (e: Exception) {
	emptyMap()
}

/** How many hubs' modes are kept: a phone talks to one hub, rarely two. */
private const val KEPT_HUBS = 4

/**
 * Worbus P1: lb-bot's downloads mode, one value for the whole app ([LbBotManager.downloads]).
 *
 * - Every advert passes [applyAdvert] (a `welcome` says from scratch, an `lb` frame or `/lb/status`
 *   that says nothing keeps what is known: [downloadsAfterAdvert]); a `welcome` with no usable `lb`
 *   at all passes [newConnection].
 * - Every non-2xx answer, whichever reader read it, passes [noteAnswer] ([failureOf] for the readers
 *   that turn it into an [LbBotManager.LbError]): lb-bot's 409 `downloads_off` turns it off.
 * - The last word for the hub [hubKey] names is kept in [memory] and is the value a new process
 *   starts from, so a cold start against a server that cannot download does not draw download
 *   actions until the welcome arrives. The welcome still overrides it. UNKNOWN is not kept: an older
 *   hub's silence starts the next launch at UNKNOWN too.
 */
class LbDownloadsState(
	private val memory: LbDownloadsMemory? = null,
	private val hubKey: () -> String = { "" }
) {
	private val _value = MutableStateFlow(remembered())
	val value: StateFlow<LbDownloads> = _value.asStateFlow()

	val current: LbDownloads get() = _value.value

	private fun remembered(): LbDownloads {
		val key = hubKey().trim()
		if (memory == null || key.isEmpty()) return LbDownloads.UNKNOWN
		return lbDownloadsOf(lbDownloadsMapOf(memory.read())[key])
	}

	/** [always]: a welcome's word is kept even when it is the value already held (another hub may be the one now). */
	private fun set(next: LbDownloads, always: Boolean = false) {
		if (next == _value.value && !always) return
		_value.value = next
		keep(next)
	}

	private fun keep(next: LbDownloads) {
		val memory = memory ?: return
		val key = hubKey().trim()
		if (key.isEmpty()) return
		val kept = lbDownloadsMapOf(memory.read())
		val word = when (next) {
			LbDownloads.ON -> "on"
			LbDownloads.OFF -> "off"
			LbDownloads.UNKNOWN -> null
		}
		if (kept[key] == word) return
		val updated = (kept - key).let { rest ->
			if (word == null) rest else (rest.entries.toList().takeLast(KEPT_HUBS - 1).associate { it.key to it.value } + (key to word))
		}
		try {
			memory.write(lbWireJson.encodeToString(modeMapSerializer, updated))
		} catch (e: Exception) {
			// A failed write costs the next cold start its head start, nothing else.
		}
	}

	/** An advert's word (`welcome.lb`, an `lb` frame, `/lb/status`): see [downloadsAfterAdvert]. */
	fun applyAdvert(advert: LbDownloads, newConnection: Boolean = false) =
		set(downloadsAfterAdvert(_value.value, advert, newConnection), always = newConnection)

	/** A `welcome` that carried no usable `lb` (an older hub): a new connection says nothing, UNKNOWN. */
	fun newConnection() = applyAdvert(LbDownloads.UNKNOWN, newConnection = true)

	/** Any non-2xx answer, read raw ([downloadsAfterError]). True when it was lb-bot's `downloads_off`. */
	fun noteAnswer(status: Int, raw: String): Boolean {
		if (status in 200..299) return false
		val error = lbFailureOf(status, raw)
		set(downloadsAfterError(_value.value, error))
		return isDownloadsOffRefusal(error)
	}

	/** [lbFailureOf], noted: the refusal as an error, and a `downloads_off` one turns the mode off. */
	fun failureOf(status: Int, raw: String): LbBotManager.LbError {
		val error = lbFailureOf(status, raw)
		set(downloadsAfterError(_value.value, error))
		return error
	}
}
