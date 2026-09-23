package paige.navic.domain.manager

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.serializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.time.Clock
import paige.navic.data.database.dao.LbIndexDao
import paige.navic.data.database.entities.LbResponseCacheEntity
import paige.navic.util.Logger
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf

/**
 * How a [LbBotManager.cachedGet] read may use the network.
 *
 * - [NORMAL]: the cached body now, then a request only if it is stale (older than the route's
 *   max age, or ownership-bearing and older than the last library change).
 * - [CACHE_ONLY]: the cached body or `null`, no request — offline, or a screen painting its first
 *   frame before it has decided whether lb-bot is up.
 * - [REFRESH]: the cached body now, then a request whatever its age — pull-to-refresh.
 */
enum class LbCachePolicy { NORMAL, CACHE_ONLY, REFRESH }

/**
 * lb-bot: what the library is *missing*.
 *
 * lb-bot keeps a per-artist MusicBrainz discography index and a Soulseek
 * acquisition pipeline that can fill a gap — a release the library doesn't have
 * at all, or the three tracks missing from one it does.
 *
 * HUB ONLY, unlike [AudioMuseManager] next door. That one keeps a direct-LAN
 * fallback because AudioMuse has a bearer token of its own; lb-bot's Flask API has
 * no authentication whatsoever and exposes delete-file and trash routes, so the
 * hub's route whitelist is the only gate that exists. There is deliberately no
 * direct route here, and no lb-bot address is ever stored on the device: no hub
 * means the feature is simply off.
 *
 * FAIL-SOFT everywhere. A missing hub, an unset LBBOT_URL upstream, an unreachable
 * lb-bot and a never-indexed artist all resolve to "render nothing" — the artist
 * page must look exactly as it does today whenever this layer is absent.
 *
 * NOTE for future edits: do not write a route path containing a slash-star
 * sequence inside a comment. Kotlin block comments nest, and one of those swallows
 * the rest of the file behind an "Unclosed comment" at EOF.
 */
class LbBotManager(
	private val preferenceManager: PreferenceManager,
	/** Only its `lb_response_cache` half — the index mirror is [LbIndexSync]'s. */
	private val responseCache: LbIndexDao
) {
	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

	private val json = Json {
		ignoreUnknownKeys = true
		isLenient = true
		coerceInputValues = true
		encodeDefaults = false
	}

	private val client by lazy {
		HttpClient {
			install(ContentNegotiation) { json(json) }
			install(UserAgent) { agent = "Navic" }
			// The discography read is an instant SQLite lookup, but `album/releases`,
			// `album/tracklist` and the download POST all sit on rate-limited
			// MusicBrainz calls upstream, and a gap rescan walks a folder. The hub
			// gives those its slow timeout; match it here rather than failing a call
			// the hub is still happily servicing.
			install(HttpTimeout) {
				connectTimeoutMillis = 8_000
				requestTimeoutMillis = 60_000
				socketTimeoutMillis = 60_000
			}
		}
	}

	/**
	 * `ws://host:4790` -> `http://host:4790`, honouring the hub toggle. Shared with
	 * [PreviewManager] — see `HubEndpoint.kt` for why it is not shared with
	 * AudioMuseManager, which deliberately ignores the toggle.
	 */
	private fun hubBase(): String? = hubHttpBase(preferenceManager)

	/**
	 * Whether lb-bot is reachable through anything at all — a hub is set, enabled and has a
	 * token. A preference read, not a probe. The artist page gates its lb-bot index mirror on
	 * this: the mirror answers offline by design, but a user who has switched the hub off has
	 * switched the lb-bot layer off, and a shelf from a mirror they can no longer act on would
	 * be the "renders something without lb-bot" the §7 rule forbids.
	 */
	val isConfigured: Boolean
		get() = hubBase() != null

	/**
	 * Changes whenever the route configuration does, so a `LaunchedEffect` keyed on it
	 * re-probes instead of caching "unavailable" for the life of the composition.
	 * Preferences here are plain delegated properties with no Flow behind them.
	 */
	val routeSignature: String
		get() = hubRouteSignature(preferenceManager)

	/**
	 * Bumped whenever something lands in the library. Screens re-read on a change —
	 * including after [SyncManager] has pulled a landed album into Room, which is the
	 * bump that makes the album itself (not just lb-bot's row for it) appear.
	 */
	private val _libraryRevision = MutableStateFlow(0L)
	val libraryRevision: StateFlow<Long> = _libraryRevision.asStateFlow()

	/** Whether the hub socket is up right now. Set by [HubManager], which owns the socket;
	 *  while it is, a `fill` frame reaches this manager within a second of lb-bot writing
	 *  its ledger and the poll below is only a safety net. */
	var hubConnected: () -> Boolean = { false }

	/** When the last `fill` frame arrived, for the poll to know push is alive. */
	@Volatile
	private var lastPushAt = 0L

	/**
	 * Where a settled fill's notification goes, when a platform has one. Set by
	 * `MainActivity` on Android; null on a platform without notifications. Called from
	 * [settle] itself, so a fill that settles while the process is alive but the activity
	 * is stopped is announced then and there, not on the next foreground entry.
	 */
	var notificationSink: ((LbFillEvent) -> Unit)? = null

	/** What landed, for the one consumer that needs more than "something did": the Room sync. */
	private val _libraryEvents = MutableSharedFlow<LbLibraryEvent>(extraBufferCapacity = 32)
	val libraryEvents: SharedFlow<LbLibraryEvent> = _libraryEvents.asSharedFlow()

	/**
	 * Every [libraryRevision] bump that a page might care about, WITH what it names — lb-bot's
	 * `library` frames and fill transitions as they arrive, then the Room sync's "that album is in
	 * Room now" ([onLocalLibrarySynced]) carrying the album ids it synced.
	 *
	 * [libraryRevision] says only "something moved", which left every artist page on the back
	 * stack re-reading on every landing anywhere in the library. This is what lets a page filter:
	 * an event naming another artist (`ndArtistId`) is not its business, one naming an album or a
	 * release-group it shows is, and one naming nothing at all (a changed-albums sweep) cannot be
	 * ruled out. Newest kept on overflow — a bump is "re-read now", so the latest is the useful one.
	 */
	private val _libraryBumps = MutableSharedFlow<LbLibraryEvent>(
		extraBufferCapacity = 32,
		onBufferOverflow = BufferOverflow.DROP_OLDEST
	)
	val libraryBumps: SharedFlow<LbLibraryEvent> = _libraryBumps.asSharedFlow()

	/** Latest poll for each watched album fill, keyed by release-group mbid. */
	private val _fills = MutableStateFlow<Map<String, LbFillStatus>>(emptyMap())
	val fills: StateFlow<Map<String, LbFillStatus>> = _fills.asStateFlow()

	/** Latest poll for each watched gap fill, keyed by lb-bot review group id. */
	private val _gaps = MutableStateFlow<Map<String, LbGap>>(emptyMap())
	val gaps: StateFlow<Map<String, LbGap>> = _gaps.asStateFlow()

	/**
	 * Every fill this device has started, newest first — in flight *and* finished.
	 *
	 * The watch map used to be pruned the moment a fill settled, which meant a failure
	 * was indistinguishable from never having happened: the sheet was long closed, the
	 * artist page showed the album as still missing, and there was nowhere to ask why.
	 * Keeping the terminal rows is what makes a downloads view, a retry button and a
	 * completion notice possible without any of them growing state of their own.
	 *
	 * It also covers a case nothing else can. lb-bot's own fill ledger is in memory and
	 * capped, so restarting it mid-fill makes `album/status` answer `unknown` forever;
	 * this is then the only record that the download was ever asked for.
	 */
	private val _ledger = MutableStateFlow<List<LbFillEntry>>(emptyList())
	val ledger: StateFlow<List<LbFillEntry>> = _ledger.asStateFlow()

	/**
	 * One event per fill reaching a terminal outcome, for whoever wants to announce it.
	 *
	 * Emitted from [settle] and nowhere else, so a sheet and a page both watching the
	 * same fill cannot produce two notifications. `extraBufferCapacity` because there is
	 * no subscriber at all until something mounts a collector, and a fill that lands
	 * while the app is backgrounded must not block the poll loop.
	 */
	private val _fillEvents = MutableSharedFlow<LbFillEvent>(extraBufferCapacity = 8)
	val fillEvents: SharedFlow<LbFillEvent> = _fillEvents.asSharedFlow()

	/** The user's sticky quality preference. Empty means "lb-bot's own default". */
	var preferredQuality: String
		get() = preferenceManager.lbBotQuality
		set(value) {
			preferenceManager.lbBotQuality = value
		}

	// ----- HTTP ------------------------------------------------------------- //

	/**
	 * Why a call failed, in terms a user can act on.
	 *
	 * This exists because the first version returned bare nulls and booleans and
	 * logged only *exceptions* — and ktor's default `expectSuccess = false` means a
	 * 404 or a 503 is an ordinary response, not an exception. A hub that didn't know
	 * a route therefore produced a button that did nothing, logged nothing, and left
	 * no way to tell "rejected" from "unreachable" from "lb-bot said no".
	 */
	sealed interface LbError {
		/** No hub configured, or the hub toggle is off. The surface should be hidden. */
		data object NotConfigured : LbError

		/** The hub proxies no such route: it is older than this client. */
		data object RouteUnknown : LbError

		/**
		 * lb-bot is alive but wouldn't answer in time — almost always because it is searching.
		 *
		 * Every lb-bot route takes one process-wide `_review_lock`, so while a source search runs
		 * an ordinary poll times out and the hub answers `{"error": "lb-bot unreachable"}`. Shown
		 * verbatim that is simply wrong, and it sends you to investigate a service that is working.
		 * Its own variant rather than a string so callers can decide it isn't worth surfacing.
		 */
		data object Busy : LbError

		/** Reached the hub; it or lb-bot refused. [message] is upstream's own words. */
		data class Rejected(val status: Int, val message: String) : LbError

		/** Never got an answer. */
		data class Unreachable(val message: String) : LbError
	}

	/** Success carries the parsed body; failure carries something to show the user. */
	sealed interface LbResult<out T> {
		data class Ok<T>(val value: T) : LbResult<T>
		data class Failed(val error: LbError) : LbResult<Nothing>
	}

	private fun <T> LbResult<T>.valueOrNull(): T? = (this as? LbResult.Ok)?.value

	/** lb-bot and the hub both answer errors as `{"error": "..."}`. */
	@Serializable
	private data class LbErrorBody(val error: String = "")

	private fun failureFor(status: Int, path: String, raw: String): LbResult.Failed {
		val message = try {
			json.decodeFromString<LbErrorBody>(raw).error
		} catch (e: Exception) {
			""
		}
		Logger.w("LbBotManager", "$path -> HTTP $status ${message.ifBlank { raw.take(200) }}")
		return LbResult.Failed(
			when (status) {
				404 -> LbError.RouteUnknown
				502, 504 -> LbError.Busy
				else -> LbError.Rejected(status, message)
			}
		)
	}

	/**
	 * @param timeoutMs overrides the client's 60 s for one call. Only the two polled
	 *   routes pass it: they run every five seconds, so a tick that hasn't answered in
	 *   twelve has already missed its slot and the next one will do — whereas holding
	 *   the connection for a full minute stalls every other watch behind it. The hub's
	 *   own timeouts (20 s for `album/status`, 45 s for the gap) sit above this on
	 *   purpose; the client is the side that has to give up first.
	 */
	private suspend inline fun <reified T> getJson(
		path: String,
		params: List<Pair<String, String>>,
		timeoutMs: Long? = null
	): LbResult<T> {
		val base = hubBase() ?: return LbResult.Failed(LbError.NotConfigured)
		return try {
			val response = client.get(base + path) {
				header("Authorization", "Bearer ${preferenceManager.hubToken}")
				params.forEach { (key, value) -> if (value.isNotBlank()) parameter(key, value) }
				if (timeoutMs != null) timeout {
					requestTimeoutMillis = timeoutMs
					socketTimeoutMillis = timeoutMs
				}
			}
			if (response.status.isSuccess()) LbResult.Ok(response.body<T>())
			else failureFor(response.status.value, "GET $path", response.bodyAsText())
		} catch (e: Exception) {
			Logger.e("LbBotManager", "GET $path failed", e)
			LbResult.Failed(LbError.Unreachable(e.message.orEmpty()))
		}
	}

	private suspend inline fun <reified B, reified T> postJson(
		path: String,
		body: B
	): LbResult<T> {
		val base = hubBase() ?: return LbResult.Failed(LbError.NotConfigured)
		return try {
			val response = client.post(base + path) {
				header("Authorization", "Bearer ${preferenceManager.hubToken}")
				contentType(ContentType.Application.Json)
				setBody(body)
			}
			if (!response.status.isSuccess()) {
				return failureFor(response.status.value, "POST $path", response.bodyAsText())
			}
			val parsed = response.body<T>()
			// lb-bot answers 200 with `{"ok": false}` for some refusals, so the
			// status code alone is not the verdict.
			if (parsed is LbOk && !parsed.ok) {
				LbResult.Failed(LbError.Rejected(200, parsed.error))
			} else {
				LbResult.Ok(parsed)
			}
		} catch (e: Exception) {
			Logger.e("LbBotManager", "POST $path failed", e)
			LbResult.Failed(LbError.Unreachable(e.message.orEmpty()))
		}
	}

	/** [getJson] for [cachedGet]: the body as text, because the text is what gets stored. */
	private suspend fun getText(path: String, params: List<Pair<String, String>>): LbResult<String> {
		val base = hubBase() ?: return LbResult.Failed(LbError.NotConfigured)
		return try {
			val response = client.get(base + path) {
				header("Authorization", "Bearer ${preferenceManager.hubToken}")
				params.forEach { (key, value) -> if (value.isNotBlank()) parameter(key, value) }
			}
			val text = response.bodyAsText()
			if (response.status.isSuccess()) LbResult.Ok(text)
			else failureFor(response.status.value, "GET $path", text)
		} catch (e: CancellationException) {
			throw e
		} catch (e: Exception) {
			Logger.e("LbBotManager", "GET $path failed", e)
			LbResult.Failed(LbError.Unreachable(e.message.orEmpty()))
		}
	}

	// ----- response cache: stale-while-revalidate --------------------------- //

	/**
	 * The last time the library changed as far as this device heard — mirrored from
	 * [PreferenceManager.lbCacheLibraryStaleAt] so the hot path never touches preferences, and
	 * written through by [markLibraryStale].
	 */
	@Volatile
	private var libraryStaleAt: Long = preferenceManager.lbCacheLibraryStaleAt

	/**
	 * Every cached answer that carries ownership is stale from now on, whatever its age.
	 *
	 * The hub invalidates `LB_LIBRARY_ROUTES` on a landing for exactly this reason — a cached
	 * "you don't own this" outlives the fill that falsified it, and a stale badge is a tile
	 * offering to fetch a record already on disk. Nothing is deleted: the stale body still renders
	 * instantly and the next read revalidates it, which is the whole contract of [cachedGet].
	 *
	 * Called from [onLibraryChanged] (a hub `library` frame, or one of our own fills landing) and
	 * from `HubManager` on every `welcome` — a socket that was down may have missed frames, and
	 * a missed frame would otherwise leave a badge wrong until its row's max age ran out. NOT from
	 * a `fill` or `index` frame: neither is a library event (contract §1b).
	 */
	fun markLibraryStale() {
		val now = nowMs()
		libraryStaleAt = now
		preferenceManager.lbCacheLibraryStaleAt = now
	}

	/**
	 * `route?k=v&…`, params sorted and blanks dropped — [getJson] drops blanks too, so two calls
	 * sending the same request share a key. An ownership-bearing answer is tagged
	 * [LIBRARY_KEY_PREFIX], which is how the tag survives into Room and a body read back after a
	 * restart is still judged against [libraryStaleAt].
	 */
	private fun cacheKey(
		route: String,
		params: List<Pair<String, String>>,
		staleOnLibrary: Boolean
	): String {
		fun esc(v: String) = v.replace("%", "%25").replace("&", "%26").replace("=", "%3D")
		val query = params
			.filter { it.second.isNotBlank() }
			.sortedWith(compareBy({ it.first }, { it.second }))
			.joinToString("&") { (k, v) -> "${esc(k)}=${esc(v)}" }
		return (if (staleOnLibrary) LIBRARY_KEY_PREFIX else "") + "$route?$query"
	}

	private fun <T : Any> decodeOrNull(serializer: KSerializer<T>, body: String): T? = try {
		json.decodeFromString(serializer, body)
	} catch (e: Exception) {
		// A body stored by an older build whose shape no longer decodes is simply not cached.
		Logger.w("LbBotManager", "cached lb-bot body no longer decodes: ${e.message}")
		null
	}

	/** One revalidation in flight per key; [startedAt] is what its stored body is stamped with. */
	private class Revalidation(val startedAt: Long, val result: Deferred<Any?>)

	private val revalidations = mutableMapOf<String, Revalidation>()
	private val revalidationLock = Mutex()

	/** Once per process: bodies older than [RESPONSE_CACHE_PRUNE_MS] go. */
	private var pruned = false

	/**
	 * Stale-while-revalidate over `lb_response_cache`, for lb-bot's NON-index reads — the one
	 * path every cached route goes through.
	 *
	 * The Flow, in order:
	 * 1. The cached body, **immediately and whatever its age**, when Room has one that decodes.
	 * 2. Stop there if it is fresh: younger than [maxAgeMs] and, for a [staleOnLibrary] route,
	 *    fetched after the last library change ([markLibraryStale]). Also stop there under
	 *    [LbCachePolicy.CACHE_ONLY] — offline, or a screen painting its first frame from Room.
	 * 3. Otherwise ask the hub, and emit the answer if it differs from what was emitted.
	 *
	 * **At least one emission, always**, and `null` only when there was no cached body AND the
	 * network gave nothing (or no hub is configured at all) — so a caller can treat `null`
	 * exactly as the old nullable return.
	 *
	 * **Failures never overwrite.** A failed request stores nothing and, with a cached body
	 * already emitted, emits nothing. [keep] says whether an answer is worth storing: an empty
	 * list or a `found: false` is a legitimate answer but not one to paint on the next open
	 * (lb-bot says those for a cold MusicBrainz as readily as for a true "nothing"), so it is
	 * emitted only when there is nothing better to show and is never persisted.
	 *
	 * **The request outlives the collector.** It runs in this manager's scope, so a screen left
	 * mid-request (or a caller that took `first()`) still gets its answer stored for next time,
	 * and two screens asking for one key share one request.
	 *
	 * Never used for fill, status or acquisition reads (`album/status`, `/lb/fills`, `/lb/gap`,
	 * sources) or anything that POSTs — a stale answer there is a wrong button, not a slow one.
	 */
	fun <T : Any> cachedGet(
		serializer: KSerializer<T>,
		route: String,
		params: List<Pair<String, String>>,
		maxAgeMs: Long,
		staleOnLibrary: Boolean = false,
		policy: LbCachePolicy = LbCachePolicy.NORMAL,
		keep: (T) -> Boolean = { true }
	): Flow<T?> = flow {
		// A user who has switched the hub off has switched this layer off: a cached body is
		// not an answer they asked for (the same gate the index mirror's readers use).
		if (!isConfigured) {
			emit(null)
			return@flow
		}
		pruneOnce()
		val key = cacheKey(route, params, staleOnLibrary)
		val row = try {
			responseCache.getCachedResponse(key)
		} catch (e: CancellationException) {
			throw e
		} catch (e: Exception) {
			Logger.w("LbBotManager", "response cache read failed for $route", e)
			null
		}
		val cached = row?.let { decodeOrNull(serializer, it.body) }
		if (cached != null) emit(cached)

		val now = nowMs()
		// `cached` is only ever non-null when `row` is, which is what lets `row` be read below.
		val fresh = cached != null &&
			row.fetchedAt <= now &&                     // a clock set back is not "fresh forever"
			now - row.fetchedAt < maxAgeMs &&
			!(staleOnLibrary && row.fetchedAt <= libraryStaleAt)
		val skipNetwork = policy == LbCachePolicy.CACHE_ONLY ||
			(fresh && policy != LbCachePolicy.REFRESH)
		if (skipNetwork) {
			if (cached == null) emit(null)
			return@flow
		}

		val answer = revalidate(key, route, params, serializer, staleOnLibrary, keep)
		if (answer == null) {
			if (cached == null) emit(null)                  // nothing to show, nothing came back
		} else if (keep(answer)) {
			if (answer != cached) emit(answer)              // the revalidated body
		} else if (cached == null) {
			emit(answer)                                    // an empty answer, when it's all there is
		}
	}

	/** [cachedGet] for a reified [T], so each route below reads as one line. */
	private inline fun <reified T : Any> cachedGet(
		route: String,
		params: List<Pair<String, String>>,
		maxAgeMs: Long,
		staleOnLibrary: Boolean = false,
		policy: LbCachePolicy = LbCachePolicy.NORMAL,
		noinline keep: (T) -> Boolean = { true }
	): Flow<T?> = cachedGet(serializer<T>(), route, params, maxAgeMs, staleOnLibrary, policy, keep)

	/**
	 * The network half of [cachedGet]: join the request already in flight for [key], or start
	 * one in [scope]. A request that started at or before the last library change is not joined
	 * by an ownership-bearing read — its answer may predate the landing — and a fresh one starts.
	 */
	@Suppress("UNCHECKED_CAST")
	private suspend fun <T : Any> revalidate(
		key: String,
		route: String,
		params: List<Pair<String, String>>,
		serializer: KSerializer<T>,
		staleOnLibrary: Boolean,
		keep: (T) -> Boolean
	): T? {
		val running = revalidationLock.withLock {
			revalidations[key]
				?.takeIf { it.result.isActive }
				?.takeIf { !staleOnLibrary || it.startedAt > libraryStaleAt }
				?: run {
					val startedAt = nowMs()
					val deferred = scope.async {
						val text = getText(route, params).valueOrNull() ?: return@async null
						val decoded = decodeOrNull(serializer, text) ?: return@async null
						if (keep(decoded)) {
							try {
								responseCache.putCachedResponseIfNewer(
									LbResponseCacheEntity(key = key, body = text, fetchedAt = startedAt)
								)
							} catch (e: CancellationException) {
								throw e
							} catch (e: Exception) {
								Logger.w("LbBotManager", "response cache write failed for $route", e)
							}
						}
						decoded
					}
					Revalidation(startedAt, deferred).also { revalidation ->
						revalidations[key] = revalidation
						deferred.invokeOnCompletion {
							scope.launch {
								revalidationLock.withLock {
									if (revalidations[key] === revalidation) revalidations.remove(key)
								}
							}
						}
					}
				}
		}
		return try {
			running.result.await() as T?
		} catch (e: CancellationException) {
			throw e
		} catch (e: Exception) {
			null
		}
	}

	private suspend fun pruneOnce() {
		if (pruned) return
		pruned = true
		try {
			responseCache.pruneCachedResponses(nowMs() - RESPONSE_CACHE_PRUNE_MS)
		} catch (e: CancellationException) {
			throw e
		} catch (e: Exception) {
			Logger.w("LbBotManager", "response cache prune failed", e)
		}
	}

	// ----- reads ------------------------------------------------------------ //

	/**
	 * Whether the lb-bot layer is usable at all. The hub answers this route even with
	 * no LBBOT_URL configured — that is the whole point of routing the prefix
	 * unconditionally, so "not configured" arrives as JSON rather than as the
	 * WebSocket upgrade's 426 text/plain.
	 */
	suspend fun probeAvailable(): Boolean {
		val probe = getJson<LbStatusProbe>("/lb/status", emptyList()).valueOrNull()
		if (probe == null) {
			_available.value = false
			_availableAt = 0L        // a failure is not an answer worth caching
			return false
		}
		_hubRoutes.value = probe.routes
		val ok = probe.configured && probe.upstreamReachable
		_available.value = ok
		_availableAt = nowMs()
		return ok
	}

	/**
	 * Last known availability, cached for the process rather than per composable.
	 *
	 * This exists because `RootBottomBar` is mounted **inside each screen's own
	 * `Scaffold`** — thirteen call sites — and a tab tap does `backStack.clear()`,
	 * so the whole bar is destroyed and rebuilt on every navigation. A
	 * `remember { mutableStateOf(false) }` there therefore restarted at *false* and
	 * re-ran a network probe every time, which made the lb-bot tab vanish for the
	 * length of a round trip and then reappear — worst on the lb-bot tab itself,
	 * where the bar you navigated *to* is the one missing its own button.
	 *
	 * Collect this instead: it is already true on remount, and the thirteen probes
	 * per session collapse into one.
	 */
	private val _available = MutableStateFlow(false)
	val available: StateFlow<Boolean> = _available.asStateFlow()
	private var _availableAt = 0L

	/**
	 * Probe at most once per [AVAILABILITY_TTL_MS], so a hub that comes back is
	 * noticed without every screen paying for it. Safe to call from any composable
	 * on every composition, and from any loader that is about to use the layer.
	 *
	 * Returns the answer, because every caller that is deciding whether to make a
	 * request needs it, and the ones that read [available] separately were calling
	 * [probeAvailable] instead — paying an unconditional `/lb/status` round trip
	 * each time and defeating this cache entirely. An artist page open cost two of
	 * them, serially, before either real request left.
	 */
	suspend fun ensureAvailability(): Boolean {
		if (_availableAt != 0L && nowMs() - _availableAt < AVAILABILITY_TTL_MS) {
			return _available.value
		}
		return probeAvailable()
	}

	/**
	 * The hub's `welcome.lb` — `{available, routes}` (navi-connect contract §1a), the same two
	 * answers [probeAvailable] gets from `/lb/status`, delivered on the socket instead.
	 *
	 * Written into exactly the state the probe writes, stamped as fresh, so the first
	 * [ensureAvailability] after a connect answers from memory rather than paying a `/lb/status`
	 * round trip before the artist page's first real request. `routes` is the probe's list
	 * verbatim, so [advertisesRoute] behaves identically whichever path filled it.
	 *
	 * Only called when the frame carries `lb`. An older hub sends no such field, nothing here is
	 * touched, and the probe path works exactly as before — a missing advert must never be read as
	 * "unavailable".
	 */
	fun applyHubAdvert(available: Boolean, routes: List<String>) {
		_hubRoutes.value = routes
		_available.value = available
		_availableAt = nowMs()
	}

	/**
	 * One page of the index change feed, for [LbIndexSync]. Through the hub's single-slot `sync`
	 * pool: a `Rejected(503)` means another client's sync holds the slot and is a BACKOFF, never
	 * a reason to touch the mirror. [epoch] blank (a never-synced mirror) is omitted, which lb-bot
	 * reads as "no epoch check".
	 */
	suspend fun indexChanges(since: Long, epoch: String): LbResult<LbIndexChanges> =
		getJson(
			"/lb/index/changes",
			listOf("since" to since.toString(), "epoch" to epoch)
		)

	/** Every `{key, seq}` of the index, for the drift check. Same pool and 503 rule. */
	suspend fun indexKeys(): LbResult<LbIndexKeys> =
		getJson("/lb/index/keys", emptyList())

	/** False only when the hub advertised a route list without the change feed. */
	val supportsIndexMirror: Boolean
		get() = advertisesRoute("GET /lb/index/changes")

	/**
	 * Route names the hub advertised on the last probe, or empty if it is old enough
	 * not to advertise any. Used to tell "this hub can't do that" apart from "that
	 * failed", which otherwise look identical from here.
	 */
	private val _hubRoutes = MutableStateFlow<List<String>>(emptyList())

	/**
	 * Whether the hub in front of lb-bot proxies a given route.
	 *
	 * An **empty** list is an older hub that does not advertise at all: treat it
	 * as capable rather than hiding a feature that probably works. Anything else
	 * is authoritative, because this client ships independently of the hub and
	 * the hub only picks up an edit when it restarts — so "my app is newer than
	 * my hub" is a permanent condition, not an edge case, and without asking it
	 * surfaces as a button that silently 404s.
	 *
	 * The named properties below are the call sites that read well as a name; the
	 * Discover catalogue asks by route string because each of its rows names a
	 * different one.
	 */
	fun advertisesRoute(route: String): Boolean =
		_hubRoutes.value.isEmpty() || _hubRoutes.value.any { it == route }

	/** False only when the hub answered a route list that doesn't include gap filling. */
	val supportsGapFilling: Boolean
		get() = advertisesRoute("POST /lb/gap/auto")

	/** As [supportsGapFilling], for the source picker. */
	val supportsSourcePicker: Boolean
		get() = advertisesRoute("GET /lb/album/sources")

	/** As [supportsGapFilling], for the Discover screen's "Fans also like" row. */
	val supportsSimilarArtists: Boolean
		get() = advertisesRoute("GET /lb/artist/similar")

	/**
	 * One artist's stored discography. An instant SQLite read upstream keyed by the
	 * Navidrome artist id this page already holds, so it is safe on every page open —
	 * the expensive MusicBrainz walk is only ever [indexArtist].
	 *
	 * `indexed == false` means "never scanned", which the UI offers to fix. It is not
	 * an error, and neither is a null return.
	 */
	suspend fun discography(ndId: String, mbid: String?): LbDiscography? {
		if (ndId.isBlank() && mbid.isNullOrBlank()) return null
		val raw = getJson<LbDiscography>(
			"/lb/artist/discography",
			listOf("nd_id" to ndId, "mbid" to (mbid ?: ""))
		).valueOrNull() ?: return null
		// The scan record rides along either way: a first scan that failed is exactly
		// the unindexed case, and dropping it there hid the reason.
		return if (raw.indexed) raw else LbDiscography(indexed = false, scan = raw.scan)
	}

	/**
	 * Start the MusicBrainz walk for one artist. Slow (one request a second upstream,
	 * 10-60s for a real discography) and always an explicit user action.
	 *
	 * Returns whether the scan was accepted. The task id it answers with is
	 * deliberately discarded: polling lb-bot's task API deep-copies its entire review
	 * state under a process-wide lock, and it is not whitelisted on the hub. Re-read
	 * the (instant) discography instead and let the section appear when it appears.
	 */
	suspend fun indexArtist(mbid: String, name: String, ndId: String): String? {
		if (mbid.isBlank() || name.isBlank()) return null
		val result = postJson<_, LbTaskStarted>(
			"/lb/artist/discography",
			LbIndexRequest(mbid, name, ndId)
		)
		// The id is only ever matched against the discography's own `scan` record, never
		// polled: lb-bot's task API is not whitelisted (see above).
		return (result as? LbResult.Ok)?.value?.takeIf { it.ok }?.taskId
	}

	/**
	 * Wait for the scan [taskId] to finish, reading the (instant) discography.
	 *
	 * A scan MusicBrainz failed used to be invisible: the index never changed, the spinner
	 * ran out, and the page looked like an artist with nothing missing. lb-bot now keeps
	 * the old discography and reports the failure on the discography read itself, matched
	 * here by task id — not by time, since lb-bot's clock is not this device's.
	 *
	 * **This is the fallback, not the feedback.** A finished scan reaches the page through
	 * the index mirror — lb-bot's `index` frame, a pull, the page's mirror Flow — within a
	 * couple of seconds, and the pages race this against that. What only this read can say is
	 * that the scan FAILED (the scan record is task state, not index state, so the mirror never
	 * carries it). So the poll runs every [SCAN_POLL_INTERVAL_MS] = 15 s instead of 5, and is
	 * woken early by lb-bot's own `artistScanned` library frame for this artist, which it sends
	 * on failure as well as success and which clears the hub's cached discography first — so
	 * the read it triggers is fresh. A frame that lands between two reads is simply missed and
	 * the 15 s tick covers it.
	 *
	 * Not the fill poll: `pollLoop` and its constants are a different loop (contract §1b).
	 */
	suspend fun awaitArtistScan(
		ndId: String,
		mbid: String?,
		taskId: String,
		scannedBefore: Double
	): LbScanOutcome {
		val deadline = nowMs() + SCAN_WAIT_MS
		while (nowMs() < deadline) {
			withTimeoutOrNull(SCAN_POLL_INTERVAL_MS) {
				libraryEvents.first { it.event == EVENT_ARTIST_SCANNED && it.ndArtistId == ndId }
			}
			val data = discography(ndId, mbid) ?: continue
			val scan = data.scan?.takeIf { it.taskId == taskId }
			if (scan?.state == "failed") {
				return LbScanOutcome.Failed(scan.error.ifBlank { "The discography scan failed" }, data)
			}
			if (data.indexed && data.scannedAt > scannedBefore) return LbScanOutcome.Done(data)
		}
		return LbScanOutcome.TimedOut
	}

	/**
	 * Add or refresh **one** release-group in an artist's stored index.
	 *
	 * The alternative is [indexArtist] — a whole-artist MusicBrainz walk at one
	 * request a second — for a single album. And it is needed constantly rather than
	 * rarely: lb-bot serves a stored discography immediately even when stale *by
	 * design*, so anything released since the last scan is simply absent, which is
	 * every row the Fresh tab leads to.
	 *
	 * `external = true` for an artist off the library (`mb:<mbid>`): there is nothing
	 * to match against, so the release classifies as `missing`.
	 *
	 * Gated on [supportsSingleReleaseIndex]: an older hub proxies no such route and
	 * answers a plain 404, which no HTTP client raises as an error.
	 */
	suspend fun indexRelease(
		rgid: String,
		mbid: String,
		ndId: String = "",
		name: String = "",
		external: Boolean = false,
		/**
		 * lb-bot's MusicBrainz-outage override, used upstream ONLY when the
		 * release-group lookup answers nothing. It caches a transient failure for
		 * five minutes per exact query and returns {} inside that window without
		 * asking again, so one 503 was a hard 502 on that album for everyone who
		 * asked next — and the caller reached here from a row that already names
		 * the release. [title] is the field it cannot do without.
		 */
		title: String = "",
		artist: String = "",
		type: String = "",
		year: String = ""
	): LbResult<LbOk> {
		if (rgid.isBlank() || (mbid.isBlank() && ndId.isBlank())) {
			return LbResult.Failed(LbError.Rejected(400, ""))
		}
		return postJson(
			"/lb/artist/release",
			LbIndexReleaseRequest(rgid, mbid, ndId, name, external, title, artist, type, year)
		)
	}

	/** As [supportsGapFilling], for the single-release index refresh. */
	val supportsSingleReleaseIndex: Boolean
		get() = _hubRoutes.value.isEmpty() ||
			_hubRoutes.value.any { it == "POST /lb/artist/release" }

	/**
	 * ListenBrainz's site-wide fresh-releases feed, recent **and** upcoming — a
	 * `releaseDate` in the future is normal, not a bug.
	 *
	 * Two *independent* ownership flags, and conflating them would hide exactly the
	 * set this screen exists for: [LbFreshRelease.artistOwned] says the artist is in
	 * the library, [LbFreshRelease.releaseOwned] says this release-group is on disk.
	 * An owned artist with a brand-new album is still a download.
	 *
	 * Fail-soft like [discography]: ListenBrainz being down answers null and the tab
	 * says so rather than erroring.
	 */
	fun freshReleases(
		days: Int,
		limit: Int = FRESH_LIMIT,
		policy: LbCachePolicy = LbCachePolicy.NORMAL
	): Flow<LbFreshFeed?> =
		cachedGet<LbFreshFeed>(
			"/lb/fresh-releases",
			listOf("days" to days.toString(), "limit" to limit.toString()),
			CACHE_FRESH_MS,
			staleOnLibrary = true,        // artistOwned / releaseOwned on every row
			policy = policy
		) { it.releases.isNotEmpty() }

	/**
	 * Editorial "About" for an artist: the real, full-length, attributed text this app
	 * has never shown. What the artist header carries today is Navidrome's Last.fm
	 * summary put through a naive 200-character `take()` — with no HTML stripping, so a
	 * short bio renders the literal `<a href="https://www.last.fm/...">` on screen.
	 *
	 * Prefer [mbid]; passing only [name] costs lb-bot a MusicBrainz search and takes its
	 * top hit, which for a generically-named artist is a coin toss. Both are accepted
	 * because Navidrome does not always carry an MBID.
	 *
	 * Fail-soft: no hub, no LBBOT_URL, or simply nobody having written about this artist
	 * all answer null, and the section does not render. Never an error — §7.
	 */
	fun artistMeta(
		mbid: String?,
		name: String?,
		policy: LbCachePolicy = LbCachePolicy.NORMAL
	): Flow<LbMeta?> {
		if (mbid.isNullOrBlank() && name.isNullOrBlank()) return flowOf(null)
		return cachedGet<LbMeta>(
			"/lb/meta/artist",
			if (!mbid.isNullOrBlank()) listOf("mbid" to mbid)
			else listOf("name" to (name ?: "")),
			CACHE_META_MS,
			policy = policy
		) { it.found }
	}

	/**
	 * The same for a release-group, plus release credits (producer, engineer, writer).
	 *
	 * [releaseMbid] is an optimisation, not a requirement: it saves lb-bot resolving the
	 * canonical release, which costs two rate-limited MusicBrainz seconds on a cold call.
	 */
	fun albumMeta(
		rgid: String,
		releaseMbid: String? = null,
		policy: LbCachePolicy = LbCachePolicy.NORMAL
	): Flow<LbMeta?> {
		if (rgid.isBlank()) return flowOf(null)
		return cachedGet<LbMeta>(
			"/lb/meta/album",
			buildList {
				add("rgid" to rgid)
				if (!releaseMbid.isNullOrBlank()) add("release_mbid" to releaseMbid)
			},
			CACHE_META_MS,
			policy = policy
		) { it.found }
	}

	/**
	 * "Similar albums" for an album page — one record per similar artist, all drawn
	 * from **your own library**, so it is a rediscovery shelf and not a shopping list.
	 *
	 * Shipped in lb-bot and whitelisted on the hub since the Fresh work landed, and
	 * consumed by no client until now: it was step 5 of the client-integration design.
	 *
	 * It keys on the *artist*, not the album — similarity is artist-to-artist
	 * (ListenBrainz, cross-checked with Last.fm) and rolled up to one album each.
	 * [rgid] only excludes the album on screen, and lb-bot backfills the slot.
	 */
	suspend fun similarAlbums(
		artistMbid: String?,
		artistName: String?,
		rgid: String? = null,
		limit: Int = 6
	): LbSimilarAlbums? {
		if (artistMbid.isNullOrBlank() && artistName.isNullOrBlank()) return null
		return getJson<LbSimilarAlbums>("/lb/album/similar", buildList {
			if (!artistMbid.isNullOrBlank()) add("artist_mbid" to artistMbid)
			if (!artistName.isNullOrBlank()) add("artist_name" to artistName)
			if (!rgid.isNullOrBlank()) add("rgid" to rgid)
			add("limit" to limit.toString())
		}).valueOrNull()
	}

	/**
	 * "Fans also like" — similar artists, the ones you do **not** own included.
	 *
	 * The sibling [similarAlbums] is deliberately a shelf of records you already
	 * hold; lb-bot filters the unowned candidates out of it on purpose. This route
	 * marks ownership instead, so the artists you are missing survive — which is
	 * the only reason the Discover row can exist at all.
	 *
	 * Cheap upstream: the merge is cached for 24h on lb-bot's side and never
	 * touches its MusicBrainz lock, unlike [artistLookup] below.
	 */
	fun similarArtists(
		artistMbid: String?,
		artistName: String?,
		limit: Int = 20,
		policy: LbCachePolicy = LbCachePolicy.NORMAL
	): Flow<LbSimilarArtists?> {
		if (artistMbid.isNullOrBlank() && artistName.isNullOrBlank()) return flowOf(null)
		return cachedGet<LbSimilarArtists>(
			"/lb/artist/similar",
			buildList {
				if (!artistMbid.isNullOrBlank()) add("mbid" to artistMbid)
				if (!artistName.isNullOrBlank()) add("name" to artistName)
				add("limit" to limit.toString())
			},
			CACHE_SIMILAR_MS,
			staleOnLibrary = true,        // `owned` / `artistId` / `indexed` per row
			policy = policy
		) { it.artists.isNotEmpty() }
	}

	/**
	 * Deezer's own similarity, as a **third** source beside ListenBrainz's.
	 *
	 * Same answer shape as [similarArtists] and marked with the same ownership
	 * vocabulary (`owned` / `artistId` / `indexed`), so the two merge without the
	 * caller translating anything. Worth having because the two sources disagree
	 * usefully: ListenBrainz knows what is listened to together, Deezer knows what
	 * an editorial catalogue files together, and an artist missing from one is
	 * routinely present in the other.
	 *
	 * Short-cached upstream (60 s), because ownership is part of the answer and a
	 * landed fill falsifies it.
	 */
	fun relatedArtists(
		artistMbid: String?,
		artistName: String?,
		limit: Int = 20,
		policy: LbCachePolicy = LbCachePolicy.NORMAL
	): Flow<LbSimilarArtists?> {
		if (artistMbid.isNullOrBlank() && artistName.isNullOrBlank()) return flowOf(null)
		return cachedGet<LbSimilarArtists>(
			"/lb/artist/related",
			buildList {
				if (!artistMbid.isNullOrBlank()) add("mbid" to artistMbid)
				if (!artistName.isNullOrBlank()) add("name" to artistName)
				add("limit" to limit.toString())
			},
			CACHE_SIMILAR_MS,
			staleOnLibrary = true,
			policy = policy
		) { it.artists.isNotEmpty() }
	}

	/**
	 * Deezer's charts — what is being played everywhere, ownership-marked.
	 *
	 * [genre] is one of [deezerGenres]'s ids; `"0"` is the global chart. There is
	 * deliberately no country parameter: Deezer's open API has none, and the chart
	 * is geolocated by **lb-bot's own egress address**, so which country's chart
	 * this is depends on where the server runs and no client can change it.
	 */
	fun deezerChart(
		limit: Int = 20,
		genre: String = "0",
		policy: LbCachePolicy = LbCachePolicy.NORMAL
	): Flow<LbBrowseFeed?> =
		cachedGet<LbBrowseFeed>(
			"/lb/deezer/chart",
			listOf("limit" to limit.toString(), "genre" to genre),
			CACHE_CHART_MS,
			staleOnLibrary = true,        // releaseOwned / owned — the badges move, the chart barely does
			policy = policy
		) { it.albums.isNotEmpty() || it.artists.isNotEmpty() }

	/** Deezer's editorial selections — what a human picked, ownership-marked. */
	fun deezerEditorial(
		limit: Int = 20,
		genre: String = "0",
		policy: LbCachePolicy = LbCachePolicy.NORMAL
	): Flow<LbBrowseFeed?> =
		cachedGet<LbBrowseFeed>(
			"/lb/deezer/editorial",
			listOf("limit" to limit.toString(), "genre" to genre),
			CACHE_CHART_MS,
			staleOnLibrary = true,
			policy = policy
		) { it.albums.isNotEmpty() || it.artists.isNotEmpty() }

	/**
	 * The genres the two rows above can be narrowed to.
	 *
	 * From lb-bot rather than a constant here, because the ids are Deezer's and
	 * there are two clients that would otherwise each hold their own copy — the
	 * `MoodCharacter` mistake, which has already drifted once.
	 */
	fun deezerGenres(policy: LbCachePolicy = LbCachePolicy.NORMAL): Flow<LbDeezerGenres?> =
		cachedGet<LbDeezerGenres>(
			"/lb/deezer/genres",
			emptyList(),
			CACHE_GENRES_MS,
			policy = policy
		) { it.genres.isNotEmpty() }

	/**
	 * Whether this hub can answer the genre list.
	 *
	 * An older hub does not advertise the route, and the picker hides itself — the
	 * two rows then behave exactly as they did before, on Deezer's global chart.
	 */
	val supportsDeezerGenres: Boolean
		get() = advertisesRoute("GET /lb/deezer/genres")

	/**
	 * A pasted streaming URL, resolved to MusicBrainz ids.
	 *
	 * The point of the route is that "I found this on Spotify" becomes "I own this",
	 * with no intermediate searching by hand. Resolution quality varies by provider
	 * and lb-bot says so rather than pretending — a MusicBrainz URL resolves exactly
	 * and a scraped page title resolves by search — which is why [LbLinkResolution]
	 * carries `confidence` and why the UI shows what it thinks the link is before
	 * acting on it.
	 *
	 * Null for anything we could not ask, and `kind == "unknown"` for a URL lb-bot
	 * could not make sense of. The two read differently to the user on purpose: one
	 * is "we could not ask", the other "that is not a link we understand".
	 */
	suspend fun resolveLink(url: String): LbLinkResolution? {
		if (url.isBlank()) return null
		return postJson<_, LbLinkResolution>("/lb/resolve-link", LbResolveLinkRequest(url))
			.valueOrNull()
	}

	// ----- wishlist ---------------------------------------------------------- //

	/**
	 * Releases the user still wants but nobody was sharing.
	 *
	 * This is the one place `retryable: false` stops being a dead end.
	 * A `no_source` failure deliberately never auto-retries — re-running the same
	 * ranked search against the same peers is the same failure, immediately — so
	 * the correct home for that retry is a persisted list plus lb-bot's own slow
	 * periodic re-search, measured in hours. A landing arrives through the existing
	 * `library` frame, exactly like any other fill.
	 */
	suspend fun wishlist(): List<LbWishlistItem> =
		getJson<LbWishlist>("/lb/wishlist", emptyList()).valueOrNull()?.wishlist.orEmpty()

	/**
	 * Add, and take the updated list straight off the answer.
	 *
	 * Both write routes echo the whole wishlist back, so a caller that re-read it
	 * would be spending a second round trip on something it was already handed. Null
	 * means the write failed and the caller should leave its list alone — which is
	 * not the same as an empty list, and is why this is nullable rather than an
	 * empty default.
	 */
	suspend fun wishlistAdd(rgid: String, artist: String, title: String): List<LbWishlistItem>? =
		postJson<_, LbWishlist>("/lb/wishlist", LbWishlistAddRequest(rgid, artist, title))
			.valueOrNull()?.wishlist

	suspend fun wishlistRemove(rgid: String): List<LbWishlistItem>? =
		postJson<_, LbWishlist>("/lb/wishlist/remove", LbWishlistRemoveRequest(rgid))
			.valueOrNull()?.wishlist

	/** As [supportsGapFilling], for the wishlist surfaces. */
	val supportsWishlist: Boolean
		get() = advertisesRoute("GET /lb/wishlist")

	/** As [supportsGapFilling], for the paste-a-link entry points. */
	val supportsResolveLink: Boolean
		get() = advertisesRoute("POST /lb/resolve-link")

	/**
	 * MusicBrainz artist search — the "Not in your library" half of search.
	 *
	 * Until `/lb/artist/lookup` was whitelisted on the hub, a client could only
	 * reach an external artist page if it already held an MBID from a Fresh row,
	 * which blocked every acquisition path that starts with "I want this artist".
	 *
	 * A live MusicBrainz search behind lb-bot's global 1 req/sec lock, not a local
	 * index read: call it debounced, and not on every keystroke. Empty list when we
	 * could not ask, so the section simply does not render.
	 */
	suspend fun artistLookup(query: String): List<LbArtistCandidate> {
		if (query.isBlank()) return emptyList()
		return getJson<LbArtistLookup>("/lb/artist/lookup", listOf("q" to query))
			.valueOrNull()?.candidates.orEmpty()
	}

	/**
	 * MusicBrainz album search — the album half of reaching past the library.
	 *
	 * Same 1 req/sec lock as [artistLookup], so the same rules: debounced term
	 * only, never a keystroke. Note a search box that fires both spends two of
	 * lb-bot's global seconds per settled query.
	 *
	 * Unlike [artistLookup] this answer marks ownership per candidate, by
	 * release-group id rather than by a name match — so an owned hit is a known
	 * fact and the row opens the library album instead of a download page.
	 */
	suspend fun albumLookup(query: String): List<LbAlbumCandidate> {
		if (query.isBlank()) return emptyList()
		return getJson<LbAlbumLookup>("/lb/album/lookup", listOf("q" to query))
			.valueOrNull()?.candidates.orEmpty()
	}

	/**
	 * Editions of one release-group. Rate-limited MusicBrainz upstream, which is why it is cached
	 * for a month: a release-group's editions are MusicBrainz data and carry no ownership, so
	 * only a new pressing makes an old answer wrong. A skeleton only on the first-ever open.
	 */
	fun albumReleases(
		rgid: String,
		policy: LbCachePolicy = LbCachePolicy.NORMAL
	): Flow<LbReleaseDetail?> {
		if (rgid.isBlank()) return flowOf(null)
		return cachedGet<LbReleaseDetail>(
			"/lb/album/releases",
			listOf("rgid" to rgid),
			CACHE_RELEASES_MS,
			policy = policy
		) { it.releases.isNotEmpty() }
	}

	/**
	 * Ranked Soulseek folders for a release-group — what a download would actually
	 * fetch, before fetching it.
	 *
	 * This is the answer to "did it grab the right record". Coverage here is matched
	 * against the canonical MusicBrainz tracklist rather than counted, so a folder
	 * holding a different album with the right number of files no longer reads as a
	 * complete match — which is precisely how a self-titled album goes wrong.
	 *
	 * Slow: a live slskd search fanning out to peers, tens of seconds. The hub caches
	 * it briefly so re-opening the sheet doesn't start another one.
	 */
	suspend fun albumSources(
		rgid: String,
		edition: LbResolvedEdition? = null
	): LbResult<LbAlbumSources> {
		if (rgid.isBlank()) return LbResult.Failed(LbError.Rejected(400, ""))
		return getJson("/lb/album/sources", buildList {
			add("rgid" to rgid)
			edition?.let {
				add("release_mbid" to it.releaseMbid)
				add("artist" to it.artist)
				add("album" to it.title)
				// Sizes lb-bot's slskd folder search; a wrong number costs match quality, so
				// send nothing rather than a zero we haven't actually counted.
				if (it.totalTracks > 0) add("total" to it.totalTracks.toString())
			}
		})
	}

	/**
	 * Canonical tracklist for one release. With [albumIds] or [groupId] every track
	 * also carries its own `present` flag; without them `presenceKnown` is false,
	 * which means "the library holds none of this" — not an error, and not `0/12`.
	 *
	 * Cached a month, like [albumReleases] — the track titles are MusicBrainz's. **But a
	 * tracklist asked for WITH presence carries per-track `present`, which no age bound makes
	 * true**: a landing flips it and nothing about the release changes. That form is therefore
	 * ownership-bearing and goes stale on every library change ([markLibraryStale]), exactly as
	 * Fresh and the Discover rows do. Without presence it is pure MusicBrainz and is not.
	 */
	fun tracklist(
		releaseMbid: String,
		albumIds: List<String> = emptyList(),
		groupId: String = "",
		policy: LbCachePolicy = LbCachePolicy.NORMAL
	): Flow<LbTracklist?> {
		if (releaseMbid.isBlank()) return flowOf(null)
		return cachedGet<LbTracklist>(
			"/lb/album/tracklist",
			listOf(
				"release_mbid" to releaseMbid,
				"album_ids" to albumIds.joinToString(","),
				"group_id" to groupId
			),
			CACHE_RELEASES_MS,
			staleOnLibrary = albumIds.isNotEmpty() || groupId.isNotBlank(),
			policy = policy
		) { it.tracks.isNotEmpty() }
	}

	/**
	 * Ask lb-bot to acquire a whole release-group.
	 *
	 * Not fast: it resolves the release-group against MusicBrainz before answering, so
	 * the button needs a spinner. Idempotent upstream — a second tap (or a tap from
	 * another device) comes back `existing`, rather than starting a second search, a
	 * second set of transfers and a second placement pass over one folder.
	 */
	suspend fun download(
		rgid: String,
		quality: String,
		source: LbGapSource? = null,
		edition: LbResolvedEdition? = null,
		excludeUsers: List<String> = emptyList(),
		/** Widen this one album's search to MP3 — the whole-album counterpart of a gap
		 *  group's opt-in. A `format_rejected` fill with no review group had
		 *  `mp3WouldHelp` on the wire and no route that could act on it. */
		allowMp3: Boolean = false
	): LbResult<LbDownloadResult> {
		// Never `release_mbid` without `rgid`: lb-bot would happily download it, but the task then
		// carries no release-group, so placement can't flip the index row to `present` and the
		// filled album double-lists as missing forever.
		if (rgid.isBlank()) return LbResult.Failed(LbError.Rejected(400, ""))
		// An empty quality is omitted rather than sent: lb-bot reads a missing key as
		// "use the global Source preference", while an explicit "" would make this
		// download look like an override in its own status view. Same for the source.
		val res = postJson<_, LbDownloadResponse>(
			"/lb/album/download",
			LbDownloadRequest(
				rgid = rgid,
				releaseMbid = edition?.releaseMbid?.ifBlank { null },
				artist = edition?.artist?.ifBlank { null },
				title = edition?.title?.ifBlank { null },
				totalTracks = edition?.totalTracks?.takeIf { it > 0 },
				quality = quality.ifBlank { null },
				sourceUsername = source?.peer?.ifBlank { null },
				sourceFolder = source?.folder?.ifBlank { null },
				excludeUsers = excludeUsers.filter { it.isNotBlank() }.distinct().ifEmpty { null },
				allowMp3 = if (allowMp3) true else null
			)
		)
		return when (res) {
			is LbResult.Failed -> res
			is LbResult.Ok -> LbResult.Ok(
				LbDownloadResult(
					ok = res.value.ok,
					existing = res.value.existing,
					releaseMbid = res.value.resolved?.releaseMbid.orEmpty()
				)
			)
		}
	}

	/**
	 * One gesture to acquire a release the library doesn't have — reviewed, not blind.
	 *
	 * The blind version existed once and was removed: it fetched the wrong record for a
	 * self-titled album, where every candidate folder's name looks plausible, and nothing
	 * before or after the fact said so. [albumSources] and [MissingAlbumSheet]'s two-step
	 * picker are what replaced it. So this does the review on the user's behalf and only
	 * skips the sheet when there is nothing left to decide — lb-bot's top-ranked folder,
	 * its own "is this the right record" verdict, and coverage complete **against the
	 * canonical MusicBrainz tracklist rather than a file count**. Anything else comes
	 * back as [AcquireOutcome.NeedsReview] and the caller opens the sheet.
	 *
	 * Two things the caller has to honour:
	 *
	 * 1. **One gesture is not an instant result.** [albumSources] is a live slskd
	 *    fan-out, tens of seconds — and until the download is posted there is no watch
	 *    and therefore nothing in the ledger to render. Show a spinner on the control
	 *    itself or the row looks inert and gets tapped again.
	 * 2. **Quality is a ranking term upstream, not a filter**, so the top-ranked folder
	 *    can legitimately be MP3 when the preference is FLAC. When the preference is
	 *    lb-bot's own default ("") there is nothing to check here — it lives on the
	 *    server. When this client has been set to a *lossless* one, a lossy top pick is
	 *    exactly the surprise the source row exists to prevent, so it goes to the sheet.
	 *
	 * Registers the watch itself, like every other path that starts a fill: it outlives
	 * whatever composable fired it.
	 */
	suspend fun acquire(
		rgid: String,
		artist: String = "",
		album: String = ""
	): AcquireOutcome {
		if (rgid.isBlank()) return AcquireOutcome.NeedsReview(AcquireReason.UNAVAILABLE)
		val sources = when (val res = albumSources(rgid)) {
			is LbResult.Failed -> return AcquireOutcome.NeedsReview(AcquireReason.UNAVAILABLE)
			is LbResult.Ok -> res.value.sources
		}
		val top = sources.firstOrNull()
			?: return AcquireOutcome.NeedsReview(AcquireReason.NO_SOURCES)
		if (!top.albumMatchOk) {
			return AcquireOutcome.NeedsReview(AcquireReason.UNCERTAIN_MATCH)
		}
		if (!top.coverageFull || top.coverageDetail.totalTracks <= 0) {
			return AcquireOutcome.NeedsReview(AcquireReason.INCOMPLETE)
		}
		val quality = preferredQuality
		val wantsLossless = quality == "flac-any" || quality == "flac-16-44"
		if (wantsLossless && !LOSSLESS_FORMATS.matches(top.format.trim())) {
			return AcquireOutcome.NeedsReview(AcquireReason.WRONG_FORMAT)
		}
		return when (val res = download(rgid, quality, top)) {
			is LbResult.Failed -> AcquireOutcome.NeedsReview(AcquireReason.UNAVAILABLE)
			is LbResult.Ok -> {
				if (!res.value.ok || res.value.releaseMbid.isBlank()) {
					AcquireOutcome.NeedsReview(AcquireReason.UNAVAILABLE)
				} else {
					startAlbumFill(
						rgid = rgid,
						releaseMbid = res.value.releaseMbid,
						quality = quality,
						artist = artist,
						album = album,
						source = top
					)
					AcquireOutcome.Started(format = top.format, peer = top.peer)
				}
			}
		}
	}

	/**
	 * Where one release's fill has got to, failure and all.
	 *
	 * The poll path must use this rather than [fillStatus]. Collapsing a failure into a
	 * default `LbFillStatus` makes it arrive as `state = "unknown"`, which is a real
	 * lb-bot answer meaning "nothing is filling this" — so a transient 502 (on this
	 * route almost always "lb-bot is busy holding its lock", not "lb-bot is gone") both
	 * blanked a downloading tile for five seconds and spent one of the eighteen
	 * `unknown` credits that exist to detect a fill which never started.
	 */
	suspend fun fillStatusResult(releaseMbid: String): LbResult<LbFillStatus> =
		getJson(
			"/lb/album/status",
			listOf("release_mbid" to releaseMbid),
			timeoutMs = POLL_TIMEOUT_MS
		)

	/** Where one release's fill has got to. For one-shot reads, where "no answer" and
	 *  "no fill" are equally uninteresting; polls want [fillStatusResult]. */
	suspend fun fillStatus(releaseMbid: String): LbFillStatus =
		fillStatusResult(releaseMbid).valueOrNull() ?: LbFillStatus()

	/**
	 * Every watched fill in one read — the ledger's poll.
	 *
	 * A Download Center with eight rows used to poll eight times per tick through the
	 * hub's four proxy slots and lb-bot's locks. This is one request, on the hub's
	 * fast pool, that never queues behind a source search.
	 */
	suspend fun fills(releaseMbids: List<String>, groupIds: List<String>): LbResult<LbFillsResponse> =
		getJson(
			"/lb/fills",
			listOf(
				"release_mbids" to releaseMbids.filter { it.isNotBlank() }.distinct().take(32).joinToString(","),
				"group_ids" to groupIds.filter { it.isNotBlank() }.distinct().take(32).joinToString(",")
			),
			timeoutMs = POLL_TIMEOUT_MS
		)

	/**
	 * Widen the accepted formats for one album's searches. lb-bot's global policy stays
	 * flac/opus; this only adds mp3, ranked last, for this album. Offered only when a
	 * status or a gap reported `mp3WouldHelp`.
	 */
	suspend fun allowMp3(groupId: String, allow: Boolean = true): LbResult<LbOk> {
		if (groupId.isBlank()) return LbResult.Failed(LbError.Rejected(400, ""))
		return postJson("/lb/album/allow-mp3", LbAllowMp3Request(groupId, allow))
	}

	// ----- gaps: albums the library already has, partly -------------------- //

	/**
	 * One partly-owned album's Fill-gaps record: which tracks are missing, which
	 * sources were found for them, and how the last attempt ended.
	 *
	 * The `group_id` this takes comes straight off an `incomplete` discography row.
	 * lb-bot's discography scan does not merely label such a release — it builds the
	 * review group at the same time — so the handle is live without any separate scan.
	 */
	suspend fun gapDetail(
		groupId: String,
		sourcePage: Int = 0,
		polling: Boolean = false
	): LbResult<LbGap> {
		if (groupId.isBlank()) return LbResult.Failed(LbError.Rejected(400, ""))
		return getJson(
			"/lb/gap",
			listOf("group_id" to groupId, "sourcePage" to sourcePage.toString()),
			timeoutMs = if (polling) POLL_TIMEOUT_MS else null
		)
	}

	/**
	 * One gap source's real folder listing, each file tagged with the track it would
	 * fill.
	 *
	 * Fetched on demand rather than ridden along with the poll: the hub strips these
	 * from `/lb/gap` because that runs every five seconds, and upstream expands the
	 * peer's directory for real, which is slow. This is what answers "does this peer
	 * have my seventeen missing tracks, or twelve from a different pressing" — the
	 * question coverage counts alone were never going to settle.
	 */
	suspend fun gapSourceFiles(groupId: String, sourceIndex: Int): LbResult<LbSourceFiles> {
		if (groupId.isBlank()) return LbResult.Failed(LbError.Rejected(400, ""))
		return getJson(
			"/lb/gap/source-files",
			listOf("group_id" to groupId, "source_index" to sourceIndex.toString())
		)
	}

	/**
	 * Search, rank and download the best source for one album's missing tracks without
	 * a manual pick.
	 *
	 * Upstream this walks the whole ranked list rather than trusting rank 1: search-time
	 * peer state goes stale within seconds, so an immediate rejection means "try the
	 * next one", not "give up". It answers with a task id, which is discarded for the
	 * same reason as in [indexArtist] — the gap detail carries a `sourceTask` field
	 * precisely so a client can watch a source search without touching the task API.
	 */
	/**
	 * Find sources for one album's missing tracks without committing to any.
	 *
	 * The review half of [gapAuto]. Splitting them is the point: a gap fill drops
	 * tracks *into* a record the user already owns, so a different pressing
	 * contaminates the album rather than merely disappointing — which is how
	 * seventeen missing tracks came back as twelve from another edition, with
	 * nothing on screen to say so beforehand.
	 *
	 * The search runs as a background task upstream; watch `sourceTask` on the gap
	 * detail rather than the task id it returns.
	 */
	suspend fun gapSearch(groupId: String, force: Boolean = false): LbResult<LbOk> {
		if (groupId.isBlank()) return LbResult.Failed(LbError.Rejected(400, ""))
		val res = postJson<_, LbOk>("/lb/gap/search", LbSearchRequest(groupId, force))
		if (res is LbResult.Ok) startGapWatch(groupId)
		return res
	}

	suspend fun gapAuto(groupId: String): LbResult<LbOk> {
		if (groupId.isBlank()) return LbResult.Failed(LbError.Rejected(400, ""))
		val res = postJson<_, LbOk>("/lb/gap/auto", LbGroupRequest(groupId))
		if (res is LbResult.Ok) startGapWatch(groupId)
		return res
	}

	/** Queue one hand-picked source (its `id` from the gap's `sources` list). */
	suspend fun gapFetch(groupId: String, sourceId: Int): LbResult<LbOk> {
		if (groupId.isBlank()) return LbResult.Failed(LbError.Rejected(400, ""))
		val res = postJson<_, LbOk>("/lb/gap/fetch", LbFetchRequest(groupId, sourceId))
		if (res is LbResult.Ok) startGapWatch(groupId)
		return res
	}

	/** Cancel every in-flight transfer belonging to one album's gap fill. */
	suspend fun gapCancel(groupId: String): LbResult<LbOk> {
		if (groupId.isBlank()) return LbResult.Failed(LbError.Rejected(400, ""))
		val res = postJson<_, LbOk>("/lb/gap/cancel", LbGroupRequest(groupId))
		if (res is LbResult.Ok) {
			settle(groupId, OUTCOME_CANCELLED, state = "cancelled")
			refreshGap(groupId)
		}
		return res
	}

	/**
	 * Re-check one album against Navidrome and its own folder, without a full library
	 * scan. This is the manual reconcile after a fill: it also picks up files put there
	 * by hand that Navidrome hasn't indexed yet.
	 */
	suspend fun gapRescan(groupId: String): LbResult<LbOk> {
		if (groupId.isBlank()) return LbResult.Failed(LbError.Rejected(400, ""))
		val res = postJson<_, LbOk>("/lb/gap/rescan", LbGroupRequest(groupId))
		if (res is LbResult.Ok) {
			refreshGap(groupId)
			onLibraryChanged()
		}
		return res
	}

	/**
	 * Read one gap and publish it, without starting a watch. Opening the sheet to look
	 * at an album is not a reason to begin polling it — only acting on it is.
	 */
	suspend fun refreshGap(groupId: String): LbResult<LbGap> {
		val result = gapDetail(groupId)
		if (result is LbResult.Ok) _gaps.update { it + (groupId to result.value) }
		return result
	}

	// ----- watches ---------------------------------------------------------- //

	/**
	 * Register a fill so it keeps being watched after the sheet closes.
	 *
	 * Deliberately here and not in the ViewModel: `ArtistDetailViewModel` is keyed per
	 * artist and disposed on navigate-away, and a fill takes minutes. The map is
	 * persisted for the same reason one step further out — the app is routinely killed
	 * inside that window.
	 */
	fun startAlbumFill(
		rgid: String,
		releaseMbid: String,
		quality: String,
		artist: String = "",
		album: String = "",
		totalTracks: Int = 0,
		source: LbGapSource? = null
	) {
		if (rgid.isBlank() || releaseMbid.isBlank()) return
		putWatch(
			LbWatch(
				kind = KIND_ALBUM,
				key = rgid,
				releaseMbid = releaseMbid,
				quality = quality,
				startedAt = nowMs(),
				// Carried purely so the downloads view and a retry need no second read.
				// The artist page has all of this at the moment of the tap; a ledger row
				// re-read hours later, possibly after lb-bot forgot the fill, has none.
				artist = artist,
				album = album,
				editionTotalTracks = totalTracks,
				sourcePeer = source?.peer.orEmpty(),
				sourceFolder = source?.folder.orEmpty()
			)
		)
	}

	fun startGapWatch(groupId: String, artist: String = "", album: String = "") {
		if (groupId.isBlank()) return
		putWatch(
			LbWatch(
				kind = KIND_GAP,
				key = groupId,
				startedAt = nowMs(),
				artist = artist,
				album = album
			)
		)
	}

	/**
	 * Re-issue a fill the ledger remembers, with the options it was started with.
	 *
	 * Not automatic, and that is the point: lb-bot already walks its entire ranked source
	 * list before it reports a failure, so an unattended retry would re-run the identical
	 * search against the identical peers. The user asking again is new information — the
	 * swarm has moved on, or they have just allowed mp3.
	 *
	 * A gap retries as `search`, never `auto`: `auto` is what failed, and `search` stops
	 * after ranking so the candidates can be judged.
	 */
	/**
	 * Stop an album fill — too slow, wrong peer, changed mind — wherever it has got to.
	 *
	 * There was no way to do this: cancelling in slskd was read by lb-bot as a transfer
	 * failure and re-queued from another peer, and a fill that never reports a failure never
	 * offers Retry, so the row sat on "downloading" with nothing to press. The row is settled
	 * from lb-bot's answer rather than the next poll, and settled even when lb-bot no longer
	 * knows the fill — from here it is not running either way.
	 */
	suspend fun cancelFill(key: String): LbResult<LbOk> {
		val watch = loadWatches()[key]
		if (watch?.kind == KIND_GAP) return gapCancel(key)
		val releaseMbid = watch?.releaseMbid?.ifBlank { null }
			?: _fills.value[key]?.releaseMbid?.ifBlank { null }
		if (releaseMbid == null) {
			if (watch == null) return LbResult.Failed(LbError.Rejected(404, ""))
			settle(key, OUTCOME_CANCELLED, state = "cancelled")
			return LbResult.Ok(LbOk(ok = true))
		}
		return when (val res = postJson<_, LbCancelResponse>("/lb/album/cancel", LbCancelRequest(releaseMbid))) {
			is LbResult.Failed -> LbResult.Failed(res.error)
			is LbResult.Ok -> {
				val status = res.value.status?.normalized()
				if (!res.value.cancelled) {
					// lb-bot answered, and said no: too late (the files are being placed)
					// or nothing was running. Its status says which — show that rather
					// than a row that claims a cancel happened.
					if (status != null && status.state != "unknown") applyAlbumStatus(key, status)
					else if (watch != null && !watch.settled) settle(key, OUTCOME_CANCELLED, state = "cancelled")
					val tooLate = status?.state in setOf("placing", "placed", "verified")
					return LbResult.Ok(LbOk(ok = true, error = if (tooLate) TOO_LATE_TO_CANCEL else ""))
				}
				status?.let { s -> _fills.update { it + (key to s) } }
				if (watch?.settled == true) {
					// A failed row whose automatic retry was pending: the verdict changes
					// in place — `settle` is once-only.
					watchLock.withLock {
						val watches = loadWatches()
						watches[key]?.let {
							saveWatches(watches + (key to it.copy(
								outcome = OUTCOME_CANCELLED, state = "cancelled",
								retryAt = 0L, cancellable = false
							)))
						}
					}
				} else {
					settle(key, OUTCOME_CANCELLED, state = "cancelled")
				}
				LbResult.Ok(LbOk(ok = true))
			}
		}
	}

	/**
	 * Retry with the peer that was the problem ruled out: the one lb-bot queued from, the one
	 * the user picked, and any excluded on an earlier go. A plain [retry] re-sends the chosen
	 * peer — exactly wrong when that peer was crawling.
	 */
	suspend fun retryAnotherSource(key: String): LbResult<LbOk> = retry(key, anotherSource = true)

	/** Retry with this one album's search widened to MP3 — for a `format_rejected` fill
	 *  that has no review group to set the opt-in on. */
	suspend fun retryAllowMp3(key: String): LbResult<LbOk> = retry(key, allowMp3 = true)

	suspend fun retry(key: String, anotherSource: Boolean = false, allowMp3: Boolean = false): LbResult<LbOk> {
		val watch = loadWatches()[key] ?: return LbResult.Failed(LbError.Rejected(404, ""))
		return when (watch.kind) {
			KIND_GAP -> gapSearch(watch.key, force = true)
			else -> {
				// Resend the edition that was chosen, not just the release-group. Without it
				// lb-bot re-resolves and may land on a different pressing — see
				// LbWatch.editionTotalTracks. A pre-snapshot ledger row has no releaseMbid, and
				// then a re-resolve is genuinely all that is available; the row says so.
				val edition = watch.releaseMbid.takeIf { it.isNotBlank() }?.let {
					LbResolvedEdition(
						releaseMbid = it,
						artist = watch.artist,
						title = watch.album,
						totalTracks = watch.editionTotalTracks
					)
				}
				if (edition == null) {
					Logger.w(
						"LbBotManager",
						"retry ${watch.key} has no edition snapshot — lb-bot will re-resolve"
					)
				}
				val excluded = if (anotherSource) watch.otherSourceExcludes() else emptyList()
				val res = download(
					rgid = watch.key,
					quality = watch.quality,
					source = watch.sourcePeer.takeIf { it.isNotBlank() && !anotherSource }?.let {
						LbGapSource(peer = it, folder = watch.sourceFolder)
					},
					edition = edition,
					excludeUsers = excluded,
					allowMp3 = allowMp3 || watch.allowMp3
				)
				when (res) {
					is LbResult.Failed -> LbResult.Failed(res.error)
					is LbResult.Ok -> {
						// Re-open the same row rather than adding a second one, so the
						// history stays one line per album rather than one per attempt.
						reopen(watch.key, res.value.releaseMbid, excludedPeers = excluded,
							allowMp3 = allowMp3 || watch.allowMp3)
						LbResult.Ok(LbOk(ok = true))
					}
				}
			}
		}
	}

	/** Forget one finished row. Only ever offered on a settled fill. */
	fun dismiss(key: String) {
		scope.launch {
			watchLock.withLock { saveWatches(loadWatches() - key) }
			// The sheet reads these; a dismissed row must not keep showing its last state.
			_fills.update { it - key }
			_gaps.update { it - key }
		}
	}

	/**
	 * A `fill` frame off the hub socket: lb-bot pushed a fill's state or progress.
	 *
	 * Goes through the same [applyAlbumStatus] the poll uses, so push cannot disagree
	 * with poll about what a state means. Nothing is replayed to a client that connects
	 * late, which is why the poll stays — as a 30 s safety net while frames arrive.
	 */
	fun onFillFrame(frame: JsonObject) {
		lastPushAt = nowMs()
		fun str(key: String): String =
			(frame[key] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
		val kind = str("kind")
		val key = str("key")
		scope.launch {
			when (kind) {
				KIND_ALBUM -> {
					val status = try {
						json.decodeFromJsonElement(LbFillStatus.serializer(), frame)
					} catch (e: Exception) {
						Logger.w("LbBotManager", "unreadable fill frame", e)
						return@launch
					}
					val watches = watchLock.withLock { loadWatches() }
					val target = when {
						key.isNotBlank() && watches.containsKey(key) -> key
						status.rgid.isNotBlank() && watches.containsKey(status.rgid) -> status.rgid
						else -> key.ifBlank { status.rgid }
					}
					if (target.isNotBlank()) applyAlbumStatus(target, status)
				}
				// The frame is a summary; the poll carries the tracks. Re-read now.
				KIND_GAP -> {
					if (key.isNotBlank()) watchLock.withLock {
						val watches = loadWatches()
						watches[key]?.let { saveWatches(watches + (key to it.copy(nextPollAt = 0L))) }
					}
					kickPoll()
				}
				// A wishlist row landed; the wishlist screen re-reads on the revision.
				"wishlist" -> _libraryRevision.value += 1
			}
		}
	}

	/** Whether this release-group has a fill we're still watching. */
	fun isFilling(rgid: String): Boolean = loadWatches()[rgid]?.settled == false

	/**
	 * The hub says something landed in the library — possibly from another client.
	 * Only ever "re-read what you can already read", so it needs no validation beyond
	 * the socket's, and missing it costs nothing: the index flip upstream is durable,
	 * so the next read is right regardless.
	 */
	fun onLibraryChanged(event: LbLibraryEvent = LbLibraryEvent()) {
		// Before the bumps: a screen that re-reads on one must already find its cached ownership
		// stale, or it would re-render the very badge the landing just falsified.
		markLibraryStale()
		_libraryEvents.tryEmit(event)
		_libraryBumps.tryEmit(event)
		_libraryRevision.value += 1
	}

	/**
	 * Room now holds what an [LbLibraryEvent] announced; screens reading Room re-read.
	 * [ndAlbumIds] are the albums that sync wrote, when it knows — empty for a sweep, which a
	 * page then cannot rule itself out of (see [libraryBumps]).
	 */
	fun onLocalLibrarySynced(ndAlbumIds: List<String> = emptyList()) {
		_libraryBumps.tryEmit(LbLibraryEvent(EVENT_LOCAL_SYNCED, ndAlbumIds = ndAlbumIds))
		_libraryRevision.value += 1
	}

	private val watchLock = Mutex()
	private var pollJob: Job? = null

	/**
	 * Two retention rules, because a running row and a finished one are different things.
	 *
	 * A row still being polled is dropped at [WATCH_TIMEOUT_MS] — twenty minutes, the
	 * wall clock that exists because lb-bot's verifier gives up and leaves a fill on
	 * `placed` forever. A settled one is *history* and is kept for [LEDGER_RETAIN_MS],
	 * capped at [LEDGER_MAX] rows so a heavy week can't grow the preference without
	 * bound. Before this, settling and deleting were the same act.
	 */
	private fun loadWatches(): Map<String, LbWatch> = try {
		val now = nowMs()
		// Decode and prune SETTLED rows only.
		//
		// An unsettled row past WATCH_TIMEOUT_MS used to be dropped right here, which made the
		// poll loop's "time out the stale ones first" step unreachable: it takes `live` from
		// this function, so the rows it wanted to settle had already ceased to exist. They were
		// deleted instead of finished — no `gave_up` history row, no notification, and a fill
		// the user had been watching simply vanished from the Download Center. Expiry is a state
		// transition and belongs to [applyAlbumStatus], not to the deserializer.
		val all = json.decodeFromString<Map<String, LbWatch>>(preferenceManager.lbBotWatches)
			.filterValues { watch ->
				!watch.settled || now - watch.finishedAtOrStart() < LEDGER_RETAIN_MS
			}
		if (all.size <= LEDGER_MAX) all
		else all.entries
			.sortedByDescending { it.value.finishedAtOrStart() }
			.take(LEDGER_MAX)
			.associate { it.key to it.value }
	} catch (e: Exception) {
		Logger.w("LbBotManager", "could not read persisted fills, starting empty", e)
		emptyMap()
	}

	/** The single write point, so publishing the ledger cannot be forgotten anywhere. */
	private fun saveWatches(watches: Map<String, LbWatch>) {
		preferenceManager.lbBotWatches = try {
			json.encodeToString(watches)
		} catch (e: Exception) {
			Logger.e("LbBotManager", "could not persist fills", e)
			"{}"
		}
		_ledger.value = watches.values
			.sortedWith(
				// In flight first — that is what someone opening this view came to see —
				// then most recent. `startedAt` for a live row, `finishedAt` for a dead
				// one, so a fill that ran for ten minutes doesn't sort above one that
				// failed since.
				compareBy<LbWatch> { it.settled }.thenByDescending { it.finishedAtOrStart() }
			)
			.map { it.toEntry() }
	}

	private fun putWatch(watch: LbWatch) {
		scope.launch {
			watchLock.withLock {
				val existing = loadWatches()[watch.key]
				// Keep whatever the previous attempt knew about the album; a retry or a
				// second tap must not blank the title the row is displayed under.
				saveWatches(
					loadWatches() + (watch.key to watch.copy(
						artist = watch.artist.ifBlank { existing?.artist.orEmpty() },
						album = watch.album.ifBlank { existing?.album.orEmpty() },
						quality = watch.quality.ifBlank { existing?.quality.orEmpty() }
					))
				)
			}
			ensurePolling()
		}
	}

	/** Re-open a settled row for another attempt, keeping its display fields. */
	private suspend fun reopen(
		key: String,
		releaseMbid: String,
		excludedPeers: List<String> = emptyList(),
		allowMp3: Boolean = false
	) {
		watchLock.withLock {
			val watches = loadWatches()
			val watch = watches[key] ?: return@withLock
			saveWatches(watches + (key to watch.reopened(releaseMbid, excludedPeers, allowMp3, nowMs())))
		}
		_fills.update { it - key }
		kickPoll()
	}

	/**
	 * Close a watch and record how it ended.
	 *
	 * The only place [fillEvents] is emitted, so however many screens are watching a
	 * fill it is announced exactly once.
	 */
	private suspend fun settle(
		key: String,
		outcome: String = OUTCOME_GAVE_UP,
		state: String = "",
		reason: String = ""
	) {
		val settledWatch = watchLock.withLock {
			val watches = loadWatches()
			val watch = watches[key] ?: return@withLock null
			if (watch.settled) return@withLock null
			val next = watch.copy(
				settled = true,
				finishedAt = nowMs(),
				outcome = outcome,
				state = state.ifBlank { watch.state },
				reason = reason.ifBlank { watch.reason }
			)
			saveWatches(watches + (key to next))
			next
		} ?: return
		_fillEvents.emit(
			LbFillEvent(
				key = settledWatch.key,
				artist = settledWatch.artist,
				album = settledWatch.album,
				outcome = settledWatch.outcome,
				reason = settledWatch.reason
			)
		)
		// From here rather than from a lifecycle collector: a settle while the process is
		// alive but the activity is stopped is announced now, not on the next foreground.
		notificationSink?.let { sink -> deliverPendingNotifications(sink) }
	}

	/**
	 * Hand over every completion notification still owed, and record that it was delivered.
	 *
	 * The live [fillEvents] flow is the fast path; this is what makes the promise hold when
	 * nothing was listening — the app was backgrounded, or the process had been killed and the
	 * fill settled on the next launch. Call it whenever the UI comes back to the foreground as
	 * well as on each live event.
	 *
	 * [notify] is invoked BEFORE the row is marked, and the mark is only written if it returns
	 * normally, so a notifier that throws (or a revoked POST_NOTIFICATIONS permission) leaves the
	 * row owed rather than silently consuming it.
	 */
	suspend fun deliverPendingNotifications(notify: (LbFillEvent) -> Unit) {
		val owed = watchLock.withLock {
			loadWatches().values.filter { it.settled && it.notifiedAt == 0L }
		}
		owed.forEach { watch ->
			val event = LbFillEvent(
				key = watch.key,
				artist = watch.artist,
				album = watch.album,
				outcome = watch.outcome,
				reason = watch.reason
			)
			try {
				notify(event)
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				Logger.w("LbBotManager", "notification for ${watch.key} failed; still owed", e)
				return@forEach
			}
			watchLock.withLock {
				val watches = loadWatches()
				val row = watches[watch.key] ?: return@withLock
				saveWatches(watches + (watch.key to row.copy(notifiedAt = nowMs())))
			}
		}
	}

	private fun ensurePolling() {
		if (pollJob?.isActive == true) return
		pollJob = scope.launch { pollLoop() }
	}

	/** Wake the loop for an immediate tick (a gap frame, a retry, a reopen). */
	private val pollWake = Channel<Unit>(Channel.CONFLATED)

	private fun kickPoll() {
		pollWake.trySend(Unit)
		ensurePolling()
	}

	/**
	 * The watch loop: ONE `/lb/fills` read per tick for every row that is due.
	 *
	 * Five seconds, and no tighter, while nothing else is telling us anything — and
	 * thirty while the hub socket is up and `fill` frames have been arriving, because
	 * then this is only the safety net for a frame that was missed. It used to fan
	 * out one `album/status` per row; a Download Center with eight rows was eight
	 * requests per tick through the hub's four slots. The loop only exists while
	 * something is live, or a failed row still has lb-bot's own retry pending.
	 */
	private suspend fun pollLoop() {
		while (true) {
			val startedTick = nowMs()
			val watched = watchLock.withLock {
				loadWatches().values.filter { !it.settled || it.retryAt > 0L }
			}
			if (watched.isEmpty()) return

			val pushed = hubConnected() && startedTick - lastPushAt < PUSH_FRESH_MS
			val due = watched.filter { startedTick >= it.nextPollAt }
			if (due.isNotEmpty()) {
				val albums = due.filter { it.kind == KIND_ALBUM && it.releaseMbid.isNotBlank() }.take(32)
				val gaps = due.filter { it.kind == KIND_GAP }.take(32)
				when (val result = fills(albums.map { it.releaseMbid }, gaps.map { it.key })) {
					is LbResult.Failed ->
						noteError((albums + gaps).map { it.key }, errorText(result.error))
					is LbResult.Ok -> {
						val now = nowMs()
						albums.forEach { watch ->
							result.value.albums[watch.releaseMbid]?.let { applyAlbumStatus(watch.key, it, now) }
						}
						gaps.forEach { watch ->
							result.value.gaps[watch.key]?.let { applyGapSummary(watch.key, it, now) }
						}
						if (pushed) {
							// Frames carry the changes; the next read is a safety net.
							val keys = (albums + gaps).map { it.key }.toSet()
							watchLock.withLock {
								val watches = loadWatches()
								saveWatches(watches.mapValues { (k, w) ->
									if (k in keys && !w.settled) w.copy(nextPollAt = now + PUSHED_POLL_MS) else w
								})
							}
						}
					}
				}
			}

			// Sleep until the earliest slot, or until something kicks the loop.
			val nextAt = watchLock.withLock {
				loadWatches().values.filter { !it.settled || it.retryAt > 0L }.minOfOrNull { it.nextPollAt }
			} ?: return
			val wait = (nextAt - nowMs()).coerceIn(MIN_POLL_GAP_MS, if (pushed) PUSHED_POLL_MS else POLL_INTERVAL_MS)
			withTimeoutOrNull(wait) { pollWake.receive() }
		}
	}

	/**
	 * How long before this watch is polled again.
	 *
	 * Five seconds is the floor and stays the answer for anything that is moving. An
	 * unchanged payload is free information: back off, and snap straight back the
	 * moment anything at all differs.
	 */
	private fun intervalFor(quietTicks: Int): Long = when {
		quietTicks < QUIET_TICKS_FIRST -> POLL_INTERVAL_MS
		quietTicks < QUIET_TICKS_SECOND -> POLL_INTERVAL_MS * 2
		else -> POLL_INTERVAL_MS * 4
	}

	private fun errorText(error: LbError): String = when (error) {
		is LbError.Rejected -> error.message.ifBlank { "lb-bot answered ${error.status}" }
		is LbError.Unreachable -> error.message.ifBlank { "Could not reach the hub" }
		else -> error.toString()
	}

	/**
	 * A failed poll is NOT an answer. The row keeps what it last knew and records that
	 * lb-bot could not be reached, which the Download Center says after two such ticks —
	 * it used to say nothing at all, and a hub that was down looked like a fill that
	 * had stalled.
	 */
	private suspend fun noteError(keys: List<String>, error: String) {
		val now = nowMs()
		watchLock.withLock {
			val watches = loadWatches()
			saveWatches(watches.mapValues { (k, w) ->
				if (k in keys) w.copy(lastError = error, lastErrorTicks = w.lastErrorTicks + 1,
					nextPollAt = now + POLL_INTERVAL_MS)
				else w
			})
		}
	}

	private sealed interface Decision {
		data class Settle(val outcome: String, val state: String, val reason: String) : Decision
	}

	/**
	 * The ONE place lb-bot's answer about an album fill reaches the ledger.
	 *
	 * A poll and a `fill` frame off the hub socket both end here, so push cannot disagree
	 * with poll about what a state means. Three things a status can be are kept apart:
	 * a real state moves the row; `unknown` is bounded patience (a grace period after
	 * the tap, then two minutes of lb-bot saying so continuously before the row settles
	 * `gave_up`); and a FAILED poll never comes here at all — [noteError] records it.
	 *
	 * A terminal status stays in [_fills]: the sheet shows the outcome, and its picker is
	 * enabled again because the state is no longer active. The map used to keep the last
	 * live state forever, so a cancelled fill's sheet still said "Downloading" with a dead
	 * Cancel button until the app restarted.
	 */
	private suspend fun applyAlbumStatus(key: String, raw: LbFillStatus, now: Long = nowMs()) {
		val status = raw.normalized()
		val previousState = _fills.value[key]?.state
		_fills.update { it + (key to status) }

		val decision = watchLock.withLock {
			val watches = loadWatches()
			val current = watches[key] ?: return@withLock null
			var next = current.copy(lastCheckedAt = now, lastError = "", lastErrorTicks = 0)

			if (status.state == "unknown") {
				if (current.settled || now - current.startedAt < UNKNOWN_GRACE_MS) {
					saveWatches(watches + (key to next))
					return@withLock null
				}
				val since = if (current.unknownSince > 0L) current.unknownSince else now
				val quiet = current.quietTicks + 1
				next = next.copy(unknownSince = since, quietTicks = quiet, nextPollAt = now + intervalFor(quiet))
				saveWatches(watches + (key to next))
				return@withLock if (now - since >= UNKNOWN_SETTLE_MS)
					Decision.Settle(OUTCOME_GAVE_UP, current.state, "lb-bot no longer knows this download")
				else null
			}

			if (current.settled) {
				// The server side came back to life — lb-bot's own retry, a wishlist
				// re-search, a retry from the other client: follow the fill, not the verdict.
				val backAlive = status.state !in TERMINAL_FILL_STATES &&
					(status.updatedAt * 1000).toLong() > current.finishedAt - 1000L
				if (!backAlive) {
					// No retry pending any more (it fired, or lb-bot restarted): stop watching for one.
					if (current.retryAt > 0L && status.retryAt <= 0.0) {
						saveWatches(watches + (key to next.copy(retryAt = 0L)))
					}
					return@withLock null
				}
				next = current.reopened(status.releaseMbid, emptyList(), current.allowMp3, now)
					.copy(lastCheckedAt = now)
			}

			val fingerprint = "${status.state}|${status.done}/${status.total}|${status.failed}|${status.bytesDone}"
			val moved = fingerprint != next.fingerprint
			val quiet = if (moved) 0 else next.quietTicks + 1
			next = next.copy(
				fingerprint = fingerprint,
				quietTicks = quiet,
				nextPollAt = now + intervalFor(quiet),
				unknownSince = 0L,
				unknownPolls = 0,
				state = status.state,
				// lb-bot's, verbatim: it clears it on a new fill and carries a switch note
				// ("Source @x failed — retrying from @y") through the transfer, both of
				// which the row should say.
				reason = status.reason,
				percent = status.percent,
				done = status.done,
				total = status.total,
				failedFiles = status.failed,
				bytesDone = status.bytesDone,
				bytesTotal = status.bytesTotal,
				speedBps = status.speedBps,
				artist = status.artist.ifBlank { next.artist },
				album = status.album.ifBlank { next.album },
				mp3WouldHelp = status.mp3WouldHelp,
				failureKind = status.failureKind,
				retryable = status.retryable,
				attempts = if (status.attempts > 0) status.attempts else next.attempts,
				groupId = status.groupId.ifBlank { next.groupId },
				lastSource = status.source.ifBlank { next.lastSource },
				retryAt = (status.retryAt * 1000).toLong(),
				cancellable = status.canCancel,
				verifyGaveUp = status.verifyGaveUp,
				allowMp3 = status.allowMp3 || next.allowMp3,
				lastProgressAt = if (moved || next.lastProgressAt == 0L) now else next.lastProgressAt
			)
			saveWatches(watches + (key to next))
			when {
				status.state in TERMINAL_FILL_STATES || (status.state == "placed" && status.verifyGaveUp) ->
					Decision.Settle(
						outcome = when {
							status.state == "verified" || status.state == "placed" -> OUTCOME_DONE
							status.state == "cancelled" -> OUTCOME_CANCELLED
							else -> OUTCOME_FAILED
						},
						state = status.state,
						reason = status.reason
					)
				// Ran out of clock rather than failed — a different thing, and worth
				// saying so: nothing is known to have gone wrong.
				!moved && now - next.lastProgressAt > WATCH_TIMEOUT_MS ->
					Decision.Settle(OUTCOME_GAVE_UP, status.state, "No progress for 20 minutes")
				else -> null
			}
		}

		// Announced on the TRANSITION only: a fill sitting on `placed` for a minute used
		// to re-read the discography on every tick. `verified` carries the Navidrome album
		// ids, which is what lets the landing be a one-album sync.
		if (status.state != previousState) {
			when (status.state) {
				"placed" -> onLibraryChanged(LbLibraryEvent(EVENT_PLACED, rgid = key))
				"verified" -> onLibraryChanged(
					LbLibraryEvent(EVENT_INDEXED, rgid = key, ndAlbumIds = status.ndAlbumIds)
				)
			}
		}
		if (decision is Decision.Settle) settle(key, decision.outcome, decision.state, decision.reason)
	}

	/**
	 * Why a gap fill ended, in lb-bot's own words, most specific first.
	 *
	 * `noSourceReason` is the attributable one ("103 peers offered 2,047 files, but none
	 * in FLAC…") and is what makes the Allow-MP3 offer make sense; `failReason` names the
	 * failure; the task error is the fallback for a search that itself broke.
	 */
	private fun gapReason(gap: LbGap): String = when {
		gap.failReason.isNotBlank() -> gap.failReason
		gap.noSourceReason.isNotBlank() -> gap.noSourceReason
		else -> gap.sourceTask?.error.orEmpty()
	}

	/** The gap counterpart of [applyAlbumStatus]: one writer for the gap ledger. */
	private suspend fun applyGapSummary(key: String, gap: LbGap, now: Long = nowMs()) {
		val previousStatus = _gaps.value[key]?.status
		_gaps.update { it + (key to gap) }

		val filling = gap.tracks.filter { it.state != "present" }
		val filled = filling.count { it.state == "done" || it.state == "downloaded" }
		val failedFiles = filling.count { it.state == "failed" || it.state == "cancelled" }
		val searching = gap.sourceTask?.status.orEmpty() in SEARCH_IN_FLIGHT
		val transferring = gap.status == "downloading" ||
			gap.tracks.any { it.state == "queued" || it.state == "downloading" }
		val busy = searching || transferring
		// `ready` after an auto run means the search found nothing, or every source
		// rejected the enqueue. Bounded patience, as `unknown` gets on the album path.
		val idle = gap.status == "ready" && !searching

		val decision = watchLock.withLock {
			val watches = loadWatches()
			val current = watches[key] ?: return@withLock null
			var next = current.copy(lastCheckedAt = now, lastError = "", lastErrorTicks = 0)
			if (current.settled) {
				if (!busy) { saveWatches(watches + (key to next)); return@withLock null }
				next = current.reopened("", emptyList(), false, now).copy(lastCheckedAt = now)
			}
			val fingerprint = "${gap.status}|${gap.sourceTask?.status}|$filled/${filling.size}"
			val moved = fingerprint != next.fingerprint
			val quiet = if (moved) 0 else next.quietTicks + 1
			val since = if (idle) (if (next.unknownSince > 0L) next.unknownSince else now) else 0L
			next = next.copy(
				fingerprint = fingerprint,
				quietTicks = quiet,
				nextPollAt = now + intervalFor(quiet),
				unknownSince = since,
				state = gap.status,
				reason = gapReason(gap),
				percent = if (filling.isEmpty()) 0 else filled * 100 / filling.size,
				done = filled,
				total = filling.size,
				failedFiles = failedFiles,
				artist = gap.artist.ifBlank { next.artist },
				album = gap.album.ifBlank { next.album },
				mp3WouldHelp = gap.mp3WouldHelp,
				cancellable = transferring,
				lastProgressAt = if (moved || next.lastProgressAt == 0L) now else next.lastProgressAt
			)
			saveWatches(watches + (key to next))
			when {
				// A source search in flight is never a reason to stop: asking for one flips
				// the group to `picking` before it has found anything.
				searching -> null
				!busy && now - next.lastProgressAt > WATCH_TIMEOUT_MS ->
					Decision.Settle(OUTCOME_GAVE_UP, gap.status, "No progress for 20 minutes")
				idle -> if (now - since >= UNKNOWN_SETTLE_MS && now - next.startedAt >= UNKNOWN_GRACE_MS)
					Decision.Settle(OUTCOME_GAVE_UP, gap.status, gapReason(gap)) else null
				// `picking` with the search finished is the picker holding candidates and
				// waiting on the user — a "your move", not a failure.
				gap.status in TERMINAL_GAP_STATES -> Decision.Settle(
					outcome = when (gap.status) {
						"complete" -> OUTCOME_DONE
						"failed" -> OUTCOME_FAILED
						else -> OUTCOME_NEEDS_PICK
					},
					state = gap.status,
					reason = gap.sourceTask?.error.orEmpty()
				)
				else -> null
			}
		}
		// `complete` means placed; lb-bot's verifier sends `albumIndexed` once Navidrome has it.
		if (gap.status == "complete" && previousStatus != "complete") onLibraryChanged()
		if (decision is Decision.Settle) settle(key, decision.outcome, decision.state, decision.reason)
	}

	/**
	 * Resume any fill that outlived the process, and publish the ledger. Called once, at
	 * startup — the downloads view must show yesterday's history without a fill running.
	 */
	fun resumeWatches() {
		scope.launch {
			val live = watchLock.withLock {
				val watches = loadWatches()
				// Re-save rather than just reading: this is what applies the retention
				// rules and, being the single write point, publishes the ledger.
				saveWatches(watches)
				watches.values.count { !it.settled }
			}
			if (live > 0) ensurePolling()
		}
	}

	private fun nowMs(): Long = Clock.System.now().toEpochMilliseconds()

	companion object {
		/** Which copy to prefer. Mirrors lb-bot's QUALITY_PREFERENCES; an unknown value
		 *  is rejected upstream with a 400, so drift fails loudly. These rank sources,
		 *  they do not filter them — word them "prefer", never "only". */
		val QUALITY_OPTIONS = listOf(
			"" to "lb-bot's default",
			"flac-any" to "Best FLAC available",
			"flac-16-44" to "CD quality (16-bit/44.1kHz)",
			"highest-bitrate" to "Highest bitrate",
			"prefer-opus" to "Prefer Opus"
		)

		/** Cover Art Archive front cover for a release-group.
		 *
		 *  Built here rather than proxied: lb-bot's own cover route serves *Navidrome*
		 *  art keyed by a Navidrome album id, which by definition does not exist for a
		 *  release the library doesn't have. The Archive is public, so the client
		 *  fetches it directly — which also keeps multi-megabyte images out of the
		 *  hub's entry-counted cache. Must be loaded WITHOUT the user's Navidrome
		 *  custom headers; see RemoteCoverArt. */
		fun caaCoverUrl(rgid: String, size: Int = 250): String =
			if (rgid.isBlank()) "" else
				"https://coverartarchive.org/release-group/$rgid/front-$size"

		private const val KIND_ALBUM = "album"
		private const val KIND_GAP = "gap"
		private const val POLL_INTERVAL_MS = 5_000L

		/** While the hub socket is up and a `fill` frame arrived within PUSH_FRESH_MS,
		 *  the poll is a safety net and runs this slowly. */
		private const val PUSHED_POLL_MS = 30_000L
		private const val PUSH_FRESH_MS = 60_000L

		/** Bounded patience for `unknown`: ignored for a grace period after the tap
		 *  (lb-bot's worker has not written its first row yet), then the row settles
		 *  `gave_up` only after lb-bot has said it continuously for this long. On the
		 *  clock, not in ticks — ticks now come from a 30 s poll, a 5 s poll and the
		 *  push alike. */
		private const val UNKNOWN_GRACE_MS = 30_000L
		private const val UNKNOWN_SETTLE_MS = 120_000L

		/** lb-bot's own "too late" for a cancel, shown as the action's message. */
		const val TOO_LATE_TO_CANCEL = "Too late to cancel — it's being added to the library."

		/** How long a cached availability verdict stands before it is re-probed.
		 *  Long enough that navigating between tabs costs nothing, short enough
		 *  that a hub coming back is noticed within a minute. */
		private const val AVAILABILITY_TTL_MS = 60_000L

		/** Rows to ask the fresh feed for. The screen filters and buckets
		 *  client-side, so this only has to exceed what anyone scrolls. */
		const val FRESH_LIMIT = 400

		// How old a cached lb-bot answer may be before [cachedGet] revalidates it. A stale
		// body is still painted first either way — these decide only whether a request
		// follows. Ownership-bearing routes are ALSO stale after any library change,
		// whatever these say, so the ages below bound only what no frame announces
		// (a Navidrome scan lb-bot has not heard about, a chart that moved).
		private const val MINUTE_MS = 60_000L
		private const val DAY_MS = 24 * 60 * MINUTE_MS

		/** Editorial meta: Wikipedia/Wikidata/MusicBrainz text, which moves on no one's clock. */
		private const val CACHE_META_MS = 30 * DAY_MS

		/** Release-group editions and tracklists: MusicBrainz data. */
		private const val CACHE_RELEASES_MS = 30 * DAY_MS

		/** Fresh: the plan's ten minutes. ListenBrainz's feed updates hourly at most. */
		private const val CACHE_FRESH_MS = 10 * MINUTE_MS

		/** "Fans also like", both sources: ownership-badged per row, so the short age. */
		private const val CACHE_SIMILAR_MS = 10 * MINUTE_MS

		/** Deezer chart and editorial: the list barely moves in an hour; its badges are
		 *  covered by the library watermark, not by this. */
		private const val CACHE_CHART_MS = 60 * MINUTE_MS

		/** Deezer's genre list: effectively static. */
		private const val CACHE_GENRES_MS = DAY_MS

		/** Bodies unrevalidated for this long are deleted, once per process. Longer than
		 *  every max age above, so a month-old meta body still paints while it revalidates. */
		private const val RESPONSE_CACHE_PRUNE_MS = 90 * DAY_MS

		/** [cacheKey]'s tag for an ownership-bearing answer. */
		private const val LIBRARY_KEY_PREFIX = "lib:"

		/** Never let the loop spin: a tick that overran its own interval still waits. */
		private const val MIN_POLL_GAP_MS = 1_000L

		/** How long the two poll routes get before the client gives up on the tick —
		 *  well under the client's general 60 s, which is sized for `gap/search`. */
		private const val POLL_TIMEOUT_MS = 12_000L

		/** Identical answers before the interval doubles, then doubles again. */
		private const val QUIET_TICKS_FIRST = 4
		private const val QUIET_TICKS_SECOND = 10

		/** How long a fill may go without MOVING — state, files or bytes — before the
		 *  row settles `gave_up`. Measured from the last progress, not from the tap: a
		 *  slow peer is not a dead fill. */
		private const val WATCH_TIMEOUT_MS = 20 * 60 * 1000L

		/** How long a *finished* row stays readable, and how many are kept at all.
		 *  Running rows are still bounded by WATCH_TIMEOUT_MS — different question. */
		private const val LEDGER_RETAIN_MS = 7 * 24 * 60 * 60 * 1000L
		private const val LEDGER_MAX = 50

		const val OUTCOME_RUNNING = "running"
		const val OUTCOME_DONE = "done"
		const val OUTCOME_FAILED = "failed"
		/** The gap picker has candidates and wants a choice — not a failure. */
		const val OUTCOME_NEEDS_PICK = "needs_pick"
		const val OUTCOME_CANCELLED = "cancelled"
		/** Nothing ever acted on it: lb-bot never wrote a ledger row, or forgot it. */
		const val OUTCOME_GAVE_UP = "gave_up"

		/** `placed` is deliberately absent: the interesting transition is
		 *  placed -> verified, which is Navidrome confirming the files really landed. */
		private val TERMINAL_FILL_STATES = setOf("cancelled", "failed", "needs_match", "verified")

		/** The states a fill may be cancelled from — the server states it per fill
		 *  (`cancellable`); this is the fallback for an lb-bot that does not. */
		val CANCELLABLE_FILL_STATES = setOf("searching", "queued", "downloading")
		private val TERMINAL_GAP_STATES = setOf("complete", "failed", "picking")

		/** A source search that hasn't answered yet. Every gap state is provisional
		 *  while one of these is true, because asking for sources changes the group's
		 *  status before it changes its contents. */
		val SEARCH_IN_FLIGHT = setOf("queued", "running")
	}
}

// ---------------------------------------------------------------------------
// Wire shapes. lb-bot speaks snake_case for the index rows and camelCase for the
// screen-shaped views; both are mirrored verbatim rather than normalized, so a
// field here can be checked against its Python source by name.
// ---------------------------------------------------------------------------

@Serializable
data class LbStatusProbe(
	val configured: Boolean = false,
	val upstreamReachable: Boolean = false,
	/** Routes this hub can proxy. Empty from a hub too old to advertise them. */
	val routes: List<String> = emptyList()
)

@Serializable
data class LbOk(
	val ok: Boolean = false,
	/** Upstream's own sentence when it refuses with a 200. Shown verbatim. */
	val error: String = "",
	/** lb-bot already had this in flight; the second request was a no-op. */
	val alreadyActive: Boolean = false
)

/** Ranked Soulseek folders for one release-group, with the album they were sought for. */
@Serializable
data class LbAlbumSources(
	val artist: String = "",
	val album: String = "",
	val query: String = "",
	val sources: List<LbGapSource> = emptyList()
)

@Serializable
private data class LbTaskStarted(
	val ok: Boolean = false,
	@SerialName("task_id") val taskId: String = ""
)

sealed interface LbScanOutcome {
	data class Done(val data: LbDiscography) : LbScanOutcome
	/** lb-bot's own sentence; [data] is the discography it kept. */
	data class Failed(val error: String, val data: LbDiscography) : LbScanOutcome
	data object TimedOut : LbScanOutcome
}

/** Scans that retry MusicBrainz can run for minutes; the scan record ends the wait early.
 *  Five minutes, as the old 60 × 5 s was. */
private const val SCAN_WAIT_MS = 5 * 60_000L
/** The scan poll's FALLBACK tick — the mirror Flow and the `artistScanned` frame are the
 *  feedback (see [LbBotManager.awaitArtistScan]). Was 5 s, when this poll was all there was. */
private const val SCAN_POLL_INTERVAL_MS = 15_000L

/** The newest discography scan lb-bot ran for an artist. `state`: running | done | failed. */
@Serializable
data class LbArtistScan(
	val state: String = "",
	val error: String = "",
	val taskId: String = "",
	val startedAt: Double = 0.0,
	val finishedAt: Double = 0.0
)

@Serializable
private data class LbIndexRequest(
	val mbid: String,
	val name: String,
	@SerialName("nd_id") val ndId: String
)

@Serializable
private data class LbIndexReleaseRequest(
	val rgid: String,
	val mbid: String,
	@SerialName("nd_id") val ndId: String,
	val name: String,
	val external: Boolean,
	/** lb-bot's MusicBrainz-outage override — see [LbBotManager.indexRelease]. */
	val title: String = "",
	val artist: String = "",
	val type: String = "",
	val year: String = ""
)

@Serializable
private data class LbGroupRequest(@SerialName("group_id") val groupId: String)

@Serializable
private data class LbSearchRequest(
	@SerialName("group_id") val groupId: String,
	/** Re-search rather than reusing results inside lb-bot's own TTL. */
	val force: Boolean = false
)

@Serializable
private data class LbFetchRequest(
	@SerialName("group_id") val groupId: String,
	val sourceId: Int
)

@Serializable
private data class LbAllowMp3Request(
	@SerialName("group_id") val groupId: String,
	val allow: Boolean
)

@Serializable
private data class LbDownloadRequest(
	val rgid: String,
	/**
	 * The pressing the user picked.
	 *
	 * lb-bot honours this over re-resolving the release-group, which fixes two things at once. The
	 * edition picker used to be decorative — `mbz_resolve_album` chose "official, earliest" on its
	 * own and nobody was told. And a resolve that hits a MusicBrainz 503 parks the whole
	 * release-group in a five-minute failure cooldown that answers every retry instantly with the
	 * same 400, so one hiccup took the album's download out for minutes with no way forward.
	 */
	@SerialName("release_mbid") val releaseMbid: String? = null,
	val artist: String? = null,
	val title: String? = null,
	@SerialName("total_tracks") val totalTracks: Int? = null,
	val quality: String? = null,
	/** A hand-picked source. lb-bot floats this peer to the front of its own ranked
	 *  list and keeps the rest as failover, so it is a strong preference rather than
	 *  a guarantee — if the peer has gone by transfer time, the best ranked folder
	 *  wins instead. */
	val sourceUsername: String? = null,
	val sourceFolder: String? = null,
	/** "Try another source": peers that already failed or crawled for this album. */
	val excludeUsers: List<String>? = null,
	/** The whole-album MP3 opt-in; omitted rather than sent false. */
	val allowMp3: Boolean? = null
)

@Serializable
private data class LbCancelRequest(@SerialName("release_mbid") val releaseMbid: String)

@Serializable
private data class LbCancelResponse(
	val ok: Boolean = false,
	val cancelled: Boolean = false,
	val status: LbFillStatus? = null
)

@Serializable
private data class LbDownloadResponse(
	val ok: Boolean = false,
	val existing: Boolean = false,
	val resolved: LbResolved? = null
)

@Serializable
private data class LbResolved(@SerialName("release_mbid") val releaseMbid: String = "")

data class LbDownloadResult(
	val ok: Boolean = false,
	val existing: Boolean = false,
	val releaseMbid: String = ""
)

@Serializable
data class LbDiscography(
	val indexed: Boolean = false,
	@SerialName("artist_name") val artistName: String = "",
	@SerialName("artist_mbid") val artistMbid: String = "",
	/** Index older than lb-bot's TTL, or built by an older scan version. */
	val stale: Boolean = false,
	/**
	 * When the walk last finished, epoch **seconds**. The only reliable "the rescan is done"
	 * signal — `indexed` is already true for every artist a rescan applies to.
	 *
	 * Double, not Long: lb-bot writes a `time.time()` float, and a Long here would throw on the
	 * decimal point and take the entire discography payload down with it.
	 */
	@SerialName("scanned_at") val scannedAt: Double = 0.0,
	val releases: List<LbRelease> = emptyList(),
	/** The last scan lb-bot ran for this artist since it started, or null. */
	val scan: LbArtistScan? = null
)

/**
 * One MusicBrainz release-group as lb-bot's index holds it.
 *
 * Almost everything past `status` is conditional upstream: `group_id`, `present`
 * and `total` are written only for an `incomplete` row, and `navidrome_album_ids`
 * only when the list is non-empty. Nothing here may be non-null.
 */
@Serializable
data class LbRelease(
	val rgid: String = "",
	val title: String = "",
	val year: String = "",
	@SerialName("primary_type") val primaryType: String = "",
	@SerialName("secondary_types") val secondaryTypes: List<String> = emptyList(),
	@SerialName("effective_type") val effectiveType: String = "",
	/** complete | incomplete | missing | untagged */
	val status: String = "missing",
	@SerialName("match_method") val matchMethod: String = "",
	@SerialName("match_score") val matchScore: Float = 0f,
	/** Present only when [status] is `incomplete` — the Fill-gaps handle. */
	@SerialName("group_id") val groupId: String? = null,
	val present: Int? = null,
	val total: Int? = null,
	@SerialName("navidrome_album_ids") val navidromeAlbumIds: List<String> = emptyList()
) {
	val isMissing: Boolean get() = status == "missing"
	val isIncomplete: Boolean get() = status == "incomplete" && !groupId.isNullOrBlank()
}

/**
 * One page of lb-bot's index change feed, `GET /lb/index/changes?since=&epoch=` (navi-connect
 * contract §1a). camelCase on the wire, unlike the snake_case rows it carries.
 *
 * Two shapes share this class. A **resync** answer (`resync: true`) carries only the envelope and
 * means "your mirror belongs to another epoch, or claims a seq this index never reached": wipe it,
 * adopt [epoch], pull again from 0. A normal answer carries [items] with `seq > since`, in seq
 * order, and [artistCount]/[seqSum] of the whole index taken in the same snapshot — the drift
 * check's reference values.
 */
@Serializable
data class LbIndexChanges(
	val resync: Boolean = false,
	val epoch: String = "",
	val headSeq: Long = 0L,
	val scanVersion: Int = 0,
	/** lb-bot's `LB_BOT_INDEX_TTL_DAYS`, a float upstream. */
	val ttlDays: Double = 0.0,
	val items: List<LbIndexItem> = emptyList(),
	val nextSince: Long = 0L,
	val more: Boolean = false,
	val artistCount: Long = 0L,
	val seqSum: Long = 0L
)

/**
 * An artist or a tombstone — flat rather than polymorphic, discriminated by [type], so a field a
 * later lb-bot adds (or an item type this client doesn't know) degrades to "ignored" instead of
 * failing the whole page.
 */
@Serializable
data class LbIndexItem(
	/** `artist` | `tombstone`. */
	val type: String = "",
	val key: String = "",
	val ndArtistId: String = "",
	val mbid: String = "",
	val name: String = "",
	/** Epoch seconds, float upstream. */
	val scannedAt: Double = 0.0,
	val scanVersion: Int = 0,
	val seq: Long = 0L,
	/** `_index_row_to_wire` rows, in lb-bot's `year, title` order. */
	val rows: List<LbRelease> = emptyList()
) {
	val isArtist: Boolean get() = type == "artist"
	val isTombstone: Boolean get() = type == "tombstone"
}

/** `GET /lb/index/keys`: every artist key with its seq (no tombstones), for the drift check. */
@Serializable
data class LbIndexKeys(
	val epoch: String = "",
	val headSeq: Long = 0L,
	val keys: List<LbIndexKey> = emptyList()
)

@Serializable
data class LbIndexKey(
	val key: String = "",
	val seq: Long = 0L
)

/**
 * The exact pressing a sheet has already resolved, passed to lb-bot instead of letting it guess.
 *
 * Not serialized: [LbBotManager.download] and [LbBotManager.albumSources] send these as flat body
 * keys and query params respectively, which is the shape lb-bot's override expects.
 */
data class LbResolvedEdition(
	val releaseMbid: String,
	val artist: String,
	val title: String,
	/** Prefer the loaded tracklist's size for this exact edition; fall back to the variant's. */
	val totalTracks: Int
)

/**
 * One page of the fresh-releases feed.
 *
 * [total] is how many rows lb-bot held before its own cut, so the screen can say
 * "showing N of M". The cut is not cosmetic: unbounded, this feed is the entire
 * site-wide ListenBrainz window, which is larger than the hub will carry — and
 * the hub used to truncate an oversized body *silently* at HTTP 200, so this
 * decoded into a `SerializationException` and the tab showed nothing.
 *
 * lb-bot keeps every row whose artist is in the library whatever the limit, so
 * [truncated] never means "we dropped something you own".
 */
@Serializable
data class LbFreshFeed(
	val releases: List<LbFreshRelease> = emptyList(),
	val total: Int = 0,
	val truncated: Boolean = false
)

/**
 * One row of ListenBrainz's site-wide fresh-releases feed, as lb-bot normalizes it.
 *
 * Field names mirror the wire **verbatim** rather than being normalized to local
 * taste — lb-bot speaks camelCase for its screen-shaped views, and mirroring means
 * any field here can be checked against its Python source by name.
 *
 * **[artistOwned] and [releaseOwned] are independent and must never be conflated.**
 * The first says the artist is in the library; the second says this exact
 * release-group is on disk. An owned artist with a brand-new album is still a
 * download. (Upstream also sends `owned` as a backward-compatible alias for
 * `artistOwned`; it is deliberately not carried here, so nothing can read it.)
 */
@Serializable
data class LbFreshRelease(
	val releaseName: String = "",
	val artist: String = "",
	val artistMbids: List<String> = emptyList(),
	val releaseMbid: String = "",
	val releaseGroupMbid: String = "",
	/** ISO date. May be in the future — that is an upcoming release, not an error. */
	val releaseDate: String = "",
	val type: String = "",
	val secondaryType: String = "",
	/** Straight from the Cover Art Archive: a release nobody owns has no Navidrome
	 *  art, and lb-bot's own cover route is Navidrome art keyed by album id. */
	val coverUrl: String = "",
	val listenCount: Int = 0,
	/** The artist is in the library. Drives the "Your artists" scope. */
	val artistOwned: Boolean = false,
	/** Navidrome artist id, when [artistOwned]. */
	val artistId: String = "",
	/** This exact release-group is on disk. Drives the "in library" chip — and only
	 *  this one, never [artistOwned]. */
	val releaseOwned: Boolean = false,
	/** Navidrome album id, when [releaseOwned]. A tile that says "in library" has
	 *  to open the library album, and this is the only handle that can. The
	 *  external album page's own redirect cannot stand in for it: that reads the
	 *  index's `navidromeAlbumIds`, which an album lb-bot filled itself does not
	 *  have — placement flips the row to `present` and cannot write them. */
	val releaseAlbumId: String = ""
)

@Serializable
data class LbArtistLookup(
	val candidates: List<LbArtistCandidate> = emptyList()
)

/**
 * What a one-tap acquire did.
 *
 * [NeedsReview] is not a failure — it is the source picker doing its job. Every
 * [AcquireReason] is a question only the user can answer.
 */
sealed interface AcquireOutcome {
	data class Started(val format: String, val peer: String) : AcquireOutcome
	data class NeedsReview(val reason: AcquireReason) : AcquireOutcome
}

enum class AcquireReason {
	/** Nobody is sharing it. */
	NO_SOURCES,

	/** lb-bot is not confident the best folder is this record. */
	UNCERTAIN_MATCH,

	/** The best folder does not cover the canonical tracklist. */
	INCOMPLETE,

	/** This client asks for lossless and the best folder is not. */
	WRONG_FORMAT,

	/** lb-bot would not answer, or refused the request. */
	UNAVAILABLE
}

/** Formats a lossless preference is actually satisfied by. */
private val LOSSLESS_FORMATS = Regex("""^(flac|alac|wav|aiff|ape|wv)$""", RegexOption.IGNORE_CASE)

@Serializable
data class LbAlbumLookup(
	val candidates: List<LbAlbumCandidate> = emptyList()
)

/**
 * One MusicBrainz album search hit, with the one thing [LbArtistCandidate]
 * cannot answer: whether the library already holds it.
 *
 * That is what makes these safe to put in a search box. [releaseOwned] is marked
 * by release-group id, which is exact, so an owned row is rendered as a library
 * row and opens [releaseAlbumId]. Rendering them unmarked is the "the tile said
 * the library holds it and the tap opened the download page" bug that the Fresh
 * tab and the similar-albums shelf have each paid for once.
 *
 * [releaseAlbumId] can be blank on an owned row: lb-bot flips its index row to
 * `present` at placement and cannot know the Navidrome ids until the backfill
 * resolves them. Owned with nowhere to send the tap is a real state, and it is
 * what "Added — waiting for library" means.
 */
@Serializable
data class LbAlbumCandidate(
	val rgid: String = "",
	val title: String = "",
	val artist: String = "",
	@SerialName("primary_type") val primaryType: String = "",
	val year: String = "",
	val score: Int = 0,
	val releaseOwned: Boolean = false,
	val releaseAlbumId: String = "",
	/** Cover Art Archive front. lb-bot's own /api/cover is Navidrome art keyed by
	 *  a Navidrome album id, so it has nothing to serve for a release the library
	 *  lacks. */
	val coverUrl: String = ""
)

/**
 * One MusicBrainz artist search hit — an artist that may or may not be in the
 * library, which is the point.
 *
 * [disambiguation] is MusicBrainz's own "(UK band)" note and is the only thing
 * that tells two identically-named artists apart. Show it.
 */
@Serializable
data class LbArtistCandidate(
	val mbid: String = "",
	val name: String = "",
	val disambiguation: String = "",
	val type: String = "",
	val country: String = "",
	val area: String = "",
	val score: Int = 0
)

/**
 * Editorial metadata for an artist or an album, as lb-bot resolves it:
 * MusicBrainz url-relations -> Wikidata -> Wikipedia.
 *
 * Field names mirror the wire verbatim, the same rule [LbFreshRelease] follows.
 *
 * All text is **plain** — lb-bot asks Wikipedia for `explaintext` extracts — so
 * unlike the Last.fm bio this replaces there is no markup to strip and nothing
 * that can render as a literal `<a href=...>` on screen.
 *
 * [found] false is a legitimate "nobody has written about this", not an error.
 */
@Serializable
data class LbMeta(
	val found: Boolean = false,
	/** The lead paragraph; equal to `paragraphs.first()` when there is any text. */
	val summary: String = "",
	/** Body text, already capped upstream to fit the hub's 4 MB ceiling. */
	val paragraphs: List<String> = emptyList(),
	/**
	 * Wikidata's one-liner ("English rock band formed in Abingdon in 1985"). Often
	 * present when there is no article at all, which is exactly what the photo
	 * header wants — a short true sentence instead of 200 truncated characters.
	 */
	val wikidataDescription: String = "",
	/**
	 * **Render whenever any text is shown.** Wikipedia is CC BY-SA and the credit is
	 * a licence condition, not a nicety — and its URL is also simply a better "read
	 * more" than the Last.fm one it replaces.
	 */
	val source: LbMetaSource? = null,
	/** Wikipedia's page image, when it has one. Never a cover. */
	val imageUrl: String = "",
	val links: List<LbMetaLink> = emptyList(),
	/** Artists only. */
	val relations: LbMetaRelations = LbMetaRelations(),
	/** Albums only: producer/engineer/writer, roles collapsed per person. */
	val credits: List<LbMetaCredit> = emptyList()
)

@Serializable
data class LbMetaSource(
	val name: String = "",
	val url: String = "",
	val license: String = "",
	val title: String = ""
)

@Serializable
data class LbMetaLink(
	/** MusicBrainz url-relation type, e.g. `official homepage`. */
	val type: String = "",
	val label: String = "",
	val url: String = ""
)

@Serializable
data class LbMetaRelations(
	/** Band members, or the bands a person is a member of. */
	val members: List<LbMetaRelation> = emptyList(),
	/** Collaborations, subgroups, side projects. */
	val related: List<LbMetaRelation> = emptyList()
)

@Serializable
data class LbMetaRelation(
	val mbid: String = "",
	val name: String = "",
	val type: String = "",
	/** MusicBrainz states a relation from one side only; this says which. */
	val direction: String = "",
	/** Years, when MusicBrainz dates the relation. */
	val begin: String = "",
	val end: String = "",
	val ended: Boolean = false,
	/**
	 * The instruments and roles MusicBrainz states for this relation — "guitar",
	 * "lead vocals". lb-bot goes to real trouble for these: MusicBrainz returns
	 * one relation row per instrument AND per stint, and lb-bot collapses them to
	 * one row per person while merging the attributes. The field was missing here,
	 * and `ignoreUnknownKeys` meant it was dropped in silence — which is why the
	 * members list was a bare row of names.
	 */
	val attributes: List<String> = emptyList()
)

@Serializable
data class LbMetaCredit(
	val mbid: String = "",
	val name: String = "",
	/** MusicBrainz's own relation-type names: "producer", "engineer", ... */
	val roles: List<String> = emptyList()
)

/**
 * The "Similar albums" shelf. [because] names the artist that justifies every
 * row — the attribution rule this stack follows everywhere, and the difference
 * between a recommendation and an unsourced popularity claim. Render it.
 */
@Serializable
data class LbSimilarAlbums(
	val albums: List<LbSimilarAlbum> = emptyList(),
	val because: String = "",
	/** Which services proposed the artists: ListenBrainz, and Last.fm when keyed. */
	val sources: List<String> = emptyList()
)

/**
 * One row of that shelf: an album the library **already holds**, by a similar
 * artist. Identified by release-group only, because lb-bot picks it out of its
 * discography index — so routing goes through the external album screen, which
 * already redirects to the library album when it can resolve one.
 */
@Serializable
data class LbSimilarAlbum(
	val rgid: String = "",
	val title: String = "",
	val artist: String = "",
	/** Navidrome artist id of the similar artist, who is in the library. */
	val artistId: String = "",
	val year: String = "",
	val status: String = "",
	/**
	 * The Navidrome album id. Every row on this shelf is a record the library
	 * already holds, so this is normally present and is what a tap should open —
	 * routing on the rgid alone sends an owned album to its own download page.
	 * Blank only when Navidrome has not scanned it yet.
	 */
	val albumId: String = "",
	val coverUrl: String = "",
	val because: String = "",
	val sources: List<String> = emptyList()
)

/**
 * "Fans also like". Unlike [LbSimilarAlbums] every candidate survives — lb-bot
 * marks ownership rather than filtering on it, and the unowned rows are the
 * point of the Discover row this feeds.
 */
/**
 * A browse row: albums and artists together, each marked for ownership.
 *
 * One type for both Deezer routes because they answer the same question in two
 * voices — what is being played everywhere, and what an editor picked — and a row
 * of either is rendered by the same tiles. The two lists are independent: a chart
 * answer may be all albums, all artists, or both.
 *
 * **Ownership marking is conservative here, and that is load-bearing.** Deezer has
 * no MusicBrainz ids, so lb-bot resolves by name; a row it could not resolve comes
 * back `owned: false` with no id rather than with a guess. So an unowned badge may
 * be wrong (you might own it under a different spelling) but an owned badge and an
 * id are never fabricated — which is the direction that matters, because the id is
 * what a tap navigates to.
 */
@Serializable
data class LbBrowseFeed(
	val albums: List<LbBrowseAlbum> = emptyList(),
	val artists: List<LbSimilarArtist> = emptyList(),
	/** Which editorial selection this is, on the editorial route only. */
	val section: String = "",
	/** The genre lb-bot actually served, which may not be the one asked for. */
	val genre: String = "",
	val sources: List<String> = emptyList()
)

/**
 * A release in a browse row. Same ownership vocabulary as [LbAlbumCandidate].
 *
 * **[rgid] is routinely blank, and that is a real state rather than a failure**: it
 * means lb-bot's discography index has never seen this record, so there was nothing
 * local to resolve Deezer's name against. lb-bot deliberately does not fan out a
 * MusicBrainz search per row — that would spend the scanner's whole budget on a
 * shelf nobody tapped — and designates the *tap* as where that one search happens.
 * See `DiscoverViewModel.resolveBrowseAlbum`.
 *
 * Note there is no `year`, no `artistMbid` and no artist-level ownership here.
 * Deezer's chart rows carry none of it, and a field that is always absent is worse
 * than no field: it reads as something the caller may rely on.
 */
@Serializable
data class LbBrowseAlbum(
	val rgid: String = "",
	val title: String = "",
	val artist: String = "",
	/** Deezer's own id — the only stable identity an unresolved row has. */
	val deezerId: String = "",
	/** The Archive's front when [rgid] resolved, else Deezer's own cover. */
	val coverUrl: String = "",
	val releaseOwned: Boolean = false,
	val releaseAlbumId: String = ""
)

/** One of Deezer's browse genres. `id` is what [LbBotManager.deezerChart] takes. */
@Serializable
data class LbDeezerGenre(
	val id: String = "",
	val name: String = "",
	val imageUrl: String = ""
)

@Serializable
data class LbDeezerGenres(
	val genres: List<LbDeezerGenre> = emptyList()
)

@Serializable
private data class LbResolveLinkRequest(val url: String)

/**
 * What a pasted link turned out to be.
 *
 * [confidence] is on the wire because the resolution paths differ by an order of
 * magnitude in reliability: a MusicBrainz URL is exact and needs no network call,
 * a Spotify or Deezer id is looked up through that provider's own API, and an
 * Apple / YT-Music / Tidal / Qobuz URL is resolved by searching MusicBrainz for an
 * artist and title scraped out of the URL or its page title. The UI shows what it
 * thinks the link is and lets the user confirm rather than acting on a guess.
 */
@Serializable
data class LbLinkResolution(
	/** `artist`, `album`, `track`, or `unknown`. */
	val kind: String = "unknown",
	val mbid: String = "",
	val rgid: String = "",
	val artist: String = "",
	val title: String = "",
	val provider: String = "",
	val confidence: Double = 0.0,
	/**
	 * Why it did not resolve, in lb-bot's own words.
	 *
	 * Additive and outside the frozen contract, so it is blank on a hub or lb-bot
	 * that predates it — which is exactly why every caller falls back to its own
	 * generic line rather than showing an empty message. When it is there it is the
	 * difference between "that didn't work" and "Couldn't read the Tidal page".
	 */
	val reason: String = ""
) {
	val resolved: Boolean get() = kind != "unknown" && (mbid.isNotBlank() || rgid.isNotBlank())
}

/**
 * The wishlist envelope.
 *
 * The list key is `wishlist`, not `items` — and the add and remove routes answer
 * with the **whole updated list** alongside their `ok`, which is why neither needs
 * a follow-up read.
 */
@Serializable
data class LbWishlist(
	val wishlist: List<LbWishlistItem> = emptyList(),
	val total: Int = 0,
	/** How often lb-bot re-searches the list, in seconds. Hours, by design. */
	val intervalSeconds: Double = 0.0,
	/** The minimum gap before the same row is tried again. */
	val cooldownSeconds: Double = 0.0,
	val ok: Boolean = false
)

/** One release on the wishlist, plus whatever lb-bot knows about looking for it. */
@Serializable
data class LbWishlistItem(
	val rgid: String = "",
	val artist: String = "",
	val title: String = "",
	val addedAt: Double = 0.0,
	/** Unix seconds of lb-bot's last re-search of THIS row, 0 if never. */
	val lastTriedAt: Double = 0.0,
	val attempts: Int = 0,
	/** Why the last attempt failed, in lb-bot's own words. Blank before the first. */
	val lastReason: String = ""
) {
	val coverUrl: String get() = LbBotManager.caaCoverUrl(rgid)
}

@Serializable
private data class LbWishlistAddRequest(
	val rgid: String,
	val artist: String,
	val title: String
)

@Serializable
private data class LbWishlistRemoveRequest(val rgid: String)

@Serializable
data class LbSimilarArtists(
	val artists: List<LbSimilarArtist> = emptyList(),
	/** The artist that justifies the row — what the reason line names. */
	val because: String = "",
	val sources: List<String> = emptyList()
)

/**
 * One similar artist. [owned] and [indexed] are separate facts on purpose: an
 * artist can be in the library and never have had their discography walked, and
 * "what am I missing from them" is only answerable in the second case. Folding
 * them into one boolean is how a row ends up offering an action it cannot take.
 */
@Serializable
data class LbSimilarArtist(
	val mbid: String = "",
	val name: String = "",
	val score: Double = 0.0,
	val sources: List<String> = emptyList(),
	/**
	 * A picture, when the source had one.
	 *
	 * Populated by the Deezer-backed routes and blank from the ListenBrainz/Last.fm
	 * merge, which names artists without picturing them. It is the only artwork an
	 * artist the library does not hold can have, so it is what keeps an unowned tile
	 * from being a bare placeholder beside an owned one.
	 */
	val imageUrl: String = "",
	val owned: Boolean = false,
	/** Navidrome artist id, blank when the library does not hold them. */
	val artistId: String = "",
	val indexed: Boolean = false
)

@Serializable
data class LbReleaseDetail(
	val artist: String = "",
	/**
	 * The lead credited artist's MusicBrainz id, or blank on an older lb-bot.
	 *
	 * The only way an album reached from a **Deezer** row can offer its artist's
	 * page: Deezer carries no MBIDs, so nothing in that navigation knows one until
	 * this route answers. Blank is an ordinary state and the caller falls back to
	 * the library, then to nothing.
	 */
	val artistMbid: String = "",
	val title: String = "",
	val coverUrl: String = "",
	/** The release-group's own primary type and year. Carried so a caller can
	 *  hand them back to [LbBotManager.indexRelease] as its MusicBrainz-outage
	 *  override — the type is what decides which section of the artist page the
	 *  stored row lands in, so an add that had to guess files it under "Other". */
	val primaryType: String = "",
	val year: String = "",
	val releases: List<LbVariant> = emptyList()
)

/** A variant changes the tracklist (Original / Remaster / Deluxe). */
@Serializable
data class LbVariant(
	val releaseMbid: String = "",
	val title: String = "",
	val disambiguation: String = "",
	val year: String = "",
	val trackCount: Int = 0,
	val coverUrl: String = "",
	/** Same tracklist, different pressing — each carries its own cover art. */
	val editions: List<LbEdition> = emptyList()
)

@Serializable
data class LbEdition(
	val releaseMbid: String = "",
	/** Digital | CD | Vinyl | Cassette | Other */
	val label: String = "",
	val format: String = "",
	val year: String = "",
	val coverUrl: String = ""
)

@Serializable
data class LbTracklist(
	/** False when the library holds none of this album. Every track is missing; that
	 *  is the normal case here, and it must not render as `0/12` or as an error. */
	val presenceKnown: Boolean = false,
	val tracks: List<LbTrack> = emptyList()
)

@Serializable
data class LbTrack(
	val position: Int = 0,
	val title: String = "",
	val present: Boolean = false
)

/**
 * unknown -> searching -> queued -> downloading -> placing -> placed -> verified,
 * with needs_match and failed as the two side exits.
 *
 * `unknown` is the resting state of every album nobody has asked for, so it must
 * never render as an error. Backwards steps are normal: lb-bot reports
 * `downloading` for as long as a transfer group is pending.
 */
@Serializable
data class LbFillStatus(
	val releaseMbid: String = "",
	val rgid: String = "",
	val state: String = "unknown",
	val artist: String = "",
	val album: String = "",
	val quality: String = "",
	val done: Int = 0,
	val total: Int = 0,
	val failed: Int = 0,
	val percent: Int = 0,
	/** lb-bot's own sentence for a failure. Shown verbatim. */
	val reason: String = "",
	/** The search rejected mp3s and would have found something with them. */
	val mp3WouldHelp: Boolean = false,
	/**
	 * What kind of failure, so the row can say "nobody is sharing this" apart from
	 * "every source was rejected for format" *before* it is opened — the second is
	 * what Allow MP3 is for, and telling them apart used to mean reading [reason].
	 *
	 * `no_source` | `format_rejected` | `transfer_failed` | `placement_failed` |
	 * `mb_unavailable`, and empty on anything that has not failed. An lb-bot
	 * predating the field sends nothing, which is the same empty.
	 */
	val failureKind: String = "",
	/**
	 * Whether a plain Retry is worth offering. Deliberately false for a format
	 * rejection MP3 would fix: re-running the identical search against the same
	 * peers under the same format policy is the same failure again, not a retry.
	 */
	val retryable: Boolean = false,
	/** Fills lb-bot has recorded for this release, including its own automatic
	 *  re-attempt for the transient kinds. Survives an lb-bot restart. */
	val attempts: Int = 0,
	/** lb-bot review group, when the album has one — required for Allow MP3. */
	val groupId: String = "",
	/** Navidrome album ids, once lb-bot's verifier has seen the album indexed. */
	val ndAlbumIds: List<String> = emptyList(),
	/** The Soulseek peer the transfer was queued from. "Try another source" excludes it. */
	val source: String = "",
	/** Bytes landed across the album's transfers, and the total; 0 when unknown. */
	val bytesDone: Long = 0L,
	val bytesTotal: Long = 0L,
	val speedBps: Long = 0L,
	val activeFiles: Int = 0,
	/** The one Cancel rule, stated by the server. Null from an lb-bot that predates it. */
	val cancellable: Boolean? = null,
	/** Epoch seconds when lb-bot's own automatic retry fires, or 0. */
	val retryAt: Double = 0.0,
	/** Epoch seconds of the last state change OR transfer progress. */
	val updatedAt: Double = 0.0,
	val serverTime: Double = 0.0,
	/** `placed` past the verifier's deadline: on disk, not (yet) in Navidrome. */
	val verifyGaveUp: Boolean = false,
	/** Started with the whole-album MP3 opt-in. */
	val allowMp3: Boolean = false
) {
	/** An lb-bot from before `cancelled` was a state of its own wrote a cancel as
	 *  `failed` + `failureKind: "cancelled"`; read both for one cycle. */
	fun normalized(): LbFillStatus =
		if (state == "failed" && failureKind == "cancelled")
			copy(state = "cancelled", failureKind = "", retryable = false)
		else this

	val canCancel: Boolean
		get() = cancellable ?: (state in LbBotManager.CANCELLABLE_FILL_STATES)
}

/** `GET /lb/fills`: every watched fill in one read. */
@Serializable
data class LbFillsResponse(
	val albums: Map<String, LbFillStatus> = emptyMap(),
	val gaps: Map<String, LbGap> = emptyMap(),
	val serverTime: Double = 0.0
)

const val EVENT_PLACED = "albumPlaced"
const val EVENT_INDEXED = "albumIndexed"
/** A discography scan finished or failed. Changes lb-bot's index, nothing in Navidrome. */
const val EVENT_ARTIST_SCANNED = "artistScanned"
/** Not a wire event: [LbBotManager.onLocalLibrarySynced]'s bump, "Room caught up". */
const val EVENT_LOCAL_SYNCED = "localSynced"

/**
 * One `library` frame from the hub (or the local fill poll saying the same thing).
 *
 * `albumPlaced`: files are in the library folder, Navidrome hasn't scanned them.
 * `albumIndexed`: Navidrome has them, and [ndAlbumIds] names the albums to sync.
 */
data class LbLibraryEvent(
	val event: String = EVENT_PLACED,
	val rgid: String = "",
	val ndArtistId: String = "",
	val ndAlbumIds: List<String> = emptyList()
)

/** One partly-owned album's Fill-gaps record. `status`: ready | picking | downloading | complete | failed. */
@Serializable
data class LbGap(
	val id: String = "",
	val albumId: String = "",
	val artist: String = "",
	val album: String = "",
	val present: Int = 0,
	val total: Int = 0,
	/** Files the tracklist can't account for — a fill that left a duplicate behind. */
	val extra: Int = 0,
	val missingCount: Int = 0,
	val status: String = "ready",
	val tracks: List<LbGapTrack> = emptyList(),
	val sources: List<LbGapSource> = emptyList(),
	val sourcesTotal: Int = 0,
	val sourcesPage: Int = 0,
	val sourcesPages: Int = 1,
	val sourcesFoundAt: Double = 0.0,
	/** The background source search, so the sheet can show it running and report how
	 *  it ended. This is what a client watches instead of the task API. */
	val sourceTask: LbGapTask? = null,
	val failReason: String = "",
	val failDetail: String = "",
	val allowMp3: Boolean = false,
	val noSourceReason: String = "",
	val mp3WouldHelp: Boolean = false,
	/** The MusicBrainz release the gap is measured against — it comes from the
	 *  canonical album's own tag, so a library tagged as a 17-track deluxe reports
	 *  17 slots even when every pressing on offer has 12. Naming it is what makes
	 *  that number explicable instead of looking like a miscount. */
	val canonicalMbid: String = ""
) {
	/** Progress across the tracks being filled, for a `downloading` gap. */
	val tracksDone: Int get() = tracks.count { it.state in GAP_TRACK_DONE_STATES }
	val tracksWanted: Int get() = tracks.count { it.state != "present" }
	val tracksFailed: Int get() = tracks.count { it.state == "failed" }
}

/** Track states that count as "no longer waiting on a transfer". */
private val GAP_TRACK_DONE_STATES = setOf("downloaded", "done", "skipped")

@Serializable
data class LbGapTask(
	val id: String = "",
	val status: String = "",
	val label: String = "",
	val current: String = "",
	val summary: String = "",
	val error: String = ""
)

/** `state`: present | missing | picked | queued | downloading | downloaded | failed | skipped | done. */
@Serializable
data class LbGapTrack(
	val position: Int = 0,
	val title: String = "",
	val artist: String = "",
	val state: String = "missing",
	val downloadError: String = ""
)

@Serializable
data class LbGapSource(
	val id: Int = 0,
	val peer: String = "",
	val folder: String = "",
	val format: String = "",
	val bitrate: String = "",
	val size: String = "",
	val fileCount: Int = 0,
	val speedMbps: Double = 0.0,
	val queueLength: Int = 0,
	val freeSlot: Boolean = false,
	/** A label ("9/12 tracks" from the album picker, "full"/"partial" from a gap),
	 *  plus the counts behind it. Matched against the canonical MusicBrainz
	 *  tracklist — NOT a file count, which is what used to let a folder holding a
	 *  different album entirely report as a complete match. */
	val coverage: String = "unknown",
	val coverageFull: Boolean = false,
	val coverageDetail: LbGapCoverage = LbGapCoverage(),
	val flags: List<String> = emptyList(),
	val recommendation: String = "",
	/** How much the folder's own name reads as this album. For an ambiguous query the
	 *  peer's whole discography comes back, and peer speed is no way to tell them apart. */
	val albumMatch: Double = 0.0,
	val albumMatchOk: Boolean = false,
	val score: Double = 0.0,
	val rank: Int = 0,
	val recommended: Boolean = false,
	/** Present on the album picker (that route is not stripped); always empty on a
	 *  gap poll, where the listing is fetched separately via [LbBotManager.gapSourceFiles]. */
	val files: List<LbSourceFile> = emptyList(),
	val filesTruncated: Boolean = false
)

/**
 * One file in a peer's folder, and the tracklist slot it would fill.
 *
 * `matchedTo` being null is the interesting case: a folder whose files match no
 * slot at all is visibly the wrong album, where a file count alone would have
 * read as a full match.
 */
@Serializable
data class LbSourceFile(
	val filename: String = "",
	val ext: String = "",
	/** False when the format is outside lb-bot's accepted list (e.g. mp3 when off). */
	val accepted: Boolean = true,
	val sizeMb: Double = 0.0,
	val bitrate: Int = 0,
	val durationSec: Int = 0,
	val matchedTo: LbSourceMatch? = null
)

@Serializable
data class LbSourceMatch(
	val position: String = "",
	val title: String = "",
	/** How it was paired: recording mbid, title, duration… */
	val basis: String = ""
)

@Serializable
data class LbSourceFiles(
	val ok: Boolean = false,
	/** False when the peer is offline or the expand failed — the list is then the
	 *  original search hits, not the real folder, and the UI must not imply otherwise. */
	val expanded: Boolean = false,
	val files: List<LbSourceFile> = emptyList(),
	val filesTruncated: Boolean = false,
	val fileCount: Int = 0,
	val coverage: String = "",
	val coverageDetail: LbGapCoverage = LbGapCoverage()
)

@Serializable
data class LbGapCoverage(
	val haveTracks: Int = 0,
	val totalTracks: Int = 0,
	val unmatched: List<String> = emptyList()
)

/**
 * One fill, persisted so it survives leaving the screen and being killed — and, once
 * settled, so it survives *ending*. This is the stored form of a ledger row.
 *
 * Every field added since the first version has a default, which is what lets an older
 * stored map decode straight into the newer shape: a fill written before the ledger
 * existed reads back as a running row with no display fields, which the poll fills in on
 * its next tick. A decode failure is caught and degrades to "no history", never a crash.
 */
@Serializable
private data class LbWatch(
	val kind: String,
	val key: String,
	val releaseMbid: String = "",
	val quality: String = "",
	val startedAt: Long = 0L,
	val settled: Boolean = false,
	/** Kept for rows persisted before `unknownSince` replaced it; no longer written. */
	val unknownPolls: Int = 0,

	// ----- ledger: enough to render and re-issue the fill with no second read ----- //
	val artist: String = "",
	val album: String = "",
	/**
	 * Canonical track count of the exact release that was chosen, completing the edition
	 * snapshot (releaseMbid + artist + album + this). Retry has to resend all four: lb-bot
	 * prefers a supplied release over re-resolving the release-group, and its own resolver
	 * picks "official, earliest" — so a retry that sent only the rgid could quietly fetch a
	 * different pressing than the one the user picked, which is the exact ambiguity the
	 * edition picker exists to remove. 0 means an older ledger row with no snapshot.
	 */
	val editionTotalTracks: Int = 0,
	val sourcePeer: String = "",
	val sourceFolder: String = "",
	val finishedAt: Long = 0L,
	/**
	 * When this row's one-time completion notification was actually delivered. 0 = still owed.
	 *
	 * [LbBotManager.fillEvents] is a replay-less SharedFlow collected only while the activity is
	 * STARTED, so a fill that finished with the app in the background emitted into nothing: the
	 * ledger recorded the completion and the promised notification never arrived. Persisting the
	 * delivery instead of trusting the emission survives backgrounding AND process death.
	 */
	val notifiedAt: Long = 0L,
	/** The last state lb-bot reported, kept verbatim so the row can explain itself. */
	val state: String = "",
	val outcome: String = "running",
	val reason: String = "",
	val percent: Int = 0,
	val done: Int = 0,
	val total: Int = 0,
	val mp3WouldHelp: Boolean = false,
	/** What kind of failure lb-bot recorded — see [LbFillStatus.failureKind]. Empty
	 *  for a gap fill, which has no equivalent, and for rows predating the field. */
	val failureKind: String = "",
	/** Whether lb-bot says a plain Retry is worth offering. Defaults true so a row
	 *  from before the field, and every gap fill, keeps its button. */
	val retryable: Boolean = true,
	val attempts: Int = 0,
	/** lb-bot's review group. A gap fill *is* one, so this only carries an album's —
	 *  which `album/status` reports, and which Allow MP3 needs to address the album. */
	val groupId: String = "",
	/** The peer lb-bot actually queued from, learned from a poll. */
	val lastSource: String = "",
	/** Peers "Try another source" has ruled out for this album so far. */
	val excludedPeers: List<String> = emptyList(),
	/** Files that failed inside a fill that is otherwise progressing. */
	val failedFiles: Int = 0,
	val bytesDone: Long = 0L,
	val bytesTotal: Long = 0L,
	val speedBps: Long = 0L,
	/** Epoch ms when lb-bot's own automatic retry fires; 0 when none is pending. */
	val retryAt: Long = 0L,
	/** The server's Cancel rule for this fill. */
	val cancellable: Boolean = true,
	/** Since when lb-bot has answered `unknown` for a fill we think is running, or 0. */
	val unknownSince: Long = 0L,
	val verifyGaveUp: Boolean = false,
	val allowMp3: Boolean = false,
	/** When a poll last reached lb-bot for this row, and what the last failed poll said. */
	val lastCheckedAt: Long = 0L,
	val lastError: String = "",
	val lastErrorTicks: Int = 0,
	/** When the row last MOVED — state, files or bytes. Expiry is measured from here. */
	val lastProgressAt: Long = 0L,

	// ----- adaptive polling ------------------------------------------------------ //
	/** Digest of the last answer. Identical twice running means nothing is moving. */
	val fingerprint: String = "",
	val quietTicks: Int = 0,
	val nextPollAt: Long = 0L
) {
	/** When this row last did anything — its sort key, live or dead. */
	fun finishedAtOrStart(): Long = if (finishedAt > 0L) finishedAt else startedAt

	/** Every peer a "Try another source" should skip. Empty when none is known yet. */
	fun otherSourceExcludes(): List<String> =
		(excludedPeers + lastSource + sourcePeer).filter { it.isNotBlank() }.distinct()

	/** This row, re-opened for another attempt with its display fields kept. */
	fun reopened(releaseMbid: String, excludedPeers: List<String>, allowMp3: Boolean, now: Long): LbWatch = copy(
		settled = false,
		outcome = LbBotManager.OUTCOME_RUNNING,
		reason = "",
		state = "",
		unknownPolls = 0,
		unknownSince = 0L,
		quietTicks = 0,
		fingerprint = "",
		nextPollAt = 0L,
		finishedAt = 0L,
		startedAt = now,
		lastProgressAt = now,
		lastError = "",
		lastErrorTicks = 0,
		failureKind = "",
		retryAt = 0L,
		cancellable = true,
		verifyGaveUp = false,
		percent = 0,
		done = 0,
		total = 0,
		failedFiles = 0,
		bytesDone = 0L,
		bytesTotal = 0L,
		speedBps = 0L,
		allowMp3 = allowMp3,
		releaseMbid = releaseMbid.ifBlank { this.releaseMbid },
		excludedPeers = (this.excludedPeers + excludedPeers).distinct(),
		// A different peer will be picked: the chosen one is what was ruled out.
		sourcePeer = if (excludedPeers.isEmpty()) sourcePeer else "",
		sourceFolder = if (excludedPeers.isEmpty()) sourceFolder else ""
	)

	fun toEntry(): LbFillEntry = LbFillEntry(
		key = key,
		isGap = kind == "gap",
		rgid = if (kind == "gap") "" else key,
		groupId = if (kind == "gap") key else groupId,
		releaseMbid = releaseMbid,
		artist = artist,
		album = album,
		quality = quality,
		state = state,
		outcome = outcome,
		reason = reason,
		percent = percent,
		done = done,
		total = total,
		mp3WouldHelp = mp3WouldHelp,
		failureKind = failureKind,
		retryable = retryable,
		attempts = attempts,
		settled = settled,
		startedAt = startedAt,
		finishedAt = finishedAt,
		canTryAnotherSource = kind != "gap" && otherSourceExcludes().isNotEmpty(),
		cancellable = cancellable,
		failedFiles = failedFiles,
		bytesDone = bytesDone,
		bytesTotal = bytesTotal,
		speedBps = speedBps,
		retryAt = retryAt,
		lastSource = lastSource,
		lastCheckedAt = lastCheckedAt,
		lastError = lastError,
		lastErrorTicks = lastErrorTicks,
		verifyGaveUp = verifyGaveUp
	)
}

/**
 * One row of the downloads view: a fill this device asked for, in flight or finished.
 *
 * Deliberately flat and self-contained — the view that renders it may be opened days
 * after the fill, on a screen that has no artist page behind it, and possibly after
 * lb-bot has forgotten the fill entirely (its own ledger is in memory and capped).
 */
data class LbFillEntry(
	val key: String,
	/** Gap fills (an album the library holds *partly*) take a different retry path. */
	val isGap: Boolean,
	val rgid: String,
	val groupId: String,
	val releaseMbid: String,
	val artist: String,
	val album: String,
	val quality: String,
	val state: String,
	val outcome: String,
	/** lb-bot's own sentence for a failure. Shown verbatim — it names the cause. */
	val reason: String,
	val percent: Int,
	val done: Int,
	val total: Int,
	/** The search rejected mp3s and would have found something with them. */
	val mp3WouldHelp: Boolean,
	/** lb-bot's own classification of the failure — see [LbFillStatus.failureKind].
	 *  Empty means unknown (a gap fill, or an lb-bot predating the field), which is
	 *  why [canRetry] treats an absent kind as "offer it anyway". */
	val failureKind: String,
	val retryable: Boolean,
	val attempts: Int,
	val settled: Boolean,
	val startedAt: Long,
	val finishedAt: Long,
	/** A peer is known to rule out, so "Try another source" means something. */
	val canTryAnotherSource: Boolean = false,
	/** The server's Cancel rule (PROTOCOL §15): searching, queued or downloading. */
	val cancellable: Boolean = true,
	val failedFiles: Int = 0,
	val bytesDone: Long = 0L,
	val bytesTotal: Long = 0L,
	val speedBps: Long = 0L,
	/** Epoch ms when lb-bot's own automatic retry fires; 0 when none is pending. */
	val retryAt: Long = 0L,
	/** The peer the current transfer is from. */
	val lastSource: String = "",
	val lastCheckedAt: Long = 0L,
	val lastError: String = "",
	/** Consecutive polls that could not reach lb-bot. */
	val lastErrorTicks: Int = 0,
	/** `placed` past lb-bot's verify deadline — on disk, not in Navidrome. */
	val verifyGaveUp: Boolean = false
) {
	val isRunning: Boolean get() = !settled

	/**
	 * Whether to offer a plain Retry — only on a FAILED outcome. A cancelled row used
	 * to offer one too, which is the "restart it from lb-bot" confusion: a cancel is
	 * restarted from the album page.
	 *
	 * Absent classification means unknown, not "no": hiding the only action on a
	 * guess is worse than offering one that may not help. Where lb-bot *has*
	 * classified it, its answer is taken — which is what stops a format rejection
	 * offering a retry that re-runs the same rejected search.
	 */
	val canRetry: Boolean get() = outcome == "failed" && (failureKind.isBlank() || retryable)

	/** Cancel is offered on a running fill the server calls cancellable, and on a
	 *  failed one whose automatic retry is still pending. */
	val canCancel: Boolean get() = (!settled && cancellable) || (settled && outcome == "failed" && retryAt > 0L)
	val succeeded: Boolean get() = outcome == "done"
	/** Cover art for a release the library by definition does not have. */
	val coverUrl: String get() = LbBotManager.caaCoverUrl(rgid)
}

/** A fill reaching a terminal outcome. Emitted once, from `settle`. */
data class LbFillEvent(
	val key: String,
	val artist: String,
	val album: String,
	val outcome: String,
	val reason: String
)
