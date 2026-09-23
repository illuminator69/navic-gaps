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
import paige.navic.domain.manager.LbBotManager
import paige.navic.domain.manager.LbMeta
import paige.navic.domain.manager.LbSimilarAlbums
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
	private val lbBotManager: LbBotManager,
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

	/**
	 * lb-bot's editorial "About" for this record — the first album description
	 * this app has ever shown. Null while in flight and null forever when lb-bot
	 * is absent, in which case nothing renders (§7).
	 *
	 * [DomainAlbumInfo.notes] remains the fallback: it has been loaded on every
	 * album page since forever and read by nothing at all.
	 */
	private val _meta = MutableStateFlow<LbMeta?>(null)
	val meta = _meta.asStateFlow()

	/** "Similar albums" — records already in the library by similar artists. The
	 *  first shelf this screen has ever had. */
	private val _similar = MutableStateFlow<LbSimilarAlbums?>(null)
	val similar = _similar.asStateFlow()

	private val _aboutOpen = MutableStateFlow(false)
	val aboutOpen = _aboutOpen.asStateFlow()

	fun openAbout() {
		_aboutOpen.value = true
	}

	fun dismissAbout() {
		_aboutOpen.value = false
	}

	/**
	 * Fire both lb-bot reads once the album is on screen. Strictly after the page
	 * has its own data, and never on the critical render path.
	 *
	 * **`getAlbumInfo2.musicBrainzId` is the RELEASE, not the release-group.** It
	 * was being passed straight to `/lb/meta/album`, which is keyed by the
	 * release-group — so every owned album asked lb-bot about an id that does not
	 * name a release-group, got an empty answer, and rendered the Navidrome notes
	 * with no article, no attribution and no way out. Verified on Obscured by
	 * Clouds: Navidrome says `d077cc98…` (release), lb-bot holds a six-paragraph
	 * article under `a6f0826e…` (release-group). Nothing errored; the answer was
	 * just always empty, which is indistinguishable from "nobody wrote about this".
	 *
	 * So the release-group is resolved from lb-bot's own discography index, which
	 * already maps release-groups to the Navidrome album ids they landed as. The
	 * release MBID still rides along as `release_mbid` — that is what it is, and
	 * it saves lb-bot resolving the canonical release itself.
	 */
	fun loadLbBotExtras(album: DomainAlbum, releaseMbid: String?) {
		viewModelScope.launch {
			if (!lbBotManager.ensureAvailability()) return@launch
			val rgid = releaseGroupIdFor(album)
			if (!rgid.isNullOrBlank()) {
				_meta.value = lbBotManager.albumMeta(rgid, releaseMbid)
			}
			// Similarity is computed artist-to-artist, so this needs the artist,
			// not the album; `rgid` only excludes the record on screen.
			//
			// No MBID is sent because Room's album row does not carry the
			// artist's. lb-bot resolves it from its own Navidrome artist index
			// before asking ListenBrainz, whose similar-artists endpoint is
			// keyed by MBID and answers nothing for a bare name.
			_similar.value = lbBotManager.similarAlbums(
				artistMbid = null,
				artistName = album.artistName,
				rgid = rgid
			)
		}
	}

	/**
	 * This album's MusicBrainz **release-group** id, from lb-bot's discography index.
	 *
	 * Matched on `navidrome_album_ids` first, which lb-bot backfills precisely so a
	 * client can go from a Navidrome album to the release-group it satisfies. Title
	 * is the fallback for a row the backfill has not reached — scoped to this one
	 * artist's discography, so it cannot collide with another artist's same-titled
	 * record.
	 *
	 * Null when the artist is not indexed, which is the ordinary fail-soft case: no
	 * About section rather than a wrong one.
	 */
	private suspend fun releaseGroupIdFor(album: DomainAlbum): String? {
		val releases = lbBotManager.discography(album.artistId, null)?.releases.orEmpty()
		if (releases.isEmpty()) return null
		releases.firstOrNull { collectionId in it.navidromeAlbumIds }
			?.rgid?.ifBlank { null }
			?.let { return it }
		val wanted = album.name?.trim()?.lowercase() ?: return null
		return releases.firstOrNull { it.title.trim().lowercase() == wanted }
			?.rgid?.ifBlank { null }
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
						val domainInfo = albumInfo.toDomainModel()
						albumInfoState.value = UiState.Success(domainInfo)
						loadLbBotExtras(it.data as DomainAlbum, domainInfo.musicBrainzId)
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
			selectedSongRating.value = songRepository.getSongRating(song)
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

	/**
	 * Rate the song whose sheet is open.
	 *
	 * The flow is written BEFORE the suspend call, as `ArtistDetailViewModel` does:
	 * `rateSong` writes Room and then queues the Subsonic `setRating` through
	 * `SyncManager`, so setting it afterwards leaves the stars on the old value for
	 * the length of a database write.
	 *
	 * `refreshCollection` is what the row underneath needs. `SongRow` draws its
	 * `SmallRatingRow` from the `DomainSong` in `collectionState`, a snapshot taken
	 * when the list loaded — so without a re-read the sheet moves and the row it came
	 * from does not. `starSelectedSong` has always done this; this one did not.
	 */
	fun rateSelectedSong(rating: Int) {
		viewModelScope.launch {
			val selection = selectedSong.value ?: return@launch
			selectedSongRating.value = rating
			runCatching {
				songRepository.rateSong(selection, rating)
				refreshCollection(false)
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
