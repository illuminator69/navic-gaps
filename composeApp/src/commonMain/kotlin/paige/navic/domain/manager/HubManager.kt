package paige.navic.domain.manager

import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import paige.navic.data.database.dao.SongDao
import paige.navic.data.database.entities.SavedQueueEntity
import paige.navic.data.database.mappers.toDomainModel
import paige.navic.domain.models.DomainExplicitStatus
import paige.navic.domain.models.DomainSong
import paige.navic.domain.models.Mix
import paige.navic.domain.repositories.SavedQueueRepository
import paige.navic.shared.MediaPlayerViewModel
import paige.navic.shared.RemotePlaybackRouter
import paige.navic.ui.core.PlayerUiState
import paige.navic.util.Logger
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.flow.firstOrNull

data class HubDevice(
	val id: String,
	val name: String,
	val platform: String,
	/**
	 * A socket claiming this id is attached to the hub.
	 *
	 * For a bridged Chromecast that is the *bridging client's* socket, which lives on some other
	 * phone or desktop — it says nothing about whether the speaker is powered on or even on the
	 * same continent. See [reachable]; PROTOCOL §3.2.
	 */
	val online: Boolean,
	val isActive: Boolean,
	val volume: Int,
	/**
	 * The bridge's verdict on the hardware: true reached recently, false connected-but-silent,
	 * null unknown or not applicable — which is every ordinary client, since a client is its own
	 * hardware and its socket already proves it.
	 */
	val reachable: Boolean? = null,
	/** For a virtual device, the hub id of the client bridging it. */
	val bridgedBy: String? = null,
	/**
	 * What the device said it is in `hello` (`computer`, `phone`, `tablet`, `speaker`, `tv`,
	 * `group`), for the icon its row draws; null if it never said (`DeviceRows.kt`).
	 */
	val kind: String? = null,
	/**
	 * What the device's current socket said it does (`hello.device.caps`). Read here for
	 * `idleVolume`: it applies a volume command while it is not the playing device.
	 */
	val caps: Set<String> = emptySet()
) {
	/** Can the session be handed here right now? Unknown reachability is permissive. */
	val transferable: Boolean get() = online && reachable != false
}

data class RemoteTrack(
	val id: String,
	val title: String,
	val artist: String,
	val album: String?,
	val durationMs: Long,
	val imageUrl: String?
)

/**
 * Mirror of the hub session for the remote-control UI: what's playing on the
 * ACTIVE device, with a wall-clock snapshot so the UI can tick the position
 * locally between the hub's ~1 Hz progress frames.
 */
data class RemoteSessionState(
	val tracks: List<RemoteTrack> = emptyList(),
	val index: Int = 0,
	val isPlaying: Boolean = false,
	val positionMs: Long = 0,
	val positionAtMs: Long = 0,
	val repeat: String = "none",
	val shuffle: Boolean = false,
	/** Saved-queue history id of the current session (the "Now Playing" record), or null. */
	val savedQueueId: String? = null,
	/**
	 * A seek this device has just sent, which [positionMs] already shows. `progress` frames
	 * still carrying the old position are ignored until it lapses (`CommandHold.kt`). A
	 * snapshot built from a `session` frame carries none.
	 */
	val seekHold: SeekHold? = null,
	/**
	 * Values this device has commanded and the hub has not answered for yet (`ActPins.kt`):
	 * the fields above show them, and each pin keeps what the hub last said underneath.
	 */
	val pins: ActPins = ActPins(),
	/**
	 * The session's sleep timer as the last `session` frame gave it, with its end on this
	 * device's clock (`SessionSleepTimer.kt`); null when none is set, and from a hub that
	 * holds none.
	 */
	val sleepTimer: SessionSleepTimer? = null,
	/**
	 * The station the session's queue continues as (`session.stationId`, PROTOCOL §18.2), from a
	 * hub with `station` in its features; null when none, and from any other hub.
	 */
	val stationId: String? = null,
	/** What the session's queue is called (`session.sourceName`): a radio's own name, an album's. */
	val sourceName: String? = null,
	/** `session.order`: the queue's indices in play order while shuffled, null in queue order. */
	val order: List<Int>? = null
) {
	val nowPlaying: RemoteTrack? get() = tracks.getOrNull(index)
}

/** `welcome.features`: the hub reads `target` on a `volume` act (PROTOCOL §5.1). */
const val HUB_FEATURE_VOLUME_TARGET = "volumeTarget"

/** `welcome.features`: the hub holds the session's sleep timer (PROTOCOL §5.1). */
const val HUB_FEATURE_SLEEP_TIMER = "sleepTimer"

/**
 * navi-connect hub client: makes Navic a Spotify-Connect-style device against
 * the relay hub (see navi-connect PROTOCOL.md). Navic is both a receiver
 * (obeys `do` directives, reports position ~1 Hz while it is the active
 * device) and a controller (can transfer playback to other devices).
 *
 * Frames are plain JSON objects with a `t` discriminator, handled with the
 * dynamic JsonObject API rather than typed DTOs so protocol additions never
 * break deserialization.
 */
class HubManager(
	private val preferenceManager: PreferenceManager,
	private val sessionManager: SessionManager,
	private val songDao: SongDao,
	private val mediaPlayer: MediaPlayerViewModel,
	private val savedQueueRepository: SavedQueueRepository,
	private val lbBotManager: LbBotManager,
	private val lbIndexSync: LbIndexSync,
	socketClientFactory: HubSocketClientFactory,
	private val localDevice: HubLocalDevice
) : RemotePlaybackRouter {
	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
	// Protocol-level WS pings, so a half-open socket (Wi-Fi→cellular, a NAT timeout) fails and
	// the loop reconnects instead of blocking `incoming` forever; the app-level `ping`/`pong` only
	// refreshes the hub's last-seen. They come from the platform's factory because the
	// `install(WebSockets) { pingIntervalMillis = 10_000 }` that used to be here sent NOTHING on
	// Android: Ktor's OkHttp engine ignores it, and only an engine-level pingInterval goes out
	// (B-045, see HubSocketClientFactory).
	private val client = socketClientFactory.create()

	private val _connected = MutableStateFlow(false)
	val connected: StateFlow<Boolean> = _connected.asStateFlow()

	init {
		// While the socket is up, `fill` frames reach lb-bot's manager within a second of
		// lb-bot writing its ledger, and its poll drops to a safety net.
		lbBotManager.hubConnected = { _connected.value }
	}

	private val _devices = MutableStateFlow<List<HubDevice>>(emptyList())
	val devices: StateFlow<List<HubDevice>> = _devices.asStateFlow()

	private val _myDeviceId = MutableStateFlow<String?>(null)
	val myDeviceId: StateFlow<String?> = _myDeviceId.asStateFlow()

	private val _activeDeviceId = MutableStateFlow<String?>(null)
	val activeDeviceId: StateFlow<String?> = _activeDeviceId.asStateFlow()

	private val _remoteSession = MutableStateFlow(RemoteSessionState())
	val remoteSession: StateFlow<RemoteSessionState> = _remoteSession.asStateFlow()

	private val _isRemoteActive = MutableStateFlow(false)

	/** True when playback is live on ANOTHER device — show/route the remote view. */
	val isRemoteActive: StateFlow<Boolean> = _isRemoteActive.asStateFlow()

	private val _hubFeatures = MutableStateFlow<Set<String>>(emptySet())

	/**
	 * What the hub on the current socket said it understands beyond the base protocol
	 * (`welcome.features`, PROTOCOL §3.1). Empty from the moment a socket opens until its
	 * `welcome`, and for a hub that says nothing. It outlives the socket it came from, so
	 * ask [hubOffers], or read it together with [connected].
	 */
	val hubFeatures: StateFlow<Set<String>> = _hubFeatures.asStateFlow()

	/** The link is up and the hub at its other end listed [feature]. */
	fun hubOffers(feature: String): Boolean = _connected.value && feature in _hubFeatures.value

	private val _stationFrames = MutableStateFlow<Map<String, HubStationFrame>>(emptyMap())

	/**
	 * What the hub last said about each station it told this device of: `welcome.station` (the
	 * session's, with its name and seed) and every `station` frame (PROTOCOL §18.2), by station id.
	 * The cursor (`seq`) here is how a device that did not serve the last batch learns `after`.
	 * In memory; a welcome restates the session's station.
	 */
	val stationFrames: StateFlow<Map<String, HubStationFrame>> = _stationFrames.asStateFlow()

	/**
	 * The station this device's queue is bound to, for the `stationId` its `setQueue` carries
	 * (PROTOCOL §5.1): a `setQueue` without one unbinds the session, and this device publishes its
	 * queue again on every edit, an autoplay top-up included. Set by `StationManager`; the queue's
	 * state and the saved-queue id the publish carries are what it decides from.
	 */
	var stationIdForPublish: (PlayerUiState, String) -> String? = { _, _ -> null }

	/**
	 * A `station` frame, or `welcome.station`. Both carry `name` and `origin`; the seed only comes
	 * with the welcome. A field a frame leaves out keeps what was known.
	 */
	private fun applyStationFrame(obj: JsonObject) {
		val id = obj["stationId"]?.jsonPrimitive?.contentOrNullSafe() ?: return
		val previous = _stationFrames.value[id]
		val seed = (obj["seed"] as? JsonObject)?.let { seedObj ->
			val kind = seedObj["kind"]?.jsonPrimitive?.contentOrNullSafe() ?: return@let null
			StationSeed(
				kind = kind,
				ids = (seedObj["ids"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty(),
				name = seedObj["name"]?.jsonPrimitive?.contentOrNullSafe()
			)
		}
		val frame = HubStationFrame(
			stationId = id,
			seq = obj["seq"]?.jsonPrimitive?.intOrNull ?: previous?.seq ?: 0,
			rev = obj["rev"]?.jsonPrimitive?.intOrNull ?: previous?.rev ?: 0,
			character = obj["character"]?.jsonPrimitive?.contentOrNullSafe() ?: previous?.character.orEmpty(),
			exhausted = obj["exhausted"]?.jsonPrimitive?.booleanOrNull ?: previous?.exhausted ?: false,
			name = obj["name"]?.jsonPrimitive?.contentOrNullSafe()?.takeIf { it.isNotBlank() } ?: previous?.name,
			seed = seed ?: previous?.seed,
			origin = obj["origin"]?.jsonPrimitive?.contentOrNullSafe()?.takeIf { it.isNotBlank() } ?: previous?.origin,
			stopped = obj["stopped"]?.jsonPrimitive?.booleanOrNull ?: previous?.stopped ?: false,
			// P7: present (a mood id or null) on a hub with moods; absent keeps what was known.
			mood = if ("mood" in obj) obj["mood"]?.takeIf { it is JsonPrimitive }?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
			else previous?.mood,
			moodFilter = if ("moodFilter" in obj) obj["moodFilter"]?.takeIf { it is JsonPrimitive }?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
			else previous?.moodFilter
		)
		// Bounded: a station frame names one station, and only a handful are alive on a hub (8).
		_stationFrames.update { frames -> (frames - id + (id to frame)).entries.toList().takeLast(16).associate { it.toPair() } }
	}

	/**
	 * The session's sleep timer where this device can vouch for it (`sessionTimerInForce`):
	 * what the sleep-timer controls show on every device, playing or not.
	 */
	val sessionSleepTimer: StateFlow<SessionSleepTimer?> =
		combine(
			_remoteSession, _connected, _hubFeatures, _activeDeviceId, _myDeviceId
		) { session, connected, features, active, me ->
			sessionTimerInForce(
				timer = session.sleepTimer,
				hubHoldsTimers = connected && HUB_FEATURE_SLEEP_TIMER in features,
				isActiveDevice = me != null && me == active
			)
		}.stateIn(scope, SharingStarted.Eagerly, null)

	/**
	 * Last hub-side error the user should know about (bad token, target offline, …).
	 * The UI (device picker / NaviConnect settings) can surface it as a snackbar —
	 * without this a wrong token just looked like "never connects". Cleared on a
	 * successful `welcome`.
	 */
	private val _connectionError = MutableStateFlow<String?>(null)
	val connectionError: StateFlow<String?> = _connectionError.asStateFlow()

	/** Set when the hub rejects our token, so the reconnect loop stops tight-looping. */
	private var authFailed = false

	/** When our socket dropped, so a brief reconnect doesn't tear the remote view down. */
	private var disconnectedAtMs = 0L

	/** When the hub said `welcome` on the current socket; the backoff asks how long it then held. */
	private var welcomedAtMs = 0L

	/**
	 * Cuts the reconnect loop's wait short, carrying why. Conflated: a burst of kicks is one
	 * kick. See [kickReconnect].
	 */
	private val reconnectKick = Channel<String>(Channel.CONFLATED)

	/**
	 * Losing the socket does NOT move playback — it only blinds us. Tearing the remote view down
	 * on a blip made the UI fall back to the LOCAL player, which (untouched, at its defaults:
	 * isPaused=false, progress=0) renders as "playing, at 0:00" while the remote device carries on
	 * — the phantom unpause + progress-bar reset. So hold the remote view across a reconnect, and
	 * only release it back to local if the hub stays gone (otherwise an actually-dead hub would
	 * strand the user in a session they can't control).
	 */
	private fun evaluateRemoteActive() {
		val active = _activeDeviceId.value
		val me = _myDeviceId.value
		val remote = active != null && me != null && active != me
		_isRemoteActive.value = remote && (
			_connected.value || nowMs() - disconnectedAtMs < REMOTE_HOLD_MS
		)
	}

	private var connectJob: Job? = null
	private var wsSession: DefaultClientWebSocketSession? = null

	// Mirrors of the Feishin client's guards:
	private var lastQueueSig = ""        // dedupe for publishing our queue
	// Have we ever put a non-empty queue on the wire? Gates the clear-propagation below, so the empty
	// queue that exists for a moment during startup hydration can't wipe another device's session.
	private var publishedNonEmptyQueue = false
	private var hubDrivenUntilMs = 0L    // player events caused by `do`, not the user
	private var lastReportedIndex = -2
	private var lastReportedPaused: Boolean? = null
	private var lastTickMs = 0L

	/**
	 * The command echo (PROTOCOL §5.2-5.3, rules in `DoEcho.kt`): the `applied` every report
	 * carries, and the directive still waiting to be seen in the player.
	 *
	 * In a flow and not in plain fields like the guards above, because two coroutines move it:
	 * the frame loop ([handleDo], `welcome`) and the reporter. Every change is one
	 * compare-and-set ([moveEcho]).
	 */
	private val doEcho = MutableStateFlow(DoEcho())

	/**
	 * The queue the player was left holding, paused, by a load that failed and was not a
	 * transfer's: a queue the hub's session does not have. While the player still shows
	 * exactly that, the reporter says nothing, or the pause this device has just done itself
	 * would be reported with the OLD queue's index and position (see [afterFailedLoad]).
	 */
	private val heldAfterFailedLoad = MutableStateFlow<HeldQueue?>(null)

	init {
		startRemoteMirror()
		// Queue-building from ANY screen (tap a song, shuffle an album, add to queue) now reaches
		// the active device through here instead of dead-ending in the local player.
		mediaPlayer.remotePlaybackRouter = this
	}

	// --- RemotePlaybackRouter ----------------------------------------------------------------

	override val isRemoteSessionActive: Boolean
		get() = isRemoteActive.value

	override val isHubConnected: Boolean
		get() = _connected.value

	override fun setQueue(
		songs: List<DomainSong>,
		startIndex: Int,
		sourceKind: String,
		sourceName: String?
	) = loadSessionQueue(songs, startIndex, sourceKind = sourceKind, sourceName = sourceName)

	override fun enqueue(songs: List<DomainSong>, playNext: Boolean) =
		enqueueSessionQueue(songs, playNext)

	override fun moveQueueItem(from: Int, to: Int) = actMoveQueueItem(from, to)

	/**
	 * The hub inserts only next or at the end, so the song goes in at the end and is moved into
	 * place: two edits on the one ordered channel, each a `queueChanged` the playing device
	 * applies around the song it is playing. Never `setQueue`, which is a `load`.
	 */
	override fun insertQueueItem(index: Int, song: DomainSong, queueSize: Int) {
		enqueueSessionQueue(listOf(song), playNext = false)
		if (index < queueSize) actMoveQueueItem(queueSize, index)
	}

	override fun skipToNext() = actNext()

	override fun restoreQueue(
		songs: List<DomainSong>,
		index: Int,
		positionMs: Long,
		play: Boolean,
		savedQueueId: String?,
		sourceKind: String,
		sourceName: String?
	) = loadSessionQueue(
		songs, index, positionMs, play,
		savedQueueId = savedQueueId, sourceKind = sourceKind, sourceName = sourceName
	)

	override fun seek(positionMs: Long) = actSeek(positionMs)

	/**
	 * Force the local queue back onto the hub even though its contents are unchanged. Clearing the
	 * dedupe signature is the whole trick: the publish path exists to send one frame per queue change,
	 * which is exactly wrong when what changed is the queue's *identity* (its record was deleted).
	 */
	override fun republishQueue() {
		lastQueueSig = ""
		scope.launch {
			try {
				val state = mediaPlayer.localUiState.value
				if (state.queue.isEmpty()) return@launch
				publishQueueIfOurs(state, positionMs = 0, now = nowMs())
			} catch (e: Exception) {
				Logger.e("HubManager", "queue republish failed", e)
			}
		}
	}

	/**
	 * Tell the hub the queue was emptied here. `publishQueueIfOurs` can't: it returns early on an empty
	 * queue (and on a paused one), so a local clear never left the device and the hub went on serving
	 * a session the user had thrown away. Guarded on having actually published something, so the
	 * momentarily-empty queue during startup can't wipe a session another device is playing.
	 */
	override fun clearSessionQueue() {
		if (!_connected.value || !publishedNonEmptyQueue) return
		publishedNonEmptyQueue = false
		lastQueueSig = ""
		actClearQueue()
	}

	override suspend fun resolveLibrarySongs(songs: List<DomainSong>): List<DomainSong> {
		if (songs.isEmpty()) return songs
		val byId = songDao.getSongsByIds(songs.map { it.id }).associateBy { it.songId }
		// 1:1 and in order — a missing song keeps its placeholder rather than being dropped, or every
		// index after it (including the one we're about to resume at) would shift.
		return songs.map { byId[it.id]?.toDomainModel() ?: it }
	}

	private fun nowMs(): Long = Clock.System.now().toEpochMilliseconds()

	/** Clear the reporter/publish dedupe state (new socket, or we just handed off). */
	private fun resetReporterGuards() {
		lastQueueSig = ""
		lastReportedIndex = -2
		lastReportedPaused = null
		lastTickMs = 0L
		lastClaimPublishAtMs = 0L
	}

	private fun isActiveDevice(): Boolean {
		val me = _myDeviceId.value
		return me != null && me == _activeDeviceId.value
	}

	fun start() = restart()

	fun restart() {
		// Join the previous connect job before starting a new one — cancelling
		// without joining let the old coroutine race the new one over the shared
		// `wsSession` (duplicate/stale sockets). restart() runs on every settings
		// change, so this race was easy to hit.
		val old = connectJob
		_connected.value = false
		authFailed = false
		_connectionError.value = null
		connectJob = scope.launch {
			old?.cancelAndJoin()
			wsSession = null
			if (!preferenceManager.hubEnabled) return@launch
			runLoop()
		}
	}

	fun stop() {
		connectJob?.cancel()
		wsSession = null
		_connected.value = false
		// Recipes live only on the hub; holding a stale list would offer the user a
		// mix whose play, rename and delete would all silently go nowhere.
		_mixes.value = emptyList()
	}

	/**
	 * Something outside says the hub may be reachable again (Android: a default network became
	 * available, `HubNetworkKick`): dial now instead of sitting out the rest of the backoff,
	 * which is up to 30 s of "disconnected" on a phone that is back online.
	 *
	 * A no-op while the link is up, and it never ends an attempt in flight: it only cuts the
	 * wait between two. The loop resets its backoff on a kick and keeps kicked attempts
	 * [KICK_FLOOR_MS] apart. Safe from any thread.
	 */
	fun kickReconnect(reason: String) {
		if (_connected.value) return
		reconnectKick.trySend(reason)
	}

	/** Controller action: hand playback to another device (state preserved). */
	fun transfer(targetDeviceId: String) {
		// Transferring to the device that's already active is a no-op. (The hub guards
		// this too; not sending at all also keeps the picker from flickering.)
		if (targetDeviceId == _activeDeviceId.value) return
		sendAsync(buildJsonObject {
			put("t", "act")
			put("action", "transfer")
			put("target", targetDeviceId)
		})
	}

	/**
	 * Append [songs] to the END of the session queue (plays on the active device).
	 * Used for "add to queue" from a controller while another device is active.
	 */
	fun enqueueSessionQueue(songs: List<DomainSong>, playNext: Boolean = false) {
		if (songs.isEmpty()) return
		sendAsync(buildJsonObject {
			put("t", "act")
			put("action", "enqueue")
			put("at", if (playNext) "next" else "end")
			putJsonArray("tracks") {
				songs.forEach { song -> add(trackJson(song)) }
			}
		})
	}

	/**
	 * Load [songs] as the session queue and play them on the ACTIVE device. Used
	 * when the user starts a generated mix (mood search / radio) from this device
	 * while ANOTHER device is the active receiver: the hub forwards a do:load to
	 * the active device, so the mix plays where playback currently is instead of
	 * starting a conflicting local playback on this device. No-op on empty queue.
	 */
	fun loadSessionQueue(
		songs: List<DomainSong>,
		startIndex: Int = 0,
		positionMs: Long = 0,
		play: Boolean = true,
		savedQueueId: String? = null,
		sourceKind: String = "manual",
		sourceName: String? = null,
		stationId: String? = null
	) {
		if (songs.isEmpty()) return
		// Pre-set the publish signature so our own reporter doesn't re-publish this.
		lastQueueSig = queueSig(songs)
		publishedNonEmptyQueue = true
		sendAsync(buildJsonObject {
			put("t", "act")
			put("action", "setQueue")
			put("index", startIndex.coerceIn(0, songs.lastIndex))
			put("positionMs", positionMs.coerceAtLeast(0))
			put("play", play)
			// Identity comes from the CALLER (the album/playlist/mix being played), not from
			// local player state — that describes the queue we're replacing, so reading it here
			// stamped every remote-routed queue with the previous one's name (usually none).
			// A genuinely new queue mints an id; a restore/undo passes the record's own id.
			put("savedQueueId", savedQueueId ?: mediaPlayer.newQueueSessionId(songs))
			put("sourceKind", sourceKind)
			sourceName?.let { put("sourceName", it) }
			// Binds the new queue to the station that built it (PROTOCOL §18.2). An older hub
			// would drop the key, but it is only sent to a hub that lists the feature.
			if (stationId != null && hubOffers(HUB_FEATURE_STATION)) put("stationId", stationId)
			putJsonArray("tracks") {
				songs.forEach { song -> add(trackJson(song)) }
			}
		})
	}

	// Controller actions for the remote-control UI (drive the ACTIVE device).
	/**
	 * The play button. A hub that answers acts is sent `play` or `pause`, whichever the button
	 * was offering, and not the hub-side toggle: the mirror shows the new state at once, and a
	 * toggle would then be applied to a state the user was not looking at.
	 */
	fun actPlayPause() {
		if (!hubAcksActs || !_connected.value) return sendAct("playpause")
		commandPlaying(!_remoteSession.value.isPlaying)
	}

	fun actPlay() = commandPlaying(true)
	fun actPause() = commandPlaying(false)

	private fun commandPlaying(playing: Boolean) {
		val action = if (playing) "play" else "pause"
		val cid = pinCid() ?: return sendAct(action)
		// Pinned before it is sent: the ack cannot then be here first.
		pushRemoteProgress(_remoteSession.updateAndGet { it.pinPlaying(playing, cid, nowMs()) })
		sendAsync(buildJsonObject {
			put("t", "act")
			put("action", action)
			put("cid", cid)
		})
	}

	fun actNext() = sendAct("next")
	fun actPrevious() = sendAct("previous")

	/**
	 * Seek the active device. Callers send ONE seek per gesture: a slider previews its own drag
	 * and calls this on release.
	 *
	 * The target goes into the mirror at once and is held against the `progress` frames still
	 * on their way from before it (`CommandHold.kt`). The hub's answer is a round trip away,
	 * and until it landed every reader of the mirror showed the playhead back where it had
	 * been: the sliders here, and the lock-screen and notification scrubber through
	 * `RemoteSessionPlayer`.
	 */
	fun actSeek(positionMs: Long) {
		// A hub that answers acts: the target is pinned until this act's ack, and no clock
		// decides (`ActPins.kt`). Otherwise the timed hold.
		val cid = pinCid()
		// Not while the socket is down: the frame is dropped, and the mirror would show a
		// seek the receiver never got.
		if (_connected.value) {
			val now = nowMs()
			val session = _remoteSession.updateAndGet {
				if (cid != null) it.pinPosition(positionMs, cid, now) else it.afterSeekSent(positionMs, now)
			}
			// Straight into the player state as well, not only through the mirror's collector:
			// the slider that was just let go reads the playhead on its very next frame.
			pushRemoteProgress(session)
		}
		sendAsync(buildJsonObject {
			put("t", "act")
			put("action", "seek")
			put("positionMs", positionMs)
			cid?.let { put("cid", it) }
		})
	}

	fun actJump(index: Int) {
		sendAsync(buildJsonObject {
			put("t", "act")
			put("action", "jump")
			put("index", index)
		})
	}

	/**
	 * Set a device's volume (0..100): the ACTIVE device's, or with [targetDeviceId] that of a
	 * device that is not playing (`act:volume` with `target`, PROTOCOL §5.1), so that a
	 * speaker can be turned down before music is sent to it.
	 *
	 * True when the level was pinned: it then shows for that device at once
	 * ([pinnedVolume]) and until the hub's ack, which for a refusal (`target_offline`,
	 * `target_unreachable`, `not_supported`) lets the device list's old level show again.
	 */
	fun actSetVolume(level: Int, targetDeviceId: String? = null): Boolean {
		val wanted = level.coerceIn(0, 100)
		val active = _activeDeviceId.value
		// The playing device is the default, and naming it changes nothing.
		val target = targetDeviceId?.takeIf { it != active }
		// A hub that does not read `target` would set the ACTIVE device's volume instead.
		if (target != null && !hubOffers(HUB_FEATURE_VOLUME_TARGET)) return false
		// With no device playing there is nothing to pin an untargeted level on.
		val pinnedOn = target ?: active
		val cid = if (pinnedOn != null) pinCid() else null
		// Pinned before it is sent: the ack cannot then be here first.
		if (cid != null && pinnedOn != null) {
			_volumePins.update { it.pinned(pinnedOn, wanted, cid, nowMs()) }
		}
		sendAsync(buildJsonObject {
			put("t", "act")
			put("action", "volume")
			put("level", wanted)
			target?.let { put("target", it) }
			cid?.let { put("cid", it) }
		})
		return cid != null
	}

	/**
	 * The level this device commanded for [deviceId] and the hub has not answered for yet,
	 * else null. Without an id: for the playing device.
	 */
	fun pinnedVolume(deviceId: String? = _activeDeviceId.value): Int? =
		_volumePins.value.levelFor(deviceId, nowMs())

	private val _hardwareVolume = MutableSharedFlow<Int>(
		extraBufferCapacity = 1,
		onBufferOverflow = BufferOverflow.DROP_OLDEST
	)

	/**
	 * A level the phone's volume buttons have just commanded for the playing device: the cue
	 * for the app's own volume bar (`RemoteVolumeHud`, `VolumeKeys.kt`). A level set from a
	 * slider in the app is not announced here.
	 */
	val hardwareVolume: SharedFlow<Int> = _hardwareVolume.asSharedFlow()

	fun noteHardwareVolume(level: Int) {
		_hardwareVolume.tryEmit(level.coerceIn(0, 100))
	}

	/**
	 * Steps the playing device's volume one press up (+1) or down (-1). Set by the remote
	 * player facade, which owns the stepping and the level it steps from; null where there is
	 * none.
	 */
	@Volatile
	var remoteVolumePress: ((direction: Int) -> Unit)? = null

	/**
	 * A volume button pressed in the app's own window while another device plays. True when the
	 * press was taken for that device; false leaves it to the system.
	 */
	fun pressRemoteVolume(up: Boolean): Boolean {
		if (!_isRemoteActive.value) return false
		val press = remoteVolumePress ?: return false
		press(if (up) 1 else -1)
		return true
	}

	/** Reorder the session queue on the active device (move [from] → [to]). */
	fun actMoveQueueItem(from: Int, to: Int) {
		if (from == to) return
		sendAsync(buildJsonObject {
			put("t", "act")
			put("action", "move")
			put("from", from)
			put("to", to)
		})
	}

	/** Drop one item from the session queue on the active device. */
	fun actRemoveQueueItem(index: Int) {
		sendAsync(buildJsonObject {
			put("t", "act")
			put("action", "remove")
			put("index", index)
		})
	}

	/** Empty the session queue on the active device (also stops playback). */
	fun actClearQueue() {
		sendAsync(buildJsonObject {
			put("t", "act")
			put("action", "clear")
		})
	}

	fun actToggleShuffle() {
		val on = !_remoteSession.value.shuffle
		sendAsync(buildJsonObject {
			put("t", "act")
			put("action", "shuffle")
			put("on", on)
		})
	}

	fun actToggleRepeat() {
		val next = when (_remoteSession.value.repeat) {
			"none" -> "all"
			"all" -> "one"
			else -> "none"
		}
		sendAsync(buildJsonObject {
			put("t", "act")
			put("action", "repeat")
			put("mode", next)
		})
	}

	private fun sendAct(action: String) {
		sendAsync(buildJsonObject {
			put("t", "act")
			put("action", action)
		})
	}

	// --- Acts the hub answers (PROTOCOL §5.1 and §5.4; the pin rules are in ActPins.kt) ----------

	/**
	 * `welcome.features` listed `actAck`: every act carries a `cid` and the hub answers it with
	 * an `ack`, written after the frames the act caused. False from the moment a socket opens
	 * until its `welcome`, and for a hub that does not say so, which is then sent what it
	 * always was.
	 */
	@Volatile
	private var hubAcksActs = false

	// A StateFlow for its compare-and-set: acts are sent from the UI thread and the player's.
	private val cidCounter = MutableStateFlow(0L)

	private fun nextCid(): String = "n${cidCounter.updateAndGet { it + 1 }}"

	/**
	 * The `cid` for an act whose value is about to be pinned, or null when there is nothing to
	 * pin by: a hub that answers no acts, or no link, where the frame is dropped.
	 */
	private fun pinCid(): String? = if (hubAcksActs && _connected.value) nextCid() else null

	/** An act on its way to a hub that answers acts gets a `cid`, unless its sender pinned by one. */
	private fun withCid(obj: JsonObject): JsonObject {
		if (!hubAcksActs || "cid" in obj) return obj
		if (obj["t"]?.jsonPrimitive?.contentOrNullSafe() != "act") return obj
		return JsonObject(obj + ("cid" to JsonPrimitive(nextCid())))
	}

	private val _volumePins = MutableStateFlow(VolumePins())

	/**
	 * The volumes this device commanded, by device, until the hub answers for each
	 * ([pinnedVolume]). Collected for its changes; the level to show is asked of [pinnedVolume].
	 */
	val volumePins: StateFlow<VolumePins> = _volumePins.asStateFlow()

	/** The hub answered act [cid]: what it pinned gives way to what the hub said. */
	private fun releasePins(cid: String) {
		_remoteSession.update { it.afterAck(cid) }
		_volumePins.update { it.afterAck(cid) }
	}

	/** Pins whose ack did not come in [ACT_PIN_MS]. Run from the mirror's tick. */
	private fun expirePins() {
		val now = nowMs()
		if (!_remoteSession.value.pins.none) _remoteSession.update { it.afterPinExpiry(now) }
		_volumePins.update { it.afterExpiry(now) }
	}

	// --- The session's sleep timer (PROTOCOL §5.1; the rules are in SessionSleepTimer.kt) --------

	/**
	 * The link is up to a hub that holds the session's sleep timer: the timed option is then
	 * an act, and no timer is armed on this device.
	 */
	val holdsSleepTimers: Boolean get() = hubOffers(HUB_FEATURE_SLEEP_TIMER)

	/** Set the session's sleep timer to [minutes] from now, replacing one that runs; 0 turns it off. */
	fun actSleepTimer(minutes: Int) {
		sendAsync(buildJsonObject {
			put("t", "act")
			put("action", "sleepTimer")
			put("minutes", minutes.coerceIn(0, SLEEP_TIMER_MAX_MINUTES))
		})
	}

	/**
	 * Turn the session's sleep timer off, if this device knows of one. Through the hub when
	 * the link is up, and its `session` frame then clears the mirror. With the link down the
	 * act cannot be sent, so only this device's own guard is disarmed: a hub that still holds
	 * the timer says so again at the next `welcome`.
	 */
	fun cancelSleepTimer() {
		if (_remoteSession.value.sleepTimer == null) return
		if (holdsSleepTimers) actSleepTimer(0)
		else _remoteSession.update { it.copy(sleepTimer = null) }
	}

	/**
	 * The playing device's own end of the session's sleep timer (`sleepGuard`): what stops the
	 * music when the hub's pause cannot reach this device. A timer the mirror still holds
	 * well past its end is forgotten here, once, and if this device is the one playing it
	 * pauses itself. A frame that replaced the timer in the meantime keeps its own.
	 */
	private suspend fun guardSleepTimer() {
		val timer = _remoteSession.value.sleepTimer ?: return
		val local = mediaPlayer.localUiState.value
		val verdict = sleepGuard(
			timer = timer,
			isActiveDevice = isActiveDevice(),
			isPlaying = !local.isPaused && local.queue.isNotEmpty(),
			nowMs = nowMs()
		)
		if (verdict == SleepGuard.WAIT) return
		var forgotten = false
		_remoteSession.update {
			forgotten = it.sleepTimer == timer
			if (forgotten) it.copy(sleepTimer = null) else it
		}
		if (!forgotten || verdict != SleepGuard.PAUSE) return
		Logger.i("HubManager", "sleep timer ran out and the hub's pause did not come: pausing here")
		mediaPlayer.awaitRemotePause()
	}

	fun currentTimeMs(): Long = nowMs()

	// ------------------------------------------------------------------ //
	// Remote-session mirror: while another device is active, push a resolved
	// snapshot of the hub session into the local player VM as a display override
	// so the entire player UI (mini player, now-playing, queue) reflects what's
	// actually playing — no per-component branching for DISPLAY (transport is
	// routed separately by the UI when isRemoteActive).
	// ------------------------------------------------------------------ //

	// Written by the mirror's two coroutines, read by actSeek on the caller's thread.
	@Volatile
	private var remoteUiBase: PlayerUiState? = null
	private var remoteSig = ""

	private fun repeatToInt(mode: String): Int = when (mode) {
		"one" -> 1
		"all" -> 2
		else -> 0
	}

	private fun startRemoteMirror() {
		scope.launch {
			combine(
				_connected, _activeDeviceId, _myDeviceId, _remoteSession
			) { _, _, _, session -> session }.collect { session ->
				evaluateRemoteActive()
				refreshMirror(session)
			}
		}
		// Ticks progress between the hub's ~1 Hz frames so the slider moves smoothly, and gives
		// the hold window above something to expire against while the session sits idle.
		scope.launch {
			while (currentCoroutineContext().isActive) {
				delay(250)
				expirePins()
				try {
					guardSleepTimer()
				} catch (e: CancellationException) {
					throw e
				} catch (e: Exception) {
					Logger.e("HubManager", "sleep timer guard failed", e)
				}
				evaluateRemoteActive()
				refreshMirror(_remoteSession.value)
			}
		}
	}

	/**
	 * Rebuilds the resolved queue only when the track set / index changes; otherwise just refreshes
	 * live progress (cheap). Re-pushing an unchanged snapshot is free — [PlayerUiState] is a data
	 * class, so the StateFlow dedupes it and nothing recomposes.
	 */
	private suspend fun refreshMirror(session: RemoteSessionState) {
		if (!_isRemoteActive.value) {
			remoteUiBase = null
			remoteSig = ""
			mediaPlayer.setRemoteState(null)
			return
		}
		val ids = session.tracks.map { it.id }
		val sig = ids.joinToString(",") + "|" + session.index
		if (sig != remoteSig) {
			remoteSig = sig
			// Resolve 1:1 with the session (placeholders for songs not in the
			// local DB) so the mirrored queue length + order MATCH the hub's —
			// otherwise dropping un-synced songs shifts the index, so the shown
			// "current song" is wrong and a jump sends the wrong index (which
			// made Feishin restart the queue).
			val resolved = resolveRemoteTracks(session.tracks)
			val idx = session.index.coerceIn(0, (resolved.size - 1).coerceAtLeast(0))
			remoteUiBase = PlayerUiState(
				queue = resolved,
				currentSong = resolved.getOrNull(idx),
				currentIndex = idx,
				isShuffleEnabled = session.shuffle,
				repeatMode = repeatToInt(session.repeat)
			)
		}
		pushRemoteProgress(session)
	}

	private fun pushRemoteProgress(session: RemoteSessionState) {
		val base = remoteUiBase ?: return
		val durationMs = session.nowPlaying?.durationMs ?: 0L
		val elapsed =
			if (session.isPlaying) (nowMs() - session.positionAtMs).coerceAtLeast(0) else 0L
		val positionMs = session.positionMs + elapsed
		val progress =
			if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
		// Shuffle and repeat are read from every frame, not baked into the base: the base is rebuilt
		// only when the tracks or the index change, so a shuffle or repeat tap on a remote session
		// showed nothing until the next song (seen on the phone, 2026-10-19).
		mediaPlayer.setRemoteState(
			base.copy(
				isPaused = !session.isPlaying,
				progress = progress,
				isShuffleEnabled = session.shuffle,
				repeatMode = repeatToInt(session.repeat)
			)
		)
	}

	// ------------------------------------------------------------------ //
	// Connection
	// ------------------------------------------------------------------ //

	private suspend fun runLoop() {
		val backoff = HubReconnectBackoff(INITIAL_BACKOFF_MS, MAX_BACKOFF_MS, STABLE_LINK_MS)
		var lastKickedAtMs = 0L
		// A kick left from before this loop has no wait to cut: the first dial is immediate.
		reconnectKick.tryReceive()
		while (currentCoroutineContext().isActive) {
			try {
				connectOnce()
			} catch (e: Exception) {
				Logger.e("HubManager", "hub connection failed: ${e.message}", e)
			}
			val wasConnected = _connected.value
			wsSession = null
			if (wasConnected) {
				disconnectedAtMs = nowMs()
				// A kick that came in while this link was still dialling asked for the link
				// it then got. Dropped while `connected` still reads true, so a kick for THIS
				// drop cannot be the one thrown away.
				reconnectKick.tryReceive()
			}
			// The backoff starts over only for a link that held (Q-048): not on `welcome`.
			backoff.onLinkEnded(welcomedAtMs.takeIf { wasConnected }, nowMs())
			_connected.value = false
			evaluateRemoteActive()
			// The hub rejected our token — retrying with the same bad token would
			// tight-loop forever and never surface the error. Stop; a settings
			// change (which calls restart()) clears the flag and tries again.
			if (authFailed) {
				Logger.e("HubManager", "hub auth rejected — stopping reconnect until settings change")
				return
			}
			val waitMs = backoff.next()
			val waitFromMs = nowMs()
			val kick = withTimeoutOrNull(waitMs) { reconnectKick.receive() }
			if (kick != null) {
				backoff.reset()
				val now = nowMs()
				delay(kickedWaitMs(now - lastKickedAtMs, waitMs - (now - waitFromMs), KICK_FLOOR_MS))
				lastKickedAtMs = nowMs()
				Logger.i("HubManager", "hub reconnect kicked: $kick")
			}
		}
	}

	private suspend fun connectOnce() {
		val url = preferenceManager.hubUrl.trim()
		if (url.isEmpty()) {
			delay(10_000)
			return
		}
		client.webSocket(url) {
			wsSession = this
			// One writer, owning THIS session. Every frame below is enqueued, never written
			// directly, so nothing built for this connection can end up on its successor.
			val out = Channel<JsonObject>(Channel.UNLIMITED)
			outbox = out
			// `welcome` is the echo's reset point, and it is reset there. Here as well,
			// because the reporter starts before `welcome` is read: a report leaving in
			// between must not carry the previous connection's number onto this one.
			resetEcho()
			// What the last hub answered says nothing about this one, until its `welcome`.
			hubAcksActs = false
			_hubFeatures.value = emptySet()
			val writer = launch {
				try {
					for (frame in out) send(Frame.Text(frame.toString()))
				} catch (e: CancellationException) {
					throw e
				} catch (e: Exception) {
					Logger.e("HubManager", "hub writer failed", e)
				}
			}
			sendFrame(buildJsonObject {
				put("t", "hello")
				put("token", preferenceManager.hubToken)
				putJsonObject("device") {
					val savedId = preferenceManager.hubDeviceId
					if (savedId.isNotEmpty()) put("id", savedId) else put("id", JsonNull)
					put("name", preferenceManager.hubDeviceName.ifBlank { "Navic" })
					put("platform", "android")
					// The icon every picker draws for this device (PROTOCOL §3.1).
					put("kind", localDevice.kind)
					putJsonArray("caps") {
						add("receiver")
						add("controller")
						// PROTOCOL §7.1: we answer every transfer's do:load with a `loaded` frame,
						// so a transfer here that can't start hands the session back instead of
						// reading as playing on every device.
						add("loadAck")
						// PROTOCOL §7.2: that answer echoes the directive's transferId, so a
						// `loaded` we took ten seconds to earn cannot satisfy the NEXT transfer
						// to this device — which is how a stale ack used to pass for a fresh one.
						add("transferAckV2")
						// PROTOCOL §5.2-5.3: every report echoes the number of the last
						// directive carried out, and keeps the five duties that makes a
						// promise of (`DoEcho.kt`, [handleDo], [reporterLoop]).
						add("cmdEcho")
						// PROTOCOL §5.1: a `do:setVolume` is applied whenever it arrives, also
						// while another device plays, so this device's row can be turned down
						// before music is sent to it. Only where the system's volume can be set.
						if (localDevice.setsMediaVolume) add(CAP_IDLE_VOLUME)
					}
				}
			})

			val reporter = launch { reporterLoop() }
			val volumeReporter = launch { volumeReportLoop() }
			// Keepalive. A paused remote session is completely silent in BOTH directions (we only
			// report while we're the active device, and the hub only fans out progress off those
			// reports), so without this the socket idles out and reconnects — which is what made
			// the player blink back to the local state mid-pause.
			val pinger = launch { pingLoop() }
			try {
				for (frame in incoming) {
					if (frame is Frame.Text) {
						try {
							handleFrame(Json.parseToJsonElement(frame.readText()).jsonObject)
						} catch (e: Exception) {
							Logger.e("HubManager", "bad hub frame", e)
						}
					}
				}
			} finally {
				// Retire this connection's outbox before anything else can enqueue into it. A frame
				// offered after this is dropped with a log line rather than delivered to the next
				// socket, which is the whole point of the per-connection writer.
				if (outbox === out) outbox = null
				out.close()
				writer.cancel()
				reporter.cancel()
				volumeReporter.cancel()
				pinger.cancel()
			}
		}
	}

	/** The hub answers `ping` with `pong` and refreshes our last-seen. */
	private suspend fun pingLoop() {
		while (currentCoroutineContext().isActive) {
			delay(PING_INTERVAL_MS)
			try {
				sendFrame(buildJsonObject { put("t", "ping") })
			} catch (e: Exception) {
				Logger.e("HubManager", "hub ping failed", e)
				return
			}
		}
	}

	/**
	 * Outbound queue for the CURRENT connection, drained by a writer that owns that one session.
	 *
	 * Two problems this replaces. Frames used to resolve the mutable `wsSession` at the moment the
	 * coroutine happened to run, so a frame built under socket A could be written to socket B after
	 * a reconnect — replaying stale queue mutations into a new hub session. And ordering was only
	 * guaranteed for `sendAsync`: hello, ping, report, released and the saved-queue sync all called
	 * `sendFrame` directly and went round the mutex, so the "fire-and-forget sends arrive in order"
	 * comment was true of about half of them.
	 *
	 * Enqueueing is synchronous, so call order IS wire order. A channel belonging to a connection
	 * that has gone is closed, and frames offered to it are dropped and logged rather than
	 * delivered to its successor.
	 */
	@Volatile
	private var outbox: Channel<JsonObject>? = null

	private fun enqueue(obj: JsonObject) {
		val out = outbox
		val t = obj["t"]?.jsonPrimitive?.contentOrNullSafe() ?: "?"
		if (out == null) {
			// Reports are excluded: they fire at 1 Hz, and a disconnect would bury the log.
			if (t != "report") Logger.w("HubManager", "dropped '$t' — no hub connection")
			return
		}
		val result = out.trySend(obj)
		if (result.isFailure && t != "report") {
			Logger.w("HubManager", "dropped '$t' — outbound channel closed")
		}
	}

	private suspend fun sendFrame(obj: JsonObject) = enqueue(withCid(obj))

	private fun sendAsync(obj: JsonObject) = enqueue(withCid(obj))

	// ------------------------------------------------------------------ //
	// "Mixed for You" — hub-owned recipes, regenerated locally.
	// ------------------------------------------------------------------ //

	private val _mixes = MutableStateFlow<List<Mix>>(emptyList())

	/**
	 * Every stored recipe, newest-updated first, exactly as the hub holds them.
	 *
	 * Read-only and hub-authoritative: unlike saved queues there is no local table,
	 * no offline accumulation and **no tombstones**. A saved queue can be published
	 * concurrently by several devices, which is why that one union-merges; a mix is
	 * created and edited from one screen at a time, so last-write-wins is the whole
	 * conflict story. The asymmetry is deliberate — it is written down here rather
	 * than left to be rediscovered from the absence of the merge code.
	 *
	 * Empty while disconnected, which is the correct answer: a recipe you cannot
	 * play (the hub is also where it lives) should not be offered.
	 */
	val mixes: StateFlow<List<Mix>> = _mixes.asStateFlow()

	private val _mixErrors = MutableSharedFlow<String>(extraBufferCapacity = 4)

	/**
	 * The hub refused a mix write, in its own words.
	 *
	 * A [SharedFlow] rather than state: this is an event about an action the user
	 * just took, and a `StateFlow` would replay the last refusal to the next screen
	 * that happened to collect it.
	 */
	val mixErrors: SharedFlow<String> = _mixErrors.asSharedFlow()

	/**
	 * Create a mix, or update one in place when [id] names an existing recipe.
	 *
	 * Minting is the hub's job — it owns the `mx_` id space — so a create simply
	 * omits the field, exactly as `saveMix` specifies.
	 *
	 * [coverArtId] is the seed's artwork, stamped once at save time. The hub has
	 * always stored the field (`_sanitize_mix` copies it through, and it is in the
	 * record `PROTOCOL.md` §17.1 specifies) — nothing here ever wrote it, which is
	 * why every mix rendered as bare text. It is deliberately the SEED's cover and
	 * not the last regenerated queue's: the seed is a fixed part of the recipe, so
	 * it stays true on a second play, whereas the tracklist does not.
	 */
	fun actSaveMix(
		name: String,
		kind: String,
		id: String? = null,
		seedId: String = "",
		seedName: String = "",
		moodCharacter: String = "",
		count: Int = 50,
		coverArtId: String = "",
		character: String = "",
		seedIds: List<String> = emptyList(),
		variety: String = "",
		moodFilter: String = ""
	) {
		sendAsync(
			saveMixAct(
				name, kind, id, seedId, seedName, moodCharacter, count, coverArtId, character,
				seedIds, variety, moodFilter
			)
		)
	}

	fun actRenameMix(id: String, name: String) {
		sendAsync(buildJsonObject {
			put("t", "act"); put("action", "renameMix"); put("id", id); put("name", name)
		})
	}

	fun actDeleteMix(id: String) {
		sendAsync(buildJsonObject {
			put("t", "act"); put("action", "deleteMix"); put("id", id)
		})
	}

	/**
	 * Bump `lastPlayedAt` and nothing else.
	 *
	 * Its own action rather than a `saveMix` with the same fields, because a save
	 * also moves `updatedAt` — which is the cap's eviction key. Playing a mix would
	 * otherwise protect it from eviction as strongly as editing it, and thirty plays
	 * of one mix would quietly evict twenty-nine recipes nobody touched.
	 */
	fun actTouchMix(id: String) {
		sendAsync(buildJsonObject {
			put("t", "act"); put("action", "touchMix"); put("id", id)
		})
	}

	/**
	 * Adopt the hub's authoritative recipe list.
	 *
	 * A record whose `kind` this build does not know is kept, not dropped: it came
	 * from a newer client, and discarding it here would make this device's next
	 * broadcast look like a deletion to everyone else. The list screen decides what
	 * to do with one it cannot regenerate.
	 */
	private fun applyMixes(records: List<JsonObject>) {
		_mixes.value = records.mapNotNull { mixFromRecord(it) }
	}

	// ------------------------------------------------------------------ //
	// Saved-queue history (Continue Listening) — hub-owned + shared.
	// ------------------------------------------------------------------ //

	/** Rename a shared saved-queue record (propagates to every client). */
	fun actRenameSavedQueue(id: String, name: String?) {
		sendAsync(buildJsonObject {
			put("t", "act"); put("action", "renameSavedQueue"); put("id", id)
			name?.let { put("name", it) }
		})
	}

	/** Delete a shared saved-queue record (propagates to every client). */
	fun actDeleteSavedQueue(id: String) {
		sendAsync(buildJsonObject {
			put("t", "act"); put("action", "deleteSavedQueue"); put("id", id)
		})
	}

	/**
	 * Delete several records in one act. Clear-all and delete-others used to send one frame per row,
	 * and the hub answers each with a full history broadcast plus a state write — twenty deletions
	 * meant twenty of both, all of which every client then re-applied to its local table.
	 */
	fun actDeleteSavedQueues(ids: List<String>) {
		if (ids.isEmpty()) return
		sendAsync(buildJsonObject {
			put("t", "act"); put("action", "deleteSavedQueues")
			putJsonArray("ids") { ids.forEach { add(it) } }
		})
	}

	/**
	 * One queue track as the hub's wire object.
	 *
	 * Four call sites publish tracks — enqueue, load-on-the-active-device, the
	 * play-routing path and the ordinary publish — and they built this object four
	 * times identically until a preview needed one field to differ. The `streamUrl`
	 * rule below is exactly the sort that gets applied to three of four copies.
	 *
	 * `streamUrl` and `mime` are what let a URL-based receiver (the Chromecast
	 * bridge) play without speaking Subsonic. For a preview they are the ONLY way it
	 * can play at all: an `ext:` track is not in anyone's library, so a Subsonic
	 * stream URL built from its id addresses a song this server has never heard of.
	 * Its own signed URL rides in `filePath` (see [PreviewManager]).
	 */
	private fun trackJson(song: DomainSong): JsonObject = buildJsonObject {
		put("id", song.id)
		put("title", song.title)
		put("artist", song.artistName)
		song.albumTitle?.let { put("album", it) }
		put("durationMs", song.duration.inWholeMilliseconds)
		song.coverArtId?.let { put("imageUrl", sessionManager.getCoverArtUrl(it)) }
		put(
			"streamUrl",
			if (PreviewManager.isPreviewId(song.id)) song.filePath.orEmpty()
			else sessionManager.api.getStreamUrl(song.id)
		)
		put("mime", song.mimeType)
	}

	private fun intToRepeat(mode: Int): String = when (mode) {
		1 -> "one"; 2 -> "all"; else -> "none"
	}

	/**
	 * What to call this queue in the shared history. [PlayerUiState.savedQueueName] is stamped by
	 * whoever replaced the queue and is therefore always right; `currentCollection` is the older,
	 * weaker guess (it resolves asynchronously from the playing song, and is null for every generated
	 * mix) and stays only as a fallback for queues built before the stamp existed.
	 */
	private fun savedQueueNameFor(state: PlayerUiState): String? =
		state.savedQueueName ?: state.currentCollection?.name

	// NOTE: we deliberately do NOT publish `coverImageUrl`. Card art is now derived by each client
	// from the record's resume track, rendered by id with that client's own credentials — a URL
	// carrying OUR server address and OUR auth token is not something the other client can display.

	/** B-046: point the live session's saved-queue card at the track actually playing. */
	private suspend fun followSavedQueueCursor() {
		val s = _remoteSession.value
		val id = s.savedQueueId ?: return
		val expect = s.tracks.getOrNull(s.index)?.id ?: return
		try {
			savedQueueRepository.followCursor(id, s.index, expect)
		} catch (e: Exception) {
			Logger.w("HubManager", "saved-queue cursor follow failed", e)
		}
	}

	/**
	 * Adopt the hub's authoritative saved-queue history into the local Room store (a REPLACE — that's
	 * what lets a delete on one client propagate here). Hub tracks are minimal, so they're resolved
	 * 1:1 to DomainSong placeholders, same as the live mirror.
	 */
	private suspend fun applySavedQueues(records: List<JsonObject>) {
		// One library lookup for the WHOLE broadcast. Resolving per record meant twenty queries every
		// time any client edited any queue, since the hub rebroadcasts the entire list each time.
		val allTracks = records.flatMap { it["songs"]?.asObjectList() ?: emptyList() }
		val byId = songDao
			.getSongsByIds(allTracks.mapNotNull { it["id"]?.jsonPrimitive?.content }.distinct())
			.associateBy { it.songId }
		val out = records.mapNotNull { rec ->
			val id = rec["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
			// A broadcast built before our sync-up frame landed still carries rows we deleted while
			// offline; adopting them would put the deleted card back for a render.
			if (savedQueueRepository.isDeleted(id)) return@mapNotNull null
			val tracks = rec["songs"]?.asObjectList() ?: emptyList()
			if (tracks.isEmpty()) return@mapNotNull null
			val songs = tracks.mapNotNull { track ->
				val songId = track["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
				byId[songId]?.toDomainModel() ?: remoteTrackToDomainSong(track)
			}
			if (songs.isEmpty()) return@mapNotNull null
			val idx = (rec["currentIndex"]?.jsonPrimitive?.intOrNull ?: 0)
				.coerceIn(0, (songs.size - 1).coerceAtLeast(0))
			SavedQueueRepository.RemoteSavedQueue(
				id = id,
				name = rec["name"]?.jsonPrimitive?.contentOrNullSafe(),
				sourceName = rec["sourceName"]?.jsonPrimitive?.contentOrNullSafe(),
				songs = songs,
				currentIndex = idx,
				currentSongId = songs.getOrNull(idx)?.id,
				currentSongName = songs.getOrNull(idx)?.title,
				// Art is the RESUME track's (B-046, PROTOCOL.md §8.3), derived from the record's own
				// cursor, as Feishin does — so one shared record still looks the same on each client.
				// The record's own `coverImageUrl` stays ignored: it's the other client's authed URL
				// against its own server, which is exactly why those covers wouldn't load.
				coverArtId = songs.getOrNull(idx)?.coverArtId ?: songs.firstOrNull()?.coverArtId,
				positionMs = rec["positionMs"]?.jsonPrimitive?.longOrNull ?: 0L,
				shuffle = rec["shuffle"]?.jsonPrimitive?.booleanOrNull ?: false,
				repeatMode = repeatToInt(rec["repeat"]?.jsonPrimitive?.contentOrNullSafe() ?: "none"),
				sourceKind = rec["sourceKind"]?.jsonPrimitive?.contentOrNullSafe() ?: "manual",
				createdAt = rec["createdAt"]?.jsonPrimitive?.longOrNull ?: nowMs(),
				updatedAt = rec["updatedAt"]?.jsonPrimitive?.longOrNull ?: nowMs(),
				songCount = rec["songCount"]?.jsonPrimitive?.intOrNull ?: songs.size
			)
		}
		savedQueueRepository.replaceFromHub(out)
	}

	/**
	 * Push our local (possibly offline-accumulated) history up so it survives + propagates — including
	 * deletions, which the hub tombstones. Without those a delete made while offline was silently
	 * undone: the row went from Room, then this sync pushed the survivors up and the hub's reply
	 * (which still had the deleted record) put it straight back.
	 */
	private suspend fun syncLocalSavedQueuesUp() {
		// Decoded up front so a row whose blob is unreadable is dropped here (decodeQueue logs it)
		// rather than uploaded with an empty `songs`, which the hub silently rejects — the record
		// would then look synced while never actually reaching the shared history.
		val rows = savedQueueRepository.allForSync()
			.map { it to savedQueueRepository.decodeQueue(it) }
			.filter { (_, songs) -> songs.isNotEmpty() }
		val deleted = savedQueueRepository.pendingTombstoneIds()
		if (rows.isEmpty() && deleted.isEmpty()) return
		sendFrame(buildJsonObject {
			put("t", "act"); put("action", "syncSavedQueues")
			if (deleted.isNotEmpty()) {
				putJsonArray("deleted") { deleted.forEach { add(it) } }
			}
			putJsonArray("queues") {
				rows.forEach { (row, songs) ->
					addJsonObject {
						put("id", row.id)
						row.name?.let { put("name", it) }
						row.sourceName?.let { put("sourceName", it) }
						put("sourceKind", row.sourceKind)
						put("currentIndex", row.currentIndex)
						put("positionMs", row.positionMs)
						put("shuffle", row.shuffle)
						put("repeat", intToRepeat(row.repeatMode))
						put("songCount", row.songCount)
						put("createdAt", row.createdAt)
						put("updatedAt", row.updatedAt)
						// The full track object, not the five fields this used to send:
						// `streamUrl` and `mime` are the only way a preview survives a
						// round trip through the shared history, and the hub's
						// SQ_TRACK_FIELDS whitelist already carries both.
						putJsonArray("songs") {
							songs.forEach { song -> add(trackJson(song)) }
						}
					}
				}
			}
		})
	}

	/**
	 * The saved-queue id to publish for [state]'s queue: reuse the local session id when present,
	 * else the hub's current record id when it's substantially the same queue (adopt/transfer-in),
	 * else the id of a record we already hold for this queue, else a fresh one.
	 *
	 * The hub-adopt test is deliberately **set overlap**, not the ordered prefix it used to be: a
	 * reorder, a removal, a play-next or a shuffle all fail a prefix test, so every one of them forked
	 * a brand-new record for a queue the user never stopped playing. Half the queue in common is
	 * enough — the same threshold Feishin uses.
	 */
	private fun hubSavedQueueIdFor(state: PlayerUiState): String {
		state.savedQueueId?.let { return it }
		val ids = state.queue.map { it.id }
		val hub = _remoteSession.value
		val hubIds = hub.tracks.map { it.id }.toSet()
		val hubId = hub.savedQueueId
		if (hubId != null && hubIds.isNotEmpty() && ids.isNotEmpty()) {
			val shared = ids.toSet().count { it in hubIds }
			if (shared.toDouble() / hubIds.size >= HUB_QUEUE_ADOPT_OVERLAP) return hubId
		}
		return mediaPlayer.newQueueSessionId(state.queue)
	}

	// ------------------------------------------------------------------ //
	// Inbound frames
	// ------------------------------------------------------------------ //

	/**
	 * `welcome.lb`, or an `lb` frame: `{available: bool, routes: [...]}`, the same answer
	 * `/lb/status` gives. Returns the `available` it applied, or null when the advert carried no
	 * usable one — which touches nothing.
	 */
	private fun applyLbAdvert(lb: JsonObject): Boolean? {
		val available = lb["available"]?.jsonPrimitive?.booleanOrNull ?: return null
		val routes = (lb["routes"] as? JsonArray)
			?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
			?: emptyList()
		val webUrl = (lb["webUrl"] as? JsonPrimitive)?.contentOrNull.orEmpty()
		lbBotManager.applyHubAdvert(available, routes, webUrl)
		return available
	}

	private suspend fun handleFrame(msg: JsonObject) {
		val frameType = msg["t"]?.jsonPrimitive?.content
		// `GET /lb/overrides` — the override map and the album-override, merge and sibling lists
		// that ride it — is read again on the frames that can have changed it
		// (`frameRereadsOverrideLists`: `index` and `library`). A few seconds out and once per
		// burst, since a bulk build sends a frame every couple of seconds and each read is an
		// upstream one; the hub flushes its own copy of the body on both frames.
		if (frameRereadsOverrideLists(frameType)) lbBotManager.requestArtistOverridesRefreshSoon()
		when (frameType) {
			"welcome" -> {
				// PROTOCOL §5.2: the one point where both sides forget the directive numbers.
				// The hub writes no `do` before this frame, so nothing of this connection is
				// lost, and nothing of the last one survives.
				resetEcho()
				val id = msg["deviceId"]?.jsonPrimitive?.content
				if (id != null) {
					_myDeviceId.value = id
					preferenceManager.hubDeviceId = id
				}
				// Before anything below sends an act. A `welcome` also ends every pin: the acts
				// they wait on went to a socket that is gone.
				val features = (msg["features"] as? JsonArray)
					?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
					?.toSet()
					.orEmpty()
				_hubFeatures.value = features
				hubAcksActs = "actAck" in features
				_volumePins.value = VolumePins()
				msg["session"]?.let { applySession(it.jsonObject, keepPins = false) }
				// The session's station, with its name and seed, so a device joining mid-station
				// knows `after` without a round trip (PROTOCOL §18.2). Null or absent: none.
				try {
					(msg["station"] as? JsonObject)?.let { applyStationFrame(it) }
				} catch (e: Exception) {
					Logger.e("HubManager", "welcome.station ignored", e)
				}
				msg["devices"]?.let { parseDevices(it.asObjectList()) }
				welcomedAtMs = nowMs()
				_connected.value = true
				_connectionError.value = null
				// Settle window. applySession has just set _activeDeviceId, but
				// _isRemoteActive is recomputed asynchronously (evaluateRemoteActive), so
				// for a moment the reporter can see connected + active-is-someone-else +
				// a STALE isRemoteActive == false — and routeLocalPlayIfRemote would then
				// republish our queue and pause us. Borrow the hub-driven exemption it
				// already honours rather than inventing a second flag.
				hubDrivenUntilMs = nowMs() + 2000
				// A new socket means the reporter's dedupe state describes a session
				// that no longer exists — clear it so the first tick after reconnect
				// actually reports instead of being swallowed as "unchanged".
				resetReporterGuards()
				// Reconcile saved-queue history: push OUR local (possibly offline)
				// rows up first, then adopt the hub's authoritative list. The hub
				// union-merges our rows and rebroadcasts the complete set.
				// Isolated: a Room/serialization failure here must not skip the adopt
				// below, which is what actually re-syncs playback.
				try {
					syncLocalSavedQueuesUp()
				} catch (e: Exception) {
					Logger.e("HubManager", "saved-queue sync-up failed", e)
				}
				// Isolated for the same reason: a failure adopting history must not take
				// down the reconnect handshake.
				try {
					applySavedQueues(msg["savedQueues"]?.asObjectList() ?: emptyList())
				} catch (e: Exception) {
					Logger.e("HubManager", "saved-queue adopt failed", e)
				}
				// Isolated for the same reason, and absent on an older hub — which
				// costs an empty Mixed for You list, not a failed handshake.
				try {
					applyMixes(msg["mixes"]?.asObjectList() ?: emptyList())
				} catch (e: Exception) {
					Logger.e("HubManager", "mix adopt failed", e)
				}
				// Hub is authoritative: adopt its session rather than re-publishing ours.
				adoptIfNoLiveReceiver()
				// lb-bot's availability and route list ride on the welcome (navi-connect
				// contract §1a), so the first lb-bot read after a connect needn't pay a
				// `/lb/status` probe first. Absent on an older hub, which keeps the probe path
				// untouched; isolated because a malformed advert must not fail the handshake.
				try {
					(msg["lb"] as? JsonObject)?.let { applyLbAdvert(it) }
				} catch (e: Exception) {
					Logger.e("HubManager", "lb advert ignored", e)
				}
				// Pull the lb-bot index mirror on EVERY welcome. `lbIndex` is only a hint and is
				// not trusted to skip the pull: an up-to-date pull costs ~100 bytes, and an
				// `index` frame missed while this socket was down would make the hint stale.
				// A new connection also starts the backoff afresh (R20b): whatever the last socket
				// was contending with is gone.
				lbIndexSync.requestSync("welcome", freshStart = true)
				// Any `library` frame sent while this socket was down is lost, so every cached
				// lb-bot answer that carries ownership (Fresh, the Discover rows) revalidates on
				// its next read. It still renders from Room first; this only decides whether a
				// request follows. Not a library event — no bump, no refetch (contract §1b).
				lbBotManager.markLibraryStale()
				Logger.i("HubManager", "connected to hub as $id")
			}

			// A session frame is the snapshot fields at the top level.
			"session" -> {
				applySession(msg)
				followSavedQueueCursor()
				// Active device may have just dropped (activeId → null): adopt the
				// last-known queue locally, paused, so we're not stranded mirroring it.
				adoptIfNoLiveReceiver()
			}

			// A partial progress frame must NOT reset the fields it omits: defaulting
			// a missing isPlaying to false / positionMs to 0 snapped the scrubber and
			// flickered a paused state. Keep the previous mirror value for anything absent.
			// And a position from before a seek this device just sent is not applied
			// ([afterProgress]). `update`, not a read and a write: actSeek writes the mirror
			// from another thread, and its target must not be lost under this frame.
			"progress" -> {
				val now = nowMs()
				_remoteSession.update { prev ->
					prev.afterProgress(
						index = msg["index"]?.jsonPrimitive?.intOrNull,
						isPlaying = msg["isPlaying"]?.jsonPrimitive?.booleanOrNull,
						positionMs = msg["positionMs"]?.jsonPrimitive?.longOrNull,
						nowMs = now
					)
				}
				followSavedQueueCursor()
			}

			"devices" -> msg["devices"]?.let { parseDevices(it.asObjectList()) }

			"savedQueues" -> try {
				applySavedQueues(msg["queues"]?.asObjectList() ?: emptyList())
				// The broadcast's cursor for the live record can be a track behind.
				followSavedQueueCursor()
			} catch (e: Exception) {
				Logger.e("HubManager", "saved-queue broadcast adopt failed", e)
			}

			"mixes" -> try {
				applyMixes(msg["mixes"]?.asObjectList() ?: emptyList())
			} catch (e: Exception) {
				Logger.e("HubManager", "mix broadcast adopt failed", e)
			}

			"do" -> handleDo(msg)

			// A station's cursor, steering, character or exhaustion moved (PROTOCOL §18.2).
			"station" -> try {
				applyStationFrame(msg)
			} catch (e: Exception) {
				Logger.e("HubManager", "station frame ignored", e)
			}

			// lb-bot placed an album somewhere in the library — possibly at another
			// client's request. The frame carries no authority: it only says "re-read
			// data you can already read", so it needs no validation beyond the socket's,
			// and missing one costs nothing, because the index flip upstream is durable
			// and the next read is right regardless.
			"library" -> lbBotManager.onLibraryChanged(parseLibraryEvent(msg))

			// A fill moved: lb-bot pushed its state or progress through the hub. Nothing
			// in the library changed, so nothing is refetched — one ledger row is updated,
			// through the same function the poll uses (PROTOCOL §15.1).
			"fill" -> lbBotManager.onFillFrame(msg)

			// lb-bot's index moved (`{seq, epoch}`). Not a library event either: nothing in
			// Navidrome changed, so nothing is refetched and no library cache is touched — the
			// mirror pulls the change feed from its own cursor. The frame's seq/epoch are not
			// needed for that; the pull answers both authoritatively.
			// The frame is also proof lb-bot is up, whatever a cached availability says.
			// An artist MBID override set on another client moves the index too (lb-bot re-keys
			// the artist and rescans), so the same frame re-reads the override map (I-019):
			// above, before this `when`.
			"index" -> lbIndexSync.requestSync("index frame", lbAlive = true)

			// The hub's lb-bot verdict CHANGED (ruling R18): `{available, routes}`, the very object
			// `welcome.lb` carries, sent whenever the hub's own probe flips. Applied exactly as the
			// welcome applies it — `welcome.lb` alone is a connect-time snapshot, and a client that
			// stays connected would otherwise never hear lb-bot come up or go away. Coming UP is
			// also when the mirror has most likely fallen behind (no `index` frame could reach us
			// while lb-bot was down), so it pulls, and as a fresh start: a backoff measured against
			// an lb-bot that was down is no reason to wait now. On EVERY frame saying available —
			// the hub only sends one when its verdict flips, so each is a coming-up; comparing
			// against this client's cached `available` would miss the flip whenever a probe or a
			// welcome had already moved the cache. Old hubs never send this frame.
			"lb" -> try {
				if (applyLbAdvert(msg) == true) lbIndexSync.requestSync("lb available", freshStart = true)
			} catch (e: Exception) {
				Logger.e("HubManager", "lb frame ignored", e)
			}

			// The hub's answer to one act of ours, written after every frame that act caused: the
			// mirror already holds the outcome, so the value pinned when the act was sent gives
			// way to it. The ack of an act that pinned nothing releases nothing. A refusal
			// changed nothing on the hub and is told here and nowhere else, with the code and
			// message an `error` frame would have carried. `ok` with a code (`no_active_device`)
			// is an act the session took although no device heard it, and is not a failure.
			"ack" -> {
				msg["cid"]?.jsonPrimitive?.contentOrNullSafe()?.let { releasePins(it) }
				if (msg["ok"]?.jsonPrimitive?.booleanOrNull == false) {
					onHubError(
						msg["code"]?.jsonPrimitive?.contentOrNullSafe(),
						msg["message"]?.jsonPrimitive?.contentOrNullSafe()
					)
				}
			}

			"error" -> onHubError(
				msg["code"]?.jsonPrimitive?.content,
				msg["message"]?.jsonPrimitive?.content
			)
		}
	}

	/** A failure the hub reported: an `error` frame, or the refusal in an act's `ack`. */
	private fun onHubError(code: String?, message: String?) {
		Logger.e("HubManager", "hub error: $code $message")
		// A refused mix is not a connection problem, and must not be written
		// into `_connectionError` — that flow is rendered by the device
		// picker and the navi-connect settings screen, where "a mix needs a
		// name and a known kind" reads as the hub being broken. The hub
		// answers these rather than dropping them precisely because the
		// client has already shown the user a mix it believes it saved, so
		// they go to the screen that made the claim.
		if (code == "bad_mix" || code == "unknown_mix") {
			_mixErrors.tryEmit(message ?: "That mix could not be saved")
			return
		}
		_connectionError.value = when (code) {
			// Both mean "the transfer you just asked for did not happen" — say which,
			// because the two have different fixes (turn the speaker on vs. try again).
			"target_unreachable" -> message ?: "That device isn't responding"
			"load_failed" -> message ?: "That device could not start playback"
			else -> message ?: code
		}
		// A bad token never resolves by retrying — stop the reconnect loop.
		if (code == "auth") authFailed = true
	}

	/**
	 * Take a `session` snapshot into the mirror. A timed seek hold ends here: the hub's own
	 * position is the answer it was waiting for. Pins do not ([keepPins]): the snapshot may
	 * have left the hub before the act did, so its values go under them (`underPins`) and
	 * only the act's ack lets them through. A `welcome` passes false and starts clean.
	 */
	private fun applySession(obj: JsonObject, keepPins: Boolean = true) {
		_activeDeviceId.value =
			obj["activeDeviceId"]?.jsonPrimitive?.contentOrNullSafe()
		val now = nowMs()
		val snapshot = RemoteSessionState(
			tracks = obj["queue"]?.asObjectList()?.mapNotNull { t ->
				val id = t["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
				RemoteTrack(
					id = id,
					title = t["title"]?.jsonPrimitive?.content ?: "",
					artist = t["artist"]?.jsonPrimitive?.content ?: "",
					album = t["album"]?.jsonPrimitive?.contentOrNullSafe(),
					durationMs = t["durationMs"]?.jsonPrimitive?.longOrNull ?: 0L,
					imageUrl = t["imageUrl"]?.jsonPrimitive?.contentOrNullSafe()
				)
			} ?: emptyList(),
			index = obj["index"]?.jsonPrimitive?.intOrNull ?: 0,
			isPlaying = obj["isPlaying"]?.jsonPrimitive?.booleanOrNull ?: false,
			positionMs = obj["positionMs"]?.jsonPrimitive?.longOrNull ?: 0L,
			positionAtMs = now,
			repeat = obj["repeat"]?.jsonPrimitive?.contentOrNullSafe() ?: "none",
			shuffle = obj["shuffle"]?.jsonPrimitive?.booleanOrNull ?: false,
			savedQueueId = obj["savedQueueId"]?.jsonPrimitive?.contentOrNullSafe(),
			stationId = obj["stationId"]?.jsonPrimitive?.contentOrNullSafe(),
			sourceName = obj["sourceName"]?.jsonPrimitive?.contentOrNullSafe(),
			order = (obj["order"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.intOrNull },
			// `remainingMs` was true when the frame was built: counted down from here on.
			sleepTimer = (obj["sleepTimer"] as? JsonObject)?.let { timer ->
				sessionSleepTimer(
					remainingMs = (timer["remainingMs"] as? JsonPrimitive)?.longOrNull,
					minutes = (timer["minutes"] as? JsonPrimitive)?.intOrNull,
					setBy = (timer["setBy"] as? JsonPrimitive)?.contentOrNull,
					arrivedAtMs = now
				)
			}
		)
		// `update`: an act pins from another thread, and its pin must not be lost under this frame.
		_remoteSession.update { shown -> if (keepPins) snapshot.underPins(shown, now) else snapshot }
	}

	/**
	 * Hub-authoritative startup/reconnect + offline takeover: adopt the hub's session
	 * as our LOCAL queue (paused) instead of publishing our own (possibly stale) queue
	 * over it. Runs on `welcome` and every `session` frame.
	 *
	 * Only adopts when there is NO live receiver (activeId == null): another active
	 * device is handled by the display mirror ([refreshMirror]); when we're active the
	 * reporter owns publishing. Adopting loads PAUSED — a launching / taking-over client
	 * stays a controller until the user presses play (which claims active).
	 */
	private suspend fun adoptIfNoLiveReceiver() {
		if (_activeDeviceId.value != null) return
		val session = _remoteSession.value
		val hubSig = session.tracks.joinToString(",") { it.id }
		val local = mediaPlayer.localUiState.value
		val localSig = local.queue.joinToString(",") { it.id }
		val localPlaying = !local.isPaused && local.queue.isNotEmpty()

		// Audibly playing with no live receiver: we ARE the receiver, whatever the hub's
		// record says. Our socket blipped (a Wi-Fi/cellular handover) and the hub cleared
		// active on our drop, so RE-CLAIM it — reset the publish signature so the reporter's
		// next tick republishes our queue (play=true) and the hub promotes us back.
		//
		// This deliberately does NOT also require the hub's queue to match ours. It used to,
		// and any divergence while the socket was down — an autoplay top-up, an offline queue
		// edit, another client having touched the session — fell through to the adopt below,
		// which loads PAUSED. That is a song stopping mid-play for no reason after a handover,
		// which is what this whole branch exists to prevent. Keeping our queue and republishing
		// is the better outcome even when the hub holds a genuinely different one: silently
		// replacing what someone is listening to is worse than ignoring a stale record.
		if (localPlaying) {
			lastQueueSig = ""
			return
		}
		// Empty hub session: keep our local queue as the offline fallback; let the first
		// real user play publish it.
		if (hubSig.isEmpty()) {
			lastQueueSig = ""
			return
		}
		// We already hold this exact queue, paused, at (about) the hub's cursor — repeated
		// session frames must not reload it. Only reload when the cursor genuinely differs:
		// that's the "the other device was force-stopped, resume where IT left off" case,
		// and it's what makes a takeover continue from the right spot.
		if (hubSig == localSig) {
			val localPositionMs = local.currentSong?.duration?.inWholeMilliseconds
				?.let { (local.progress * it).toLong() } ?: 0L
			val inSync = local.currentIndex == session.index &&
				(localPositionMs - session.positionMs) in -2_000..2_000
			if (inSync) return
		}

		val songs = resolveRemoteTracks(session.tracks)
		if (songs.isEmpty()) return
		hubDrivenUntilMs = nowMs() + 2000
		// Do NOT pin the signature to the hub's: publishQueueIfOurs bails while sig ==
		// lastQueueSig, so pinning it here meant a reopened app could never publish and
		// therefore never be promoted back to active — it sat connected but mute.
		lastQueueSig = ""
		// Carry the hub's record identity into local state so the "Now Playing" highlight
		// survives the adopt (a null id here read as "this queue isn't in history").
		mediaPlayer.loadRemoteQueue(
			songs, session.index, session.positionMs, play = false,
			savedQueueId = session.savedQueueId,
			savedQueueKind = savedQueueKindFor(session.savedQueueId)
		)
	}

	/** The kind stored on the hub record with this id, if we know it (else "manual"). */
	private suspend fun savedQueueKindFor(id: String?): String {
		if (id == null) return "manual"
		return savedQueueRepository.get(id)?.sourceKind ?: "manual"
	}

	private fun parseDevices(arr: List<JsonObject>) {
		_devices.value = arr.mapNotNull { d ->
			val id = d["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
			HubDevice(
				id = id,
				name = d["name"]?.jsonPrimitive?.content ?: "Unknown",
				platform = d["platform"]?.jsonPrimitive?.content ?: "unknown",
				online = d["online"]?.jsonPrimitive?.booleanOrNull ?: false,
				isActive = d["isActive"]?.jsonPrimitive?.booleanOrNull ?: false,
				volume = d["volume"]?.jsonPrimitive?.intOrNull ?: 100,
				reachable = d["reachable"]?.jsonPrimitive?.booleanOrNull,
				bridgedBy = d["bridgedBy"]?.jsonPrimitive?.contentOrNullSafe(),
				kind = d["kind"]?.jsonPrimitive?.contentOrNullSafe(),
				caps = (d["caps"] as? JsonArray)
					?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
					?.toSet()
					.orEmpty()
			)
		}
	}

	/** Acknowledge a transfer's `do:load` — see PROTOCOL §7.1. */
	private suspend fun sendLoaded(ok: Boolean, error: String?, transferId: String?) {
		sendAsync(buildJsonObject {
			put("t", "loaded")
			put("ok", ok)
			if (error != null) put("error", error)
			if (transferId != null) put("transferId", transferId)
		})
	}

	private suspend fun handleDo(msg: JsonObject) {
		val cmd = msg["cmd"]?.jsonPrimitive?.content
		// A volume directive is not playback, and with the `idleVolume` cap it comes while
		// ANOTHER device plays (PROTOCOL §5.1). It must not open the hub-driven window: for
		// those two seconds a play started on this device would be neither routed to the
		// playing device nor published. It changes nothing a report says, so the hub gives
		// it no `seq` and there is no echo to wait for; one that did carry a number is
		// owed it back at once.
		if (cmd == "setVolume") {
			applyVolumeDirective(msg["level"]?.jsonPrimitive?.intOrNull ?: 100)
			msg["seq"]?.jsonPrimitive?.intOrNull?.let {
				settleDirective(doEcho.value.connection, it, null, EchoReport.FromPlayer)
			}
			return
		}
		hubDrivenUntilMs = nowMs() + 2000
		// PROTOCOL §5.2: the hub numbers the directives that change what a report says. The
		// number is owed back on EVERY way out of this function (§5.3, duty 1: carried out,
		// refused, failed or superseded), hence the `finally`. And only once the command has
		// run (duty 2), hence every player call below is awaited: each used to be posted to
		// the main thread and left, while the reporter went on describing the state before it.
		val seq = msg["seq"]?.jsonPrimitive?.intOrNull
		val connection = doEcho.value.connection
		// What the command should leave in the player; null where there is nothing to wait for.
		var expectIndex: Int? = null
		var expectPositionMs: Long? = null
		var expectation: DoExpectation? = null
		// The report owed the moment `applied` moves (duty 4).
		var echo: EchoReport = EchoReport.FromPlayer
		var cancelled = false
		try {
			when (cmd) {
				"load" -> {
					val transferId = msg["transferId"]?.jsonPrimitive?.contentOrNullSafe()
					// The hub sends the deadline it is holding us to (§7.2). Keeping our own would let a
					// slow load land after the rollback — audio on a device no client thinks is active.
					val budgetMs = msg["timeoutMs"]?.jsonPrimitive?.longOrNull ?: 10_000L
					val tracks = msg["tracks"]?.jsonArray?.map { it.jsonObject } ?: emptyList()
					val index = msg["index"]?.jsonPrimitive?.intOrNull ?: 0
					val positionMs = msg["positionMs"]?.jsonPrimitive?.longOrNull ?: 0L
					// Whatever an earlier failed load left the player holding, this one replaces.
					heldAfterFailedLoad.value = null
					// Resolve 1:1 (placeholders for un-synced songs) so the hub index
					// stays aligned — dropping a song would shift the index and play
					// the wrong track.
					val songs = resolveQueue(tracks)
					if (songs.isEmpty()) {
						Logger.e("HubManager", "do:load — empty track list")
						// PROTOCOL §7.1: the hub commits the active slot before sending this load, so
						// staying silent would leave the whole stack believing this device took a
						// session it cannot play a note of.
						sendLoaded(false, "nothing in the queue resolved", transferId)
						echo = afterFailedLoad(transferId, index, positionMs, songs)
						return
					}
					val play = msg["play"]?.jsonPrimitive?.booleanOrNull ?: true
					lastQueueSig = songs.joinToString(",") { it.id }
					// ACK_RESERVE_MS is kept back for this frame's own trip: answering AT the deadline is
					// the same as not answering, because the hub has already rolled the slot back by then.
					val outcome = mediaPlayer.awaitRemoteQueueLoad(
						songs, index, positionMs, play,
						(budgetMs - ACK_RESERVE_MS).coerceAtLeast(1_000L)
					)
					// A superseded load has no standing to answer for a queue it no longer owns.
					if (outcome.error == "superseded by a newer load") return
					sendLoaded(outcome.ok, outcome.error, transferId)
					if (!outcome.ok) {
						Logger.w("HubManager", "do:load failed: ${outcome.error}")
						echo = afterFailedLoad(transferId, index, positionMs, songs)
					}
					// No expectation: the awaited outcome above IS the look at the player.
				}

				"play" -> mediaPlayer.awaitRemoteResume()
				"pause" -> mediaPlayer.awaitRemotePause()

				"jump" -> {
					val index = msg["index"]?.jsonPrimitive?.intOrNull ?: 0
					mediaPlayer.awaitRemotePlayAt(index)
					expectIndex = index
				}

				"seek" -> {
					val positionMs = msg["positionMs"]?.jsonPrimitive?.longOrNull ?: 0L
					val durationMs = mediaPlayer.localUiState.value.currentSong
						?.duration?.inWholeMilliseconds ?: 0L
					// With no duration to seek within, the seek is refused: nothing to wait for.
					if (durationMs > 0) {
						mediaPlayer.awaitRemoteSeek((positionMs.toFloat() / durationMs).coerceIn(0f, 1f))
						expectPositionMs = positionMs
					}
				}

				"queueChanged" -> {
					val tracks = msg["tracks"]?.jsonArray?.map { it.jsonObject } ?: emptyList()
					val songs = resolveQueue(tracks)
					if (songs.isEmpty()) return
					val index = msg["index"]?.jsonPrimitive?.intOrNull ?: 0
					lastQueueSig = songs.joinToString(",") { it.id }
					// Edits the queue around the current track without restarting
					// it (falls back to a reload when the current track changed).
					mediaPlayer.awaitRemoteQueueReconcile(songs, index)
					expectIndex = index
				}

				// Emptying the queue can't ride on `queueChanged` — it carries a track
				// list, and an empty one is indistinguishable from "nothing to apply".
				"clear" -> {
					lastQueueSig = ""
					// Stops as well: an emptied player that still says "playing" is what the
					// next report used to tell every device.
					mediaPlayer.awaitRemoteClear()
				}

				"setRepeat" -> {
					// media3 constants: 0 = off, 1 = one, 2 = all
					val mode = when (msg["mode"]?.jsonPrimitive?.content) {
						"one" -> 1
						"all" -> 2
						else -> 0
					}
					mediaPlayer.applyRemoteRepeat(mode)
				}

				"setShuffle" -> mediaPlayer.applyRemoteShuffle(
					msg["on"]?.jsonPrimitive?.booleanOrNull ?: false
				)

				"release" -> {
					// Stop, THEN the final report and `released`: the hub resumes the next device
					// at the spot these two name, so they are read once the pause has run. They
					// used to leave before it, with the player still moving.
					mediaPlayer.awaitRemotePause()
					// We're no longer the active device; the next time we claim it, the
					// guards must not still describe this handed-off session.
					resetReporterGuards()
					// A release sends its own report and no other follows it (§5.3, duty 4). A
					// directive still waiting on the player is judged by the state it reads.
					echo = EchoReport.None
					val seen = echoPlayerState(mediaPlayer.localUiState.value)
					moveEcho { it.evaluated(seen, nowMs()) }
					// `applied` before the state, as in every report (see [reporterLoop]).
					val applied = doEcho.value.applied
					val state = mediaPlayer.localUiState.value
					val positionMs = positionOf(state)
					sendFrame(buildJsonObject {
						put("t", "report")
						put("positionMs", positionMs)
						put("index", state.currentIndex.coerceAtLeast(0))
						put("isPlaying", false)
						put("applied", applied)
					})
					sendFrame(buildJsonObject {
						put("t", "released")
						put("positionMs", positionMs)
						put("index", state.currentIndex.coerceAtLeast(0))
						// §7.2 — name the handoff this belongs to, so a duplicate from an earlier
						// one cannot complete it (and rewind the session to where we were then).
						msg["transferId"]?.jsonPrimitive?.contentOrNullSafe()?.let { put("transferId", it) }
					})
				}
			}
			// Reached only when the command ran: an early return above is a refusal, and a
			// refusal has nothing to wait for.
			if (seq != null) expectation = doExpectation(cmd, seq, expectIndex, expectPositionMs, nowMs())
		} catch (e: CancellationException) {
			// The connection this directive came in on is being torn down, and the next
			// `welcome` starts the numbers over: nothing is owed on its behalf.
			cancelled = true
			throw e
		} finally {
			if (seq != null && !cancelled) settleDirective(connection, seq, expectation, echo)
		}
	}

	/**
	 * `do:setVolume`: the phone's media volume, where the platform lets it be set, and the
	 * player's own gain back at full, so that the two never multiply. Elsewhere the gain is
	 * the volume, as it was. Applied whenever it arrives, playing or not, and nothing here
	 * sends an act: the level goes back to the hub as a report only.
	 *
	 * The level the system then holds is reported even when it did not move (PROTOCOL §5.3):
	 * the hub has just written the level it ASKED for on this device's row, and a phone has
	 * 15 to 25 steps, so the level it holds is seldom that number.
	 */
	private fun applyVolumeDirective(level: Int) {
		if (localDevice.setMediaVolume(level.coerceIn(0, 100))) {
			mediaPlayer.setPlayerVolume(1f)
			volumeReportsAsked.update { it + 1 }
		} else {
			mediaPlayer.setPlayerVolume(level.coerceIn(0, 100) / 100f)
		}
	}

	/** Bumped to have the current level reported again although it did not change. */
	private val volumeReportsAsked = MutableStateFlow(0)

	/**
	 * Tells the hub this device's media volume (`deviceState.volume`, PROTOCOL §5.3): once
	 * after `welcome`, then on every change whoever made it, at most one every
	 * [VOLUME_REPORT_GAP_MS] and always the last value. Says nothing where the platform
	 * reports no volume.
	 */
	private suspend fun volumeReportLoop() {
		// The hub reads nothing but `hello` before it has answered it.
		_connected.first { it }
		combine(localDevice.mediaVolume, volumeReportsAsked) { level, _ -> level }
			.filterNotNull()
			// The newest level waits out the gap; the ones it replaced are never sent.
			.conflate()
			.collect { level ->
				enqueue(buildJsonObject {
					put("t", "deviceState")
					put("volume", level)
				})
				delay(VOLUME_REPORT_GAP_MS)
			}
	}

	/**
	 * What a load answered `loaded{ok:false}` owes the hub besides that answer (PROTOCOL
	 * §5.3, duty 4), as the report to send when `applied` moves.
	 *
	 * A transfer's load (it carried a `transferId`): none. The hub rolls the slot back on the
	 * answer alone, and a report would describe a player it has already given up on.
	 *
	 * Any other load: this device still holds the session, so it stops, and says so. That
	 * report repeats the load's OWN index and position under "not playing", and is not read
	 * from the player: the player may still hold the queue it had before, and the hub would
	 * write that queue's index and position over the new one's.
	 */
	private suspend fun afterFailedLoad(
		transferId: String?,
		index: Int,
		positionMs: Long,
		songs: List<DomainSong>
	): EchoReport {
		if (transferId != null) return EchoReport.None
		mediaPlayer.awaitRemotePause()
		// The same goes for the reporter, which would report that pause from the old queue.
		// Marked AFTER the pause has run and BEFORE `applied` moves: [reporterLoop] reads
		// `applied`, then this, then the player, so a report that carries the new number has
		// seen the mark, and one that has not seen it carries the old number and is dropped.
		val left = mediaPlayer.localUiState.value
		if (left.queue !== songs) heldAfterFailedLoad.value = HeldQueue(left.queue)
		return EchoReport.OfFailedLoad(index, positionMs)
	}

	private suspend fun resolveSongs(ids: List<String>): List<DomainSong> {
		if (ids.isEmpty()) return emptyList()
		// IN(...) queries don't preserve order — restore the hub's queue order.
		val byId = songDao.getSongsByIds(ids).associateBy { it.songId }
		return ids.mapNotNull { byId[it]?.toDomainModel() }
	}

	/**
	 * Resolve a hub queue (full track objects) to DomainSongs, preserving the
	 * hub's order AND length 1:1 — songs missing from the local DB become
	 * placeholders synthesized from the hub track metadata. This is critical for
	 * transfer/queueChanged: dropping a missing song would shift every later
	 * index, so the hub's index would point to the wrong track (and Navic would
	 * play one song off). Placeholders still play + show art because Navidrome
	 * serves both stream and cover by song id (same server).
	 */
	private suspend fun resolveQueue(tracks: List<JsonObject>): List<DomainSong> {
		if (tracks.isEmpty()) return emptyList()
		val ids = tracks.mapNotNull { it["id"]?.jsonPrimitive?.content }
		val byId = songDao.getSongsByIds(ids).associateBy { it.songId }
		return tracks.mapNotNull { track ->
			val id = track["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
			byId[id]?.toDomainModel() ?: remoteTrackToDomainSong(track)
		}
	}

	/** Minimal DomainSong for a hub track that isn't in the local library. */
	private fun remoteTrackToDomainSong(track: JsonObject): DomainSong {
		val id = track["id"]?.jsonPrimitive?.content ?: ""
		// A preview is not "a library track we happen not to have synced yet" — it is
		// external by construction and can never resolve locally, so two fields below
		// read from the wire rather than being derived from the id.
		val isPreview = PreviewManager.isPreviewId(id)
		return DomainSong(
			id = id,
			title = track["title"]?.jsonPrimitive?.content ?: "",
			artistName = track["artist"]?.jsonPrimitive?.content ?: "",
			artistId = "",
			albumTitle = track["album"]?.jsonPrimitive?.contentOrNullSafe(),
			albumId = null,
			parentId = null,
			comment = null,
			trackNumber = null,
			discNumber = null,
			isrc = emptyList(),
			year = null,
			genre = null,
			genres = emptyList(),
			moods = emptyList(),
			duration = (track["durationMs"]?.jsonPrimitive?.longOrNull ?: 0L).milliseconds,
			bpm = null,
			contributors = emptyList(),
			playCount = 0,
			userRating = null,
			averageRating = null,
			bitRate = null,
			bitDepth = null,
			sampleRate = null,
			audioChannelCount = null,
			replayGain = null,
			fileSize = 0,
			fileExtension = "",
			mimeType = track["mime"]?.jsonPrimitive?.contentOrNullSafe() ?: "",
			// A preview's signed stream URL, which is the one field that cannot be
			// re-derived on this device: there is no library row to look it up in and
			// no Subsonic id to build one from. `filePath` is where the player reads an
			// arbitrary URL from; for an ordinary track it stays null, as before.
			filePath = if (isPreview) {
				track["streamUrl"]?.jsonPrimitive?.contentOrNullSafe()
			} else null,
			starredAt = null,
			// Navidrome serves the cover by song id, so this lets art load — except for
			// a preview, whose id names nothing on this server. There the publisher's
			// `imageUrl` is the ONLY source, and it is safe to trust precisely because
			// it is a public third-party URL (Cover Art Archive) rather than another
			// device's authed Navidrome address. That distinction is why the general
			// rule "never adopt a RemoteTrack imageUrl" does not apply here.
			coverArtId = if (isPreview) {
				track["imageUrl"]?.jsonPrimitive?.contentOrNullSafe()
			} else id,
			musicBrainzId = null,
			explicitStatus = DomainExplicitStatus.Unknown,
			// Hub placeholders carry no per-artist breakdown: the wire format sends one
			// credit string, and resolveQueue swaps these for the library's own rows.
			artists = emptyList(),
			albumArtists = emptyList(),
			isExternal = isPreview
		)
	}

	/** [resolveQueue] for the mirror's [RemoteTrack] list — 1:1, placeholders. */
	private suspend fun resolveRemoteTracks(tracks: List<RemoteTrack>): List<DomainSong> {
		if (tracks.isEmpty()) return emptyList()
		val byId = songDao.getSongsByIds(tracks.map { it.id }).associateBy { it.songId }
		return tracks.map { track ->
			byId[track.id]?.toDomainModel() ?: remoteTrackToDomainSong(track)
		}
	}

	/** Minimal DomainSong for a mirror [RemoteTrack] not in the local library. */
	private fun remoteTrackToDomainSong(track: RemoteTrack): DomainSong = DomainSong(
		id = track.id,
		title = track.title,
		artistName = track.artist,
		artistId = "",
		albumTitle = track.album,
		albumId = null,
		parentId = null,
		comment = null,
		trackNumber = null,
		discNumber = null,
		isrc = emptyList(),
		year = null,
		genre = null,
		genres = emptyList(),
		moods = emptyList(),
		duration = track.durationMs.milliseconds,
		bpm = null,
		contributors = emptyList(),
		playCount = 0,
		userRating = null,
		averageRating = null,
		bitRate = null,
		bitDepth = null,
		sampleRate = null,
		audioChannelCount = null,
		replayGain = null,
		fileSize = 0,
		fileExtension = "",
		mimeType = "",
		filePath = null,
		starredAt = null,
		// Navidrome serves the cover by song id, so this lets art load — except for a
		// preview, whose id names nothing on this server, and whose publisher-supplied
		// `imageUrl` is a public third-party URL rather than another device's authed
		// Navidrome address. This is the mirror, so there is no stream URL to carry:
		// the remote device is the one playing.
		coverArtId = if (PreviewManager.isPreviewId(track.id)) track.imageUrl else track.id,
		musicBrainzId = null,
		explicitStatus = DomainExplicitStatus.Unknown,
		artists = emptyList(),
		albumArtists = emptyList(),
		isExternal = PreviewManager.isPreviewId(track.id)
	)

	// ------------------------------------------------------------------ //
	// Outbound: reports + queue publishing
	// ------------------------------------------------------------------ //

	private suspend fun reporterLoop(): Unit = coroutineScope {
		// The deadline of a directive still waiting to be seen in the player (PROTOCOL §5.3,
		// duty 5). A coroutine of its own, because a pause, a clear or a paused seek that did
		// not take emits nothing further for the collector below to judge it by.
		launch {
			doEcho.collectLatest { echo ->
				val waiting = echo.pending ?: return@collectLatest
				delay((waiting.deadlineMs - nowMs()).coerceIn(0L, DO_ECHO_DEADLINE_MS))
				// The deadline IS this delay, whatever the wall clock says by now.
				if (moveEcho { it.settled(echo.connection, waiting.seq) }) {
					sendEchoReport(EchoReport.FromPlayer)
				}
			}
		}
		mediaPlayer.localUiState.collect { state ->
			try {
				val now = nowMs()
				// A directive waiting on the player is judged by every state it emits.
				judgeWaitingDirective(now)

				if (routeLocalPlayIfRemote(state, now)) return@collect
				publishQueueIfOurs(state, positionOf(state), now)

				if (!isActiveDevice()) return@collect

				// `applied` is read BEFORE the player's state, never after. The number moves
				// only once a directive's effect is in the player, so a state read after the
				// number cannot be older than the number claims. Read the other way round,
				// the state this collector was handed (from before a skip) could leave with
				// the number the frame loop set after it, and the hub would believe it.
				val applied = doEcho.value.applied
				val held = heldAfterFailedLoad.value
				val current = mediaPlayer.localUiState.value
				if (held != null) {
					// Paused over a queue the hub does not have: nothing true to say about
					// the session's index or position. Any change ends it: playing publishes
					// this queue (above), and a new queue is no longer the one held.
					if (current.isPaused && current.queue === held.queue) return@collect
					heldAfterFailedLoad.compareAndSet(held, null)
				}

				val stateChanged = current.currentIndex != lastReportedIndex ||
					current.isPaused != lastReportedPaused
				val tickDue = !current.isPaused && now - lastTickMs >= 1000

				if (stateChanged || tickDue) {
					lastReportedIndex = current.currentIndex
					lastReportedPaused = current.isPaused
					lastTickMs = now
					sendFrame(reportOf(current, applied))
				}
			} catch (e: Exception) {
				Logger.e("HubManager", "report failed", e)
			}
		}
	}

	// --- The command echo (PROTOCOL §5.2-5.3; the rules are in DoEcho.kt) ----------------------

	/** `applied` is 0 again and nothing is waited for: a new connection, and its `welcome`. */
	private fun resetEcho() {
		doEcho.update { it.onNewConnection() }
		heldAfterFailedLoad.value = null
	}

	/**
	 * Change the echo state in one compare-and-set, and say whether `applied` moved. A move
	 * obliges whoever made it to report at once (§5.3, duty 4): the frame loop and the
	 * reporter can both try, and exactly one of them is told true.
	 */
	private inline fun moveEcho(transform: (DoEcho) -> DoEcho): Boolean {
		while (true) {
			val before = doEcho.value
			val after = transform(before)
			if (doEcho.compareAndSet(before, after)) {
				return after.connection == before.connection && after.applied > before.applied
			}
		}
	}

	private fun echoPlayerState(state: PlayerUiState) = EchoPlayerState(
		isPaused = state.isPaused,
		// As a report gives it.
		index = state.currentIndex.coerceAtLeast(0),
		positionMs = positionOf(state),
		queueEmpty = state.queue.isEmpty()
	)

	/**
	 * A numbered directive is over. With nothing to wait for (a load, a refusal, a failure)
	 * `applied` moves now. Otherwise its [expectation] is registered and judged AT ONCE against
	 * the player: the command has run, so the value is there, and a pause, a clear or a paused
	 * seek emits nothing more — waiting for the next emission would cost the whole deadline.
	 */
	private fun settleDirective(connection: Int, seq: Int, expectation: DoExpectation?, echo: EchoReport) {
		val moved = if (expectation == null) {
			moveEcho { it.settled(connection, seq) }
		} else {
			val seen = echoPlayerState(mediaPlayer.localUiState.value)
			val now = nowMs()
			moveEcho { it.expecting(connection, expectation).evaluated(seen, now) }
		}
		if (moved) sendEchoReport(echo)
	}

	/** The reporter's side of the same judgement: the waiting directive against the player now. */
	private fun judgeWaitingDirective(now: Long) {
		if (doEcho.value.pending == null) return
		val seen = echoPlayerState(mediaPlayer.localUiState.value)
		if (moveEcho { it.evaluated(seen, now) }) sendEchoReport(EchoReport.FromPlayer)
	}

	/**
	 * The report owed the moment `applied` moves: read from the player then, and sent whether
	 * or not this device believes it is the active one. The hub decides that, and right after
	 * a transfer in this device's own idea of it can be a frame behind.
	 */
	private fun sendEchoReport(echo: EchoReport) {
		// `applied` before the state: see [reporterLoop].
		val applied = doEcho.value.applied
		when (echo) {
			EchoReport.None -> return
			EchoReport.FromPlayer -> enqueue(reportOf(mediaPlayer.localUiState.value, applied))
			is EchoReport.OfFailedLoad -> enqueue(buildJsonObject {
				put("t", "report")
				put("positionMs", echo.positionMs.coerceAtLeast(0))
				put("index", echo.index.coerceAtLeast(0))
				put("isPlaying", false)
				put("applied", applied)
			})
		}
	}

	/** Where [state]'s playhead is: the player's fraction of the library's duration. */
	private fun positionOf(state: PlayerUiState): Long =
		state.currentSong?.duration?.inWholeMilliseconds?.let { (state.progress * it).toLong() } ?: 0L

	/** One `report` of [state]. [applied] must have been read before [state] was. */
	private fun reportOf(state: PlayerUiState, applied: Int): JsonObject = buildJsonObject {
		put("t", "report")
		put("positionMs", positionOf(state))
		put("index", state.currentIndex.coerceAtLeast(0))
		put("isPlaying", !state.isPaused)
		put("applied", applied)
	}

	private var lastRoutedAtMs = 0L
	private var lastClaimPublishAtMs = 0L

	// uiState emits every ~200ms while playing (progress ticks) but keeps the
	// SAME queue list instance — cache the joined-ids signature by reference so
	// we don't rebuild a potentially huge string on every tick (this caused
	// visible jank with large queues).
	private var sigCacheQueue: List<DomainSong>? = null
	private var sigCacheValue = ""

	private fun queueSig(queue: List<DomainSong>): String {
		if (queue !== sigCacheQueue) {
			sigCacheQueue = queue
			sigCacheValue = queue.joinToString(",") { it.id }
		}
		return sigCacheValue
	}

	/**
	 * Spotify semantics: if the user starts playback on THIS device while
	 * ANOTHER device is active, the music belongs to the session — send the
	 * local queue to the hub (which loads it on the active remote device) and
	 * silence the local player. Hub-driven events are exempt. Returns true
	 * when the play was routed (callers should skip publishing/reporting).
	 */
	private suspend fun routeLocalPlayIfRemote(
		state: paige.navic.ui.core.PlayerUiState,
		now: Long
	): Boolean {
		if (!_connected.value) return false
		// While another device is active, the system MediaSession is driven by
		// RemoteSessionPlayer (Android) which mirrors the remote into the local
		// controller's state. Re-routing that mirror back to the hub would echo
		// the remote queue / pause the remote, so don't. (New local playback
		// while remote is blocked by the facade — transfer here first.)
		if (isRemoteActive.value) return false
		if (state.isPaused || state.queue.isEmpty()) return false
		if (now < hubDrivenUntilMs) return false
		if (now - lastRoutedAtMs < 1000) return false
		val active = _activeDeviceId.value ?: return false
		val me = _myDeviceId.value ?: return false
		if (active == me) return false

		lastRoutedAtMs = now
		lastQueueSig = queueSig(state.queue)
		publishedNonEmptyQueue = true
		val savedQueueId = hubSavedQueueIdFor(state)
		mediaPlayer.adoptQueueSessionId(savedQueueId)
		sendFrame(buildJsonObject {
			put("t", "act")
			put("action", "setQueue")
			put("index", state.currentIndex.coerceAtLeast(0))
			put("positionMs", 0)
			put("play", true)
			put("savedQueueId", savedQueueId)
			put("sourceKind", state.savedQueueKind)
			savedQueueNameFor(state)?.let { put("sourceName", it) }
			// The station this queue belongs to, or the session is unbound by this very publish
			// (PROTOCOL §18.2): every edit, an autoplay top-up included, republishes the queue.
			if (hubOffers(HUB_FEATURE_STATION)) {
				stationIdForPublish(state, savedQueueId)?.let { put("stationId", it) }
			}
			putJsonArray("tracks") {
				state.queue.forEach { song -> add(trackJson(song)) }
			}
		})
		// The session plays it remotely — silence the local player.
		mediaPlayer.pause()
		return true
	}

	/**
	 * Publish our local queue as session intent when the user plays music on
	 * this device — this is how the hub learns the queue and how this device
	 * claims the active role. Never hijacks: only when no device is active or
	 * we already are, and never for hub-driven changes (do:load echoes).
	 */
	private suspend fun publishQueueIfOurs(
		state: paige.navic.ui.core.PlayerUiState,
		positionMs: Long,
		now: Long
	) {
		if (!_connected.value) return
		if (state.isPaused || state.queue.isEmpty()) return
		if (now < hubDrivenUntilMs) return
		val active = _activeDeviceId.value
		val me = _myDeviceId.value
		if (!(active == null || (me != null && active == me))) return

		val sig = queueSig(state.queue)
		// Normally one publish per queue change. But when NOBODY is active and we're
		// playing, publishing is also how we CLAIM the session — and after adopting the
		// hub's queue our signature already equals it, so the dedupe would block the
		// claim forever (connected, playing locally, but never promoted → no reports).
		// Re-publish in that state, throttled so the ~5 Hz state flow doesn't spam.
		val unclaimed = active == null
		if (sig == lastQueueSig && !(unclaimed && now - lastClaimPublishAtMs >= CLAIM_REPUBLISH_MS)) return
		if (unclaimed) lastClaimPublishAtMs = now
		lastQueueSig = sig
		publishedNonEmptyQueue = true

		// Remembered on the player, or the claim re-publish loop above would re-derive it every
		// pass and — before the hub's broadcast round-trips back into the local index — could mint
		// a different id each time, forking one history card per republish.
		val savedQueueId = hubSavedQueueIdFor(state)
		mediaPlayer.adoptQueueSessionId(savedQueueId)
		sendFrame(buildJsonObject {
			put("t", "act")
			put("action", "setQueue")
			put("index", state.currentIndex.coerceAtLeast(0))
			put("positionMs", positionMs)
			put("play", true)
			put("savedQueueId", savedQueueId)
			put("sourceKind", state.savedQueueKind)
			savedQueueNameFor(state)?.let { put("sourceName", it) }
			// The station this queue belongs to, or the session is unbound by this very publish
			// (PROTOCOL §18.2): every edit, an autoplay top-up included, republishes the queue.
			if (hubOffers(HUB_FEATURE_STATION)) {
				stationIdForPublish(state, savedQueueId)?.let { put("stationId", it) }
			}
			putJsonArray("tracks") {
				state.queue.forEach { song -> add(trackJson(song)) }
			}
		})
	}

	private companion object {
		/** Matches the hub's PING_INTERVAL, so a silent (paused) session still holds the socket. */
		const val PING_INTERVAL_MS = 10_000L

		/** Reconnect backoff: the protocol mandates exponential backoff, not a fixed retry. */
		const val INITIAL_BACKOFF_MS = 1_000L
		const val MAX_BACKOFF_MS = 30_000L

		/** How long past `welcome` a link has to hold before the backoff starts over (Q-048). */
		const val STABLE_LINK_MS = 10_000L

		/**
		 * The least time between two kicked attempts. Each `welcome` costs a saved-queue sync,
		 * an index pull and a 2000 ms settle window, so a flapping network must not buy one per
		 * flap.
		 */
		const val KICK_FLOOR_MS = 2_000L

		/**
		 * How long the remote view survives a lost socket before falling back to local. Comfortably
		 * longer than the 3s reconnect backoff, short enough that a genuinely dead hub hands control
		 * back rather than stranding the user in a session they can't drive.
		 */
		const val REMOTE_HOLD_MS = 30_000L

		/**
		 * Time kept back from the hub's do:load budget for the `loaded` frame's own trip.
		 * Answering AT the deadline is the same as not answering — the hub has already
		 * rolled the active slot back by then.
		 */
		const val ACK_RESERVE_MS = 1_500L

		/** Throttle for the unclaimed-session re-publish (the "claim active" path). */
		const val CLAIM_REPUBLISH_MS = 2_000L

		/**
		 * Share of the hub session's tracks our queue must contain for it to count as the same
		 * listening session (so we refresh the hub's record instead of forking one). Half, matching
		 * Feishin — a shuffle or a heavy edit still leaves that much in common.
		 */
		const val HUB_QUEUE_ADOPT_OVERLAP = 0.5
	}
}

/** The report this device owes the hub the moment `applied` moves (PROTOCOL §5.3, duty 4). */
private sealed interface EchoReport {
	/** The player's state, read at that moment. */
	data object FromPlayer : EchoReport

	/** None: a `release` sends its own, and a transfer's failed load is the hub's to roll back. */
	data object None : EchoReport

	/** "Not playing", at the index and position the failed load itself named. */
	data class OfFailedLoad(val index: Int, val positionMs: Long) : EchoReport
}

/** A queue by identity: the list object the player holds, not what is in it. */
private class HeldQueue(val queue: List<DomainSong>)

// Small helpers for tolerant JSON access ------------------------------------

private fun kotlinx.serialization.json.JsonPrimitive.contentOrNullSafe(): String? =
	if (this is JsonNull) null else content

private fun kotlinx.serialization.json.JsonElement.asObjectList(): List<JsonObject> =
	// tolerate non-array payloads

	try {
		jsonArray.map { it.jsonObject }
	} catch (_: Exception) {
		emptyList()
	}

/** A `library` frame, read defensively: an older hub sends only `event`/`rgid`. */
private fun parseLibraryEvent(msg: JsonObject): LbLibraryEvent {
	fun str(key: String): String = (msg[key] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
	return LbLibraryEvent(
		event = str("event").ifBlank { EVENT_PLACED },
		rgid = str("rgid"),
		ndArtistId = str("ndArtistId"),
		ndAlbumIds = (msg["ndAlbumIds"] as? JsonArray)
			?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
			?.filter { it.isNotBlank() }
			.orEmpty()
	)
}

/**
 * A mix record's `seedIds` (P5): its strings in order, blanks and repeats left out. Absent, or not
 * a list, is none, which is every recipe but a custom radio's.
 */
internal fun mixSeedIds(raw: JsonElement?): List<String> =
	(raw as? JsonArray).orEmpty()
		.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.takeIf { c -> c.isNotBlank() } }
		.distinct()

/** The `saveMix` act (PROTOCOL §17.2), every optional field sent only when there is something to say. */
internal fun saveMixAct(
	name: String,
	kind: String,
	id: String?,
	seedId: String,
	seedName: String,
	moodCharacter: String,
	count: Int,
	coverArtId: String,
	character: String,
	seedIds: List<String>,
	variety: String,
	moodFilter: String
): JsonObject = buildJsonObject {
	put("t", "act"); put("action", "saveMix")
	id?.takeIf { it.isNotBlank() }?.let { put("id", it) }
	put("name", name)
	put("kind", kind)
	if (seedId.isNotBlank()) put("seedId", seedId)
	if (seedName.isNotBlank()) put("seedName", seedName)
	if (moodCharacter.isNotBlank()) put("moodCharacter", moodCharacter)
	// `close|balanced|explore` (PROTOCOL §17.1). A hub from before stations drops it and
	// keeps the legacy name beside it, which is why both are sent.
	if (character.isNotBlank()) put("character", character)
	if (coverArtId.isNotBlank()) put("coverArtId", coverArtId)
	// A custom radio's (P5, PROTOCOL §17.1); sent only when there is something to say, and
	// dropped harmlessly by a hub whose sanitiser predates them.
	if (seedIds.isNotEmpty()) putJsonArray("seedIds") { seedIds.forEach { add(it) } }
	if (variety.isNotBlank()) put("variety", variety)
	// P7: a custom radio's mood filter; a hub that predates it drops it.
	if (moodFilter.isNotBlank()) put("moodFilter", moodFilter)
	put("count", count)
}

/** One record of the hub's `mixes` list as a [Mix]; null without an id. */
internal fun mixFromRecord(rec: JsonObject): Mix? {
	val id = rec["id"]?.jsonPrimitive?.contentOrNullSafe() ?: return null
	return Mix(
		id = id,
		name = rec["name"]?.jsonPrimitive?.contentOrNullSafe().orEmpty(),
		kind = rec["kind"]?.jsonPrimitive?.contentOrNullSafe().orEmpty(),
		seedId = rec["seedId"]?.jsonPrimitive?.contentOrNullSafe().orEmpty(),
		seedName = rec["seedName"]?.jsonPrimitive?.contentOrNullSafe().orEmpty(),
		moodCharacter = rec["moodCharacter"]?.jsonPrimitive?.contentOrNullSafe().orEmpty(),
		character = rec["character"]?.jsonPrimitive?.contentOrNullSafe().orEmpty(),
		count = rec["count"]?.jsonPrimitive?.intOrNull ?: 50,
		coverArtId = rec["coverArtId"]?.jsonPrimitive?.contentOrNullSafe().orEmpty(),
		seedIds = mixSeedIds(rec["seedIds"]),
		variety = rec["variety"]?.jsonPrimitive?.contentOrNullSafe().orEmpty(),
		moodFilter = rec["moodFilter"]?.jsonPrimitive?.contentOrNullSafe().orEmpty(),
		createdAt = rec["createdAt"]?.jsonPrimitive?.longOrNull ?: 0L,
		updatedAt = rec["updatedAt"]?.jsonPrimitive?.longOrNull ?: 0L,
		lastPlayedAt = rec["lastPlayedAt"]?.jsonPrimitive?.longOrNull ?: 0L
	)
}
