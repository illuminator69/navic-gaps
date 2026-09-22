package paige.navic.ui.screens.search.viewmodels

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import paige.navic.domain.manager.ConnectivityManager
import paige.navic.domain.manager.DownloadManager
import paige.navic.domain.manager.LbAlbumCandidate
import paige.navic.domain.manager.LbArtistCandidate
import paige.navic.domain.manager.LbBotManager
import paige.navic.domain.models.DomainSong
import paige.navic.domain.repositories.SearchRepository
import paige.navic.domain.repositories.SongRepository
import paige.navic.ui.core.UiState
import kotlin.time.Duration.Companion.milliseconds

@OptIn(FlowPreview::class)
class SearchViewModel(
	private val repository: SearchRepository,
	private val songRepository: SongRepository,
	private val lbBotManager: LbBotManager,
	connectivityManager: ConnectivityManager,
	downloadManager: DownloadManager
) : ViewModel() {
	val searchState: StateFlow<UiState<List<Any>>>
		field = MutableStateFlow<UiState<List<Any>>>(UiState.Success(emptyList()))

	val searchHistory: StateFlow<List<String>>
		field = MutableStateFlow<List<String>>(emptyList())

	val selectedSong: StateFlow<DomainSong?>
		field = MutableStateFlow(null)

	val selectedSongIsStarred: StateFlow<Boolean>
		field = MutableStateFlow(false)

	val selectedSongRating: StateFlow<Int>
		field = MutableStateFlow(0)

	val searchQuery = TextFieldState()

	val isOnline = connectivityManager.isOnline
	val downloadedSongs = downloadManager.downloadedSongs

	val gridState = LazyGridState()

	/**
	 * "Not in your library": MusicBrainz artists, so search can reach past the
	 * library at all. Until `/lb/artist/lookup` was whitelisted on the hub, an
	 * external artist page was only reachable if the client already held an MBID
	 * from a Fresh row — which blocked every acquisition path that starts with
	 * "I want this artist".
	 *
	 * Empty forever when lb-bot is absent, and the section does not render (§7).
	 */
	val externalArtists: StateFlow<List<LbArtistCandidate>>
		field = MutableStateFlow<List<LbArtistCandidate>>(emptyList())

	/**
	 * The album half of the same reach. Unlike [externalArtists] these rows carry
	 * ownership, marked by release-group id rather than guessed from a name, so an
	 * owned hit opens the library album instead of a download page.
	 *
	 * Empty forever when lb-bot is absent, and the section does not render (§7).
	 */
	val externalAlbums: StateFlow<List<LbAlbumCandidate>>
		field = MutableStateFlow<List<LbAlbumCandidate>>(emptyList())

	init {
		viewModelScope.launch {
			snapshotFlow { searchQuery.text }
				.debounce(300.milliseconds)
				.collectLatest { queryText ->
					val query = queryText.toString()
					if (query.isBlank()) {
						searchState.value = UiState.Success(emptyList())
					} else {
						searchState.value = UiState.Loading()
						try {
							searchState.value = UiState.Success(repository.search(query))
						} catch (e: Exception) {
							if (e !is CancellationException) {
								searchState.value = UiState.Error(e)
							}
						}
						lookUpExternalArtists(query)
						lookUpExternalAlbums(query)
					}
				}
		}
	}

	/**
	 * A live MusicBrainz search behind lb-bot's global 1 req/sec lock, not a
	 * local index read — so it rides the same 300 ms debounce as the library
	 * search and skips anything too short to be worth a second of that budget.
	 */
	private suspend fun lookUpExternalArtists(query: String) {
		if (query.trim().length < EXTERNAL_LOOKUP_MIN_LENGTH) {
			externalArtists.value = emptyList()
			return
		}
		externalArtists.value = try {
			lbBotManager.artistLookup(query.trim())
		} catch (e: Exception) {
			if (e is CancellationException) throw e
			emptyList()
		}
	}

	/**
	 * The album lookup, on the same terms — and deliberately issued from the same
	 * debounce site as [lookUpExternalArtists] rather than from one of its own.
	 * Both spend lb-bot's single global MusicBrainz second, so two independent
	 * debounces would be two budgets queued behind each other and behind any
	 * running discography scan.
	 */
	private suspend fun lookUpExternalAlbums(query: String) {
		if (query.trim().length < EXTERNAL_LOOKUP_MIN_LENGTH) {
			externalAlbums.value = emptyList()
			return
		}
		externalAlbums.value = try {
			lbBotManager.albumLookup(query.trim())
		} catch (e: Exception) {
			if (e is CancellationException) throw e
			emptyList()
		}
	}

	fun addToSearchHistory(query: String) {
		if (query.isBlank()) return
		val current = searchHistory.value.toMutableList()
		if (current.contains(query)) {
			current.remove(query)
		}
		current.add(0, query)
		searchHistory.value = current.take(10)
	}

	fun removeFromSearchHistory(query: String) {
		val current = searchHistory.value.toMutableList()
		current.remove(query)
		searchHistory.value = current
	}

	fun selectSong(song: DomainSong) {
		viewModelScope.launch {
			selectedSong.value = song
			selectedSongIsStarred.value = songRepository.isSongStarred(song)
			selectedSongRating.value = songRepository.getSongRating(song)
		}
	}

	fun starSelectedSong(starred: Boolean) {
		viewModelScope.launch {
			val selection = selectedSong.value ?: return@launch
			runCatching {
				if (starred) {
					songRepository.starSong(selection)
				} else {
					songRepository.unstarSong(selection)
				}
				selectedSongIsStarred.value = starred
			}
		}
	}

	fun rateSelectedSong(rating: Int) {
		viewModelScope.launch {
			val selection = selectedSong.value ?: return@launch
			runCatching {
				songRepository.rateSong(selection, rating)
				selectedSongRating.value = rating
			}
		}
	}

	fun clearSelectedSong() {
		selectedSong.value = null
	}
}

private const val EXTERNAL_LOOKUP_MIN_LENGTH = 3
