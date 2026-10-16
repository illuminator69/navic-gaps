package paige.navic.domain.manager

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import paige.navic.domain.models.settings.StationCharacter
import paige.navic.ui.core.PlayerUiState
import paige.navic.util.Logger

/**
 * The hub's station engine, from this device (navi-connect PROTOCOL §18, I-040).
 *
 * Three things, and only these: the HTTP to `/sonic/station/...`; whether the engine can be had
 * ([available]) and the words it uses ([catalogue]); and the record of the station that built this
 * device's queue ([provenance], persisted). What to ask for and when is `RadioManager`'s, through
 * the pure rules in `StationModels.kt`.
 *
 * **Hub only**, like `LbBotManager` and `PreviewManager`: the base is `hubHttpBase`, so a hub
 * switched off in the settings means no request at all. **Its own client**, timed out well inside
 * the 120 s of the shared one (CLAUDE.md §7): the hub answers a `start` or `next` within 12 s or
 * says `partial`, so a request still out at 20 s is a hub in trouble, and autoplay should fall back
 * rather than wait.
 *
 * [available] is true only when the hub listed `station` in `welcome.features` AND the probe
 * (`GET /sonic/station/characters`) said `available`. Either alone is not enough: an older hub
 * has no routes, and a hub with the routes may have no tier answering. It is re-probed every
 * 10 minutes while connected, after a failure, and on a 503.
 */
class StationManager(
	private val preferenceManager: PreferenceManager,
	private val hubManager: HubManager
) {
	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

	private val json = Json {
		ignoreUnknownKeys = true
		isLenient = true
		coerceInputValues = true
		encodeDefaults = false
		explicitNulls = false
	}

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

	private fun base(): String? = hubHttpBase(preferenceManager)

	// ----- the catalogue and availability --------------------------------------------------- //

	private val _catalogue = MutableStateFlow(restoreCatalogue())

	/** The hub's characters and their words; the bundled copy until a hub has answered. */
	val catalogue: StateFlow<StationCatalogue> = _catalogue.asStateFlow()

	/** The last probe said a tier answers. Learned per process, never restored. */
	private val _probeAvailable = MutableStateFlow(false)

	/**
	 * The station engine can be asked: a hub that lists `station` (from its last `welcome`, which
	 * outlives a socket drop, so a blip does not turn a radio into Auto DJ) and whose probe said
	 * available, with the hub switched on here.
	 */
	val available: StateFlow<Boolean> = combine(hubManager.hubFeatures, _probeAvailable) { features, probe ->
		HUB_FEATURE_STATION in features && probe && base() != null
	}.stateIn(scope, SharingStarted.Eagerly, false)

	/**
	 * The last probe got an answer, whatever it said. A hub that answered "not available" probes
	 * its own tiers every 10 minutes, so asking it again sooner learns nothing; one that did not
	 * answer is asked again in a minute.
	 */
	private var lastProbeAnswered = false

	/** Ask the hub. Returns whether the engine is available. */
	suspend fun probe(): Boolean {
		lastProbeAnswered = false
		val base = base()
		if (base == null || HUB_FEATURE_STATION !in hubManager.hubFeatures.value) {
			_probeAvailable.value = false
			return false
		}
		return try {
			val response = client.get("$base/sonic/station/characters") { auth() }
			if (!response.status.isSuccess()) {
				Logger.w(TAG, "characters -> HTTP ${response.status.value}")
				_probeAvailable.value = false
				return false
			}
			val answer = json.decodeFromString<StationCatalogue>(response.bodyAsText())
			lastProbeAnswered = true
			if (answer.characters.isNotEmpty()) {
				_catalogue.value = answer
				preferenceManager.stationCatalogue = json.encodeToString(answer)
			} else {
				_catalogue.update { it.copy(configured = answer.configured, available = answer.available, engine = answer.engine) }
			}
			_probeAvailable.value = answer.configured && answer.available
			_probeAvailable.value
		} catch (e: Exception) {
			Logger.w(TAG, "characters failed: ${e.message}")
			_probeAvailable.value = false
			false
		}
	}

	private var reprobe: Job? = null

	/**
	 * A 503, a bare 404 or an unreachable hub: stop asking (autoplay is Auto DJ meanwhile) until a
	 * probe a minute later says otherwise. One re-probe at a time, however many calls failed.
	 */
	private fun markUnavailable() {
		_probeAvailable.value = false
		if (reprobe?.isActive == true) return
		reprobe = scope.launch {
			delay(RETRY_PROBE_MS)
			probe()
		}
	}

	private fun restoreCatalogue(): StationCatalogue {
		val raw = preferenceManager.stationCatalogue
		if (raw.isBlank()) return StationCatalogue.BUNDLED
		return try {
			json.decodeFromString<StationCatalogue>(raw)
				.takeIf { it.characters.isNotEmpty() }
				?.copy(configured = false, available = false)
				?: StationCatalogue.BUNDLED
		} catch (e: Exception) {
			StationCatalogue.BUNDLED
		}
	}

	// ----- the record of what built this device's queue -------------------------------------- //

	private val _provenance = MutableStateFlow(restoreProvenance())

	/** The station that built this device's queue, as last applied; one record, for one queue. */
	val provenance: StateFlow<StationProvenance?> = _provenance.asStateFlow()

	private val _autoDjQueueId = MutableStateFlow(preferenceManager.autoDjQueueId.ifBlank { null })

	/** The saved-queue id of the queue Auto DJ last topped up. */
	val autoDjQueueId: StateFlow<String?> = _autoDjQueueId.asStateFlow()

	private val _inFlight = MutableStateFlow(false)

	/** A `start` or `next` (or Auto DJ's top-up) is out right now; the queue sheet shows it. */
	val busy: StateFlow<Boolean> = _inFlight.asStateFlow()

	internal fun setBusy(busy: Boolean) {
		_inFlight.value = busy
	}

	private fun restoreProvenance(): StationProvenance? {
		val raw = preferenceManager.stationProvenance
		if (raw.isBlank()) return null
		return try {
			json.decodeFromString<StationProvenance>(raw).takeIf { it.stationId.isNotBlank() && it.queueId.isNotBlank() }
		} catch (e: Exception) {
			null
		}
	}

	/** The one writer of [provenance]. Null forgets it. */
	internal fun record(provenance: StationProvenance?) {
		_provenance.value = provenance
		preferenceManager.stationProvenance = provenance?.let { json.encodeToString(it) } ?: ""
	}

	internal fun recordAutoDj(queueId: String?) {
		_autoDjQueueId.value = queueId
		preferenceManager.autoDjQueueId = queueId.orEmpty()
		val current = _provenance.value
		if (current != null && queueId != null && current.queueId == queueId) {
			record(current.copy(lastFromAutoDj = true))
		}
	}

	/**
	 * The station [state]'s queue belongs to ([boundStation]). The session's own station counts
	 * only while this device plays the session and the queue here is the session's: same saved
	 * queue, or one a transfer loaded here with none of its own.
	 */
	fun boundFor(state: PlayerUiState): StationProvenance? {
		val session = hubManager.remoteSession.value
		val me = hubManager.myDeviceId.value
		val playingSession = hubManager.connected.value && me != null && hubManager.activeDeviceId.value == me &&
			HUB_FEATURE_STATION in hubManager.hubFeatures.value
		val sameQueue = state.savedQueueId == null || state.savedQueueId == session.savedQueueId
		val sessionStation = if (playingSession && sameQueue) session.stationId else null
		val frames = hubManager.stationFrames.value
		return boundStation(
			provenance = _provenance.value,
			queueId = state.savedQueueId ?: session.savedQueueId.takeIf { sessionStation != null },
			sessionStationId = sessionStation,
			frameOf = { frames[it] },
			sessionName = session.sourceName
		)
	}

	/** The station the session plays when ANOTHER device plays it, for this device's queue sheet. */
	fun remoteBound(): StationProvenance? {
		val session = hubManager.remoteSession.value
		val id = session.stationId ?: return null
		if (HUB_FEATURE_STATION !in hubManager.hubFeatures.value) return null
		val frame = hubManager.stationFrames.value[id]
		val mine = _provenance.value?.takeIf { it.stationId == id }
		return (mine ?: StationProvenance(queueId = session.savedQueueId.orEmpty(), stationId = id))
			.let { base -> if (frame != null) base.withFrame(frame) else base }
			.let { it.copy(name = it.name.ifBlank { frame?.name ?: session.sourceName.orEmpty() }) }
	}

	init {
		// What this device publishes: the bound station of the queue being published, judged by
		// the saved-queue id the publish carries (a queue a transfer loaded here has none of its
		// own, and is the session's only when that id is the session's).
		hubManager.stationIdForPublish = { state, publishId ->
			boundFor(state.copy(savedQueueId = state.savedQueueId ?: publishId))?.stationId
		}
		// Probe on every welcome that lists the engine, then every 10 minutes while connected.
		scope.launch {
			combine(hubManager.connected, hubManager.hubFeatures) { connected, features ->
				connected && HUB_FEATURE_STATION in features
			}.distinctUntilChanged().collectLatest { offered ->
				if (!offered) {
					// Kept as it was across a drop: [available] still needs the feature from the
					// last welcome, and a hub that went away fails the next request instead.
					if (base() == null) _probeAvailable.value = false
					return@collectLatest
				}
				while (true) {
					probe()
					delay(if (lastProbeAnswered) PROBE_INTERVAL_MS else RETRY_PROBE_MS)
				}
			}
		}
		// The station frames keep the record's cursor and exhaustion current: another device may
		// have served the last batch, or a like may have cleared "exhausted".
		scope.launch {
			hubManager.stationFrames.collect { frames ->
				val current = _provenance.value ?: return@collect
				val frame = frames[current.stationId] ?: return@collect
				val next = current.withFrame(frame)
				if (next != current) record(next)
			}
		}
	}

	// ----- the routes ------------------------------------------------------------------------- //

	/** `POST /sonic/station/start`. */
	suspend fun start(
		seed: StationSeed,
		character: StationCharacter,
		count: Int,
		exclude: List<String>,
		bindSession: String?
	): StationResult = call("start") { base ->
		client.post("$base/sonic/station/start") {
			auth()
			contentType(ContentType.Application.Json)
			setBody(
				json.encodeToString(
					StationStartBody(
						seed = seed,
						character = character.wireId,
						count = count.coerceIn(1, 50),
						exclude = stationExclude(exclude),
						bindSession = bindSession,
						deviceId = hubManager.myDeviceId.value
					)
				)
			)
		}
	}

	/** `POST /sonic/station/next`. */
	suspend fun next(stationId: String, after: Int, count: Int, exclude: List<String>): StationResult =
		call("next") { base ->
			client.post("$base/sonic/station/next") {
				auth()
				contentType(ContentType.Application.Json)
				setBody(
					json.encodeToString(
						StationNextBody(
							stationId = stationId,
							after = after,
							count = count.coerceIn(1, 25),
							exclude = stationExclude(exclude),
							deviceId = hubManager.myDeviceId.value
						)
					)
				)
			}
		}

	/**
	 * `POST /sonic/station/signal`: a like, an unlike or a character change. Best effort: a signal
	 * that does not arrive steers nothing, and nothing waits on it. Returns whether the hub took it.
	 */
	suspend fun signal(stationId: String, trackId: String, kind: String, value: String? = null): Boolean {
		val base = base() ?: return false
		if (!available.value) return false
		return try {
			val response = client.post("$base/sonic/station/signal") {
				auth()
				contentType(ContentType.Application.Json)
				setBody(json.encodeToString(StationSignalBody(stationId, trackId, kind, value)))
			}
			if (!response.status.isSuccess()) Logger.w(TAG, "signal $kind -> HTTP ${response.status.value}")
			response.status.isSuccess()
		} catch (e: Exception) {
			Logger.w(TAG, "signal $kind failed: ${e.message}")
			false
		}
	}

	private suspend fun call(route: String, send: suspend (String) -> HttpResponse): StationResult {
		val base = base() ?: return StationResult.Unavailable("no hub")
		if (!available.value) return StationResult.Unavailable("engine not available")
		return try {
			val response = send(base)
			val body = response.bodyAsText()
			val status = response.status.value
			when {
				response.status.isSuccess() -> {
					val answer = json.decodeFromString<StationAnswer>(body)
					// A 200 that is not an answer (`{}`) is not "no tracks": it names no station.
					if (answer.stationId.isBlank()) StationResult.Unavailable("$route answered no station")
					else StationResult.Ok(answer)
				}
				status == 409 -> {
					val refusal = refusal(body)
					val id = refusal?.stationId
					if (refusal?.code == "session_bound" && !id.isNullOrBlank()) StationResult.SessionBound(id)
					else StationResult.Unavailable("$route -> 409 ${refusal?.code}")
				}
				status == 404 -> when (refusal(body)?.code) {
					"station_unknown" -> StationResult.StationUnknown
					"seed_unknown" -> StationResult.SeedUnknown
					// A bare 404: a hub without the route after all.
					else -> {
						markUnavailable()
						StationResult.Unavailable("$route -> 404")
					}
				}
				status == 503 -> {
					markUnavailable()
					StationResult.Unavailable("$route -> 503")
				}
				else -> {
					Logger.w(TAG, "$route -> HTTP $status")
					StationResult.Unavailable("$route -> HTTP $status")
				}
			}
		} catch (e: Exception) {
			// Unreachable, timed out, or an answer that would not decode. The hub's own failure,
			// never "the station has nothing": that is `exhausted`, an answer.
			Logger.w(TAG, "$route failed: ${e.message}")
			markUnavailable()
			StationResult.Unavailable("$route failed")
		}
	}

	private fun refusal(body: String): StationRefusal? = try {
		json.decodeFromString<StationRefusal>(body)
	} catch (e: Exception) {
		null
	}

	private fun io.ktor.client.request.HttpRequestBuilder.auth() {
		header("Authorization", "Bearer ${preferenceManager.hubToken}")
	}

	private companion object {
		const val TAG = "StationManager"

		/** Above the hub's 12 s request budget, below the hub's handshake ceiling. */
		const val REQUEST_TIMEOUT_MS = 20_000L

		/** The hub re-probes its tiers every 10 minutes; so does this. */
		const val PROBE_INTERVAL_MS = 10 * 60 * 1000L

		/** After a failure, a 503, or a probe that said unavailable. */
		const val RETRY_PROBE_MS = 60_000L
	}
}
