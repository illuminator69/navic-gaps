package paige.navic.domain.search

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import paige.navic.data.database.dao.AlbumDao
import paige.navic.data.database.dao.ArtistDao
import paige.navic.data.database.dao.SearchNameRow
import paige.navic.data.database.dao.SongDao
import paige.navic.data.database.mappers.toDomainModel
import paige.navic.domain.manager.PreferenceManager
import paige.navic.ui.screens.search.LibraryHits
import paige.navic.util.Logger
import kotlin.time.TimeSource

/**
 * P3: the typo-tolerant half of library search. Room holds the whole library, so every artist,
 * album and song name is scored locally ([FuzzyIndex]) against what was typed; Navidrome's search3
 * and Room's LIKE find nothing for a typo. Albums and songs are indexed with their artist after
 * the name, so "radioheed" finds Radiohead's records too, as search3 does for "radiohead"; a hit on
 * the name itself still ranks first (a prefix outscores a word further in).
 *
 * Built lazily on the first search from three name projections (about 16,000 rows on the owner's
 * library) and rebuilt when the library's signature moves: the three row counts and the last full
 * sync, read at the start of every search.
 */
class LibraryFuzzyIndex(
	private val artistDao: ArtistDao,
	private val albumDao: AlbumDao,
	private val songDao: SongDao,
	private val preferenceManager: PreferenceManager
) {
	private class Built(
		val signature: List<Long>,
		val artists: FuzzyIndex<String>,
		val albums: FuzzyIndex<String>,
		val songs: FuzzyIndex<String>
	)

	private val lock = Mutex()
	private var built: Built? = null

	/** How long the last build took and how many names it holds, for the results note. */
	var lastBuild: String = ""
		private set

	suspend fun search(query: String): LibraryHits = withContext(Dispatchers.Default) {
		if (query.isBlank()) return@withContext LibraryHits.None
		val index = current()
		val artistIds = index.artists.search(query, ARTIST_LIMIT)
		val albumIds = index.albums.search(query, ALBUM_LIMIT)
		val songIds = index.songs.search(query, SONG_LIMIT)
		// Room answers IN (…) in its own order: put the hits back in score order.
		val artists = if (artistIds.isEmpty()) emptyList() else {
			val byId = artistDao.getArtistsByIds(artistIds).associateBy { it.artistId }
			artistIds.mapNotNull { byId[it]?.toDomainModel() }
		}
		val albums = if (albumIds.isEmpty()) emptyList() else {
			val byId = albumDao.getAlbumsByIds(albumIds).associateBy { it.album.albumId }
			albumIds.mapNotNull { byId[it]?.toDomainModel() }
		}
		val songs = if (songIds.isEmpty()) emptyList() else {
			val byId = songDao.getSongsByIds(songIds).associateBy { it.songId }
			songIds.mapNotNull { byId[it]?.toDomainModel() }
		}
		LibraryHits(artists = artists, albums = albums, songs = songs, playlists = emptyList())
	}

	private suspend fun current(): Built = lock.withLock {
		val signature = listOf(
			artistDao.count().toLong(),
			albumDao.count().toLong(),
			songDao.count().toLong(),
			preferenceManager.lastFullSyncTime
		)
		built?.takeIf { it.signature == signature }?.let { return@withLock it }
		val started = TimeSource.Monotonic.markNow()
		val fresh = coroutineScope {
			val artists = async { artistDao.searchNames() }
			val albums = async { albumDao.searchNames() }
			val songs = async { songDao.searchNames() }
			Built(
				signature = signature,
				artists = index(artists.await(), withArtist = false),
				albums = index(albums.await(), withArtist = true),
				songs = index(songs.await(), withArtist = true)
			)
		}
		lastBuild = "${fresh.artists.size + fresh.albums.size + fresh.songs.size} names in ${started.elapsedNow()}"
		Logger.i("LibraryFuzzyIndex", "built: $lastBuild")
		built = fresh
		fresh
	}

	private fun index(rows: List<SearchNameRow>, withArtist: Boolean): FuzzyIndex<String> =
		FuzzyIndex(
			rows.mapNotNull { row ->
				val name = row.name?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
				val text = if (withArtist && !row.artist.isNullOrBlank()) "$name ${row.artist}" else name
				FuzzyIndex.Entry(row.id, text)
			}
		)

	private companion object {
		const val ARTIST_LIMIT = 20
		const val ALBUM_LIMIT = 20
		const val SONG_LIMIT = 50
	}
}
