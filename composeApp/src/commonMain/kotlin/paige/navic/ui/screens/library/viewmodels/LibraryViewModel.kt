package paige.navic.ui.screens.library.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import paige.navic.data.database.dao.AlbumDao
import paige.navic.data.database.dao.ArtistDao
import paige.navic.data.database.dao.PlaylistDao
import paige.navic.data.database.dao.SongDao
import paige.navic.data.database.mappers.toDomainModel
import paige.navic.data.database.relations.AlbumWithSongs
import paige.navic.data.database.relations.PlaylistWithSongs
import paige.navic.domain.manager.HubManager
import paige.navic.domain.models.DomainAlbum
import paige.navic.domain.models.DomainArtist
import paige.navic.domain.models.DomainPlaylist
import paige.navic.domain.repositories.SavedQueueRepository
import paige.navic.ui.screens.library.LibraryEntry
import paige.navic.ui.screens.library.RECENTS_LIMIT
import paige.navic.ui.screens.library.recentsEntries

/**
 * The Library's Recents (P5): Liked, then what was used last, from Room and the hub's mixes. Only
 * light reads: the recently played albums without their songs, the playlist rows, the starred
 * count. A row's sheet reads the whole item when it opens ([album], [playlist], [artist]).
 *
 * Kept in the persistent store by the screen, so a chip switch and a tab switch keep it.
 */
class LibraryViewModel(
	private val albumDao: AlbumDao,
	private val playlistDao: PlaylistDao,
	private val artistDao: ArtistDao,
	songDao: SongDao,
	hubManager: HubManager,
	savedQueueRepository: SavedQueueRepository
) : ViewModel() {

	/** Null until the first read: the screen shows nothing rather than an empty list. */
	val recents: StateFlow<List<LibraryEntry>?> = combine(
		songDao.countStarred().distinctUntilChanged(),
		hubManager.mixes,
		savedQueueRepository.observeAll(),
		playlistDao.getPlaylistRows(),
		// Enough albums for a hundred entries whatever they are: an artist's clock is one of these.
		albumDao.getRecentlyPlayedAlbumRows(RECENTS_LIMIT * 2)
	) { liked, mixes, queues, playlists, albums ->
		val domainAlbums = albums.map { AlbumWithSongs(it, emptyList()).toDomainModel() }
		val artistIds = domainAlbums.map { it.artistId }.filter { it.isNotBlank() }.distinct()
		val covers = if (artistIds.isEmpty()) emptyMap()
		else artistDao.getArtistsByIds(artistIds).associate { it.artistId to it.coverArtId }
		recentsEntries(
			liked = liked,
			mixes = mixes,
			savedQueues = queues,
			playlists = playlists.map { PlaylistWithSongs(it, emptyList()).toDomainModel() },
			albums = domainAlbums,
			artistCovers = covers
		)
	}
		.flowOn(Dispatchers.Default)
		.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

	/** The album with its songs, for its sheet; null when Room no longer holds it. */
	suspend fun album(id: String): DomainAlbum? =
		albumDao.getAlbumsByIds(listOf(id)).firstOrNull()?.toDomainModel()

	suspend fun playlist(id: String): DomainPlaylist? = playlistDao.getPlaylistById(id)?.toDomainModel()

	suspend fun artist(id: String): DomainArtist? = artistDao.getArtistById(id)?.toDomainModel()
}
