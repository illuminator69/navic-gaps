package paige.navic.data.database.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.RawQuery
import androidx.room3.RoomRawQuery
import androidx.room3.Transaction
import kotlinx.coroutines.flow.Flow
import paige.navic.data.database.entities.AlbumEntity
import paige.navic.data.database.relations.AlbumWithSongs
import paige.navic.util.Logger

@Dao
interface AlbumDao {
	@Transaction
	@Query(" SELECT * FROM AlbumEntity WHERE genre = :genreName OR genres LIKE '%' || :genreName || '%' ORDER BY year DESC, name COLLATE NOCASE ASC")
	fun getAlbumsByGenre(genreName: String): Flow<List<AlbumWithSongs>>

	@Transaction
	@Query("SELECT * FROM AlbumEntity ORDER BY name ASC")
	fun getAllAlbums(): Flow<List<AlbumWithSongs>>

	@Transaction
	@Query("SELECT * FROM AlbumEntity ORDER BY name ASC")
	suspend fun getAllAlbumsList(): List<AlbumWithSongs>

	// @Transaction is mandatory, not tidiness: AlbumWithSongs makes Room walk the cursor twice —
	// once to collect albumIds, then again to attach the songs it fetched for them. Without a
	// transaction a concurrent sync can insert an album between the passes, and the second pass
	// hits a row the first never saw ("Key <albumId> is missing in the map").
	//
	// It is NOT sufficient on its own, and the note above used to imply it was. A
	// NON-DETERMINISTIC query breaks the same way with no writer involved at all:
	// `ORDER BY RANDOM()` is re-evaluated on the second walk, so the two passes
	// simply disagree. No transaction can fix that — see `getRandomAlbumIds`, and
	// never hand this method a query whose answer can change between executions.
	@Transaction
	@RawQuery
	suspend fun getAlbumsByQuery(query: RoomRawQuery): List<AlbumWithSongs>

	/**
	 * Random album ids, with **no** relation attached.
	 *
	 * The two halves of a random read have to be separated, and this is the first
	 * of them. `AlbumWithSongs` makes Room walk the parent cursor twice — once to
	 * collect the ids it needs songs for, then again to assemble — and SQLite
	 * re-evaluates `RANDOM()` on the second walk. The two passes therefore see
	 * *different albums*, and assembling one the first pass never saw throws
	 * `NoSuchElementException: Key <albumId> is missing in the map`.
	 *
	 * `@Transaction` does not help and was already here: the problem is not a
	 * concurrent writer, it is a query that does not answer the same thing twice.
	 * Returning bare ids means the randomness is resolved exactly once, and
	 * [getAlbumsByIds] — which already existed — is deterministic.
	 */
	@Query("SELECT albumId FROM AlbumEntity ORDER BY RANDOM() LIMIT :limit")
	suspend fun getRandomAlbumIds(limit: Int): List<String>

	@Transaction
	@Query("SELECT COUNT(albumId) FROM AlbumEntity")
	suspend fun getAlbumCount(): Int

	@Transaction
	@Query("SELECT * FROM AlbumEntity WHERE albumId = :albumId LIMIT 1")
	suspend fun getAlbumById(albumId: String): AlbumWithSongs?

	@Query("SELECT EXISTS(SELECT 1 FROM AlbumEntity WHERE albumId = :albumId AND starredAt IS NOT NULL)")
	suspend fun isAlbumStarred(albumId: String): Boolean

	@Query("SELECT userRating FROM AlbumEntity WHERE albumId = :albumId")
	suspend fun getAlbumRating(albumId: String): Int?

	@Transaction
	@Query("SELECT * FROM AlbumEntity WHERE artistId = :artistId ORDER BY year DESC")
	fun getAlbumsByArtist(artistId: String): Flow<List<AlbumWithSongs>>

	@Transaction
	@Query("SELECT * FROM AlbumEntity WHERE artistName = :artistName ORDER BY year DESC")
	fun getAlbumsByArtistName(artistName: String): Flow<List<AlbumWithSongs>>

	@Transaction
	@Query("SELECT * FROM AlbumEntity WHERE artistId = :artistId AND albumId != :albumId ORDER BY year DESC")
	fun getAlbumsByArtistExcluding(artistId: String, albumId: String): Flow<List<AlbumWithSongs>>

	@Transaction
	@Query("SELECT * FROM AlbumEntity WHERE name LIKE '%' || :query || '%' COLLATE NOCASE")
	suspend fun searchAlbumsList(query: String): List<AlbumWithSongs>

	@Insert(onConflict = OnConflictStrategy.REPLACE)
	suspend fun insertAlbum(album: AlbumEntity)

	@Insert(onConflict = OnConflictStrategy.REPLACE)
	suspend fun insertAlbums(albums: List<AlbumEntity>)

	@Insert(onConflict = OnConflictStrategy.IGNORE)
	suspend fun insertAlbumsIgnoringConflicts(albums: List<AlbumEntity>)

	@Query("DELETE FROM AlbumEntity WHERE albumId = :albumId")
	suspend fun deleteAlbum(albumId: String)

	@Query("DELETE FROM AlbumEntity")
	suspend fun clearAllAlbums()

	@Query("SELECT albumId FROM AlbumEntity")
	suspend fun getAllAlbumIds(): List<String>

	/** Just enough of every album to tell whether Navidrome's copy has changed. */
	@Query("SELECT albumId, songCount, coverArtId FROM AlbumEntity")
	suspend fun getAlbumFingerprints(): List<AlbumFingerprint>

	@Transaction
	@Query("SELECT * FROM AlbumEntity WHERE albumId IN (:ids)")
	suspend fun getAlbumsByIds(ids: List<String>): List<AlbumWithSongs>

	@Transaction
	suspend fun updateAllAlbums(remoteAlbums: List<AlbumEntity>) {
		val remoteIds = remoteAlbums.map { it.albumId }.toSet()
		getAllAlbumIds().forEach { localId ->
			if (localId !in remoteIds) {
				Logger.w("AlbumDao", "album $localId no longer exists remotely")
				deleteAlbum(localId)
			}
		}
		insertAlbums(remoteAlbums)
	}

	@Transaction
	suspend fun deleteObsoleteAlbums(remoteIds: Set<String>) {
		getAllAlbumIds().forEach { localId ->
			if (localId !in remoteIds) {
				Logger.w("AlbumDao", "album $localId no longer exists remotely")
				deleteAlbum(localId)
			}
		}
	}
}

/** An album row reduced to what [AlbumDao.getAlbumFingerprints] compares. */
data class AlbumFingerprint(
	val albumId: String,
	val songCount: Int,
	val coverArtId: String
)
