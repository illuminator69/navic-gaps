package paige.navic.domain.repositories

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import paige.navic.data.database.dao.AlbumDao
import paige.navic.data.database.dao.ArtistDao
import paige.navic.data.database.dao.PlaylistDao
import paige.navic.data.database.dao.SongDao
import paige.navic.data.database.mappers.toDomainModel
import paige.navic.data.database.mappers.toEntity
import paige.navic.domain.manager.ConnectivityManager
import paige.navic.domain.manager.SessionManager
import paige.navic.domain.models.DomainArtist
import paige.navic.domain.search.LibraryFuzzyIndex
import paige.navic.ui.screens.search.LibraryHits
import paige.navic.ui.screens.search.mergeHits
import paige.navic.ui.screens.search.offlineHits
import paige.navic.util.Logger

class SearchRepository(
	private val albumDao: AlbumDao,
	private val artistDao: ArtistDao,
	private val songDao: SongDao,
	private val playlistDao: PlaylistDao,
	private val sessionManager: SessionManager,
	private val fuzzyIndex: LibraryFuzzyIndex,
	connectivityManager: ConnectivityManager
) {
	val isOnline = connectivityManager.isOnline

	/**
	 * The library half of Search (P3). Online: Navidrome's search3 (whole words and prefixes), with
	 * the typo-tolerant pass over Room beside it, merged exact first. Offline: downloaded music only,
	 * [downloadedIds] being the songs on this device, so nothing is offered that cannot play.
	 */
	suspend fun search(query: String, downloadedIds: Collection<String>): LibraryHits {
		if (!isOnline.value) {
			Logger.i("SearchRepository", "Device offline, searching downloaded music.")
			val downloaded = if (downloadedIds.isEmpty()) emptyList()
			else songDao.getSongsByIds(downloadedIds.toList()).map { it.toDomainModel() }
			return offlineHits(downloaded, query)
		}
		return coroutineScope {
			val fuzzy = async {
				try {
					fuzzyIndex.search(query)
				} catch (e: Exception) {
					if (e is CancellationException) throw e
					Logger.e("SearchRepository", "Typo-tolerant pass failed", e)
					LibraryHits.None
				}
			}
			val exact = try {
				onlineSearch(query)
			} catch (e: Exception) {
				if (e is CancellationException) throw e
				Logger.e(
					"SearchRepository",
					"Online search failed despite network connection, falling back to local DB",
					e
				)
				performLocalSearch(query)
			}
			mergeHits(exact, fuzzy.await())
		}
	}

	/**
	 * Android Auto's voice and text search (`MediaPlayer.android.kt`, upstream's caller): search3, or
	 * the whole cached library offline, flattened as it always was. Search's page uses the other one.
	 */
	suspend fun search(query: String): List<Any> {
		val hits = if (isOnline.value) {
			try {
				onlineSearch(query)
			} catch (e: Exception) {
				if (e is CancellationException) throw e
				Logger.e("SearchRepository", "Online search failed, falling back to local DB", e)
				performLocalSearch(query)
			}
		} else {
			performLocalSearch(query)
		}
		return hits.albums + hits.artists + hits.songs + hits.playlists
	}

	/** Library artists by id, in the order asked: the MusicBrainz hits the library holds (P3). */
	suspend fun artistsByIds(ids: List<String>): List<DomainArtist> {
		if (ids.isEmpty()) return emptyList()
		val byId = artistDao.getArtistsByIds(ids.distinct()).associateBy { it.artistId }
		return ids.distinct().mapNotNull { byId[it]?.toDomainModel() }
	}

	private suspend fun onlineSearch(query: String): LibraryHits {
		val data = sessionManager.api.searchID3(query)

		albumDao.insertAlbumsIgnoringConflicts(data.albums.map { it.toEntity() })
		artistDao.insertArtistsIgnoringConflicts(data.artists.map { it.toEntity() })
		songDao.insertSongsIgnoringConflicts(data.songs.map { it.toEntity() })

		// Room answers IN (…) in its own order; keep search3's ranking.
		val albums = albumDao.getAlbumsByIds(data.albums.map { it.id }).associateBy { it.album.albumId }
		val artists = artistDao.getArtistsByIds(data.artists.map { it.id }).associateBy { it.artistId }
		val songs = songDao.getSongsByIds(data.songs.map { it.id }).associateBy { it.songId }
		return LibraryHits(
			artists = data.artists.mapNotNull { artists[it.id]?.toDomainModel() },
			albums = data.albums.mapNotNull { albums[it.id]?.toDomainModel() },
			songs = data.songs.mapNotNull { songs[it.id]?.toDomainModel() },
			playlists = playlistDao.searchPlaylistsList(query).map { it.toDomainModel() }
		)
	}

	private suspend fun performLocalSearch(query: String): LibraryHits = LibraryHits(
		artists = artistDao.searchArtistsList(query).map { it.toDomainModel() },
		albums = albumDao.searchAlbumsList(query).map { it.toDomainModel() },
		songs = songDao.searchSongsList(query).map { it.toDomainModel() },
		playlists = playlistDao.searchPlaylistsList(query).map { it.toDomainModel() }
	)
}
