package paige.navic.domain.manager

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import paige.navic.util.Logger

/**
 * The hub's moods, from this device (navi-connect PROTOCOL §18.5, P7): the catalogue Home's chips
 * draw, and a tile's ranked song ids. What to do with the ids (resolve in Room, the length limit, the
 * draw) is `MoodModels.kt`'s and the page's.
 *
 * **Hub only**, like `StationManager`: the base is `hubHttpBase`, so a hub switched off here means no
 * request and no chips. The catalogue is cached ([PreferenceManager.moodCatalogue]) and shown at once
 * on a cold start, then replaced by the hub's answer: on every Home visit and on every hub `welcome`,
 * never per frame. A hub that answers 404 has no moods (older than P7): the cache stops offering
 * chips. A hub that does not answer keeps the cache, so a blip does not take the chips away; a tile
 * then says it could not search, with Retry.
 */
class MoodManager(
	private val preferenceManager: PreferenceManager,
	private val hubManager: HubManager
) {
	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

	private val client by lazy {
		HttpClient {
			install(UserAgent) { agent = "Navic" }
			install(HttpTimeout) {
				connectTimeoutMillis = 8_000
				requestTimeoutMillis = REQUEST_TIMEOUT_MS
				socketTimeoutMillis = REQUEST_TIMEOUT_MS
			}
		}
	}

	/** The wire, replaceable by a test. A null reply is a transport failure (no status at all). */
	internal var transport: suspend (MoodHttpRequest) -> MoodHttpReply? = { request -> send(request) }

	private val _catalogue = MutableStateFlow(restoreCatalogue(preferenceManager.moodCatalogue))

	/** The last catalogue the hub gave; [MoodCatalogue.NONE] before any. */
	val catalogue: StateFlow<MoodCatalogue> = _catalogue.asStateFlow()

	private val refreshLock = Mutex()
	private val tileReads = SingleFlight<String, TileIdsResult>(scope)

	init {
		// Every welcome: the hub may be another one, or newer.
		scope.launch {
			hubManager.connected.filter { it }.collect { refresh() }
		}
	}

	/** Ask the hub for its catalogue. True when it answered (404 included). */
	suspend fun refresh(): Boolean = refreshLock.withLock {
		val request = catalogueRequest(hubHttpBase(preferenceManager), preferenceManager.hubToken)
		val reply = if (request == null) null else transport(request)
		val outcome = catalogueOutcome(request != null, reply)
		val next = catalogueAfterRefresh(_catalogue.value, outcome)
		if (next != _catalogue.value) {
			_catalogue.value = next
			preferenceManager.moodCatalogue = moodJson.encodeToString(next)
		}
		outcome !is CatalogueOutcome.NoAnswer
	}

	/** A tile's ids, one request per key however many ask at once. */
	suspend fun tileIds(moodId: String, variantId: String): TileIdsResult =
		tileReads.run("$moodId/$variantId") {
			val request = tileRequest(hubHttpBase(preferenceManager), preferenceManager.hubToken, moodId, variantId)
				?: return@run TileIdsResult.Unavailable
			val reply = transport(request) ?: return@run TileIdsResult.Unavailable
			tileIdsOutcome(reply.status, reply.body, moodId, variantId)
		}

	private suspend fun send(request: MoodHttpRequest): MoodHttpReply? = try {
		val response = if (request.body == null) {
			client.get(request.url) { header("Authorization", request.authorization) }
		} else {
			client.post(request.url) {
				header("Authorization", request.authorization)
				contentType(ContentType.Application.Json)
				setBody(request.body)
			}
		}
		MoodHttpReply(response.status.value, response.bodyAsText())
	} catch (e: CancellationException) {
		throw e
	} catch (e: Exception) {
		Logger.w(TAG, "${request.url.substringAfterLast("/sonic/")} failed: ${e.message}")
		null
	}

	private companion object {
		const val TAG = "MoodManager"

		/** A cold tile is one CLAP search on the hub (about 1 s, 2.2 s for AudioMuse's first). */
		const val REQUEST_TIMEOUT_MS = 20_000L
	}
}

// --------------------------------------------------------------------------------------------- //
// The pure parts (`MoodManagerTest`)
// --------------------------------------------------------------------------------------------- //

private val moodJson = Json {
	ignoreUnknownKeys = true
	isLenient = true
	coerceInputValues = true
	encodeDefaults = true
	explicitNulls = false
}

/** One request to the hub: the full URL, the bearer header's value, and a JSON body for a POST. */
data class MoodHttpRequest(val url: String, val authorization: String, val body: String? = null)

data class MoodHttpReply(val status: Int, val body: String)

fun catalogueRequest(base: String?, token: String): MoodHttpRequest? =
	base?.let { MoodHttpRequest("$it/sonic/mood/catalogue", "Bearer $token") }

@Serializable
private data class TileRequestBody(val mood: String, val variant: String)

fun tileRequest(base: String?, token: String, moodId: String, variantId: String): MoodHttpRequest? =
	base?.let {
		MoodHttpRequest(
			"$it/sonic/mood/list", "Bearer $token",
			moodJson.encodeToString(TileRequestBody(moodId, variantId))
		)
	}

/** The hub's catalogue answer, or null when it is not one. Unknown fields are ignored. */
fun parseMoodCatalogue(body: String): MoodCatalogue? = try {
	moodJson.decodeFromString<MoodCatalogue>(body)
} catch (e: Exception) {
	null
}

sealed interface CatalogueOutcome {
	data class Answer(val catalogue: MoodCatalogue) : CatalogueOutcome
	/** 404: a hub older than the moods. */
	data object Absent : CatalogueOutcome
	/** No hub configured: nothing to offer. */
	data object NoHub : CatalogueOutcome
	/** No status, a 5xx, a body that is not a catalogue: the hub did not say. */
	data object NoAnswer : CatalogueOutcome
}

fun catalogueOutcome(hubConfigured: Boolean, reply: MoodHttpReply?): CatalogueOutcome = when {
	!hubConfigured -> CatalogueOutcome.NoHub
	reply == null -> CatalogueOutcome.NoAnswer
	reply.status == 404 -> CatalogueOutcome.Absent
	reply.status in 200..299 -> parseMoodCatalogue(reply.body)?.let { CatalogueOutcome.Answer(it) }
		?: CatalogueOutcome.NoAnswer
	else -> CatalogueOutcome.NoAnswer
}

/**
 * What the cache becomes. An answer replaces it. A hub without the routes, or no hub, offers no
 * chips (`available` false; the moods stay for a later hub's words). No answer keeps it as it was.
 */
fun catalogueAfterRefresh(cached: MoodCatalogue, outcome: CatalogueOutcome): MoodCatalogue = when (outcome) {
	is CatalogueOutcome.Answer -> outcome.catalogue
	CatalogueOutcome.Absent, CatalogueOutcome.NoHub -> cached.copy(configured = false, available = false, ready = false)
	CatalogueOutcome.NoAnswer -> cached
}

internal fun restoreCatalogue(raw: String): MoodCatalogue =
	if (raw.isBlank()) MoodCatalogue.NONE else parseMoodCatalogue(raw) ?: MoodCatalogue.NONE

sealed interface TileIdsResult {
	data class Ok(val ids: List<String>, val version: String) : TileIdsResult
	/** The hub could not search, or could not be reached: Retry. */
	data object Unavailable : TileIdsResult
	/** A hub without the moods, or a tile it does not know (a newer catalogue was cached). */
	data object Absent : TileIdsResult
}

@Serializable
private data class TileIdsBody(
	val mood: String = "",
	val variant: String = "",
	val version: String = "",
	val ids: List<String>? = null
)

/**
 * A tile's answer. Only a 200 whose `ids` is a list of the asked (mood, variant) is an answer; a 404
 * is a hub (or a catalogue) without that tile; anything else could not search.
 */
fun tileIdsOutcome(status: Int, body: String, moodId: String, variantId: String): TileIdsResult = when (status) {
	in 200..299 -> {
		val parsed = try {
			moodJson.decodeFromString<TileIdsBody>(body)
		} catch (e: Exception) {
			null
		}
		val ids = parsed?.ids
		if (parsed == null || ids == null || parsed.mood != moodId || parsed.variant != variantId) TileIdsResult.Unavailable
		else TileIdsResult.Ok(ids, parsed.version)
	}
	404 -> TileIdsResult.Absent
	else -> TileIdsResult.Unavailable
}

/**
 * Concurrent callers of [run] with one key share one call; a later caller after it ends starts a new
 * one. The call runs in [scope], so a caller leaving does not cancel it for the others.
 */
class SingleFlight<K, V>(private val scope: CoroutineScope) {
	private val lock = Mutex()
	private val inFlight = HashMap<K, Deferred<V>>()

	suspend fun run(key: K, block: suspend () -> V): V {
		// One entry per key at most (a finished one is replaced), so the map stays as small as the
		// set of keys ever asked.
		val deferred = lock.withLock {
			inFlight[key]?.takeIf { it.isActive } ?: scope.async { block() }.also { inFlight[key] = it }
		}
		return deferred.await()
	}
}
