package paige.navic.data.database.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.RawQuery
import androidx.room3.RoomRawQuery
import androidx.room3.Transaction
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow
import paige.navic.data.database.entities.SongEntity
import paige.navic.util.Logger

@Dao
interface SongDao {
	@Query("SELECT * FROM SongEntity WHERE songId = :songId LIMIT 1")
	suspend fun getSongById(songId: String): SongEntity?

	@Upsert
	suspend fun insertSong(song: SongEntity)

	@Upsert
	suspend fun insertSongs(songs: List<SongEntity>)

	@Insert(onConflict = OnConflictStrategy.IGNORE)
	suspend fun insertSongsIgnoringConflicts(songs: List<SongEntity>)

	@Query("SELECT * FROM SongEntity")
	suspend fun getAllSongs(): List<SongEntity>

	/** Sorted/filtered song list. Build [query] with `DomainSongListType.songListSql`. */
	@RawQuery
	suspend fun getSongsByQuery(query: RoomRawQuery): List<SongEntity>

	/** The Songs list's ids, in its order (`songListSql`); the rows are read typed (`rowsInOrder`). */
	@RawQuery
	suspend fun getSongIdsByQuery(query: RoomRawQuery): List<String>

	@Query("SELECT SUM(playCount) FROM SongEntity")
	fun getTotalPlayCount(): Flow<Int?>

	@Query("SELECT SUM(duration * playCount) FROM SongEntity")
	fun getTotalListeningTime(): Flow<Long?>

	@Query("SELECT * FROM SongEntity WHERE playCount > 0 ORDER BY playCount DESC LIMIT :limit")
	fun getTopSongs(limit: Int): Flow<List<SongEntity>>

	@Query("SELECT * FROM SongEntity WHERE starredAt IS NOT NULL")
	fun getStarredSongs(): Flow<List<SongEntity>>

	@Query("SELECT belongsToAlbumId FROM SongEntity WHERE playCount > 0 GROUP BY belongsToAlbumId ORDER BY SUM(playCount) DESC LIMIT :limit")
	fun getTopAlbumIds(limit: Int): Flow<List<String>>

	@Query("SELECT artistId FROM SongEntity WHERE playCount > 0 GROUP BY artistId ORDER BY SUM(playCount) DESC LIMIT :limit")
	fun getTopArtistIds(limit: Int): Flow<List<String>>

	@Query("SELECT SUM(playCount) FROM SongEntity WHERE artistId = :artistId")
	fun getArtistPlayCount(artistId: String): Flow<Int?>

	@Query("SELECT SUM(duration * playCount) FROM SongEntity WHERE artistId = :artistId")
	fun getArtistListeningTime(artistId: String): Flow<Long?>

	@Query("SELECT SUM(duration * playCount) FROM SongEntity WHERE belongsToAlbumId = :albumId")
	fun getAlbumListeningTime(albumId: String): Flow<Long?>

	@Query("SELECT * FROM SongEntity WHERE belongsToAlbumId = :albumId")
	suspend fun getSongsByAlbumId(albumId: String): List<SongEntity>

	@Query("DELETE FROM SongEntity WHERE songId = :songId")
	suspend fun deleteSong(songId: String)

	// TODO
	@Query("SELECT EXISTS(SELECT 1 FROM SongEntity WHERE songId = :songId AND starredAt IS NOT NULL)")
	suspend fun isSongStarred(songId: String): Boolean

	@Query("SELECT userRating FROM SongEntity WHERE songId = :songId")
	suspend fun getSongRating(songId: String): Int?

	// Row 10 (adapted from upstream ce5861ed … 33e28839): a star or a rating is ONE column written
	// by id. It used to REPLACE the whole row from the domain object the screen held, which could
	// be stale (or, since row 4a, a list row with no songs), and put back whatever it remembered.
	// Each returns the rows it changed: 0 = no such row, and the caller then inserts as before.
	@Query("UPDATE SongEntity SET starredAt = :starredAtMillis WHERE songId = :songId")
	suspend fun setSongStarredAt(songId: String, starredAtMillis: Long?): Int

	@Query("UPDATE SongEntity SET userRating = :rating WHERE songId = :songId")
	suspend fun setSongRating(songId: String, rating: Int): Int

	@Query("DELETE FROM SongEntity")
	suspend fun clearAllSongs()

	@Query("SELECT songId FROM SongEntity")
	suspend fun getAllSongIds(): List<String>

	@Query("SELECT * FROM SongEntity WHERE songId IN (:ids)")
	suspend fun getSongsByIds(ids: List<String>): List<SongEntity>

	/** Every song's id, title and artist, for Search's typo-tolerant pass (P3). */
	@Query("SELECT songId AS id, title AS name, artistName AS artist FROM SongEntity")
	suspend fun searchNames(): List<SearchNameRow>

	@Query("SELECT COUNT(*) FROM SongEntity")
	suspend fun count(): Int

	/** How many songs are starred: the Library's pinned Liked row (P5). */
	@Query("SELECT COUNT(*) FROM SongEntity WHERE starredAt IS NOT NULL")
	fun countStarred(): Flow<Int>

	// Offline-radio fallback (AudioMuse / similar-songs agents unavailable): a local mix.
	@Query("SELECT * FROM SongEntity WHERE genre = :genre ORDER BY RANDOM() LIMIT :limit")
	suspend fun getRandomSongsByGenre(genre: String, limit: Int): List<SongEntity>

	@Query("SELECT * FROM SongEntity ORDER BY RANDOM() LIMIT :limit")
	suspend fun getRandomSongs(limit: Int): List<SongEntity>

	@Query("SELECT * FROM SongEntity WHERE title LIKE '%' || :query || '%' COLLATE NOCASE")
	suspend fun searchSongsList(query: String): List<SongEntity>

	@Query("SELECT * FROM SongEntity WHERE artistId = :artistId")
	suspend fun getSongsByArtistId(artistId: String): List<SongEntity>

	@Transaction
	suspend fun updateSongsByAlbumId(albumId: String, remoteSongs: List<SongEntity>) {
		val remoteIds = remoteSongs.map { it.songId }.toSet()
		getSongsByAlbumId(albumId).forEach { localSong ->
			if (localSong.songId !in remoteIds) {
				Logger.w("SongDao", "song ${localSong.songId} no longer belongs to album $albumId")
				deleteSong(localSong.songId)
			}
		}
		insertSongs(remoteSongs)
	}

	@Transaction
	suspend fun updateAllSongs(remoteSongs: List<SongEntity>) {
		val remoteIds = remoteSongs.map { it.songId }.toSet()
		getAllSongIds().forEach { localId ->
			if (localId !in remoteIds) {
				Logger.w("SongDao", "song $localId no longer exists remotely")
				deleteSong(localId)
			}
		}
		insertSongs(remoteSongs)
	}

	@Transaction
	suspend fun deleteObsoleteSongs(remoteIds: Set<String>) {
		getAllSongIds().forEach { localId ->
			if (localId !in remoteIds) {
				Logger.w("SongDao", "song $localId no longer exists remotely")
				deleteSong(localId)
			}
		}
	}
}
