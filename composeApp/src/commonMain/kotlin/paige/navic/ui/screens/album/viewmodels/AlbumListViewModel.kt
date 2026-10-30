package paige.navic.ui.screens.album.viewmodels

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import paige.navic.domain.manager.PreferenceManager
import paige.navic.domain.manager.SessionManager
import paige.navic.domain.models.DomainAlbum
import paige.navic.domain.models.DomainAlbumListType
import paige.navic.domain.models.DomainFilter
import paige.navic.domain.models.toBitmask
import paige.navic.domain.models.toDomainFilters
import paige.navic.domain.repositories.AlbumRepository
import paige.navic.ui.core.UiState
import paige.navic.util.LatestLoad
import paige.navic.util.Logger

class AlbumListViewModel(
	initialListType: DomainAlbumListType = DomainAlbumListType.AlphabeticalByArtist,
	initialFilters: Set<DomainFilter>? = null,
	private val repository: AlbumRepository,
	private val sessionManager: SessionManager,
	private val preferenceManager: PreferenceManager
) : ViewModel() {
	val albumsState: StateFlow<UiState<ImmutableList<DomainAlbum>>>
		field = MutableStateFlow<UiState<ImmutableList<DomainAlbum>>>(UiState.Loading())

	val selectedAlbum: StateFlow<DomainAlbum?>
		field = MutableStateFlow(null)

	val starred: StateFlow<Boolean>
		field = MutableStateFlow(false)

	val rating: StateFlow<Int>
		field = MutableStateFlow(0)

	val listType: StateFlow<DomainAlbumListType>
		field = MutableStateFlow(initialListType)

	val selectedReversed: StateFlow<Boolean>
		field = MutableStateFlow(false)

	val selectedFilters: StateFlow<Set<DomainFilter>>
		field = MutableStateFlow(
			initialFilters ?: preferenceManager.albumFilters.toDomainFilters()
		)

	val gridState = LazyGridState()

	init {
		viewModelScope.launch {
			sessionManager.isLoggedIn.collect { if (it) refreshAlbums(false) }
		}
	}

	/** The read in flight: a newer sort or filter cancels it, so a slow older one never lands last. */
	private var refreshing: Job? = null

	fun refreshAlbums(fullRefresh: Boolean) {
		refreshing?.cancel()
		refreshing = viewModelScope.launch {
			repository.getAlbumsFlow(
				fullRefresh,
				listType.value,
				selectedReversed.value,
				selectedFilters.value
			).collect {
				albumsState.value = it
			}
		}
	}

	/** The selection's read: a new long press, or the sheet closing, cancels the one before. */
	private val selecting = LatestLoad(viewModelScope)

	fun selectAlbum(album: DomainAlbum) {
		// The list's rows carry no songs (row 4a); the sheet's actions (play next, download,
		// add to a playlist) act on them, so the album is read whole before the sheet opens,
		// with its star and rating, and the sheet opens on all three at once. A read that fails
		// opens no sheet.
		selecting.launch(
			load = {
				Triple(
					repository.getAlbumWithSongs(album.id) ?: album,
					repository.isAlbumStarred(album),
					repository.getAlbumRating(album)
				)
			},
			onError = { Logger.w("AlbumListViewModel", "could not read album ${album.id}", it) }
		) { (whole, isStarred, albumRating) ->
			starred.value = isStarred
			rating.value = albumRating
			selectedAlbum.value = whole
		}
	}

	fun clearSelection() {
		selecting.cancel()
		selectedAlbum.value = null
	}

	fun starAlbum(isStarred: Boolean) {
		// The selection is read at the tap: the sheet closes right after some of its actions.
		val selection = selectedAlbum.value ?: return
		viewModelScope.launch {
			runCatching {
				if (isStarred) {
					repository.starAlbum(selection)
				} else {
					repository.unstarAlbum(selection)
				}
				starred.value = isStarred
			}
		}
	}

	fun setRating(newRating: Int) {
		val selection = selectedAlbum.value ?: return
		viewModelScope.launch {
			runCatching {
				rating.value = newRating
				repository.rateAlbum(selection, newRating)
			}
		}
	}

	fun setListType(newListType: DomainAlbumListType) {
		listType.value = newListType
		refreshAlbums(false)
	}

	fun setReversed(reversed: Boolean) {
		selectedReversed.value = reversed
		refreshAlbums(false)
	}

	fun toggleFilter(filter: DomainFilter) {
		val current = selectedFilters.value
		val newFilters = if (current.contains(filter)) {
			current - filter
		} else {
			current + filter
		}
		selectedFilters.value = newFilters
		preferenceManager.albumFilters = newFilters.toBitmask()
		refreshAlbums(false)
	}

	fun clearError() {
		albumsState.value = UiState.Success(albumsState.value.data ?: persistentListOf())
	}
}
