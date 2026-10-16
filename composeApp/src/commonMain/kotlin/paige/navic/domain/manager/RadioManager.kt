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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import paige.navic.data.database.dao.SongDao
import paige.navic.data.database.mappers.toDomainModel
import paige.navic.domain.models.DomainSong
import paige.navic.domain.models.Mix
import paige.navic.domain.models.MixKind
import paige.navic.domain.models.SavedQueueSource
import paige.navic.domain.models.settings.StationCharacter
import paige.navic.domain.repositories.SongRepository
import paige.navic.shared.MediaPlayerViewModel
import paige.navic.ui.core.PlayerUiState
import paige.navic.util.Logger

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
 * [isAudioMuseMix] is true while the queue is a station's or exactly the last mix generated here:
 * the mini player's AudioMuse colour.
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

	/** Ids of the last mix generated here without a station, joined. */
	private val mixSig = MutableStateFlow<String?>(null)

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
		val sig = mixSig.value
		val shown = mediaPlayer.uiState.value.queue
		return queueGenerator(
			bound = boundNow(),
			queueId = queueId,
			autoDjQueueId = if (remote) null else stationManager.autoDjQueueId.value,
			generatedMix = sig != null && shown.isNotEmpty() && queueSig(shown) == sig
		)
	}

	private val generatorInputs: List<Flow<Any?>> = listOf(
		mediaPlayer.steadyState, stationManager.provenance, stationManager.autoDjQueueId,
		hubManager.stationFrames, hubManager.isRemoteActive, mixSig, stationManager.available,
		hubManager.remoteSession.map { it.stationId to it.savedQueueId }.distinctUntilChanged()
	)

	/**
	 * What built the queue on screen, for the queue sheet's line: the station's name and
	 * character, "via Navidrome" when only Navidrome answered, Auto DJ, or nothing left that fits.
	 * Read from the station and from what topped the queue up, never from the autoplay setting.
	 */
	val queueGenerator: StateFlow<QueueGenerator?> = combine(generatorInputs) { generatorNow() }
		.distinctUntilChanged()
		.stateIn(scope, SharingStarted.WhileSubscribed(5_000), null)

	/** The character on show: the bound station's, else the one new stations start with. */
	val shownCharacter: StateFlow<StationCharacter> = combine(
		combine(generatorInputs) { boundNow()?.character }, _character
	) { bound, preferred -> StationCharacter.fromWire(bound) ?: preferred }
		.distinctUntilChanged()
		.stateIn(scope, SharingStarted.WhileSubscribed(5_000), preferenceManager.stationCharacter)

	/**
	 * The queue on screen is a radio that keeps going whether or not autoplay is on (a bound
	 * station of origin `radio`, not exhausted): the settings' Off wording, and the character
	 * button, which still steers it.
	 */
	val radioKeepsGoing: StateFlow<Boolean> = combine(generatorInputs) {
		boundNow()?.let { it.effectiveOrigin == ORIGIN_RADIO && !it.exhausted } ?: false
	}
		.distinctUntilChanged()
		.stateIn(scope, SharingStarted.WhileSubscribed(5_000), false)

	/** A station request (or an Auto DJ top-up) is out. */
	val busy: StateFlow<Boolean> get() = stationManager.busy

	val isAudioMuseMix: StateFlow<Boolean> = queueGenerator
		.map { it is QueueGenerator.Station || it is QueueGenerator.Exhausted || it is QueueGenerator.AudioMuseMix }
		.stateIn(scope, SharingStarted.WhileSubscribed(5_000), false)

	/**
	 * Change the character: what new stations start with, and the bound station's from its next
	 * batch (a `character` signal). Steering the station playing is the point of the control, so
	 * the queue on screen stays as it is.
	 */
	fun setCharacter(character: StationCharacter) {
		preferenceManager.stationCharacter = character
		_character.value = character
		val bound = boundNow() ?: return
		if (bound.character == character.wireId && !bound.exhausted) return
		val mine = stationManager.provenance.value?.takeIf { it.stationId == bound.stationId }
		if (mine != null) stationManager.record(mine.copy(character = character.wireId, exhausted = false))
		val trackId = mediaPlayer.uiState.value.currentSong?.id.orEmpty()
		scope.launch {
			stationManager.signal(bound.stationId, trackId, "character", character.wireId)
			driverKick.update { it + 1 }
		}
	}

	/** The exhausted line's one tap: widen the station to Explore, which clears "exhausted" on the hub. */
	fun explore() = setCharacter(StationCharacter.Explore)

	// ---- Radio ------------------------------------------------------------------------------ //

	/** A track's radio: the track, then its station. */
	fun startRadio(song: DomainSong) {
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
				playMix((listOf(song) + similar).distinctBy { it.id }, SavedQueueSource.RADIO, "${song.title} radio")
			} catch (e: Exception) {
				Logger.e("RadioManager", "failed to start radio for ${song.id}", e)
			}
		}
	}

	/** An album's radio: up to five of its tracks, spread over it, then their station. */
	fun startAlbumRadio(albumId: String, albumName: String) {
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
				playMix((seeds + similar).distinctBy { it.id }, SavedQueueSource.RADIO, "$albumName radio")
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
		scope.launch {
			try {
				val songs = songDao.getSongsByArtistId(artistId).map { it.toDomainModel() }
				val seeds = artistSeedSongs(songs)
				if (seeds.isNotEmpty()) {
					val seed = StationSeed(StationSeed.TRACKS, seeds.map { it.id }, artistName)
					when (playStation(seed, seeds, _character.value, "$artistName radio")) {
						StationPlay.Played, StationPlay.Empty -> return@launch
						StationPlay.Unavailable -> {}
					}
				}
				// `getSimilarSongs2` takes an artist id.
				val similar = resolveIds(
					runCatching { sessionManager.fetchSimilarSongIds(artistId, RADIO_FALLBACK_COUNT) }.getOrDefault(emptyList())
				)
				val queue = (seeds + similar).distinctBy { it.id }
				if (queue.isEmpty()) {
					Logger.i("RadioManager", "no songs for artist radio $artistId")
					return@launch
				}
				playMix(queue, SavedQueueSource.RADIO, "$artistName radio")
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
		when (playStation(StationSeed.YourMix, emptyList(), _character.value, name)) {
			StationPlay.Played -> true
			StationPlay.Empty -> false
			StationPlay.Unavailable -> {
				val songs = resolveIds(audioMuseManager.fetchSonicFingerprintIds(RADIO_FALLBACK_COUNT))
				if (songs.isNotEmpty()) playMix(songs, SavedQueueSource.RADIO, name)
				songs.isNotEmpty()
			}
		}
	} catch (e: Exception) {
		Logger.e("RadioManager", "failed to start Your mix", e)
		false
	}

	private enum class StationPlay { Played, Empty, Unavailable }

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
		count: Int = RADIO_START_COUNT
	): StationPlay {
		if (!stationManager.available.value) return StationPlay.Unavailable
		stationManager.setBusy(true)
		try {
			val result = stationManager.start(
				seed, character, count, seedSongs.map { it.id }, bindSession = null, origin = ORIGIN_RADIO
			)
			val answer = (result as? StationResult.Ok)?.answer ?: run {
				Logger.i("RadioManager", "station start: $result, using the fallback")
				return StationPlay.Unavailable
			}
			val seedIds = seedSongs.mapTo(HashSet()) { it.id }
			val tracks = resolveIds(answer.tracks.map { it.id }).filter { it.id !in seedIds }
			val queue = (seedSongs + tracks).distinctBy { it.id }
			if (queue.isEmpty()) {
				Logger.i("RadioManager", "station ${answer.stationId}: nothing to play (exhausted=${answer.exhausted})")
				return StationPlay.Empty
			}
			val name = fallbackName.ifBlank { answer.name }
			mixSig.value = null
			val queueId = mediaPlayer.newQueueSessionId()
			// Recorded BEFORE the queue is loaded, so this device's first publish of it already
			// carries the station id (PROTOCOL §18.2) rather than unbinding the session.
			stationManager.record(
				StationProvenance(
					queueId = queueId,
					stationId = answer.stationId,
					name = name,
					character = answer.character.ifBlank { character.wireId },
					engine = answer.engine,
					seed = answer.seed ?: seed,
					seq = answer.seq,
					rev = answer.rev,
					exhausted = answer.exhausted,
					// This device's entry point started it: a radio, also for a hub that echoes nothing.
					origin = stationOrigin(answer.origin, ORIGIN_RADIO)
				)
			)
			if (hubManager.isRemoteActive.value) {
				// Played where playback is; the playing device continues the station, by its id.
				hubManager.loadSessionQueue(
					queue,
					savedQueueId = queueId,
					sourceKind = SavedQueueSource.RADIO,
					sourceName = name,
					stationId = answer.stationId
				)
			} else {
				mediaPlayer.loadRemoteQueue(
					queue, 0, 0L, true,
					savedQueueId = queueId,
					savedQueueKind = SavedQueueSource.RADIO,
					savedQueueName = name
				)
			}
			return StationPlay.Played
		} finally {
			stationManager.setBusy(false)
		}
	}

	/** Up to [SEED_TRACKS_MAX] of an artist's songs: the most played, one per album first. */
	private fun artistSeedSongs(songs: List<DomainSong>): List<DomainSong> {
		val ranked = songs.sortedByDescending { it.playCount }
		val firstPerAlbum = ranked.distinctBy { it.albumId ?: it.id }
		return (firstPerAlbum + ranked).distinctBy { it.id }.take(SEED_TRACKS_MAX)
	}

	// ---- "Mixed for You": regenerating a stored recipe ------------------------------------ //

	/**
	 * Regenerate [mix] and play it.
	 *
	 * With the engine, `station`, `similar`, `fingerprint` and `adaptive` play as stations
	 * ([stationRecipe]): `similar` a track radio on Balanced, `fingerprint` and `adaptive` Your mix
	 * (`adaptive` keeping its character). Otherwise the recipe runs through [generate], the
	 * engines it always had. Returns false when nothing could be built.
	 */
	suspend fun regenerate(mix: Mix): Boolean {
		if (stationBacked(mix)) {
			val recipe = stationRecipe(mix)
			if (recipe != null) {
				val name = mix.name.ifBlank { defaultMixName(SavedQueueSource.RADIO).orEmpty() }
				when (playStation(recipe.seed, recipe.seedSongs, recipe.character, name, mix.count.coerceIn(5, 50))) {
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
		// Continue Listening card says which mix it was. The recipe stays the durable
		// thing; this snapshot is just where playback resumes from.
		playMix(queue, SavedQueueSource.RADIO, mix.name.ifBlank { defaultMixName(SavedQueueSource.RADIO) })
		hubManager.actTouchMix(mix.id)
		return true
	}

	/**
	 * Whether [mix] plays as a station right now. Such a mix is built as it plays, so the mix
	 * sheet offers no preview of it (a sample of the first batch would not be the mix).
	 */
	fun stationBacked(mix: Mix): Boolean = stationManager.available.value && mix.kind in STATION_KINDS

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

			else -> null
		}
	}

	/**
	 * Run the recipe and return the tracklist, **without** playing it or touching
	 * `lastPlayedAt`: the engines a mix has without the station engine, and the mix sheet's
	 * preview. A `station` recipe here is its seed's similar songs, or the fingerprint list
	 * for Your mix.
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
				// with no AudioMuse and no network.
				MixKind.GENRE -> songDao.getRandomSongsByGenre(mix.seedId, count)
					.map { it.toDomainModel() }
					.shuffled()

				MixKind.ARTIST -> songDao.getSongsByArtistId(mix.seedId)
					.map { it.toDomainModel() }
					.shuffled()
					.take(count)

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

	/** Play a tracklist a preview already built, without running the engine twice. */
	fun playGenerated(mix: Mix, songs: List<DomainSong>) {
		if (songs.isEmpty()) return
		playMix(songs, SavedQueueSource.RADIO, mix.name.ifBlank { defaultMixName(SavedQueueSource.RADIO) })
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
				playMix(
					queue, SavedQueueSource.JOURNEY,
					"${queue.first().title} → ${queue.last().title}"
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
		playMix(songs, SavedQueueSource.MANUAL, name)
	}

	/**
	 * Play a generated mix as the queue (sets [mixSig] so the AudioMuse indicator
	 * lights). When another device is the active receiver, hand the mix to the
	 * session so it plays THERE (the hub forwards a do:load to the active device)
	 * rather than starting a conflicting local playback that desyncs the session.
	 *
	 * Local mixes are saved to queue history as their [kind] (radio / journey / a search) so the
	 * saved-queues list can group them; remote mixes are the hub's session (not local history).
	 */
	private fun playMix(
		songs: List<DomainSong>,
		kind: String = SavedQueueSource.RADIO,
		name: String? = null
	) {
		mixSig.value = songs.joinToString(",") { it.id }
		if (hubManager.isRemoteActive.value) {
			// Tag the shared history record with what this mix IS. Without the kind/name the
			// hub recorded remote-started mixes as anonymous "manual" queues.
			hubManager.loadSessionQueue(
				songs,
				sourceKind = kind,
				sourceName = name ?: defaultMixName(kind)
			)
		} else {
			// The LOCAL branch needs the name just as much: this path has no source collection at
			// all, so without it every locally started radio / search / journey was saved as an
			// unnamed row and the history read "No name".
			mediaPlayer.loadRemoteQueue(
				songs, 0, 0L, true,
				savedQueueId = mediaPlayer.newQueueSessionId(songs),
				savedQueueKind = kind,
				savedQueueName = name ?: defaultMixName(kind)
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
	 * playback service lives. It runs with autoplay off too: a radio keeps going regardless, and
	 * [autoplayStep] decides what the switch stops.
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
			) { state, order, on, available, kick -> AutoplayInput(state, order, on, available, kick) }
				.collect { input ->
					val state = input.state
					val remaining = playOrderRemaining(input.order, state.queue.size, state.currentIndex)
					val bound = if (input.available) stationManager.boundFor(state) else null
					val step = autoplayStep(
						AutoplayLook(
							autoplayOn = input.on,
							remoteActive = hubManager.isRemoteActive.value,
							paused = state.isPaused,
							repeatMode = state.repeatMode,
							queueSize = state.queue.size,
							currentIndex = state.currentIndex,
							remaining = remaining,
							stationsAvailable = input.available,
							bound = bound,
							inFlight = stationManager.busy.value,
							autoDjTiming = preferenceManager.autoDjTiming
						)
					)
					if (step == AutoplayStep.Idle || step == AutoplayStep.Exhausted) return@collect
					// The trigger is not the cursor: an answer that only moved it (served elsewhere)
					// waits for the next change rather than asking again at once. A like or a
					// character change moves `rev`, which does ask again (it can clear "exhausted").
					val triggerKey = "${state.savedQueueId}:${state.currentSong?.id}:$remaining:" +
						"${step::class.simpleName}:${bound?.rev}:${input.on}"
					if (triggerKey != trigger) {
						trigger = triggerKey
						transientAttempts = 0
					}
					val sig = "$triggerKey:${input.kick}"
					if (sig == lastSig) return@collect
					lastSig = sig
					stationManager.setBusy(true)
					val retry = try {
						runStep(step, state, input.order, input.on, transientAttempts)
					} catch (e: Exception) {
						Logger.e("RadioManager", "autoplay $step failed", e)
						null
					} finally {
						stationManager.setBusy(false)
					}
					when (retry) {
						DriverRetry.Transient -> {
							transientAttempts++
							scheduleRetry(STATION_TRANSIENT_RETRY_MS)
						}
						DriverRetry.Later -> scheduleRetry(RETRY_MS)
						null -> {}
					}
				}
		}
	}

	private data class AutoplayInput(
		val state: PlayerUiState,
		val order: List<Int>?,
		val on: Boolean,
		val available: Boolean,
		val kick: Int
	)

	/** What a step leaves for later: a transient failure's retry, or a partial answer's. */
	private enum class DriverRetry { Transient, Later }

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
				continueSession = { continueSessionStation(state, it, bound.seed, autoplayOn) }
			)
		}
	}

	/** Start again from the recorded seed, with the station's own origin and character. */
	private suspend fun restartStation(state: PlayerUiState, bound: StationProvenance, autoplayOn: Boolean): DriverRetry? {
		val seed = bound.seed
		if (seed == null) {
			// A station this device has no seed for (another device started it): only autoplay
			// may carry the queue on from its own last songs.
			return if (autoplayOn) startFromQueue(state, mediaPlayer.playOrder.value, STATION_TRANSIENT_RETRIES) else null
		}
		val character = StationCharacter.fromWire(bound.character) ?: _character.value
		val bind = bindSessionFor()
		val origin = bound.effectiveOrigin
		return when (
			val result = stationManager.start(seed, character, STATION_BATCH, queueIdsOf(state), bind, origin)
		) {
			is StationResult.Ok -> applyBatch(
				state,
				bound.copy(seq = 0, rev = 0, origin = stationOrigin(result.answer.origin, origin)),
				result.answer,
				fresh = true,
				askedToBind = bind != null
			)
			is StationResult.SessionBound -> continueSessionStation(state, result.stationId, seed, autoplayOn)
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
				continueSession = { continueSessionStation(queued, it, seed, autoplayOn = true) }
			)
		}
	}

	/** Continue the session's station [stationId] from the cursor the hub last broadcast. */
	private suspend fun continueSessionStation(
		state: PlayerUiState,
		stationId: String,
		seed: StationSeed?,
		autoplayOn: Boolean
	): DriverRetry? {
		val queueId = state.savedQueueId ?: return null
		val frame = hubManager.stationFrames.value[stationId]
		var bound = StationProvenance(
			queueId = queueId,
			stationId = stationId,
			name = frame?.name ?: hubManager.remoteSession.value.sourceName.orEmpty(),
			character = frame?.character?.ifBlank { null } ?: _character.value.wireId,
			seed = frame?.seed ?: seed,
			seq = frame?.seq ?: 0,
			origin = frame?.origin
		)
		var result = stationManager.next(stationId, bound.seq, STATION_BATCH, queueIdsOf(state))
		// The cursor was learned second-hand; the answer names the real one. Once.
		val answer = (result as? StationResult.Ok)?.answer
		if (answer != null && answer.servedElsewhere && answer.seq > bound.seq) {
			bound = bound.copy(seq = answer.seq)
			result = stationManager.next(stationId, bound.seq, STATION_BATCH, queueIdsOf(state))
		}
		return when (result) {
			is StationResult.Ok -> applyBatch(state, bound, result.answer, fresh = true)
			is StationResult.Transient -> DriverRetry.Transient
			is StationResult.Fallback -> if (autoplayOn) autoDjTopUp(state) else null
			else -> null
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
		val queued = mediaPlayer.localUiState.value.queue.mapTo(HashSet()) { it.id }
		val batch = stationBatch(bound, answer, queued, fresh, askedToBind)
		val songs = resolveIds(batch.append).filter { it.id !in queued }
		// The record first: the append republishes the queue, and the publish names the station.
		stationManager.record(batch.provenance)
		if (songs.isNotEmpty()) mediaPlayer.appendToQueue(songs)
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
		mediaPlayer.appendToQueue(picks)
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
		observeCapabilities()
		observeLikes()
	}

	private companion object {
		/** A radio's first batch. */
		const val RADIO_START_COUNT = 20
		/** The no-engine radio's similar songs. */
		const val RADIO_FALLBACK_COUNT = 50
		/** An album or artist radio's seed tracks (PROTOCOL §18.1: 1–5). */
		const val SEED_TRACKS_MAX = 5
		/** A queue continued as a station is seeded from its last 1–3 tracks. */
		const val SEED_TRACKS_FROM_QUEUE = 3
		/** How long after a partial or empty answer the driver looks again by itself. */
		const val RETRY_MS = 15_000L
		// How many queue tracks to seed the "Related" tab from (see fetchQueueRelatedSongs).
		const val RELATED_SEEDS = 5

		/** The kinds that play as a station when the engine is there. */
		val STATION_KINDS = setOf(MixKind.STATION, MixKind.SIMILAR, MixKind.FINGERPRINT, MixKind.ADAPTIVE)
	}
}
