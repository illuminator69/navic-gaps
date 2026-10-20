package paige.navic.domain.manager

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import paige.navic.data.database.dao.SongDao
import paige.navic.data.database.mappers.toDomainModel
import paige.navic.domain.models.DomainSong
import paige.navic.domain.models.Mix
import paige.navic.domain.models.MixKind
import paige.navic.domain.models.YOUR_MIX_ID
import paige.navic.domain.models.SavedQueueSource
import paige.navic.domain.models.genreSeedNames
import paige.navic.domain.models.mixArtistIds
import paige.navic.domain.models.settings.StationCharacter
import paige.navic.domain.repositories.SongRepository
import paige.navic.shared.MediaPlayerViewModel
import paige.navic.ui.core.PlayerUiState
import paige.navic.util.Logger
import kotlin.time.Clock
import kotlin.time.TimeSource

/**
 * Radio, autoplay and the mixes that play through them (I-040).
 *
 * **With the hub's station engine** (navi-connect PROTOCOL §18; [StationManager]) a radio is a
 * station: a track's, an album's (up to five of its tracks), an artist's (up to five of their
 * songs) or Your mix (seedless, from the hub user's listening). The queue is the seed songs and
 * the station's first batch, bound to the station so the hub hears its skips. Autoplay is the
 * station's driver ([observeAutoplay]): when fewer than [STATION_LOOKAHEAD] songs are left in
 * play order it asks for [STATION_BATCH] more, and a queue no station built (an album, a
 * playlist, a journey) continues as a station seeded from its last tracks. A station that has
 * nothing left that fits says so, and autoplay stops.
 *
 * **Without it** (no hub, an older hub, the engine down) radio is Navidrome's similar songs as it
 * always was, and autoplay is [AutoDj], a port of upstream Feishin's. No path here tops a queue
 * up with whole-library random songs except Auto DJ's own last step, which is upstream's.
 *
 * [isAudioMuseMix] is true while the queue is a station's, or exactly the last queue generated
 * here when AudioMuse chose its songs ([wearsAudioMuseColour]): the mini player's AudioMuse colour.
 */
class RadioManager(
	private val sessionManager: SessionManager,
	private val songDao: SongDao,
	private val mediaPlayer: MediaPlayerViewModel,
	private val preferenceManager: PreferenceManager,
	private val hubManager: HubManager,
	private val audioMuseManager: AudioMuseManager,
	private val stationManager: StationManager,
	private val songRepository: SongRepository
) {
	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

	/** The last queue generated here without a station: its song ids joined, and what it is. */
	private val generated = MutableStateFlow<Pair<String, GeneratedQueue>?>(null)

	// True when the server advertises the OpenSubsonic `sonicSimilarity` extension (i.e. the
	// AudioMuse plugin is loaded): gates Song Journey and makes the no-hub radio and Auto DJ
	// prefer guaranteed-AudioMuse results. Re-probed on login.
	private val _sonicSimilarityAvailable = MutableStateFlow(false)
	val sonicSimilarityAvailable: StateFlow<Boolean> = _sonicSimilarityAvailable.asStateFlow()

	private val _autoplayOn = MutableStateFlow(preferenceManager.autoplayOn)

	/** Autoplay On / Off. Mirrors `PreferenceManager.autoplayOn`; write through [setAutoplayOn]. */
	val autoplayOn: StateFlow<Boolean> = _autoplayOn.asStateFlow()

	fun setAutoplayOn(on: Boolean) {
		preferenceManager.autoplayOn = on
		_autoplayOn.value = on
	}

	private val _character = MutableStateFlow(preferenceManager.stationCharacter)

	/** The character new stations start with (the preference). */
	val character: StateFlow<StationCharacter> = _character.asStateFlow()

	/** Whether the hub's engine can be asked right now. */
	val stationsAvailable: StateFlow<Boolean> get() = stationManager.available

	val catalogue: StateFlow<StationCatalogue> get() = stationManager.catalogue

	private val autoDj = AutoDj(songDao, preferenceManager) { id, count -> fetchSimilar(id, count) }

	// Re-evaluates the autoplay driver without a player change: a retry after a partial answer,
	// a character change, Explore.
	private val driverKick = MutableStateFlow(0)

	/** A chip asked the driver to look: that one look decides paused too ([AutoplayLook.chipTap]). */
	private val chipLook = MutableStateFlow(false)

	// Reference-cached: uiState emits ~5x/sec during playback with the same
	// queue list instance; only recompute the signature when it changes.
	private var sigCacheQueue: List<DomainSong>? = null
	private var sigCacheValue = ""

	private fun queueSig(queue: List<DomainSong>): String {
		if (queue !== sigCacheQueue) {
			sigCacheQueue = queue
			sigCacheValue = queue.joinToString(",") { it.id }
		}
		return sigCacheValue
	}

	/** The station the queue on screen belongs to: this device's queue, or the session another device plays. */
	private fun boundNow(): StationProvenance? =
		if (hubManager.isRemoteActive.value) stationManager.remoteBound()
		else stationManager.boundFor(mediaPlayer.localUiState.value)

	private fun generatorNow(): QueueGenerator? {
		val remote = hubManager.isRemoteActive.value
		val queueId = if (remote) hubManager.remoteSession.value.savedQueueId
		else mediaPlayer.localUiState.value.savedQueueId
		val shown = mediaPlayer.uiState.value.queue
		return queueGenerator(
			bound = boundNow(),
			queueId = queueId,
			autoDjQueueId = if (remote) null else stationManager.autoDjQueueId.value,
			generated = generated.value?.takeIf { shown.isNotEmpty() && queueSig(shown) == it.first }?.second
		)
	}

	private val generatorInputs: List<Flow<Any?>> = listOf(
		mediaPlayer.steadyState, stationManager.provenance, stationManager.autoDjQueueId,
		hubManager.stationFrames, hubManager.isRemoteActive, generated, stationManager.available,
		hubManager.remoteSession.map { it.stationId to it.savedQueueId }.distinctUntilChanged()
	)

	/**
	 * What built the queue on screen, for the queue sheet's line: the station's name and
	 * character, "via Navidrome" when only Navidrome answered, Auto DJ, nothing left that fits,
	 * or a queue generated here by its own name. Read from the station, from what topped the
	 * queue up and from what generated it, never from the autoplay setting.
	 */
	val queueGenerator: StateFlow<QueueGenerator?> = combine(generatorInputs) { generatorNow() }
		.distinctUntilChanged()
		.stateIn(scope, SharingStarted.WhileSubscribed(5_000), null)

	/** A station request (or an Auto DJ top-up) is out. */
	val busy: StateFlow<Boolean> get() = stationManager.busy

	val isAudioMuseMix: StateFlow<Boolean> = queueGenerator
		.map { wearsAudioMuseColour(it) }
		.stateIn(scope, SharingStarted.WhileSubscribed(5_000), false)

	/**
	 * The station bound to the queue on screen (this device's record, or the session's while another
	 * device plays), for the staged queue's groups (P2): its served list says which songs are autoplay's.
	 */
	val boundStation: StateFlow<StationProvenance?> = combine(generatorInputs) { boundNow() }
		.distinctUntilChanged()
		.stateIn(scope, SharingStarted.WhileSubscribed(5_000), null)

	/** The queue sheet's autoplay chips (I-041): which are offered and which is selected. */
	data class ChipsUi(val chips: List<AutoplayChip>, val selected: AutoplayChip)

	val chips: StateFlow<ChipsUi> = combine(
		combine(generatorInputs) { boundNow() }, _autoplayOn, _character, stationManager.available
	) { bound, on, character, available ->
		ChipsUi(autoplayChips(available), selectedAutoplayChip(available, on, character, bound))
	}
		.distinctUntilChanged()
		.stateIn(
			scope,
			SharingStarted.WhileSubscribed(5_000),
			ChipsUi(autoplayChips(false), if (preferenceManager.autoplayOn) AutoplayChip.AutoDj else AutoplayChip.Off)
		)

	/**
	 * Station work that edits the queue, one at a time: the driver's top-ups and the chips' stop,
	 * steer and swap. Two of them at once could add a batch twice or swap songs a top-up was
	 * appending (I-041).
	 */
	private val stationOps = Mutex()

	/**
	 * A tap on an autoplay chip ([planChipTap]). Off: autoplay off, the bound station stopped for
	 * every device and the songs it added after the next one taken out. A character: autoplay on
	 * with it; a bound station on another character (or stopped) is steered, the songs it added
	 * after the next one are swapped for a fresh batch at once, and the top-up decision runs now.
	 * Never the playing song or the next one, never a reload.
	 */
	fun onChipTap(chip: AutoplayChip) {
		val bound = boundNow()
		val remote = hubManager.isRemoteActive.value
		val session = hubManager.remoteSession.value
		val onHub = chipEditsOnHub(
			stationEdits = hubManager.hubOffers(HUB_FEATURE_STATION_EDITS),
			bound = bound,
			sessionStationId = session.stationId,
			sessionQueueId = session.savedQueueId,
			queueId = if (remote) session.savedQueueId else mediaPlayer.localUiState.value.savedQueueId,
			playsSession = !remote && bindSessionFor() != null
		)
		val plan = planChipTap(chip, bound, onHub)
		setAutoplayOn(plan.autoplayOn)
		plan.character?.let {
			preferenceManager.stationCharacter = it
			_character.value = it
		}
		// What this device keeps about the bound station says it at once, so the selected chip and
		// the queue line follow the tap before the hub's frame does.
		val keep = bound?.takeIf { !remote || stationManager.provenance.value?.stationId == it.stationId }
		if (keep != null && (plan.stopStation || plan.signalCharacter != null)) {
			stationManager.record(
				if (plan.stopStation) keep.copy(stopped = true)
				else keep.copy(character = plan.signalCharacter!!.wireId, stopped = false, exhausted = false)
			)
		}
		val trackId = mediaPlayer.uiState.value.currentSong?.id.orEmpty()
		scope.launch {
			stationOps.withLock {
				stationManager.setBusy(true)
				try {
					if (bound != null && plan.applyOnHub) {
						// The hub edits the session's queue and tells the playing device (S-035):
						// the signal is the whole of this tap here.
						val kind = if (plan.stopStation) "stop" else "character"
						val answer = stationManager.signalEdit(bound.stationId, trackId, kind, plan.signalCharacter?.wireId)
						Logger.i("RadioManager", "chip $chip: hub edit ${answer?.edited ?: "none"} ${answer?.reason.orEmpty()}")
					} else if (bound != null) {
						if (plan.stopStation) stationManager.signal(bound.stationId, trackId, "stop")
						plan.signalCharacter?.let {
							stationManager.signal(bound.stationId, trackId, "character", it.wireId)
						}
						val latest = boundNow()?.takeIf { it.stationId == bound.stationId } ?: bound
						if (plan.removeTail) removeStationTail(latest)
						if (plan.replaceTail) replaceStationTail(latest)
					}
				} catch (e: Exception) {
					Logger.e("RadioManager", "autoplay chip $chip failed", e)
				} finally {
					stationManager.setBusy(false)
				}
			}
			if (plan.runDecision) {
				// Paused counts: the look this asks for decides whether or not the music plays.
				chipLook.value = true
				driverKick.update { it + 1 }
			}
		}
	}

	/**
	 * The staged queue's autoplay switch (P2): a tap on the chip it stands for ([autoplayToggleChip]),
	 * so the plan, the hub edit and the signals are the chips' own, by one path.
	 */
	fun onAutoplayToggle(on: Boolean) {
		onChipTap(autoplayToggleChip(on, stationManager.available.value, _character.value))
	}

	/** The queue as the chip edits see it: queue ids, play order, playing index, ms left of the playing song. */
	private data class Upcoming(val ids: List<String>, val order: List<Int>?, val current: Int, val remainingMs: Long?)

	private fun upcomingOf(remote: Boolean): Upcoming =
		if (remote) {
			val session = hubManager.remoteSession.value
			val playing = session.tracks.getOrNull(session.index)
			val position = session.positionMs +
				if (session.isPlaying) (Clock.System.now().toEpochMilliseconds() - session.positionAtMs).coerceAtLeast(0) else 0L
			Upcoming(
				session.tracks.map { it.id }, session.order, session.index,
				playing?.durationMs?.takeIf { it > 0 }?.let { it - position }
			)
		} else {
			val state = mediaPlayer.localUiState.value
			Upcoming(
				state.queue.map { it.id }, mediaPlayer.playOrder.value, state.currentIndex,
				state.currentSong?.duration?.inWholeMilliseconds?.takeIf { it > 0 }
					?.let { (it * (1f - state.progress.coerceIn(0f, 1f))).toLong() }
			)
		}

	/** Off: take out what [bound] added that is still to come. Locally an in-place edit; remotely `remove` acts. */
	private suspend fun removeStationTail(bound: StationProvenance) {
		val remote = hubManager.isRemoteActive.value
		val up = upcomingOf(remote)
		val keepNext = chipEditKeepsNext(up.remainingMs)
		val tail = stationTailIndices(up.ids, up.order, up.current, bound.served, keepNext)
		if (tail.isEmpty()) return
		if (remote) {
			// Highest first, so each index still names the song it was computed for. Never a
			// `setQueue`: on the playing device that is a load, a gap in the music.
			tail.forEach { hubManager.actRemoveQueueItem(it) }
		} else {
			mediaPlayer.editUpcoming(tail, emptyList(), keepNext = keepNext)
		}
		val removed = tail.map { up.ids[it] }.toSet()
		stationManager.provenance.value?.takeIf { it.stationId == bound.stationId }?.let { mine ->
			stationManager.record(mine.copy(served = mine.served.filterNot { it in removed }))
		}
	}

	/**
	 * A character chip on a bound station this device edits: swap what it added that is still to
	 * come for a fresh batch now, in the new character, rather than at the next top-up. The answer
	 * arrives first; with nothing in it the old songs stay.
	 */
	private suspend fun replaceStationTail(bound: StationProvenance) {
		val remote = hubManager.isRemoteActive.value
		val up = upcomingOf(remote)
		val keepNext = chipEditKeepsNext(up.remainingMs)
		val ids = up.ids
		val tail = stationTailIndices(ids, up.order, up.current, bound.served, keepNext)
		if (tail.isEmpty()) return
		val result = stationManager.next(bound.stationId, bound.seq, STATION_BATCH, ids)
		val answer = (result as? StationResult.Ok)?.answer ?: run {
			Logger.i("RadioManager", "chip swap: $result, the songs stay")
			return
		}
		if (answer.servedElsewhere) {
			markServedElsewhere()
			return
		}
		val removed = tail.map { ids[it] }.toSet()
		val kept = ids.toSet() - removed
		val batch = stationBatch(bound, answer, kept, fresh = false)
		val songs = resolveIds(batch.append).filter { it.id !in kept }
		if (songs.isEmpty()) {
			stationManager.record(batch.provenance)
			return
		}
		if (remote) {
			tail.forEach { hubManager.actRemoveQueueItem(it) }
			hubManager.enqueueSessionQueue(songs)
		} else {
			// Where the first of them stood: the removals are all after it, so it is the same index.
			mediaPlayer.editUpcoming(tail, songs, insertAt = tail.min(), keepNext = keepNext)
		}
		stationManager.record(batch.provenance.copy(served = batch.provenance.served.filterNot { it in removed }))
	}

	// ---- Radio ------------------------------------------------------------------------------ //

	/**
	 * Starting any radio turns autoplay back on, with the character it had (I-041): a radio is
	 * asked for to keep going, and Off stops every station, radios included.
	 */
	private fun radioStarted() = setAutoplayOn(true)

	/** A track's radio: the track, then its station. */
	fun startRadio(song: DomainSong) {
		radioStarted()
		scope.launch {
			try {
				val seed = StationSeed(StationSeed.TRACK, listOf(song.id), song.title)
				when (playStation(seed, listOf(song), _character.value, "${song.title} radio")) {
					StationPlay.Played, StationPlay.Empty -> return@launch
					StationPlay.Unavailable -> {}
				}
				// The old radio: Navidrome's similar songs, nearest first. Never Auto DJ's random
				// steps, which are what a station keeps out of a radio; with nothing similar the
				// tap still plays the song itself.
				val similar = resolveIds(
					runCatching { fetchSimilar(song.id, RADIO_FALLBACK_COUNT) }.getOrDefault(emptyList())
				).filter { it.id != song.id }
				playMix((listOf(song) + similar).distinctBy { it.id }, SavedQueueSource.RADIO, GeneratedQueue("${song.title} radio", audioMuse = false))
			} catch (e: Exception) {
				Logger.e("RadioManager", "failed to start radio for ${song.id}", e)
			}
		}
	}

	/** An album's radio: up to five of its tracks, spread over it, then their station. */
	fun startAlbumRadio(albumId: String, albumName: String) {
		radioStarted()
		scope.launch {
			try {
				val tracks = songDao.getSongsByAlbumId(albumId).map { it.toDomainModel() }
					.sortedWith(compareBy({ it.discNumber ?: 0 }, { it.trackNumber ?: 0 }))
				val seeds = spreadPick(tracks, SEED_TRACKS_MAX)
				if (seeds.isEmpty()) return@launch
				val seed = StationSeed(StationSeed.TRACKS, seeds.map { it.id }, albumName)
				when (playStation(seed, seeds, _character.value, "$albumName radio")) {
					StationPlay.Played, StationPlay.Empty -> return@launch
					StationPlay.Unavailable -> {}
				}
				// `getSimilarSongs2` takes an album id; `getSonicSimilarTracks` does not.
				val similar = resolveIds(
					runCatching { sessionManager.fetchSimilarSongIds(albumId, RADIO_FALLBACK_COUNT) }.getOrDefault(emptyList())
				)
				playMix((seeds + similar).distinctBy { it.id }, SavedQueueSource.RADIO, GeneratedQueue("$albumName radio", audioMuse = false))
			} catch (e: Exception) {
				Logger.e("RadioManager", "failed to start album radio for $albumId", e)
			}
		}
	}

	/**
	 * An artist's radio: up to five of their songs (the most played, from different albums
	 * first), then their station. Never an artist id to a track endpoint: the old artist radio
	 * sent one to `getSonicSimilarTracks`.
	 */
	fun startArtistRadio(artistId: String, artistName: String) {
		radioStarted()
		scope.launch {
			try {
				val songs = songDao.getSongsByArtistId(artistId).map { it.toDomainModel() }
				// One plan for this and an `artist` mix (B-150).
				val plan = artistRadioPlan(stationManager.available.value, artistName, songs)
				if (plan.station != null) {
					when (playStation(plan.station, plan.seeds, _character.value, "$artistName radio")) {
						StationPlay.Played, StationPlay.Empty -> return@launch
						StationPlay.Unavailable -> {}
					}
				}
				val queue = artistSimilarQueue(artistId, plan.seeds, RADIO_FALLBACK_COUNT)
				if (queue.isEmpty()) {
					Logger.i("RadioManager", "no songs for artist radio $artistId")
					return@launch
				}
				playMix(queue, SavedQueueSource.RADIO, GeneratedQueue("$artistName radio", audioMuse = false))
			} catch (e: Exception) {
				Logger.e("RadioManager", "failed to start artist radio for $artistId", e)
			}
		}
	}

	/**
	 * Your mix: the seedless station, from the hub user's most played songs, on the current
	 * character. Without the engine, the Sonic Fingerprint list as before. Returns whether
	 * something plays.
	 */
	suspend fun playYourMix(name: String): Boolean = try {
		radioStarted()
		when (playStation(StationSeed.YourMix, emptyList(), _character.value, name)) {
			StationPlay.Played -> true
			StationPlay.Empty -> false
			StationPlay.Unavailable -> {
				val songs = resolveIds(audioMuseManager.fetchSonicFingerprintIds(RADIO_FALLBACK_COUNT))
				// The Sonic Fingerprint: AudioMuse's list.
				if (songs.isNotEmpty()) playMix(songs, SavedQueueSource.RADIO, GeneratedQueue(name, audioMuse = true))
				songs.isNotEmpty()
			}
		}
	} catch (e: Exception) {
		Logger.e("RadioManager", "failed to start Your mix", e)
		false
	}

	private enum class StationPlay { Played, Empty, Unavailable }

	/** A started station's first songs, not yet loaded: what [playStation] plays, and a mix's page shows. */
	private sealed interface StationStart {
		class Ok(val built: BuiltStation) : StationStart
		data object Empty : StationStart
		data object Unavailable : StationStart
	}

	/**
	 * Start a station from [seed] (origin `radio`: it keeps going whether or not autoplay is on)
	 * and play [seedSongs] followed by its first batch. [Unavailable] when no station could be had,
	 * whatever the reason, fallback, transient or refused (the caller then plays Navidrome's
	 * similar songs at once rather than keep a tap waiting); [Empty] when the station answered but
	 * there is nothing to play.
	 */
	private suspend fun playStation(
		seed: StationSeed,
		seedSongs: List<DomainSong>,
		character: StationCharacter,
		fallbackName: String,
		count: Int = RADIO_START_COUNT,
		variety: String = ""
	): StationPlay = when (val start = startStation(seed, seedSongs, character, fallbackName, count, variety)) {
		is StationStart.Ok -> {
			loadStation(start.built, start.built.queue, 0)
			StationPlay.Played
		}
		StationStart.Empty -> StationPlay.Empty
		StationStart.Unavailable -> StationPlay.Unavailable
	}

	/** [playStation]'s first half: the station started and its songs resolved, nothing loaded. */
	private suspend fun startStation(
		seed: StationSeed,
		seedSongs: List<DomainSong>,
		character: StationCharacter,
		fallbackName: String,
		count: Int,
		variety: String
	): StationStart {
		if (!stationManager.available.value) return StationStart.Unavailable
		stationManager.setBusy(true)
		try {
			val result = stationManager.start(
				seed, character, count, seedSongs.map { it.id }, bindSession = null, origin = ORIGIN_RADIO,
				variety = variety
			)
			val answer = (result as? StationResult.Ok)?.answer ?: run {
				Logger.i("RadioManager", "station start: $result, using the fallback")
				return StationStart.Unavailable
			}
			val seedIds = seedSongs.mapTo(HashSet()) { it.id }
			val tracks = resolveIds(answer.tracks.map { it.id }).filter { it.id !in seedIds }
			val queue = (seedSongs + tracks).distinctBy { it.id }
			if (queue.isEmpty()) {
				Logger.i("RadioManager", "station ${answer.stationId}: nothing to play (exhausted=${answer.exhausted})")
				return StationStart.Empty
			}
			return StationStart.Ok(
				BuiltStation(answer, seed, character, fallbackName.ifBlank { answer.name }, queue, tracks.map { it.id })
			)
		} finally {
			stationManager.setBusy(false)
		}
	}

	/**
	 * [playStation]'s second half: [queue] (the station's songs, in the order to play them) loaded
	 * from [startIndex] as that station's queue, here or where playback is.
	 */
	private fun loadStation(built: BuiltStation, queue: List<DomainSong>, startIndex: Int) {
		val answer = built.answer
		generated.value = null
		val queueId = mediaPlayer.newQueueSessionId()
		// Recorded BEFORE the queue is loaded, so this device's first publish of it already
		// carries the station id (PROTOCOL §18.2) rather than unbinding the session.
		stationManager.record(
			StationProvenance(
				queueId = queueId,
				stationId = answer.stationId,
				name = built.name,
				character = answer.character.ifBlank { built.character.wireId },
				engine = answer.engine,
				seed = answer.seed ?: built.seed,
				seq = answer.seq,
				rev = answer.rev,
				exhausted = answer.exhausted,
				// This device's entry point started it: a radio, also for a hub that echoes nothing.
				origin = stationOrigin(answer.origin, ORIGIN_RADIO),
				stopped = answer.stopped,
				served = built.served
			)
		)
		if (hubManager.isRemoteActive.value) {
			// Played where playback is; the playing device continues the station, by its id.
			hubManager.loadSessionQueue(
				queue,
				startIndex = startIndex,
				savedQueueId = queueId,
				sourceKind = SavedQueueSource.RADIO,
				sourceName = built.name,
				stationId = answer.stationId
			)
		} else {
			mediaPlayer.loadRemoteQueue(
				queue, startIndex, 0L, true,
				savedQueueId = queueId,
				savedQueueKind = SavedQueueSource.RADIO,
				savedQueueName = built.name
			)
		}
	}

	/**
	 * An artist radio without a station: [seeds], then Navidrome's similar songs for the artist
	 * (`getSimilarSongs2` takes an artist id), nearest first. Nothing similar leaves the seeds.
	 */
	private suspend fun artistSimilarQueue(artistId: String, seeds: List<DomainSong>, count: Int): List<DomainSong> {
		val similar = resolveIds(
			runCatching { sessionManager.fetchSimilarSongIds(artistId, count) }.getOrDefault(emptyList())
		)
		return (seeds + similar).distinctBy { it.id }
	}

	// ---- "Mixed for You": regenerating a stored recipe ------------------------------------ //

	/**
	 * Regenerate [mix] and play it.
	 *
	 * With the engine, `station`, `similar`, `fingerprint`, `adaptive` and `artist` play as
	 * stations ([stationRecipe]): `similar` a track radio on Balanced, `fingerprint` and
	 * `adaptive` Your mix (`adaptive` keeping its character), `artist` the artist page's radio
	 * on the recipe's character. Otherwise the recipe runs through [generate], the engines it
	 * always had (an `artist` recipe: the artist radio's similar songs). Returns false when
	 * nothing could be built.
	 */
	suspend fun regenerate(mix: Mix): Boolean {
		radioStarted()
		if (stationBacked(mix)) {
			val recipe = stationRecipe(mix)
			if (recipe != null) {
				val name = mix.name.ifBlank { defaultMixName(SavedQueueSource.RADIO).orEmpty() }
				when (playStation(recipe.seed, recipe.seedSongs, recipe.character, name, mix.count.coerceIn(5, 50), mix.variety)) {
					StationPlay.Played -> {
						hubManager.actTouchMix(mix.id)
						return true
					}
					StationPlay.Empty -> return false
					StationPlay.Unavailable -> {}
				}
			}
		}
		val queue = generate(mix)
		if (queue.isEmpty()) return false

		// Saved to history as a RADIO-kind queue under the mix's own name, so the
		// Continue Listening card says which mix it was, and the queue's line says the same
		// name. The recipe stays the durable thing; this snapshot is just where playback
		// resumes from.
		playMix(queue, SavedQueueSource.RADIO, GeneratedQueue.ofMix(mix))
		hubManager.actTouchMix(mix.id)
		return true
	}

	/**
	 * Whether [mix] plays as a station right now. Such a mix is built as it plays, so the mix
	 * sheet offers no preview of it (a sample of the first batch would not be the mix).
	 */
	fun stationBacked(mix: Mix): Boolean = MixKind.playsAsStation(mix.kind, stationManager.available.value)

	private class StationRecipe(
		val seed: StationSeed,
		val seedSongs: List<DomainSong>,
		val character: StationCharacter
	)

	/** The station a mix plays as, or null when its seed is gone from the library. */
	private suspend fun stationRecipe(mix: Mix): StationRecipe? {
		val stored = StationCharacter.fromWire(mix.character) ?: StationCharacter.fromWire(mix.moodCharacter)
		return when (mix.kind) {
			MixKind.STATION -> {
				val character = stored ?: StationCharacter.Default
				if (mix.seedId.isBlank()) StationRecipe(StationSeed.YourMix, emptyList(), character)
				else {
					val song = songDao.getSongById(mix.seedId)?.toDomainModel()
					StationRecipe(
						StationSeed(StationSeed.TRACK, listOf(mix.seedId), mix.seedName.ifBlank { song?.title }),
						listOfNotNull(song),
						character
					)
				}
			}

			// A song, an album or an artist id: a track radio on Balanced, or the album's or the
			// artist's tracks as the seed.
			MixKind.SIMILAR -> {
				val song = songDao.getSongById(mix.seedId)?.toDomainModel()
				if (song != null) {
					StationRecipe(StationSeed(StationSeed.TRACK, listOf(song.id), song.title), listOf(song), StationCharacter.Balanced)
				} else {
					val album = songDao.getSongsByAlbumId(mix.seedId).map { it.toDomainModel() }
						.sortedWith(compareBy({ it.discNumber ?: 0 }, { it.trackNumber ?: 0 }))
					val seeds = spreadPick(album, SEED_TRACKS_MAX)
						.ifEmpty { artistSeedSongs(songDao.getSongsByArtistId(mix.seedId).map { it.toDomainModel() }) }
					if (seeds.isEmpty()) null
					else StationRecipe(
						StationSeed(StationSeed.TRACKS, seeds.map { it.id }, mix.seedName.ifBlank { null }),
						seeds,
						StationCharacter.Balanced
					)
				}
			}

			MixKind.FINGERPRINT, MixKind.ADAPTIVE ->
				StationRecipe(StationSeed.YourMix, emptyList(), stored ?: StationCharacter.Default)

			// The artist page's radio (B-150), on the recipe's character; a custom radio (P5) is the
			// same from each of its artists' songs, an artist the library no longer holds skipped.
			MixKind.ARTIST -> {
				val ids = mixArtistIds(mix)
				val songs = ids.map { id -> songDao.getSongsByArtistId(id).map { it.toDomainModel() } }
				val name = if (ids.size > 1) mix.name.ifBlank { mix.seedName }
				else mix.seedName.ifBlank { songs.firstOrNull()?.firstOrNull()?.artistName.orEmpty() }
				val plan = customRadioPlan(engine = true, name = name, songsPerArtist = songs)
				plan.station?.let { StationRecipe(it, plan.seeds, stored ?: StationCharacter.Default) }
			}

			else -> null
		}
	}

	/**
	 * Run the recipe and return the tracklist, **without** playing it or touching
	 * `lastPlayedAt`: the engines a mix has without the station engine, and the mix sheet's
	 * preview. A `station` recipe here is its seed's similar songs, or the fingerprint list
	 * for Your mix; an `artist` recipe the artist radio's fallback.
	 *
	 * Deliberately does not `touchMix`: previewing is not playing, and
	 * `lastPlayedAt` is what the list sorts a mix's recency by.
	 */
	suspend fun generate(mix: Mix): List<DomainSong> {
		val count = mix.count.coerceIn(5, 200)
		val songs = try {
			when (mix.kind) {
				// The seed is a song/album/artist id and `fetchSimilar` already prefers
				// AudioMuse when the plugin is there, so this is one branch rather than two.
				MixKind.SIMILAR -> resolveIds(fetchSimilar(mix.seedId, count))

				MixKind.STATION ->
					if (mix.seedId.isBlank()) resolveIds(audioMuseManager.fetchSonicFingerprintIds(count))
					else resolveIds(fetchSimilar(mix.seedId, count))

				// Seedless: built from listening habits.
				MixKind.FINGERPRINT ->
					resolveIds(audioMuseManager.fetchSonicFingerprintIds(count))

				// Seedless, steered by the stored character. Alchemy needs at least one ADD, so
				// a top-played track cold-starts it.
				MixKind.ADAPTIVE -> {
					val character = StationCharacter.fromWire(mix.character)
						?: StationCharacter.fromWire(mix.moodCharacter)
						?: preferenceManager.stationCharacter
					val add = songDao.getTopSongs(1).firstOrNull()?.map { it.songId }.orEmpty()
					if (add.isEmpty()) emptyList() else resolveIds(
						audioMuseManager.fetchAlchemyMixIds(
							add,
							emptyList(),
							count,
							temperature = character.alchemyTemperature,
							subtractDistance = character.alchemySubtractDistance
						)
					)
				}

				// Local, and deliberately so: a genre mix wants breadth across the
				// library rather than a similarity neighbourhood, and it keeps working
				// with no AudioMuse and no network. By name; an older recipe's id is
				// tried first and finds nothing (`genreSeedNames`).
				MixKind.GENRE -> genreSeedNames(mix).firstNotNullOfOrNull { name ->
					songDao.getRandomSongsByGenre(name, count).ifEmpty { null }
				}.orEmpty()
					.map { it.toDomainModel() }
					.shuffled()

				// The artist radio without a station (B-150): their songs as the seed, then
				// Navidrome's similar songs. Never a shuffle of the artist's own catalogue.
				// A custom radio (P5) without a station: each artist's seeds, dealt in turn, then
				// each artist's similar songs, dealt in turn.
				MixKind.ARTIST -> {
					val ids = mixArtistIds(mix)
					val held = ids.map { id -> id to songDao.getSongsByArtistId(id).map { it.toDomainModel() } }
						.filter { it.second.isNotEmpty() }
					if (held.size <= 1) {
						val (id, songs) = held.firstOrNull() ?: (mix.seedId to emptyList())
						val plan = artistRadioPlan(engine = false, artistName = mix.seedName, songs = songs)
						artistSimilarQueue(id, plan.seeds, count).take(count)
					} else {
						val plan = customRadioPlan(engine = false, name = mix.name, songsPerArtist = held.map { it.second })
						val each = (count / held.size).coerceAtLeast(5)
						val similar = held.map { (id, _) ->
							resolveIds(runCatching { sessionManager.fetchSimilarSongIds(id, each) }.getOrDefault(emptyList()))
						}
						(plan.seeds + interleaveSeeds(similar)).distinctBy { it.id }.take(count)
					}
				}

				else -> {
					Logger.w("RadioManager", "mix ${mix.id}: unknown kind '${mix.kind}'")
					return emptyList()
				}
			}.distinctBy { it.id }
		} catch (e: Exception) {
			Logger.e("RadioManager", "failed to regenerate mix ${mix.id}", e)
			return emptyList()
		}
		if (songs.isEmpty()) Logger.i("RadioManager", "mix ${mix.id}: nothing to play")
		return songs
	}

	/**
	 * [mix]'s songs for its page (P5 round 2, the owner's note: a mix opens as a playlist and
	 * plays from there), built afresh on each opening as YouTube Music's mixes are. A mix that
	 * plays as a station starts that station now and shows its first batch, so what plays is what
	 * the page showed and the station carries on after it; any other mix runs its recipe
	 * ([generate]). Null when nothing could be built. Not a play: `lastPlayedAt` is untouched.
	 */
	suspend fun buildMix(mix: Mix, yourMixName: String): MixBuild? {
		val yourMix = mix.id == YOUR_MIX_ID
		if (yourMix || stationBacked(mix)) {
			val recipe = if (yourMix) StationRecipe(StationSeed.YourMix, emptyList(), _character.value)
			else stationRecipe(mix)
			if (recipe != null) {
				val name = if (yourMix) yourMixName
				else mix.name.ifBlank { defaultMixName(SavedQueueSource.RADIO).orEmpty() }
				val count = if (yourMix) MIX_PAGE_COUNT else mix.count.coerceIn(5, 50)
				val variety = if (yourMix) "" else mix.variety
				when (val start = startStation(recipe.seed, recipe.seedSongs, recipe.character, name, count, variety)) {
					is StationStart.Ok -> return MixBuild(mix, start.built.queue, start.built)
					StationStart.Empty -> return null
					StationStart.Unavailable -> {}
				}
			}
		}
		val songs = if (yourMix) {
			resolveIds(runCatching { audioMuseManager.fetchSonicFingerprintIds(RADIO_FALLBACK_COUNT) }.getOrDefault(emptyList()))
		} else generate(mix)
		return songs.takeIf { it.isNotEmpty() }?.let { MixBuild(mix, it, null) }
	}

	/**
	 * Play what [build] holds from [startIndex], or shuffled: a station's songs as that station's
	 * queue (it continues), anything else as the mix's generated queue. Counts as playing the mix.
	 */
	fun playMixBuild(build: MixBuild, startIndex: Int, shuffle: Boolean) {
		if (build.songs.isEmpty()) return
		radioStarted()
		val queue = if (shuffle) build.songs.shuffled() else build.songs
		val start = if (shuffle) 0 else startIndex.coerceIn(0, queue.lastIndex)
		val station = build.station
		if (station != null) loadStation(station, queue, start)
		else if (build.mix.id == YOUR_MIX_ID) {
			playMix(queue, SavedQueueSource.RADIO, GeneratedQueue(build.mix.name, audioMuse = true), start)
		} else playMix(queue, SavedQueueSource.RADIO, GeneratedQueue.ofMix(build.mix), start)
		if (build.mix.id != YOUR_MIX_ID) hubManager.actTouchMix(build.mix.id)
	}

	/** Play a tracklist a preview already built, without running the engine twice. */
	fun playGenerated(mix: Mix, songs: List<DomainSong>) {
		if (songs.isEmpty()) return
		radioStarted()
		playMix(songs, SavedQueueSource.RADIO, GeneratedQueue.ofMix(mix))
		hubManager.actTouchMix(mix.id)
	}

	/** Songs for a list of server ids, in the server's order, dropping what we lack. */
	private suspend fun resolveIds(ids: List<String>): List<DomainSong> {
		if (ids.isEmpty()) return emptyList()
		val byId = songDao.getSongsByIds(ids).associateBy { it.songId }
		return ids.mapNotNull { byId[it]?.toDomainModel() }
	}

	/**
	 * The recipe of what is playing, for "Save as a mix": the station the queue belongs to (a
	 * `station` recipe with its seed track, none for Your mix, and its character), or, with
	 * autoplay on and no station yet, a radio of [seed] on the current character. Null otherwise.
	 */
	fun currentRecipe(seed: DomainSong?): Mix? {
		val bound = boundNow()
		if (bound != null) {
			val character = StationCharacter.fromWire(bound.character) ?: StationCharacter.Default
			val yourMix = bound.seed?.kind == StationSeed.YOUR_MIX
			val seedId = if (yourMix) "" else bound.seed?.ids?.firstOrNull().orEmpty()
			val cover = mediaPlayer.uiState.value.queue.firstOrNull { it.id == seedId }?.coverArtId
			return Mix(
				id = "",
				name = bound.name.ifBlank { seed?.title?.let { "$it radio" }.orEmpty() },
				kind = MixKind.STATION,
				seedId = seedId,
				seedName = if (yourMix) "" else bound.seed?.name.orEmpty(),
				character = character.wireId,
				moodCharacter = character.legacyName,
				coverArtId = cover.orEmpty()
			)
		}
		if (!_autoplayOn.value) return null
		val song = seed ?: return null
		val character = _character.value
		return Mix(
			id = "",
			name = "${song.title} radio",
			kind = MixKind.STATION,
			seedId = song.id,
			seedName = song.title,
			character = character.wireId,
			moodCharacter = character.legacyName,
			// The seed's own sleeve, so the saved recipe has a face.
			coverArtId = song.coverArtId.orEmpty()
		)
	}

	/**
	 * Build and play a sonic "journey" (path) from [fromId] to [toId] via
	 * OpenSubsonic `findSonicPath` — a queue that morphs between the two tracks.
	 * Requires the AudioMuse plugin ([sonicSimilarityAvailable]); the caller is
	 * expected to gate the UI on that. When it ends, autoplay continues it as a
	 * station seeded from its last tracks, like any queue no station built.
	 */
	fun startJourney(fromId: String, toId: String) {
		scope.launch {
			try {
				val ids = sessionManager.findSonicPathIds(fromId, toId)
				val byId = songDao.getSongsByIds(ids).associateBy { it.songId }
				val queue = ids.mapNotNull { byId[it]?.toDomainModel() }.distinctBy { it.id }
				if (queue.isEmpty()) {
					Logger.i("RadioManager", "no path from $fromId to $toId")
					return@launch
				}
				// `findSonicPath` is the AudioMuse plugin's path between two tracks.
				playMix(
					queue, SavedQueueSource.JOURNEY,
					GeneratedQueue("${queue.first().title} → ${queue.last().title}", audioMuse = true)
				)
			} catch (e: Exception) {
				Logger.e("RadioManager", "failed to start journey $fromId -> $toId", e)
			}
		}
	}

	/**
	 * Resolve a CLAP text→mood search (AudioMuse Tier 2) to playable songs WITHOUT
	 * playing — the UI shows them as a proposed queue (Feishin-style) so the user
	 * can review and choose to play ([playMoodMix]) or enqueue. The caller gates the
	 * UI on [AudioMuseManager.isClapAvailable].
	 */
	suspend fun fetchMoodSearchSongs(query: String, count: Int = 100): List<DomainSong> {
		return try {
			val ids = audioMuseManager.fetchClapSearchIds(query, count)
			val byId = songDao.getSongsByIds(ids).associateBy { it.songId }
			// Preserve CLAP order (best match first); drop ids not in the local library.
			ids.mapNotNull { byId[it]?.toDomainModel() }.distinctBy { it.id }
		} catch (e: Exception) {
			Logger.e("RadioManager", "mood search failed for '$query'", e)
			emptyList()
		}
	}

	/**
	 * Songs to show in the queue sheet's "Related" tab: recommendations DERIVED FROM
	 * THE WHOLE QUEUE that are NOT already queued — a browsing surface, so this fetches
	 * without playing (like [fetchMoodSearchSongs]).
	 *
	 * Seeds [fetchSimilar] from a handful of tracks spread across [queue], ranks candidates by
	 * how many of those seeds returned them (songs that fit MULTIPLE queue tracks surface first —
	 * the queue's shared character), and drops everything already queued. Falls back to a
	 * same-genre local draw (also queue-excluded) when the server is offline or every similar
	 * track is already queued.
	 */
	suspend fun fetchQueueRelatedSongs(queue: List<DomainSong>, count: Int = 50): List<DomainSong> {
		if (queue.isEmpty()) return emptyList()
		return try {
			val queued = queue.mapTo(HashSet()) { it.id }
			val state = mediaPlayer.uiState.value
			val nowPlaying = state.currentSong ?: queue.firstOrNull()
			val seeds = pickRelatedSeeds(queue, state.currentIndex)

			// Fetch each seed's neighbours in parallel; a single failing seed shouldn't
			// sink the tab, so swallow per-seed errors to an empty list.
			val perSeed = coroutineScope {
				seeds.map { seed ->
					async { runCatching { fetchSimilar(seed.id, count) }.getOrDefault(emptyList()) }
				}.awaitAll()
			}

			// Rank by cross-seed agreement (int[0] = #seeds that returned this id), tie-broken
			// by first-seen order (int[1]) so a single seed's ranking is still respected.
			val ranks = LinkedHashMap<String, IntArray>()
			var order = 0
			perSeed.forEach { ids ->
				ids.forEach { id ->
					val existing = ranks[id]
					if (existing == null) ranks[id] = intArrayOf(1, order++) else existing[0]++
				}
			}
			val rankedIds = ranks.entries
				.asSequence()
				.filter { it.key !in queued }
				.sortedWith(
					compareByDescending<Map.Entry<String, IntArray>> { it.value[0] }
						.thenBy { it.value[1] }
				)
				.map { it.key }
				.toList()

			val byId = songDao.getSongsByIds(rankedIds).associateBy { it.songId }
			val resolved = rankedIds.mapNotNull { byId[it]?.toDomainModel() }
				.distinctBy { it.id }
				.take(count)

			resolved.ifEmpty { localGenreSongs(nowPlaying, count, queued) }
		} catch (e: Exception) {
			Logger.e("RadioManager", "queue-related fetch failed", e)
			emptyList()
		}
	}

	/**
	 * Up to [RELATED_SEEDS] seed tracks for [fetchQueueRelatedSongs]: the now-playing
	 * track (from [currentIndex]) first, then tracks spread evenly across the queue so
	 * the recommendations reflect the whole queue, not just its head. Deduped by id.
	 */
	private fun pickRelatedSeeds(queue: List<DomainSong>, currentIndex: Int): List<DomainSong> {
		val unique = queue.distinctBy { it.id }
		if (unique.size <= RELATED_SEEDS) return unique
		val seeds = LinkedHashMap<String, DomainSong>()
		queue.getOrNull(currentIndex)?.let { seeds[it.id] = it }
		val step = unique.size.toFloat() / RELATED_SEEDS
		var i = 0f
		while (seeds.size < RELATED_SEEDS && i.toInt() < unique.size) {
			val s = unique[i.toInt()]
			if (!seeds.containsKey(s.id)) seeds[s.id] = s
			i += step
		}
		return seeds.values.toList()
	}

	/**
	 * Play a resolved mood (CLAP) search as the queue (remote-aware — see [playMix]). Saved to
	 * history as an ordinary queue named [name] ("Search: …"): it is a search's results, not a
	 * radio and not anything that continues by itself.
	 */
	fun playMoodMix(songs: List<DomainSong>, name: String) {
		if (songs.isEmpty()) return
		playMix(songs, SavedQueueSource.MANUAL, GeneratedQueue.ofMoodSearch(name))
	}

	/**
	 * Play a generated queue under [what]'s name. It is recorded in [generated], so the queue's
	 * line says that name, crediting AudioMuse only when AudioMuse chose the songs. When another
	 * device is the active receiver, hand the mix to the session so it plays THERE (the hub
	 * forwards a do:load to the active device) rather than starting a conflicting local playback
	 * that desyncs the session.
	 *
	 * Local mixes are saved to queue history as their [kind] (radio / journey / a search) so the
	 * saved-queues list can group them; remote mixes are the hub's session (not local history).
	 */
	private fun playMix(
		songs: List<DomainSong>,
		kind: String,
		what: GeneratedQueue,
		startIndex: Int = 0
	) {
		generated.value = songs.joinToString(",") { it.id } to what
		val name = what.name.ifBlank { defaultMixName(kind) }
		if (hubManager.isRemoteActive.value) {
			// Tag the shared history record with what this mix IS. Without the kind/name the
			// hub recorded remote-started mixes as anonymous "manual" queues.
			hubManager.loadSessionQueue(
				songs,
				startIndex = startIndex,
				sourceKind = kind,
				sourceName = name
			)
		} else {
			// The LOCAL branch needs the name just as much: this path has no source collection at
			// all, so without it every locally started radio / search / journey was saved as an
			// unnamed row and the history read "No name".
			mediaPlayer.loadRemoteQueue(
				songs, startIndex, 0L, true,
				savedQueueId = mediaPlayer.newQueueSessionId(songs),
				savedQueueKind = kind,
				savedQueueName = name
			)
		}
	}

	/** Fallback display name for a generated mix that has no more specific one. */
	private fun defaultMixName(kind: String): String? = when (kind) {
		SavedQueueSource.RADIO -> "Radio"
		SavedQueueSource.JOURNEY -> "Song Journey"
		else -> null
	}

	/** Append a mood mix to the queue — to the session when remote, else locally. */
	fun enqueueMoodMix(songs: List<DomainSong>) {
		if (songs.isEmpty()) return
		if (hubManager.isRemoteActive.value) {
			hubManager.enqueueSessionQueue(songs)
		} else {
			mediaPlayer.appendToQueue(songs)
		}
	}

	/**
	 * Probe the server's OpenSubsonic extensions on (re)login and flip
	 * [sonicSimilarityAvailable] when the AudioMuse sonic plugin is present.
	 */
	private fun observeCapabilities() {
		scope.launch {
			sessionManager.isLoggedIn.collect { loggedIn ->
				if (!loggedIn) {
					_sonicSimilarityAvailable.value = false
					return@collect
				}
				try {
					_sonicSimilarityAvailable.value = sessionManager.fetchOpenSubsonicExtensions()
						.any { it.equals("sonicSimilarity", ignoreCase = true) }
				} catch (e: Exception) {
					Logger.e("RadioManager", "capability probe failed", e)
				}
			}
		}
	}

	/**
	 * Similar-song ids for a SONG id. Prefers `getSonicSimilarTracks` (guaranteed
	 * AudioMuse) when the plugin is present, falling back to `getSimilarSongs2`
	 * (heuristic, or AudioMuse-as-agent) otherwise or on error.
	 */
	private suspend fun fetchSimilar(seedId: String, count: Int): List<String> {
		if (_sonicSimilarityAvailable.value) {
			try {
				val sonic = sessionManager.fetchSonicSimilarTrackIds(seedId, count)
				if (sonic.isNotEmpty()) return sonic
			} catch (e: Exception) {
				Logger.w("RadioManager", "sonic similar failed, falling back", e)
			}
		}
		return sessionManager.fetchSimilarSongIds(seedId, count)
	}

	/** The Related tab's offline draw: same-genre songs, shuffled. Never whole-library random. */
	private suspend fun localGenreSongs(seed: DomainSong?, count: Int, exclude: Set<String>): List<DomainSong> {
		val genre = seed?.let { songDao.getSongById(it.id)?.genre }?.takeIf { it.isNotBlank() } ?: return emptyList()
		return songDao.getRandomSongsByGenre(genre, (count + exclude.size) * 2)
			.map { it.toDomainModel() }
			.filter { it.id !in exclude && it.id != seed.id }
			.distinctBy { it.id }
			.shuffled()
			.take(count)
	}

	// ---- Autoplay: the station driver -------------------------------------------------------- //

	/**
	 * The driver, for the LOCAL player only: while another hub device plays, that device tops up.
	 * Each look at the player goes through [autoplayStep]; a step runs once per trigger (queue,
	 * playing song, songs left, the station's `rev`, the switch), with one request out at a time,
	 * inside this manager's process scope, so it carries on with the screen off for as long as the
	 * playback service lives. With autoplay off it adds nothing, radios included ([autoplayStep]).
	 *
	 * A transient failure is asked again up to [STATION_TRANSIENT_RETRIES] times,
	 * [STATION_TRANSIENT_RETRY_MS] apart, for the same trigger, then waits for the next one; it
	 * never hands the top-up to Auto DJ ([stationFailureAction]).
	 */
	private fun observeAutoplay() {
		scope.launch {
			var lastSig = ""
			var trigger = ""
			var transientAttempts = 0
			combine(
				mediaPlayer.localUiState, mediaPlayer.playOrder, _autoplayOn, stationManager.available, driverKick
			) { _, _, _, _, kick -> kick }
				.collect { kick ->
					// The emission is the cue to look, not what to look at. `combine` hands over
					// values queued while the last step ran, so one combined before that step's
					// songs went in (the queue still on its last song) and paired with the record
					// that step wrote (a new station after a restart) read as a fresh trigger: a
					// `start` at once followed by a `next`, ten songs for one top-up (the restart
					// path, seen on the emulator against a hub that had lost the station). What the
					// player holds NOW is what decides.
					val state = mediaPlayer.localUiState.value
					val order = mediaPlayer.playOrder.value
					val on = _autoplayOn.value
					val available = stationManager.available.value
					val remaining = playOrderRemaining(order, state.queue.size, state.currentIndex)
					val bound = if (available) stationManager.boundFor(state) else null
					// Another device's station for this queue, met while playing it, becomes the
					// record: it is the queue's even after the hub forgets it (B-139).
					adoptedStationRecord(bound, stationManager.provenance.value)?.let { stationManager.record(it) }
					val step = autoplayStep(
						AutoplayLook(
							autoplayOn = on,
							remoteActive = hubManager.isRemoteActive.value,
							paused = state.isPaused,
							repeatMode = state.repeatMode,
							queueSize = state.queue.size,
							currentIndex = state.currentIndex,
							remaining = remaining,
							stationsAvailable = available,
							bound = bound,
							inFlight = stationManager.busy.value,
							autoDjTiming = preferenceManager.autoDjTiming,
							elsewhereRecent = elsewhereRecent(),
							chipTap = chipLook.getAndUpdate { false }
						)
					)
					if (step == AutoplayStep.Idle || step == AutoplayStep.Exhausted) return@collect
					// The trigger is not the cursor: an answer that only moved it (served elsewhere)
					// waits for the next change rather than asking again at once. A like or a
					// character change moves `rev`, which does ask again (it can clear "exhausted").
					// The station's id is part of it: a restart or a continuation that names a new
					// station at the same moment is a new trigger only once its songs are in.
					val triggerKey = "${state.savedQueueId}:${state.currentSong?.id}:$remaining:" +
						"${step::class.simpleName}:${bound?.stationId}:${bound?.rev}:$on"
					if (triggerKey != trigger) {
						trigger = triggerKey
						transientAttempts = 0
					}
					val sig = "$triggerKey:$kick"
					if (sig == lastSig) return@collect
					// A chip's stop, steer or swap is editing the queue: look again once it is done
					// (it asks for that look itself).
					if (!stationOps.tryLock()) return@collect
					lastSig = sig
					stationManager.setBusy(true)
					val retry = try {
						runStep(step, state, order, on, transientAttempts)
					} catch (e: Exception) {
						Logger.e("RadioManager", "autoplay $step failed", e)
						null
					} finally {
						stationManager.setBusy(false)
						stationOps.unlock()
					}
					when (retry) {
						DriverRetry.Transient -> {
							transientAttempts++
							scheduleRetry(STATION_TRANSIENT_RETRY_MS)
						}
						DriverRetry.Later -> scheduleRetry(RETRY_MS)
						DriverRetry.Publishing -> scheduleRetry(STATION_PUBLISH_RETRY_MS)
						null -> {}
					}
				}
		}
	}

	/** What a step leaves for later: a transient failure's retry, or a partial answer's. */
	private enum class DriverRetry { Transient, Later, Publishing }

	private suspend fun runStep(
		step: AutoplayStep,
		state: PlayerUiState,
		order: List<Int>?,
		autoplayOn: Boolean,
		transientAttempts: Int
	): DriverRetry? = when (step) {
		is AutoplayStep.Next -> continueStation(state, step.stationId, step.after, autoplayOn, transientAttempts)
		AutoplayStep.Start -> startFromQueue(state, order, transientAttempts)
		AutoplayStep.AutoDj -> autoDjTopUp(state)
		AutoplayStep.Idle, AutoplayStep.Exhausted -> null
	}

	private fun queueIdsOf(state: PlayerUiState): List<String> = state.queue.map { it.id }

	/** The same queue is still playing: an answer for a queue that has since been replaced is dropped. */
	private fun stillPlaying(state: PlayerUiState): Boolean {
		val now = mediaPlayer.localUiState.value
		return now.queue.isNotEmpty() && now.savedQueueId == state.savedQueueId &&
			!hubManager.isRemoteActive.value
	}

	/** Act on a `start` / `next` that did not succeed ([stationFailureAction]). */
	private suspend fun onStationFailure(
		result: StationResult,
		state: PlayerUiState,
		autoplayOn: Boolean,
		transientAttempts: Int,
		restart: suspend () -> DriverRetry?,
		continueSession: suspend (String) -> DriverRetry?
	): DriverRetry? {
		val action = stationFailureAction(result, autoplayOn, transientAttempts)
		Logger.i("RadioManager", "station: $result -> $action")
		return when (action) {
			StationFailureAction.RetrySoon -> DriverRetry.Transient
			StationFailureAction.WaitForNextTrigger -> null
			StationFailureAction.AutoDj -> autoDjTopUp(state)
			StationFailureAction.Restart -> restart()
			StationFailureAction.ContinueSessionStation ->
				(result as? StationResult.SessionBound)?.let { continueSession(it.stationId) }
		}
	}

	private suspend fun continueStation(
		state: PlayerUiState,
		stationId: String,
		after: Int,
		autoplayOn: Boolean,
		transientAttempts: Int
	): DriverRetry? {
		val bound = stationManager.boundFor(state)?.takeIf { it.stationId == stationId } ?: return null
		return when (val result = stationManager.next(stationId, after, STATION_BATCH, queueIdsOf(state))) {
			is StationResult.Ok -> applyBatch(state, bound, result.answer)
			// Expired, or a hub that lost it: start again once, from the seed kept here.
			else -> onStationFailure(
				result, state, autoplayOn, transientAttempts,
				restart = { restartStation(state, bound, autoplayOn) },
				continueSession = { continueSessionStation(state, it, bound.seed) }
			)
		}
	}

	/**
	 * Bring back a station the hub lost, as itself: its own seed, or its last songs under its own
	 * name when the seed is not known here, in its own character ([stationRestart], B-139).
	 */
	private suspend fun restartStation(state: PlayerUiState, bound: StationProvenance, autoplayOn: Boolean): DriverRetry? {
		val recent = playOrderRecent(mediaPlayer.playOrder.value, state.queue.size, state.currentIndex, SEED_TRACKS_FROM_QUEUE)
			.mapNotNull { state.queue.getOrNull(it)?.id }
			.filterNot { PreviewManager.isPreviewId(it) || it.startsWith("radio_") }
			.distinct()
		val restart = stationRestart(bound, recent, state.currentSong?.title)
			?: return if (autoplayOn) autoDjTopUp(state) else null
		val character = restart.character ?: _character.value
		val bind = bindSessionFor()
		return when (
			val result = stationManager.start(restart.seed, character, STATION_BATCH, queueIdsOf(state), bind, restart.origin)
		) {
			is StationResult.Ok -> applyBatch(
				state,
				bound.copy(
					seq = 0,
					rev = 0,
					seed = bound.seed ?: restart.seed,
					origin = stationOrigin(result.answer.origin, restart.origin)
				),
				result.answer,
				fresh = true,
				askedToBind = bind != null
			)
			is StationResult.SessionBound -> continueSessionStation(state, result.stationId, restart.seed)
			// Restarted once; whatever this answer was, the next trigger decides again.
			is StationResult.Fallback -> if (autoplayOn) autoDjTopUp(state) else null
			else -> null
		}
	}

	/**
	 * A queue no station built, near its end, with autoplay on: it continues as a station seeded
	 * from its last tracks (origin `continue`), bound to the session when this device plays it.
	 */
	private suspend fun startFromQueue(state: PlayerUiState, order: List<Int>?, transientAttempts: Int): DriverRetry? {
		val current = state.currentSong ?: return null
		val seedIds = playOrderRecent(order, state.queue.size, state.currentIndex, SEED_TRACKS_FROM_QUEUE)
			.mapNotNull { state.queue.getOrNull(it)?.id }
			.filterNot { PreviewManager.isPreviewId(it) || it.startsWith("radio_") }
			.distinct()
		if (seedIds.isEmpty()) return autoDjTopUp(state)
		val seed = StationSeed(StationSeed.TRACKS, seedIds, current.title)
		val queueId = state.savedQueueId
			?: hubManager.remoteSession.value.savedQueueId
			?: mediaPlayer.newQueueSessionId(state.queue)
		mediaPlayer.adoptQueueSessionId(queueId)
		val queued = state.copy(savedQueueId = queueId)
		val bind = bindSessionFor()
		// The session still names the queue before this one: this queue's `setQueue` has not
		// landed. Bound to that id the start continued nothing (`bound: false`), or, with the old
		// queue still a station's, got that station back as a 409. Look again in a moment.
		if (!startMayBind(bind, queueId)) return DriverRetry.Publishing
		return when (
			val result = stationManager.start(seed, _character.value, STATION_BATCH, queueIdsOf(state), bind, ORIGIN_CONTINUE)
		) {
			is StationResult.Ok -> {
				val fresh = StationProvenance(
					queueId = queueId,
					stationId = result.answer.stationId,
					character = _character.value.wireId,
					seed = seed,
					origin = stationOrigin(result.answer.origin, ORIGIN_CONTINUE)
				)
				applyBatch(queued, fresh, result.answer, fresh = true, askedToBind = bind != null)
			}
			else -> onStationFailure(
				result, queued, autoplayOn = true, transientAttempts = transientAttempts,
				restart = { null },
				// Another device got there first: the session has a station, so this queue continues it.
				continueSession = { continueSessionStation(queued, it, seed) }
			)
		}
	}

	/**
	 * The session already has a station [stationId] (a 409 `session_bound`): this queue is that
	 * station's now. Another device bound it, most likely by topping it up a moment ago, and that
	 * batch reaches this queue through the session. So the station is kept here (its seed too, for
	 * a restart) and the driver looks again after [STATION_ELSEWHERE_GRACE_MS], from the cursor the
	 * frames then hold. Asking `next` at once, and again with the cursor a `servedElsewhere`
	 * answer named, added a second batch for the same top-up (the walk's two-device scenario).
	 */
	private fun continueSessionStation(
		state: PlayerUiState,
		stationId: String,
		seed: StationSeed?
	): DriverRetry? {
		val queueId = state.savedQueueId ?: return null
		val frame = hubManager.stationFrames.value[stationId]
		stationManager.record(
			StationProvenance(
				queueId = queueId,
				stationId = stationId,
				name = frame?.name ?: hubManager.remoteSession.value.sourceName.orEmpty(),
				// The station's own, from its frame; not yet known when none has come (B-139: this
				// device's preference here named another device's station wrongly).
				character = frame?.character.orEmpty(),
				seed = frame?.seed ?: seed,
				seq = frame?.seq ?: 0,
				origin = frame?.origin
			)
		)
		markServedElsewhere()
		return null
	}

	/** When another device last topped up the bound station, for [STATION_ELSEWHERE_GRACE_MS]. */
	private var elsewhereAt: TimeSource.Monotonic.ValueTimeMark? = null

	private fun elsewhereRecent(): Boolean =
		elsewhereAt?.let { it.elapsedNow().inWholeMilliseconds < STATION_ELSEWHERE_GRACE_MS } ?: false

	/** Another device served the bound station's next batch: wait for it to land, then look again. */
	private fun markServedElsewhere() {
		elsewhereAt = TimeSource.Monotonic.markNow()
		scheduleRetry(STATION_ELSEWHERE_GRACE_MS + 500)
	}

	/**
	 * A `station` frame that moves the bound station's cursor while this device asked nothing is
	 * another device's top-up (Feishin's `stationFrame` rule): this device waits for that batch
	 * rather than asking from the new cursor and adding a second one.
	 */
	private fun observeElsewhere() {
		scope.launch {
			val seen = HashMap<String, Int>()
			hubManager.stationFrames.collect { frames ->
				val bound = stationManager.boundFor(mediaPlayer.localUiState.value)
				val before = bound?.let { seen[it.stationId] }
				frames.forEach { (id, frame) -> seen[id] = frame.seq }
				val now = bound?.let { frames[it.stationId] } ?: return@collect
				if (before != null && now.seq > before && !stationManager.busy.value) markServedElsewhere()
			}
		}
	}

	/** Append a station's answer to the queue it was asked for, and keep its cursor. */
	private suspend fun applyBatch(
		state: PlayerUiState,
		bound: StationProvenance,
		answer: StationAnswer,
		fresh: Boolean = false,
		askedToBind: Boolean = false
	): DriverRetry? {
		if (!stillPlaying(state)) {
			Logger.i("RadioManager", "station answer for a queue no longer playing, dropped")
			return null
		}
		if (answer.servedElsewhere) markServedElsewhere()
		val queued = mediaPlayer.localUiState.value.queue.mapTo(HashSet()) { it.id }
		val batch = stationBatch(bound, answer, queued, fresh, askedToBind)
		val songs = resolveIds(batch.append).filter { it.id !in queued }
		// The record first: the append republishes the queue, and the publish names the station.
		stationManager.record(batch.provenance)
		// Awaited: the driver's next look must see these songs, or it asks for another batch for
		// the same top-up (a `start` followed at once by a `next`, 10 songs for one top-up).
		if (songs.isNotEmpty()) mediaPlayer.appendToQueueAndWait(songs)
		// `partial`, every track already queued, or none of them in this library: ask again at
		// the next trigger, and at the latest in a little while, so a queue on its last song
		// does not just end.
		return if (batch.retrySoon || (songs.isEmpty() && batch.append.isNotEmpty())) DriverRetry.Later else null
	}

	private suspend fun autoDjTopUp(state: PlayerUiState): DriverRetry? {
		val current = state.currentSong ?: return null
		val picks = autoDj.collect(current, state.queue.mapTo(HashSet()) { it.id })
		if (!stillPlaying(state)) return null
		if (picks.isEmpty()) return DriverRetry.Later
		mediaPlayer.appendToQueueAndWait(picks)
		stationManager.recordAutoDj(state.savedQueueId)
		return null
	}

	/** `bindSession` for a `start`: the session's saved queue, while this device plays the session. */
	private fun bindSessionFor(): String? {
		val me = hubManager.myDeviceId.value ?: return null
		if (!hubManager.connected.value || hubManager.activeDeviceId.value != me) return null
		return hubManager.remoteSession.value.savedQueueId
	}

	private var retryScheduled = false

	private fun scheduleRetry(delayMs: Long) {
		if (retryScheduled) return
		retryScheduled = true
		scope.launch {
			delay(delayMs)
			retryScheduled = false
			driverKick.update { it + 1 }
		}
	}

	// ---- Likes ------------------------------------------------------------------------------- //

	/**
	 * A star given or taken back while a station is bound steers it (`signal like|unlike`): stars
	 * go straight to Navidrome, and the hub hears of them only from here. Only for a song in the
	 * queue on screen; the hub ignores one its station never served.
	 */
	private fun observeLikes() {
		scope.launch {
			songRepository.starChanges.collect { change ->
				try {
					val bound = boundNow() ?: return@collect
					if (mediaPlayer.uiState.value.queue.none { it.id == change.songId }) return@collect
					stationManager.signal(bound.stationId, change.songId, if (change.starred) "like" else "unlike")
				} catch (e: Exception) {
					Logger.w("RadioManager", "like signal failed", e)
				}
			}
		}
	}

	// MUST stay after all property declarations: these launch coroutines on
	// Dispatchers.Default that read fields above (e.g. _autoplayOn), and an
	// init block higher up would race construction and read them as null.
	init {
		observeAutoplay()
		observeElsewhere()
		observeCapabilities()
		observeLikes()
	}

	private companion object {
		/** A radio's first batch. */
		const val RADIO_START_COUNT = 20
		/** The no-engine radio's similar songs. */
		const val RADIO_FALLBACK_COUNT = 50
		/** A queue continued as a station is seeded from its last 1–3 tracks. */
		const val SEED_TRACKS_FROM_QUEUE = 3
		/** How long after a partial or empty answer the driver looks again by itself. */
		const val RETRY_MS = 15_000L
		// How many queue tracks to seed the "Related" tab from (see fetchQueueRelatedSongs).
		const val RELATED_SEEDS = 5
		/** Your mix's songs on its page (a stored mix shows its own count). */
		const val MIX_PAGE_COUNT = 50
	}
}
