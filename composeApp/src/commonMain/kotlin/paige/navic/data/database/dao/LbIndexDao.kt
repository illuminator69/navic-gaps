package paige.navic.data.database.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import kotlinx.coroutines.flow.Flow
import paige.navic.data.database.LbIndexPagePlan
import paige.navic.data.database.entities.LbIndexArtistEntity
import paige.navic.data.database.entities.LbIndexMetaEntity
import paige.navic.data.database.entities.LbIndexReleaseEntity
import paige.navic.data.database.entities.LbResponseCacheEntity
import paige.navic.data.database.planIndexPage
import paige.navic.data.database.relations.LbIndexArtistWithReleases
import paige.navic.domain.manager.LbIndexChanges

/** Projection for [LbIndexDao.getKeySeqs]. */
data class LbIndexKeySeq(
	val artistKey: String,
	val seq: Long
)

/** Projection for [LbIndexDao.getTotals] — the drift check's local side. */
data class LbIndexTotals(
	val artistCount: Long,
	val seqSum: Long
)

/**
 * The lb-bot index mirror. Only [paige.navic.domain.manager.LbIndexSync] writes it, and only from
 * pages of lb-bot's change feed; everything else here is a read.
 */
@Dao
interface LbIndexDao {

	// ----- reads, for the artist / external / collection pages ------------------------------ //

	/**
	 * The mirrored artist for a Navidrome artist id, resolved EXACTLY as lb-bot's
	 * `_index_get_artist` resolves it (contract §1a): the [mbid] key first when the caller has
	 * one; otherwise `artistKey = 'nd:'+id OR ndArtistId = id`, newest scan first, `artistKey` as
	 * the tiebreak. That makes the brief two-rows state of an `nd:` → mbid swap resolve to the
	 * same row here as on the server and on every other client.
	 *
	 * One query rather than two lookups so the Flow twin below is a single observable read. Blank
	 * arguments are guarded explicitly: an external (`mb:`) artist has a blank `ndArtistId`, and a
	 * bare `ndArtistId = ''` would match every one of them.
	 */
	@Transaction
	@Query(
		"SELECT * FROM lb_index_artist " +
			"WHERE (:mbid != '' AND artistKey = :mbid) " +
			"OR (:ndId != '' AND (artistKey = 'nd:' || :ndId OR ndArtistId = :ndId)) " +
			"ORDER BY (:mbid != '' AND artistKey = :mbid) DESC, scannedAt DESC, artistKey " +
			"LIMIT 1"
	)
	suspend fun findArtist(ndId: String, mbid: String): LbIndexArtistWithReleases?

	/** [findArtist], re-emitted whenever the mirror changes. */
	@Transaction
	@Query(
		"SELECT * FROM lb_index_artist " +
			"WHERE (:mbid != '' AND artistKey = :mbid) " +
			"OR (:ndId != '' AND (artistKey = 'nd:' || :ndId OR ndArtistId = :ndId)) " +
			"ORDER BY (:mbid != '' AND artistKey = :mbid) DESC, scannedAt DESC, artistKey " +
			"LIMIT 1"
	)
	fun observeArtist(ndId: String, mbid: String): Flow<LbIndexArtistWithReleases?>

	/** Every mirrored row for one release-group, across artists (a collaboration spans several). */
	@Query("SELECT * FROM lb_index_release WHERE rgid = :rgid ORDER BY artistKey")
	suspend fun releasesByRgid(rgid: String): List<LbIndexReleaseEntity>

	/** The mirrored rows that claim a Navidrome album id (a JSON list, hence the quoted LIKE). */
	@Query("SELECT * FROM lb_index_release WHERE navidromeAlbumIds LIKE '%\"' || :albumId || '\"%'")
	suspend fun releasesByNavidromeAlbumId(albumId: String): List<LbIndexReleaseEntity>

	@Query("SELECT * FROM lb_index_artist WHERE artistKey = :artistKey LIMIT 1")
	suspend fun getArtistByKey(artistKey: String): LbIndexArtistEntity?

	@Query("SELECT * FROM lb_index_meta WHERE id = 0")
	suspend fun getMeta(): LbIndexMetaEntity?

	@Query("SELECT * FROM lb_index_meta WHERE id = 0")
	fun observeMeta(): Flow<LbIndexMetaEntity?>

	// ----- sync bookkeeping ------------------------------------------------------------------ //

	@Query("SELECT artistKey, seq FROM lb_index_artist")
	suspend fun getKeySeqs(): List<LbIndexKeySeq>

	@Query("SELECT COUNT(*) AS artistCount, COALESCE(SUM(seq), 0) AS seqSum FROM lb_index_artist")
	suspend fun getTotals(): LbIndexTotals

	@Insert(onConflict = OnConflictStrategy.REPLACE)
	suspend fun putMeta(meta: LbIndexMetaEntity)

	@Query("UPDATE lb_index_meta SET cursor = :cursor WHERE id = 0")
	suspend fun setCursor(cursor: Long)

	@Insert(onConflict = OnConflictStrategy.REPLACE)
	suspend fun upsertArtists(artists: List<LbIndexArtistEntity>)

	@Insert(onConflict = OnConflictStrategy.REPLACE)
	suspend fun insertReleases(releases: List<LbIndexReleaseEntity>)

	@Query("DELETE FROM lb_index_release WHERE artistKey IN (:keys)")
	suspend fun deleteReleasesFor(keys: List<String>)

	@Query("DELETE FROM lb_index_artist WHERE artistKey IN (:keys)")
	suspend fun deleteArtistRows(keys: List<String>)

	@Query("DELETE FROM lb_index_artist")
	suspend fun clearArtists()

	@Query("DELETE FROM lb_index_release")
	suspend fun clearReleases()

	@Query("DELETE FROM lb_index_meta")
	suspend fun clearMeta()

	/** Artists and their rows together, chunked by [KEY_CHUNK]. */
	@Transaction
	suspend fun deleteArtists(keys: List<String>) {
		keys.chunked(KEY_CHUNK).forEach {
			deleteReleasesFor(it)
			deleteArtistRows(it)
		}
	}

	/**
	 * Apply one normal page of the change feed in ONE transaction: the per-artist replaces, the
	 * tombstones, and the cursor/epoch/envelope together — so a crash or a cancelled coroutine
	 * leaves either the whole page or none of it, never an advanced cursor over rows that were not
	 * written (which no later pull would ever resend).
	 *
	 * The local seqs are read INSIDE the transaction and the rules live in the pure
	 * [planIndexPage]; [force] is the drift repair's set (see there).
	 */
	@Transaction
	suspend fun applyPage(page: LbIndexChanges, force: Set<String>): LbIndexPagePlan {
		val meta = getMeta() ?: LbIndexMetaEntity()
		val local = getKeySeqs().associate { it.artistKey to it.seq }
		val plan = planIndexPage(local, page, meta.cursor, force)
		deleteArtists(plan.deletes)
		// Artist-level replace: every row of a touched artist goes, then the new set lands.
		plan.upserts.map { it.artist.artistKey }.chunked(KEY_CHUNK).forEach { deleteReleasesFor(it) }
		if (plan.upserts.isNotEmpty()) {
			upsertArtists(plan.upserts.map { it.artist })
			insertReleases(plan.upserts.flatMap { it.releases })
		}
		putMeta(
			meta.copy(
				id = 0,
				epoch = page.epoch.ifBlank { meta.epoch },
				cursor = plan.newCursor,
				scanVersion = page.scanVersion,
				ttlDays = page.ttlDays
			)
		)
		return plan
	}

	/**
	 * A `resync` answer: the mirror belongs to another epoch (or is ahead of a restored lb-bot).
	 * Wipe the rows, adopt the new epoch at cursor 0. The ONLY path that wipes the mirror besides
	 * [clearAll].
	 */
	@Transaction
	suspend fun resetForEpoch(epoch: String, scanVersion: Int, ttlDays: Double) {
		clearReleases()
		clearArtists()
		putMeta(
			LbIndexMetaEntity(
				id = 0,
				epoch = epoch,
				cursor = 0L,
				scanVersion = scanVersion,
				ttlDays = ttlDays
			)
		)
	}

	// ----- response cache (non-index reads) --------------------------------------------------- //

	@Query("SELECT * FROM lb_response_cache WHERE `key` = :key LIMIT 1")
	suspend fun getCachedResponse(key: String): LbResponseCacheEntity?

	@Insert(onConflict = OnConflictStrategy.REPLACE)
	suspend fun putCachedResponse(entry: LbResponseCacheEntity)

	/**
	 * [putCachedResponse], unless the stored body is from a request that STARTED later.
	 *
	 * `fetchedAt` is stamped with when the request left, not when it answered (see
	 * `LbBotManager.cachedGet`), so two revalidations of one key racing — only possible across a
	 * library landing, which starts a second one rather than joining the first — cannot let the
	 * older answer, arriving last, overwrite the newer.
	 */
	@Transaction
	suspend fun putCachedResponseIfNewer(entry: LbResponseCacheEntity) {
		val existing = getCachedResponse(entry.key)
		if (existing != null && existing.fetchedAt > entry.fetchedAt) return
		putCachedResponse(entry)
	}

	/** Drop bodies nobody has revalidated since [cutoff] — the table's only bound. */
	@Query("DELETE FROM lb_response_cache WHERE fetchedAt < :cutoff")
	suspend fun pruneCachedResponses(cutoff: Long)

	@Query("DELETE FROM lb_response_cache")
	suspend fun clearResponseCache()

	/** Everything lb-bot-derived on this device — the logout / "clear data" path. */
	@Transaction
	suspend fun clearAll() {
		clearReleases()
		clearArtists()
		clearMeta()
		clearResponseCache()
	}
}

/** Keys per `IN (...)` statement — far under SQLite's bound-variable limit. */
private const val KEY_CHUNK = 500
