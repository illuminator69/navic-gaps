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
import kotlinx.coroutines.launch
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
 * It reads what already exists and adds no request of its own: the newest albums from Room, the saved
 * queues from Room, and Fresh from `LbBotManager.freshReleases`, the call (and the cache key) the Fresh
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
	connectivityManager: ConnectivityManager
) : ViewModel() {
	private val isOnline = connectivityManager.isOnline

	// --- Recently added ---------------------------------------------------------------------------

	private val _recent = MutableStateFlow<UiState<ImmutableList<DomainAlbum>>>(UiState.Loading())

	/** The newest albums in the library (Room). Loading until the first read; an error keeps what was there. */
	val recent: StateFlow<UiState<ImmutableList<DomainAlbum>>> = _recent.asStateFlow()

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

	/** The station engine is there: Your mix can be made. */
	val yourMixAvailable: StateFlow<Boolean> = stationManager.available

	/** The library is being pulled right now: a page with nothing in it yet is waiting, not empty. */
	val syncing: StateFlow<Boolean> = syncManager.syncState
		.map { it.isSyncing }
		.distinctUntilChanged()
		.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), syncManager.syncState.value.isSyncing)

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
