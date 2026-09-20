package paige.navic.data.database.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import kotlinx.coroutines.flow.Flow
import paige.navic.data.database.entities.ArtistEntity
import paige.navic.util.Logger

@Dao
interface ArtistDao {
	@Query("SELECT * FROM ArtistEntity ORDER BY name COLLATE NOCASE ASC")
	suspend fun getArtistsAlphabeticalByName(): List<ArtistEntity>

	@Query("SELECT * FROM ArtistEntity ORDER BY RANDOM()")
	suspend fun getArtistsRandom(): List<ArtistEntity>

	@Query("SELECT * FROM ArtistEntity WHERE starredAt IS NOT NULL ORDER BY starredAt DESC")
	suspend fun getArtistsStarred(): List<ArtistEntity>

	@Query("SELECT * FROM ArtistEntity ORDER BY name COLLATE NOCASE ASC")
	fun getAllArtists(): Flow<List<ArtistEntity>>

	@Query("SELECT * FROM ArtistEntity")
	suspend fun getAllArtistsList(): List<ArtistEntity>

	/**
	 * The two most-shared cover IMAGE HASHES in the library, most shared first.
	 *
	 * Navidrome shapes an artist's cover id as `ar-<artist>_<imageHash>` — exactly one underscore —
	 * so the hash of the image it will actually serve is already here, no fetching required. Every
	 * artist with no artwork gets the same generic avatar and so the same hash, which is what
	 * [paige.navic.util.core.CoverPlaceholder] recognises it by. The runner-up comes back too
	 * because the rule needs it: the top hash counts only if it is a clear outlier.
	 */
	@Query("""
		SELECT substr(coverArtId, instr(coverArtId, '_') + 1) AS hash, COUNT(*) AS artistCount
		FROM ArtistEntity
		WHERE coverArtId IS NOT NULL AND instr(coverArtId, '_') > 0
		GROUP BY hash
		ORDER BY artistCount DESC
		LIMIT 2
	""")
	suspend fun coverImageHashCounts(): List<CoverHashCount>

	@Query("SELECT * FROM ArtistEntity WHERE artistId = :artistId LIMIT 1")
	suspend fun getArtistById(artistId: String): ArtistEntity?

	@Query("SELECT EXISTS(SELECT 1 FROM ArtistEntity WHERE artistId = :artistId AND starredAt IS NOT NULL)")
	suspend fun isArtistStarred(artistId: String): Boolean

	@Query("SELECT * FROM ArtistEntity WHERE name LIKE '%' || :query || '%' COLLATE NOCASE")
	suspend fun searchArtistsList(query: String): List<ArtistEntity>

	@Insert(onConflict = OnConflictStrategy.REPLACE)
	suspend fun insertArtist(artist: ArtistEntity)

	@Insert(onConflict = OnConflictStrategy.REPLACE)
	suspend fun insertArtists(artists: List<ArtistEntity>)

	@Insert(onConflict = OnConflictStrategy.IGNORE)
	suspend fun insertArtistsIgnoringConflicts(artists: List<ArtistEntity>)

	@Query("DELETE FROM ArtistEntity WHERE artistId = :artistId")
	suspend fun deleteArtist(artistId: String)

	@Query("DELETE FROM ArtistEntity")
	suspend fun clearAllArtists()

	@Query("SELECT artistId FROM ArtistEntity")
	suspend fun getAllArtistIds(): List<String>

	@Query("SELECT * FROM ArtistEntity WHERE artistId IN (:ids)")
	suspend fun getArtistsByIds(ids: List<String>): List<ArtistEntity>

	/**
	 * Exact (case-insensitive) name lookup, for turning a credit string into artist ids.
	 *
	 * A track's artist is a single string — "A feat. B" — and the bundled Subsonic client exposes
	 * no per-artist array to go with it, so the featured names have to be resolved back to rows by
	 * name. Deliberately exact rather than LIKE: a substring match would let "Pink" claim
	 * "Pink Floyd".
	 */
	@Query("SELECT * FROM ArtistEntity WHERE name COLLATE NOCASE IN (:names)")
	suspend fun getArtistsByNames(names: List<String>): List<ArtistEntity>

	@Transaction
	suspend fun updateAllArtists(remoteArtists: List<ArtistEntity>) {
		val remoteIds = remoteArtists.map { it.artistId }.toSet()
		getAllArtistIds().forEach { localId ->
			if (localId !in remoteIds) {
				Logger.w("ArtistDao", "artist $localId no longer exists remotely")
				deleteArtist(localId)
			}
		}
		insertArtists(remoteArtists)
	}

	@Transaction
	suspend fun deleteObsoleteArtists(remoteIds: Set<String>) {
		getAllArtistIds().forEach { localId ->
			if (localId !in remoteIds) {
				Logger.w("ArtistDao", "artist $localId no longer exists remotely")
				deleteArtist(localId)
			}
		}
	}
}

/** One row of [ArtistDao.coverImageHashCounts]: an image hash and how many artists carry it. */
data class CoverHashCount(
	val hash: String,
	val artistCount: Int
)
