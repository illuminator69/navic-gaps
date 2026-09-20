package paige.navic.ui.screens.collection.viewmodels

import androidx.compose.foundation.lazy.LazyListState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import navic.composeapp.generated.resources.Res
import navic.composeapp.generated.resources.notice_deleted_download
import navic.composeapp.generated.resources.notice_download_started
import navic.composeapp.generated.resources.notice_removed_from_playlist
import paige.navic.data.database.entities.DownloadStatus
import paige.navic.data.database.mappers.toDomainModel
import paige.navic.domain.manager.ConnectivityManager
import paige.navic.domain.manager.DownloadManager
import paige.navic.domain.manager.SessionManager
import paige.navic.domain.manager.SnackBarManager
import paige.navic.domain.models.DomainAlbum
import paige.navic.domain.models.DomainAlbumInfo
import paige.navic.domain.models.DomainSong
import paige.navic.domain.models.DomainSongCollection
import paige.navic.domain.repositories.AlbumRepository
import paige.navic.domain.repositories.CollectionRepository
import paige.navic.domain.repositories.SongRepository
import paige.navic.ui.core.UiState
import paige.navic.util.Logger
import kotlinx.coroutines.Dispatchers

class CollectionDetailViewModel(
	private val collectionId: String,
	private val repository: CollectionRepository,
	private val songRepository: SongRepository,
	private val albumRepository: AlbumRepository,
	private val downloadManager: DownloadManager,
	private val sessionManager: SessionManager,
	private val snackBarManager: SnackBarManager,
	connectivityManager: ConnectivityManager
) : ViewModel() {
	// Starts empty and is filled by [refreshCollection]'s first emission, which comes off
	// `getCollectionFlow` on Dispatchers.IO within a frame or two.
	//
	// This used to be seeded with `runBlocking { repository.getLocalData(collectionId) }`. A new
	// instance of this ViewModel is built for every album or playlist opened, so that Room relation
	// query ran synchronously on the thread composing the screen — i.e. squarely inside the enter
	// transition, which is exactly when the stutter showed. The cached data it was fetching arrives
	// from the flow below anyway.
	private val _collectionState =
		MutableStateFlow<UiState<DomainSongCollection>>(UiState.Loading())
	val collectionState: StateFlow<UiState<DomainSongCollection>> = _collectionState.asStateFlow()

	val starred: StateFlow<Boolean>
		field = MutableStateFlow(false)

	val selectedSong: StateFlow<DomainSong?>
		field = MutableStateFlow(null)

	val albumInfoState: StateFlow<UiState<DomainAlbumInfo>>
		field = MutableStateFlow<UiState<DomainAlbumInfo>>(UiState.Loading())

	val selectedSongIsStarred: StateFlow<Boolean>
		field = MutableStateFlow(false)

	private val _selectedSongRating = MutableStateFlow(0)
	val selectedSongRating: StateFlow<Int>
		field = MutableStateFlow(0)

	val selectedAlbum: StateFlow<DomainAlbum?>
		field = MutableStateFlow(null)

	val selectedAlbumIsStarred: StateFlow<Boolean>
		field = MutableStateFlow(false)

	val selectedAlbumRating: StateFlow<Int>
		field = MutableStateFlow(0)

	val rating: StateFlow<Int>
		field = MutableStateFlow(0)

	val listState = LazyListState()

	val isOnline = connectivityManager.isOnline

	val allDownloads = downloadManager.allDownloads
		.stateIn(
			scope = viewModelScope,
			started = SharingStarted.Lazily,
			initialValue = emptyList()
		)

	// Derived from the collection rather than read off it once at construction: the state is empty
	// at that point now that the blocking seed above is gone, so a one-shot read would always have
	// resolved to an empty list.
	@OptIn(ExperimentalCoroutinesApi::class)
	val otherAlbums: StateFlow<List<DomainAlbum>> = _collectionState
		.map { it.data as? DomainAlbum }
		.distinctUntilChangedBy { album -> album?.artistId to album?.id }
		.flatMapLatest { album ->
			if (album == null) flowOf(emptyList())
			else repository.getOtherAlbums(album.artistId, album.id)
		}
		.stateIn(
			scope = viewModelScope,
			started = SharingStarted.Lazily,
			initialValue = emptyList()
		)

	init {
		viewModelScope.launch {
			sessionManager.isLoggedIn.collect { if (it) refreshCollection(false) }
		}
	}

	fun refreshCollection(fullRefresh: Boolean) {
		viewModelScope.launch {
			repository.getCollectionFlow(fullRefresh, collectionId).collect {
				_collectionState.value = it
				if (it.data is DomainAlbum) {
					starred.value = albumRepository.isAlbumStarred(it.data as DomainAlbum)
					rating.value = albumRepository.getAlbumRating(it.data as DomainAlbum)
					try {
						val albumInfo = repository.getAlbumInfo(collectionId)
						albumInfoState.value = UiState.Success(albumInfo.toDomainModel())
					} catch (e: Exception) {
						albumInfoState.value = UiState.Error(e)
					}
				}
			}
		}
	}

	fun selectSong(song: DomainSong) {
		viewModelScope.launch {
			selectedSong.value = song
			selectedSongIsStarred.value = songRepository.isSongStarred(song)
			_selectedSongRating.value = songRepository.getSongRating(song)
		}
	}

	fun selectAlbum(album: DomainAlbum) {
		viewModelScope.launch {
			selectedAlbum.value = album
			selectedAlbumIsStarred.value = albumRepository.isAlbumStarred(album)
			selectedAlbumRating.value = albumRepository.getAlbumRating(album)
		}
	}

	fun clearSelection() {
		selectedSong.value = null
		selectedAlbum.value = null
	}

	fun clearError() {
		collectionState.value.data?.let {
			_collectionState.value = UiState.Success(it)
		}
	}

	fun removeFromPlaylist() {
		val song = selectedSong.value ?: return
		val songs = collectionState.value.data?.songs ?: return
		viewModelScope.launch {
			try {
				sessionManager.api.updatePlaylist(
					id = collectionId,
					songIndicesToRemove = listOf(songs.indexOf(song))
				)
				snackBarManager.notify(Res.string.notice_removed_from_playlist)
				refreshCollection(true)
			} catch (e: Exception) {
				Logger.e("CollectionDetailViewModel", "Failed to remove song from playlist", e)
			}
		}
		clearSelection()
	}

	fun starSelectedSong() {
		viewModelScope.launch {
			val selection = selectedSong.value ?: return@launch
			runCatching {
				songRepository.starSong(selection)
				selectedSongIsStarred.value = true
				refreshCollection(false)
			}
		}
	}

	fun unstarSelectedSong() {
		viewModelScope.launch {
			val selection = selectedSong.value ?: return@launch
			runCatching {
				songRepository.unstarSong(selection)
				selectedSongIsStarred.value = false
				refreshCollection(false)
			}
		}
	}

	fun rateSelectedSong(rating: Int) {
		viewModelScope.launch {
			val selection = selectedSong.value ?: return@launch
			runCatching {
				songRepository.rateSong(selection, rating)
				_selectedSongRating.value = rating
			}
		}
	}

	fun rateAlbum(newRating: Int) {
		viewModelScope.launch {
			(collectionState.value.data as? DomainAlbum)?.let { album ->
				albumRepository.rateAlbum(album, newRating)
				rating.value = newRating
			}
		}
	}

	fun starAlbum(starred: Boolean) {
		viewModelScope.launch {
			runCatching {
				val collection = collectionState.value.data ?: return@launch
				if (collection !is DomainAlbum) return@launch
				if (starred) {
					albumRepository.starAlbum(collection)
				} else {
					albumRepository.unstarAlbum(collection)
				}
				refreshCollection(false)
			}
		}
	}

	fun rateSelectedAlbum(rating: Int) {
		viewModelScope.launch {
			selectedAlbum.value?.let { album ->
				albumRepository.rateAlbum(album, rating)
				selectedAlbumRating.value = rating
			}
		}
	}

	fun starSelectedAlbum(starred: Boolean) {
		viewModelScope.launch {
			runCatching {
				val collection = selectedAlbum.value ?: return@launch
				if (starred) {
					albumRepository.starAlbum(collection)
				} else {
					albumRepository.unstarAlbum(collection)
				}
				selectedAlbumIsStarred.value = starred
			}
		}
	}

	fun downloadSong(song: DomainSong) {
		downloadManager.downloadSong(song)
		snackBarManager.notify(Res.string.notice_download_started)
	}

	fun cancelDownload(songId: String) {
		downloadManager.cancelDownload(songId)
	}

	fun deleteDownload(songId: String) {
		downloadManager.deleteDownload(songId)
		snackBarManager.notify(Res.string.notice_deleted_download)
	}

	fun downloadAll() {
		val collection = collectionState.value.data ?: return
		viewModelScope.launch {
			downloadManager.downloadCollection(collection)
			snackBarManager.notify(Res.string.notice_download_started)
		}
	}

	fun cancelDownloadAll() {
		collectionState.value.data?.songs?.forEach {
			downloadManager.cancelDownload(it.id)
		}
	}

	fun collectionDownloadStatus(): Flow<DownloadStatus> {
		val songs = collectionState.value.data?.songs.orEmpty()
		return downloadManager.getCollectionDownloadStatus(songs.map { it.id })
	}
}
