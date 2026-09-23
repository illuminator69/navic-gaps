package paige.navic.data.database

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import paige.navic.data.database.entities.LbIndexArtistEntity
import paige.navic.data.database.entities.LbIndexReleaseEntity
import paige.navic.domain.manager.LbDiscography
import paige.navic.domain.manager.LbIndexChanges
import paige.navic.domain.manager.LbIndexItem
import paige.navic.domain.manager.LbIndexKey
import paige.navic.domain.manager.LbRelease

/*
 * How a client applies lb-bot's index change feed (navi-connect contract §1a, "Client mirror
 * rules"), as PURE functions: local state and a page in, the writes to make out. No Room, no
 * clock, no network — so the rules can be read in one place and checked by hand (Navic has no
 * test source set; see the task report), and the DAO's transaction only executes the plan.
 */

/** An artist to write: the row, and the complete set of its releases that replaces the old one. */
data class LbIndexUpsert(
	val artist: LbIndexArtistEntity,
	val releases: List<LbIndexReleaseEntity>
)

/**
 * What one page does to the mirror. [upserts] and [deletes] are disjoint by key; [newCursor] is
 * `max(cursor, nextSince)` and is written in the same transaction as the rows.
 */
data class LbIndexPagePlan(
	val upserts: List<LbIndexUpsert>,
	val deletes: List<String>,
	val newCursor: Long,
	/** Items skipped because the local seq was already at or past them (duplicates, replays). */
	val skipped: Int
)

/**
 * Plan one normal (non-resync) page.
 *
 * - An artist is applied only if its seq is greater than the local seq for that key — so a page
 *   seen twice, or pages arriving out of order, change nothing the second time.
 * - A tombstone deletes the key only if its seq is greater than the local one. A tombstone for a
 *   key this mirror never held is a no-op; nothing records it, because a later reappearance of
 *   that key necessarily carries a larger seq anyway.
 * - Keys in [force] are applied whenever the seq DIFFERS rather than only when it is larger. Only
 *   the drift repair passes them: its whole premise is that the local seq for those keys is wrong,
 *   possibly wrong in the high direction, and "greater than" would then refuse the correction.
 * - Items are folded in seq order against a running copy of [localSeqs], so an item that appears
 *   twice in one page (it cannot, on a correct server) still resolves to the last applicable one.
 * - An item of an unknown `type` is ignored, not fatal.
 */
fun planIndexPage(
	localSeqs: Map<String, Long>,
	page: LbIndexChanges,
	cursor: Long,
	force: Set<String> = emptySet()
): LbIndexPagePlan {
	val running = localSeqs.toMutableMap()
	// key -> the item that wins for it; LinkedHashMap keeps the page's order for the writes.
	val winners = LinkedHashMap<String, LbIndexItem>()
	var skipped = 0
	for (item in page.items.sortedBy { it.seq }) {
		if (item.key.isBlank() || !(item.isArtist || item.isTombstone)) {
			skipped++
			continue
		}
		val local = running[item.key]
		val applies = when {
			item.key in force -> item.seq != local
			local == null -> item.isArtist
			else -> item.seq > local
		}
		if (!applies) {
			skipped++
			continue
		}
		winners.remove(item.key)
		winners[item.key] = item
		if (item.isArtist) running[item.key] = item.seq else running.remove(item.key)
	}
	val upserts = ArrayList<LbIndexUpsert>()
	val deletes = ArrayList<String>()
	for ((key, item) in winners) {
		if (item.isTombstone) {
			// Only a key that is actually held needs deleting; a forced tombstone for a missing
			// key is as much a no-op as an ordinary one.
			if (key in localSeqs) deletes.add(key)
		} else {
			upserts.add(item.toUpsert())
		}
	}
	return LbIndexPagePlan(
		upserts = upserts,
		deletes = deletes,
		newCursor = maxOf(cursor, page.nextSince),
		skipped = skipped
	)
}

/** What the drift check found, against `/lb/index/keys`. */
data class LbIndexDriftPlan(
	/** Local artists the server no longer has. Deleted directly — they will never reappear in the feed. */
	val deletes: List<String>,
	/** Keys whose server seq differs from the local one, or that are missing locally. */
	val refetch: Set<String>,
	/**
	 * `min(server seq of [refetch]) - 1`, or null when nothing needs re-pulling. There is no
	 * per-key route (contract ruling R6), so the repair is a re-pull from just below the oldest
	 * wrong key — at most once per sync — with [refetch] passed as `force`.
	 */
	val refetchFrom: Long?
)

/**
 * Compare the mirror with the server's key list. Never a wipe: only a `resync` answer wipes.
 * A blank server key is ignored rather than trusted.
 */
fun planIndexDrift(localSeqs: Map<String, Long>, server: List<LbIndexKey>): LbIndexDriftPlan {
	val serverSeqs = server.filter { it.key.isNotBlank() }.associate { it.key to it.seq }
	val deletes = localSeqs.keys.filter { it !in serverSeqs }
	val refetch = serverSeqs.filter { (key, seq) -> localSeqs[key] != seq }
	return LbIndexDriftPlan(
		deletes = deletes,
		refetch = refetch.keys,
		refetchFrom = refetch.values.minOrNull()?.let { maxOf(0L, it - 1) }
	)
}

/**
 * Whether an artist's stored discography should be offered a rescan — lb-bot's own rule, computed
 * here from the stored facts because the wire deliberately carries none of it (contract §1a:
 * "nothing computed at read time goes on the wire as a fact").
 */
fun lbIndexIsStale(
	scannedAt: Double,
	scanVersion: Int,
	envelopeScanVersion: Int,
	ttlDays: Double,
	nowSeconds: Double
): Boolean = nowSeconds - scannedAt > ttlDays * 86_400.0 || scanVersion != envelopeScanVersion

private val lbIndexJson = Json { ignoreUnknownKeys = true }
private val stringList = ListSerializer(String.serializer())

private fun LbIndexItem.toUpsert(): LbIndexUpsert = LbIndexUpsert(
	artist = LbIndexArtistEntity(
		artistKey = key,
		ndArtistId = ndArtistId,
		mbid = mbid,
		name = name,
		scannedAt = scannedAt,
		scanVersion = scanVersion,
		seq = seq
	),
	// distinctBy: the server's primary key is (artist_key, rgid), so a repeat can only be a
	// server bug — but a repeat here would abort the whole page's transaction on our own PK.
	releases = rows.filter { it.rgid.isNotBlank() }
		.distinctBy { it.rgid }
		.mapIndexed { index, row -> row.toEntity(key, index) }
)

private fun LbRelease.toEntity(artistKey: String, position: Int) = LbIndexReleaseEntity(
	artistKey = artistKey,
	rgid = rgid,
	position = position,
	title = title,
	year = year,
	primaryType = primaryType,
	secondaryTypes = lbIndexJson.encodeToString(stringList, secondaryTypes),
	effectiveType = effectiveType,
	status = status,
	matchMethod = matchMethod,
	matchScore = matchScore.toDouble(),
	groupId = groupId,
	present = present,
	total = total,
	navidromeAlbumIds = lbIndexJson.encodeToString(stringList, navidromeAlbumIds)
)

private fun decodeList(raw: String): List<String> =
	try {
		lbIndexJson.decodeFromString(stringList, raw)
	} catch (e: Exception) {
		emptyList()
	}

/** Back to the wire row the rest of the app already renders, so a mirror read and a network read are interchangeable. */
fun LbIndexReleaseEntity.toWire(): LbRelease = LbRelease(
	rgid = rgid,
	title = title,
	year = year,
	primaryType = primaryType,
	secondaryTypes = decodeList(secondaryTypes),
	effectiveType = effectiveType,
	status = status,
	matchMethod = matchMethod,
	matchScore = matchScore.toFloat(),
	groupId = groupId,
	present = present,
	total = total,
	navidromeAlbumIds = decodeList(navidromeAlbumIds)
)

/**
 * A mirrored artist in the shape `GET /lb/artist/discography` answers, `stale` computed against
 * the envelope. `scan` is absent: the in-flight scan record is not part of the index and only the
 * network read carries it.
 */
fun LbIndexArtistEntity.toDiscography(
	releases: List<LbIndexReleaseEntity>,
	envelopeScanVersion: Int,
	ttlDays: Double,
	nowSeconds: Double
): LbDiscography = LbDiscography(
	indexed = true,
	artistName = name,
	artistMbid = mbid,
	stale = lbIndexIsStale(scannedAt, scanVersion, envelopeScanVersion, ttlDays, nowSeconds),
	scannedAt = scannedAt,
	releases = releases.sortedBy { it.position }.map { it.toWire() }
)
