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

	/** When each album was added, alone: the songs' "Recently added" order needs nothing else. */
	@Query("SELECT albumId, createdAt FROM AlbumEntity")
	suspend fun getAlbumAddedTimes(): List<AlbumAddedAt>

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
	 * Album ROWS for a whole-library list (row 4a): no songs relation, so no second walk and no
	 * song read at all. Build [query] with `DomainAlbumListType.albumListSql`.
	 */
	@RawQuery
	suspend fun getAlbumRowsByQuery(query: RoomRawQuery): List<AlbumEntity>

	/** Android Auto's Albums node, one page of rows, in the Albums list's A–Z order. */
	@Query("SELECT * FROM AlbumEntity ORDER BY LOWER(name) ASC, rowid ASC LIMIT :limit OFFSET :offset")
	suspend fun getAlbumRowsPage(limit: Int, offset: Int): List<AlbumEntity>

	/** An artist's album ids, for the artist's song list ("See more"), with no songs read. */
	@Query("SELECT albumId FROM AlbumEntity WHERE artistId = :artistId")
	suspend fun getAlbumIdsByArtist(artistId: String): List<String>

	/** The same by name, for an album filed under another spelling of the artist. */
	@Query("SELECT albumId FROM AlbumEntity WHERE artistName = :artistName")
	suspend fun getAlbumIdsByArtistName(artistName: String): List<String>

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

	/** B-038: an offline song row's fallback art — the album's cover, which downloads and album
	 *  tiles cache, where a song's own `mf-` id was often never fetched. */
	@Query("SELECT coverArtId FROM AlbumEntity WHERE albumId = :albumId")
	suspend fun getAlbumCoverArtId(albumId: String): String?

	@Query("SELECT userRating FROM AlbumEntity WHERE albumId = :albumId")
	suspend fun getAlbumRating(albumId: String): Int?

	// Row 10 (adapted from upstream ce5861ed … 33e28839): a star or a rating is ONE column written
	// by id. It used to REPLACE the whole row from the domain object the screen held, which could
	// be stale (or, since row 4a, a list row with no songs), and put back whatever it remembered.
	// Each returns the rows it changed: 0 = no such row, and the caller then inserts as before.
	@Query("UPDATE AlbumEntity SET starredAt = :starredAtMillis WHERE albumId = :albumId")
	suspend fun setAlbumStarredAt(albumId: String, starredAtMillis: Long?): Int

	@Query("UPDATE AlbumEntity SET userRating = :rating WHERE albumId = :albumId")
	suspend fun setAlbumRating(albumId: String, rating: Int): Int

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

	/**
	 * Delete the album row only when no song is filed under it, as ONE statement (I-022,
	 * `DbRepository.forgetAlbumIfEmpty`). Returns the number of rows deleted: 0 when there was
	 * no such album or it still has songs.
	 */
	@Query(
		"DELETE FROM AlbumEntity WHERE albumId = :albumId " +
			"AND NOT EXISTS (SELECT 1 FROM SongEntity WHERE belongsToAlbumId = :albumId)"
	)
	suspend fun deleteAlbumIfEmpty(albumId: String): Int

	@Query("DELETE FROM AlbumEntity")
	suspend fun clearAllAlbums()

	@Query("SELECT albumId FROM AlbumEntity")
	suspend fun getAllAlbumIds(): List<String>

	/** Just enough of every album to tell whether Navidrome's copy has changed. */
	@Query("SELECT albumId, songCount, coverArtId FROM AlbumEntity")
	suspend fun getAlbumFingerprints(): List<AlbumFingerprint>

	/**
	 * The [limit] most recently played albums, without their songs (P5, the Library's Recents): a
	 * row needs the album, not its tracks, and 2,000+ albums with songs is the read the old home
	 * warned about.
	 */
	@Query("SELECT * FROM AlbumEntity WHERE lastPlayedAt IS NOT NULL ORDER BY lastPlayedAt DESC LIMIT :limit")
	fun getRecentlyPlayedAlbumRows(limit: Int): Flow<List<AlbumEntity>>

	@Transaction
	@Query("SELECT * FROM AlbumEntity WHERE albumId IN (:ids)")
	suspend fun getAlbumsByIds(ids: List<String>): List<AlbumWithSongs>

	/** Every album's id, name and artist, for Search's typo-tolerant pass (P3). */
	@Query("SELECT albumId AS id, name AS name, artistName AS artist FROM AlbumEntity")
	suspend fun searchNames(): List<SearchNameRow>

	@Query("SELECT COUNT(*) FROM AlbumEntity")
	suspend fun count(): Int

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

/** An album's id and when it was added ([AlbumDao.getAlbumAddedTimes]). */
data class AlbumAddedAt(val albumId: String, val createdAt: kotlin.time.Instant)
