package paige.navic.ui.screens.mixes.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import paige.navic.data.database.dao.ArtistDao
import paige.navic.data.database.dao.GenreDao
import paige.navic.domain.manager.HubManager
import paige.navic.domain.manager.PreferenceManager
import paige.navic.domain.manager.RadioManager
import paige.navic.domain.models.DomainSong
import paige.navic.domain.models.Mix
import paige.navic.domain.models.MixKind
import paige.navic.domain.models.settings.MoodCharacter
import paige.navic.shared.MediaPlayerViewModel

/**
 * One choosable seed, flattened out of whatever table it came from.
 *
 * The two seeded kinds draw from different tables and store different things in
 * [Mix.seedId] — an artist id for [MixKind.ARTIST], a genre **name** for
 * [MixKind.GENRE], because that is what `getRandomSongsByGenre` takes. Flattening
 * them here is what lets the dialog hold one picker instead of two.
 */
data class MixSeedOption(
	val id: String,
	val name: String,
	val coverArtId: String = ""
)

/** What the list screen needs to say after an action that can fail silently. */
enum class MixMessage { RegenerateFailed, UnknownKind, NotConnected }

/**
 * Mixed for You.
 *
 * Thin on purpose: the recipes live on the hub and the engine lives in
 * [RadioManager], so this owns neither. What it does own is the one piece of state
 * that is genuinely local — **which mix is currently being regenerated** — because
 * a regeneration is a round trip to AudioMuse or Navidrome and a row with no
 * feedback reads as a dead tap.
 *
 * Nothing here caches the list. It is a hub [StateFlow], and a local copy would go
 * stale the moment another client created a mix.
 */
class MixListViewModel(
	private val hubManager: HubManager,
	private val radioManager: RadioManager,
	private val mediaPlayer: MediaPlayerViewModel,
	private val artistDao: ArtistDao,
	private val genreDao: GenreDao,
	private val preferenceManager: PreferenceManager
) : ViewModel() {

	/**
	 * Most recently updated first — the same ordering the hub evicts by, so the row
	 * at the bottom of this list is visibly the one that would go next.
	 */
	val mixes: StateFlow<List<Mix>> = hubManager.mixes
		.map { list -> list.sortedByDescending { it.updatedAt } }
		.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

	val connected: StateFlow<Boolean> = hubManager.connected

	private val _busyId = MutableStateFlow<String?>(null)
	val busyId: StateFlow<String?> = _busyId.asStateFlow()

	private val _message = MutableStateFlow<MixMessage?>(null)
	val message: StateFlow<MixMessage?> = _message.asStateFlow()

	/**
	 * The hub's own words when it refuses a write, shown in preference to anything
	 * here. It answers rather than dropping precisely so the screen that claimed the
	 * save can take it back.
	 */
	private val _hubMessage = MutableStateFlow<String?>(null)
	val hubMessage: StateFlow<String?> = _hubMessage.asStateFlow()

	init {
		viewModelScope.launch {
			hubManager.mixErrors.collect { _hubMessage.value = it }
		}
	}

	/**
	 * Artists and genres to seed a new recipe from, read once when the form first
	 * asks for them.
	 *
	 * Local reads, and deliberately so: `regenerate` resolves both against Room
	 * anyway (`getSongsByArtistId` / `getRandomSongsByGenre`), so offering a seed
	 * the library does not hold would build a recipe that can never produce a
	 * queue. Loaded on demand rather than in `init` because the overwhelmingly
	 * common visit to this screen plays an existing mix and never opens the form.
	 */
	private val _seedArtists = MutableStateFlow<List<MixSeedOption>>(emptyList())
	val seedArtists: StateFlow<List<MixSeedOption>> = _seedArtists.asStateFlow()

	private val _seedGenres = MutableStateFlow<List<MixSeedOption>>(emptyList())
	val seedGenres: StateFlow<List<MixSeedOption>> = _seedGenres.asStateFlow()

	fun loadSeedOptions() {
		if (_seedArtists.value.isNotEmpty() || _seedGenres.value.isNotEmpty()) return
		viewModelScope.launch {
			_seedArtists.value = artistDao.getArtistsAlphabeticalByName().map {
				MixSeedOption(id = it.artistId, name = it.name, coverArtId = it.coverArtId.orEmpty())
			}
			// By name, which is what `Mix.seedId` holds for a genre recipe.
			_seedGenres.value = genreDao.getAllGenreNames()
				.filter { it.isNotBlank() }
				.map { MixSeedOption(id = it, name = it) }
		}
	}

	/**
	 * What is playing, for the form's `similar` seed.
	 *
	 * `steadyState`, not `uiState` — the latter re-stamps progress about five times
	 * a second, which would recompose the open form for the whole of playback.
	 */
	val playingSong: StateFlow<DomainSong?> = mediaPlayer.steadyState
		.map { it.currentSong }
		.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

	/** The mood character to offer as the default on a new Adaptive recipe. */
	val defaultMoodCharacter: MoodCharacter get() = preferenceManager.moodCharacter

	fun clearMessage() { _message.value = null }

	fun clearHubMessage() { _hubMessage.value = null }

	/**
	 * The recipe implied by the autoplay settings in force right now, or null.
	 *
	 * A flow rather than a function, because both inputs change while this screen is
	 * open: switching autoplay mode from the player, or the track advancing under a
	 * Similar recipe, both change what "save what's playing" would save. Read as a
	 * plain `.value` during composition it would have gone stale silently, and the
	 * button's appearance would have depended on an unrelated recomposition.
	 *
	 * `steadyState`, not `uiState`: the latter re-stamps `progress` about five times
	 * a second, which would re-derive this — and recompose the screen — for the whole
	 * of playback.
	 */
	val currentRecipe: StateFlow<Mix?> = combine(
		radioManager.autoplayMode,
		mediaPlayer.steadyState
	) { _, state -> radioManager.currentRecipe(state.currentSong) }
		.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

	/**
	 * A mix's tracks, built but not played.
	 *
	 * Its own state rather than a parameter on the sheet, because the build is a
	 * round trip to AudioMuse or Navidrome and the sheet has to open immediately —
	 * a tap that shows nothing for several seconds reads as a dead control, which
	 * is the same reason `busyId` exists for the play path.
	 */
	private val _previewing = MutableStateFlow<Mix?>(null)
	val previewing: StateFlow<Mix?> = _previewing.asStateFlow()

	private val _previewSongs = MutableStateFlow<List<DomainSong>>(emptyList())
	val previewSongs: StateFlow<List<DomainSong>> = _previewSongs.asStateFlow()

	private val _previewLoading = MutableStateFlow(false)
	val previewLoading: StateFlow<Boolean> = _previewLoading.asStateFlow()

	fun preview(mix: Mix) {
		if (MixKind.fromWire(mix.kind) == null) {
			_message.value = MixMessage.UnknownKind
			return
		}
		_previewing.value = mix
		_previewSongs.value = emptyList()
		_previewLoading.value = true
		viewModelScope.launch {
			try {
				_previewSongs.value = radioManager.generate(mix)
			} finally {
				_previewLoading.value = false
			}
		}
	}

	fun dismissPreview() {
		_previewing.value = null
		_previewSongs.value = emptyList()
		_previewLoading.value = false
	}

	/**
	 * Play what the preview already built, rather than running the engine a second
	 * time — which would produce a *different* tracklist from the one on screen and
	 * make the preview a lie.
	 */
	fun playPreviewed() {
		val mix = _previewing.value ?: return
		radioManager.playGenerated(mix, _previewSongs.value)
	}

	fun enqueuePreviewed() {
		radioManager.enqueueMoodMix(_previewSongs.value)
	}

	fun play(mix: Mix) {
		if (_busyId.value != null) return
		if (MixKind.fromWire(mix.kind) == null) {
			_message.value = MixMessage.UnknownKind
			return
		}
		_busyId.value = mix.id
		viewModelScope.launch {
			try {
				if (!radioManager.regenerate(mix)) _message.value = MixMessage.RegenerateFailed
			} finally {
				// In a `finally` because cancelling the scope mid-regeneration (the
				// user navigating away) would otherwise leave the row spinning
				// forever the next time this screen is opened.
				_busyId.value = null
			}
		}
	}

	/**
	 * Create or update. Every write goes through the hub and comes back as a
	 * broadcast, so nothing is written locally and nothing needs reconciling —
	 * which is also why an action taken while disconnected says so rather than
	 * appearing to work.
	 */
	fun save(recipe: Mix, name: String) {
		if (!hubManager.connected.value) {
			_message.value = MixMessage.NotConnected
			return
		}
		hubManager.actSaveMix(
			name = name.ifBlank { recipe.name },
			kind = recipe.kind,
			id = recipe.id.ifBlank { null },
			seedId = recipe.seedId,
			seedName = recipe.seedName,
			moodCharacter = recipe.moodCharacter,
			count = recipe.count,
			coverArtId = recipe.coverArtId
		)
	}

	fun rename(id: String, name: String) {
		if (!hubManager.connected.value) {
			_message.value = MixMessage.NotConnected
			return
		}
		hubManager.actRenameMix(id, name)
	}

	fun delete(id: String) {
		if (!hubManager.connected.value) {
			_message.value = MixMessage.NotConnected
			return
		}
		hubManager.actDeleteMix(id)
	}
}
