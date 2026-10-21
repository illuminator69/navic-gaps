package paige.navic.ui.screens.home

import paige.navic.ui.screens.fresh.viewmodels.FreshFilters
import paige.navic.domain.manager.LbFreshFeed
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import navic.composeapp.generated.resources.Res
import navic.composeapp.generated.resources.mood_play_short
import navic.composeapp.generated.resources.mood_unavailable_title
import paige.navic.data.database.dao.SongDao
import paige.navic.domain.manager.HomeMoodSelection
import paige.navic.domain.manager.MixState
import paige.navic.domain.manager.MoodCatalogue
import paige.navic.domain.manager.MoodEntry
import paige.navic.domain.manager.MoodManager
import paige.navic.domain.manager.MoodVariant
import paige.navic.domain.manager.RadioManager
import paige.navic.domain.manager.SnackBarManager
import paige.navic.domain.manager.clear
import paige.navic.domain.manager.tap
import paige.navic.ui.screens.home.mood.drawMoodMix
import paige.navic.data.database.entities.SavedQueueEntity
import paige.navic.domain.manager.ConnectivityManager
import paige.navic.domain.manager.HubManager
import paige.navic.domain.manager.LbBotManager
import paige.navic.domain.manager.LbCachePolicy
import paige.navic.domain.manager.PreferenceManager
import paige.navic.domain.manager.StationManager
import paige.navic.domain.manager.SyncManager
import paige.navic.domain.models.DomainAlbum
import paige.navic.domain.models.DomainAlbumListType
import paige.navic.domain.repositories.AlbumRepository
import paige.navic.domain.repositories.SavedQueueRepository
import paige.navic.shared.MediaPlayerViewModel
import paige.navic.ui.core.UiState

/**
 * Home's state (P1). A root page's ViewModel, which dies on a tab switch unless it opts out, so
 * `HomeScreen` obtains it through `PersistentViewModelStoreOwner` (CLAUDE.md §2 rule 14) and
 * [revalidate] runs on every visit: `init` alone would show the first answers of the session forever.
 *
 * It reads what already exists and adds no request of its own: the newest, recently played and most
 * played albums from Room, the saved queues from Room, the hub's mixes, and Fresh from `LbBotManager.freshReleases`, the call (and the cache key) the Fresh
 * page makes, so opening Home and then Fresh costs lb-bot one read, not two. The pure decisions are
 * `HomeModels.kt`.
 */
class HomeViewModel(
	private val albumRepository: AlbumRepository,
	private val lbBotManager: LbBotManager,
	savedQueueRepository: SavedQueueRepository,
	stationManager: StationManager,
	syncManager: SyncManager,
	private val preferenceManager: PreferenceManager,
	hubManager: HubManager,
	private val player: MediaPlayerViewModel,
	connectivityManager: ConnectivityManager,
	private val moodManager: MoodManager,
	private val songDao: SongDao,
	private val radioManager: RadioManager,
	private val snackBarManager: SnackBarManager
) : ViewModel() {
	private val isOnline = connectivityManager.isOnline

	// --- Recently added ---------------------------------------------------------------------------

	private val _recent = MutableStateFlow<UiState<ImmutableList<DomainAlbum>>>(UiState.Loading())

	/** The newest albums in the library (Room). Loading until the first read; an error keeps what was there. */
	val recent: StateFlow<UiState<ImmutableList<DomainAlbum>>> = _recent.asStateFlow()

	// --- Recently played and Frequently played (P6) -----------------------------------------------

	private val _played = MutableStateFlow<List<DomainAlbum>>(emptyList())

	/** Navidrome's recently played albums (Room). Room answers at once, so there is no placeholder. */
	val played: StateFlow<List<DomainAlbum>> = _played.asStateFlow()

	private val _frequent = MutableStateFlow<List<DomainAlbum>>(emptyList())

	/** Navidrome's most played albums (Room). */
	val frequent: StateFlow<List<DomainAlbum>> = _frequent.asStateFlow()

	// --- Fresh ------------------------------------------------------------------------------------

	private val _fresh = MutableStateFlow<List<FreshTile>>(emptyList())

	/** New releases by artists the library holds. Never emptied by a failed read. */
	val fresh: StateFlow<List<FreshTile>> = _fresh.asStateFlow()

	private val _freshLoading = MutableStateFlow(false)

	/** A read is in flight and there is nothing to show yet: the shelf shows tinted placeholders. */
	val freshLoading: StateFlow<Boolean> = _freshLoading.asStateFlow()

	/** The lb-bot layer is known to be there. Absent, Fresh is not drawn at all, with no gap. */
	val freshAvailable: StateFlow<Boolean> = lbBotManager.available

	// --- Continue listening and Your mix ----------------------------------------------------------

	/** Every saved queue, newest first (Room). The page narrows them with [continueListening]. */
	val queues: StateFlow<List<SavedQueueEntity>> = savedQueueRepository.observeAll()
		.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

	/**
	 * The queue playing now. What is playing may be on ANOTHER device, in which case only the hub
	 * session knows the queue's identity (the Library home's rule): the session's while the hub is
	 * connected, else this device's own.
	 */
	val playingQueueId: StateFlow<String?> = combine(
		hubManager.connected,
		hubManager.remoteSession.map { it.savedQueueId }.distinctUntilChanged(),
		player.localUiState.map { it.savedQueueId }.distinctUntilChanged()
	) { connected, hubQueueId, localQueueId ->
		(if (connected) hubQueueId else null) ?: localQueueId
	}.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

	/** No song is loaded on this device: the pill starts open. */
	val nothingPlaying: StateFlow<Boolean> = player.steadyState
		.map { it.currentSong == null }
		.distinctUntilChanged()
		.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), player.steadyState.value.currentSong == null)


	/** Your mixes (P6): Your mix while the engine is there, then every mix the hub holds ([mixTiles]). */
	val mixTiles: StateFlow<List<MixTile>> = combine(hubManager.mixes, stationManager.available) { mixes, engine ->
		mixTiles(mixes, engine)
	}.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

	/** The library is being pulled right now: a page with nothing in it yet is waiting, not empty. */
	val syncing: StateFlow<Boolean> = syncManager.syncState
		.map { it.isSyncing }
		.distinctUntilChanged()
		.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), syncManager.syncState.value.isSyncing)

	// --- Moods (P7) --------------------------------------------------------------------------------

	/** The hub's mood catalogue (cached, then the hub's answer on each visit). */
	val moodCatalogue: StateFlow<MoodCatalogue> = moodManager.catalogue

	private val _moodSelection = MutableStateFlow(HomeMoodSelection())

	/** The selected chip. In this view model only, so a tab switch keeps it and a restart forgets it. */
	val moodSelection: StateFlow<HomeMoodSelection> = _moodSelection.asStateFlow()

	fun tapMood(id: String) = _moodSelection.update { it.tap(id) }

	fun clearMood() = _moodSelection.update { it.clear() }

	private val _moodBusy = MutableStateFlow<String?>(null)

	/** The tile ("<mood>/<tile>") whose play button is drawing its mix. */
	val moodBusy: StateFlow<String?> = _moodBusy.asStateFlow()

	/**
	 * A tile's play button: one draw, played as the page would have played it, without opening the
	 * page. A draw the library cannot fill, or a hub that could not search, is said in a snackbar.
	 */
	fun playMoodTile(mood: MoodEntry, variant: MoodVariant) {
		if (_moodBusy.value != null) return
		_moodBusy.value = "${mood.id}/${variant.id}"
		viewModelScope.launch {
			try {
				val draw = drawMoodMix(moodManager, songDao, mood, variant.id)
				when (val state = draw.state) {
					is MixState.Ready -> radioManager.playMoodTile(draw.songs, variant.title, mood.id, shuffle = false)
					is MixState.Short -> snackBarManager.notify(Res.string.mood_play_short, state.found)
					MixState.Unavailable, MixState.Loading -> snackBarManager.notify(Res.string.mood_unavailable_title)
				}
			} catch (e: CancellationException) {
				throw e
			} catch (_: Exception) {
				snackBarManager.notify(Res.string.mood_unavailable_title)
			} finally {
				_moodBusy.value = null
			}
		}
	}

	// --- An album's long-press sheet --------------------------------------------------------------

	private val _selectedAlbum = MutableStateFlow<DomainAlbum?>(null)

	/** The album whose menu is open (a long press on a Recently added tile), the library lists' own sheet. */
	val selectedAlbum: StateFlow<DomainAlbum?> = _selectedAlbum.asStateFlow()

	private val _starred = MutableStateFlow(false)
	val starred: StateFlow<Boolean> = _starred.asStateFlow()

	private val _rating = MutableStateFlow(0)
	val rating: StateFlow<Int> = _rating.asStateFlow()

	fun selectAlbum(album: DomainAlbum) {
		viewModelScope.launch {
			_selectedAlbum.value = album
			_starred.value = albumRepository.isAlbumStarred(album)
			_rating.value = albumRepository.getAlbumRating(album)
		}
	}

	fun clearSelection() {
		_selectedAlbum.value = null
	}

	fun starAlbum(isStarred: Boolean) {
		viewModelScope.launch {
			val selection = _selectedAlbum.value ?: return@launch
			runCatching {
				if (isStarred) albumRepository.starAlbum(selection) else albumRepository.unstarAlbum(selection)
				_starred.value = isStarred
			}
		}
	}

	fun setRating(newRating: Int) {
		viewModelScope.launch {
			val selection = _selectedAlbum.value ?: return@launch
			runCatching {
				_rating.value = newRating
				albumRepository.rateAlbum(selection, newRating)
			}
		}
	}

	private var freshJob: Job? = null

	init {
		// lb-bot answering later than the page opened (the hub connects after the first frame): Fresh
		// is read then, not on the next visit.
		viewModelScope.launch {
			lbBotManager.available.collect { available -> if (available) loadFresh() }
		}
	}

	/** The last Fresh feed read, so a visit can redraw the shelf under the page's filters as they are now. */
	private var lastFeed: LbFreshFeed? = null

	/** Every visit. A Room read, and one cached-or-revalidated Fresh read where lb-bot is there. */
	fun revalidate() {
		loadRecent()
		loadPlayed()
		viewModelScope.launch { moodManager.refresh() }
		// The Fresh page's scope, type or sort may have changed since: the shelf follows at once.
		lastFeed?.let { _fresh.value = freshShelf(it, filters = FreshFilters.of(preferenceManager)) }
		viewModelScope.launch {
			if (lbBotManager.ensureAvailability()) loadFresh()
		}
	}

	private fun loadRecent() {
		viewModelScope.launch {
			// What is on screen stays on screen while the read runs (a parameter change keeps what is shown).
			_recent.value = UiState.Loading(_recent.value.data)
			_recent.value = try {
				UiState.Success(albumRepository.getAlbumsLimited(DomainAlbumListType.Newest, RECENT_COUNT))
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				UiState.Error(e, _recent.value.data)
			}
		}
	}

	/**
	 * Recently played and Frequently played: what is on screen stays while the read runs, and a read
	 * that fails keeps it (an empty answer is an answer: the shelf goes, leaving no gap).
	 */
	private fun loadPlayed() {
		viewModelScope.launch {
			// Navidrome's "recent" order has no filter of its own: an album never played sorts last
			// but is still listed, and it is not "recently played".
			readAlbums(DomainAlbumListType.Recent)?.let { albums -> _played.value = albums.filter { it.lastPlayedAt != null } }
			readAlbums(DomainAlbumListType.Frequent)?.let { _frequent.value = it }
		}
	}

	private suspend fun readAlbums(type: DomainAlbumListType): List<DomainAlbum>? = try {
		albumRepository.getAlbumsLimited(type, HOME_ALBUMS_PER_SHELF)
	} catch (e: CancellationException) {
		throw e
	} catch (e: Exception) {
		null
	}

	/**
	 * Stale-while-revalidate over `lb_response_cache`, as the Fresh page reads it: the cached feed
	 * first, a request only when it is old or the library changed under it, and offline the cache alone.
	 * A read that fails never replaces a shelf already on screen.
	 */
	private fun loadFresh() {
		if (freshJob?.isActive == true) return
		freshJob = viewModelScope.launch {
			if (_fresh.value.isEmpty()) _freshLoading.value = true
			val policy = if (isOnline.value) LbCachePolicy.NORMAL else LbCachePolicy.CACHE_ONLY
			try {
				lbBotManager.freshReleases(preferenceManager.freshDays, policy = policy).collect { feed ->
					if (feed != null) lastFeed = feed
					_fresh.value = freshShelfAfterRead(_fresh.value, feed, filters = FreshFilters.of(preferenceManager))
				}
			} catch (e: CancellationException) {
				throw e
			} catch (_: Exception) {
				// Fail-soft: the shelf stays as it was.
			} finally {
				_freshLoading.value = false
			}
		}
	}

	companion object {
		/** How many newest albums the shelf reads. */
		const val RECENT_COUNT = 20
	}
}
