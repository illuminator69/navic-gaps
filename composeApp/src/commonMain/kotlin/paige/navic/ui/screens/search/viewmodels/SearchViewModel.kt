package paige.navic.ui.screens.search.viewmodels

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import paige.navic.domain.manager.ConnectivityManager
import paige.navic.domain.manager.DownloadManager
import paige.navic.domain.manager.LbBotManager
import paige.navic.domain.models.DomainSong
import paige.navic.domain.repositories.SearchRepository
import paige.navic.domain.repositories.SongRepository
import paige.navic.domain.search.mbFuzzyQuery
import paige.navic.ui.screens.search.LibraryHits
import paige.navic.ui.screens.search.Lookup
import paige.navic.ui.screens.search.SearchEvent
import paige.navic.ui.screens.search.SearchUi
import paige.navic.ui.screens.search.reduce
import paige.navic.ui.screens.search.splitExternalArtists
import paige.navic.util.Logger
import kotlin.time.Duration.Companion.milliseconds

@OptIn(FlowPreview::class)
class SearchViewModel(
	private val repository: SearchRepository,
	private val songRepository: SongRepository,
	private val lbBotManager: LbBotManager,
	connectivityManager: ConnectivityManager,
	downloadManager: DownloadManager
) : ViewModel() {
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
	 * Everything the page shows for the settled query (P3, `SearchState.kt`): the library hits, the
	 * two lb-bot lookups and the phase. Moved only through [reduce], which drops an answer for any
	 * query but the current one.
	 */
	val ui: StateFlow<SearchUi>
		field = MutableStateFlow(
			reduce(SearchUi(), SearchEvent.Environment(isOnline.value, lbBotManager.isConfigured))
		)

	private fun send(event: SearchEvent) {
		ui.update { reduce(it, event) }
	}

	init {
		viewModelScope.launch {
			// The connection is part of the key: going offline mid-query searches again, downloads only.
			combine(snapshotFlow { searchQuery.text.toString() }, isOnline) { query, online -> query to online }
				// An emptied field is Browse at once; a query settles for 300 ms first.
				.debounce { (query, _) -> if (query.isBlank()) 0.milliseconds else 300.milliseconds }
				.distinctUntilChanged()
				.collectLatest { (query, online) ->
					send(SearchEvent.Environment(online, lbBotManager.isConfigured))
					send(SearchEvent.QueryChanged(query))
					if (query.isBlank()) return@collectLatest
					val hits = try {
						repository.search(query, downloadedSongs.value.keys)
					} catch (e: Exception) {
						if (e is CancellationException) throw e
						Logger.e("SearchViewModel", "library search failed", e)
						LibraryHits.None
					}
					send(SearchEvent.LibraryLanded(query, hits))
					lookUp(query)
				}
		}
	}

	/**
	 * lb-bot's MusicBrainz lookups for [query], whichever of the two are out ([Lookup.Looking]). A
	 * live MusicBrainz search behind lb-bot's global 1 req/sec lock, so at most one of each per
	 * settled query, and one AFTER the other, never two budgets queued behind each other and behind
	 * any running discography scan. The words go out with MusicBrainz's own fuzzy terms
	 * ([mbFuzzyQuery]), which costs no request. A failure is [Lookup.Failed], said on the page.
	 */
	private suspend fun lookUp(query: String) {
		if (ui.value.query != query) return
		if (ui.value.artists is Lookup.Looking) {
			val result = when (val r = lbBotManager.artistLookupResult(mbFuzzyQuery(query.trim()))) {
				is LbBotManager.LbResult.Ok -> {
					val owned = try {
						lbBotManager.ownedArtistIdsByMbid(r.value.map { it.mbid })
					} catch (e: Exception) {
						if (e is CancellationException) throw e
						emptyMap()
					}
					val split = splitExternalArtists(r.value, owned)
					val ownedIds = split.mapNotNull { it.ownedNdArtistId }
					if (ownedIds.isNotEmpty()) {
						send(SearchEvent.OwnedArtistsLanded(query, repository.artistsByIds(ownedIds)))
					}
					Lookup.Done(split)
				}
				is LbBotManager.LbResult.Failed -> Lookup.Failed
			}
			send(SearchEvent.ArtistsLanded(query, result))
		}
		if (ui.value.query != query) return
		if (ui.value.albums is Lookup.Looking) {
			val result = when (val r = lbBotManager.albumLookupResult(mbFuzzyQuery(query.trim()))) {
				is LbBotManager.LbResult.Ok -> Lookup.Done(r.value)
				is LbBotManager.LbResult.Failed -> Lookup.Failed
			}
			send(SearchEvent.AlbumsLanded(query, result))
		}
	}

	private var retryJob: Job? = null

	/** The "Couldn't reach lb-bot" pill: ask the failed lookups once more, for the query on screen. */
	fun retryLookups() {
		val query = ui.value.query
		if (query.isBlank() || retryJob?.isActive == true) return
		send(SearchEvent.RetryLookups(query))
		retryJob = viewModelScope.launch {
			try {
				lookUp(query)
			} catch (e: Exception) {
				if (e is CancellationException) throw e
				Logger.e("SearchViewModel", "lookup retry failed", e)
			}
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

