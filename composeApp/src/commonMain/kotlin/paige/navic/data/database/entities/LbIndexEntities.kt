package paige.navic.data.database.entities

import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey

/*
 * The local mirror of lb-bot's library index (navi-connect PLAN-lbbot-index-mirror, contract
 * §1a "Client mirror rules"). lb-bot is the only writer of the index; these tables change ONLY
 * when a page of `GET /lb/index/changes` is applied by [paige.navic.domain.manager.LbIndexSync].
 * Nothing a user does on this device writes a row here — an optimistic "fill in progress" belongs
 * in LbBotManager's ledgers, never in the mirror.
 *
 * Deliberately separate from [ArtistEntity] / [AlbumEntity]: `syncArtists` and the album sync
 * rewrite those rows whole, so a column added there would be erased by the next library pull.
 *
 * Added in CacheDatabase 23 by a hand-written, purely additive migration (MIGRATION_CACHE_22_23);
 * a column change here must change that migration's SQL too, or Room's post-migration validation
 * fails on launch for every upgraded install.
 */

/**
 * One artist as lb-bot's `artists` table holds it. [artistKey] is the artist's MusicBrainz id, or
 * `nd:<navidrome id>` for an artist lb-bot could not resolve to one.
 *
 * [seq] is lb-bot's per-artist change sequence. An incoming artist is applied only if its seq is
 * greater than the one stored here, which makes a duplicate or out-of-order page harmless.
 *
 * `stale` is NOT stored: it is computed on read from [scannedAt], [scanVersion] and the envelope's
 * `scanVersion`/`ttlDays` kept in [LbIndexMetaEntity], so it cannot drift while nothing is written.
 */
@Entity(
	tableName = "lb_index_artist",
	indices = [Index("ndArtistId")]
)
data class LbIndexArtistEntity(
	@PrimaryKey val artistKey: String,
	val ndArtistId: String,
	val mbid: String,
	val name: String,
	/** Epoch SECONDS, a float upstream (`time.time()`). */
	val scannedAt: Double,
	val scanVersion: Int,
	val seq: Long
)

/**
 * One release-group row of an artist, exactly the fields of lb-bot's `_index_row_to_wire` and
 * nothing invented. Always replaced as a whole set per artist — a client never holds a
 * half-updated artist.
 *
 * [position] is the order the rows arrived in, which is lb-bot's own `ORDER BY year, title`;
 * reading back by it reproduces the server's order without re-implementing its collation.
 */
@Entity(
	tableName = "lb_index_release",
	primaryKeys = ["artistKey", "rgid"]
)
data class LbIndexReleaseEntity(
	val artistKey: String,
	val rgid: String,
	val position: Int,
	val title: String,
	val year: String,
	val primaryType: String,
	/** JSON text: a list of MusicBrainz secondary types. */
	val secondaryTypes: String,
	val effectiveType: String,
	val status: String,
	val matchMethod: String,
	val matchScore: Double,
	/** Only on an `incomplete` row upstream; null otherwise. */
	val groupId: String?,
	val present: Int?,
	val total: Int?,
	/** JSON text: a list of Navidrome album ids (empty list when upstream omitted the key). */
	val navidromeAlbumIds: String
)

/**
 * The mirror's single bookkeeping row (always [id] 0): which index epoch the rows belong to, how
 * far the change feed has been applied, and the envelope values `stale` is computed against.
 *
 * An empty [epoch] means "never synced"; the first pull adopts whatever epoch lb-bot answers.
 */
@Entity(tableName = "lb_index_meta")
data class LbIndexMetaEntity(
	@PrimaryKey val id: Int = 0,
	val epoch: String = "",
	val cursor: Long = 0L,
	val scanVersion: Int = 0,
	val ttlDays: Double = 0.0
)

/**
 * Cached bodies of lb-bot's NON-index reads (editorial meta, releases, tracklists, Fresh, Discover
 * rows), for stale-while-revalidate. [key] is the route plus its sorted params; [body] is the raw
 * JSON exactly as the hub answered it; [fetchedAt] is epoch millis of when the request that
 * produced it LEFT. Written and read only by `LbBotManager.cachedGet`, which owns the key format.
 */
@Entity(tableName = "lb_response_cache")
data class LbResponseCacheEntity(
	@PrimaryKey val key: String,
	val body: String,
	val fetchedAt: Long
)
