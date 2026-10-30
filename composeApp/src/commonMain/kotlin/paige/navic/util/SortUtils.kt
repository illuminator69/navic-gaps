package paige.navic.util

import androidx.room3.RoomRawQuery
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import paige.navic.domain.models.DomainAlbumListType
import paige.navic.domain.models.DomainSongListType

/**
 * A raw query's SQL and its arguments, kept apart so the host tests can run the very SQL the
 * repositories hand Room (`SongAndAlbumListSqlTest`, on a real SQLite).
 */
data class SqlQuery(val sql: String, val args: List<Any>) {
	fun toRoomRawQuery(): RoomRawQuery = RoomRawQuery(sql) { statement ->
		args.forEachIndexed { index, arg ->
			val bindIndex = index + 1
			when (arg) {
				is String -> statement.bindText(bindIndex, arg)
				is Int -> statement.bindInt(bindIndex, arg)
				is Long -> statement.bindLong(bindIndex, arg)
				is Float -> statement.bindFloat(bindIndex, arg)
				is Double -> statement.bindDouble(bindIndex, arg)
				is Boolean -> statement.bindInt(bindIndex, if (arg) 1 else 0)
			}
		}
	}
}

/**
 * Ids as ONE bound argument, a JSON array, read back in SQL with `json_each(?)`.
 *
 * The downloaded songs live in another database file (`downloads.db`), so a list can't JOIN
 * them; and binding each id would pass SQLite's bind-variable ceiling on a large library. One
 * JSON text has no ceiling, and the bundled SQLite has the JSON functions built in.
 */
fun idsAsJson(ids: Collection<String>): String = JsonArray(ids.map(::JsonPrimitive)).toString()

/**
 * A whole-library list is read in two parts: the ids, filtered and sorted in SQL through a raw
 * query (one column), then the rows through Room's typed queries, put in the ids' order here.
 * Room maps a RAW query's rows looking each column up by name on every row: measured on the VM
 * AVD over 11,821 songs, the sorted full rows took 4.0 to 4.3 s through `@RawQuery`, the sorted
 * ids 25 ms and every row through a typed `@Query` 0.42 s.
 */
const val ROWS_BY_ID_MAX = 2000

/** [rows] in the order of [ids]; an id with no row (a sync between the two reads) is left out. */
fun <T> arrangeByIds(ids: List<String>, rows: List<T>, id: (T) -> String): List<T> {
	val byId = rows.associateBy(id)
	return ids.mapNotNull { byId[it] }
}

/**
 * The rows for [ids], in their order: by id in chunks under SQLite's oldest bind ceiling for a
 * short list (an artist's songs, a filter), else the whole table, which a whole-library list
 * reads anyway.
 */
suspend fun <T> rowsInOrder(
	ids: List<String>,
	all: suspend () -> List<T>,
	byIds: suspend (List<String>) -> List<T>,
	id: (T) -> String
): List<T> {
	if (ids.isEmpty()) return emptyList()
	val rows = if (ids.size > ROWS_BY_ID_MAX) all() else ids.chunked(500).flatMap { byIds(it) }
	return arrangeByIds(ids, rows, id)
}

/**
 * The Songs list's query: its sort, its filters and its scope in SQL, where it used to read every
 * song, map it and sort it in Kotlin (upstream `12f75c49`'s idea). It selects the ids alone
 * ([rowsInOrder] reads the rows).
 *
 * Ties keep the table's own order (`rowid`), which is the order the old Kotlin sort kept them in
 * (a stable sort over `SELECT *`); [reversed] turns every key, ties included, so the list is the
 * old one read backwards. Nulls go last in a descending sort and first in a reversed one, as they
 * did in Kotlin.
 *
 * - [starredOnly]: the Starred filter.
 * - [downloadedSongIdsJson]: the Downloaded filter, the DOWNLOADED song ids ([idsAsJson]); null = off.
 * - [albumIdsJson]: an artist page's "See more", the artist's album ids ([idsAsJson]); null = the library.
 */
fun DomainSongListType.songListSql(
	reversed: Boolean = false,
	starredOnly: Boolean = false,
	downloadedSongIdsJson: String? = null,
	albumIdsJson: String? = null
): SqlQuery {
	val where = mutableListOf<String>()
	val args = mutableListOf<Any>()

	when (this) {
		is DomainSongListType.ByArtist -> {
			where += "artistId = ?"
			args += artistId
		}

		is DomainSongListType.ByGenre -> {
			// `genres` is stored "a||b" (Converters); an exact element match, as `genre in genres` was.
			where += "(genre = ? OR (? <> '' AND instr('||' || genres || '||', '||' || ? || '||') > 0))"
			args += genre
			args += genre
			args += genre
		}

		else -> {}
	}
	if (starredOnly) where += "starredAt IS NOT NULL"
	if (downloadedSongIdsJson != null) {
		where += "songId IN (SELECT value FROM json_each(?))"
		args += downloadedSongIdsJson
	}
	if (albumIdsJson != null) {
		where += "belongsToAlbumId IN (SELECT value FROM json_each(?))"
		args += albumIdsJson
	}

	val key = when (this) {
		DomainSongListType.FrequentlyPlayed,
		is DomainSongListType.ByArtist,
		is DomainSongListType.ByGenre -> "playCount"

		DomainSongListType.Newest ->
			"(SELECT a.createdAt FROM AlbumEntity a WHERE a.albumId = SongEntity.belongsToAlbumId)"

		DomainSongListType.Rating -> "COALESCE(userRating, 0)"
		DomainSongListType.Year -> "year"
		DomainSongListType.Random -> null
	}
	val orderBy = if (key == null) "RANDOM()"
	else if (reversed) "$key ASC, rowid DESC"
	else "$key DESC, rowid ASC"

	val whereClause = if (where.isEmpty()) "" else " WHERE " + where.joinToString(" AND ")
	return SqlQuery("SELECT songId FROM SongEntity$whereClause ORDER BY $orderBy", args)
}

/**
 * An album list's query, with no songs: the sort, plus the Library's two filters in SQL.
 *
 * - [starredOnly]: the Starred filter.
 * - [downloadedSongIdsJson]: the Downloaded filter. An album passes when none of its songs is
 *   missing from these ids (upstream `AlbumQueryBuilder`'s `NOT EXISTS`, against the ids since
 *   the downloads are in another database file), which is what `containsAll(album.songs)` was.
 */
fun DomainAlbumListType.albumListSql(
	limit: Int? = null,
	starredOnly: Boolean = false,
	downloadedSongIdsJson: String? = null,
	/** `albumId` for a whole-library list ([rowsInOrder]); `*` for the relation reads. */
	select: String = "*"
): SqlQuery {
	val where = mutableListOf<String>()
	val orderBy: String
	val args = mutableListOf<Any>()

	when (this) {
		DomainAlbumListType.AlphabeticalByArtist -> orderBy = "LOWER(artistName) ASC"
		DomainAlbumListType.AlphabeticalByName -> orderBy = "LOWER(name) ASC"
		DomainAlbumListType.Frequent -> {
			where += "playCount != 0"
			orderBy = "playCount DESC"
		}

		DomainAlbumListType.Highest -> orderBy = "userRating DESC"
		DomainAlbumListType.Newest -> orderBy = "createdAt DESC"
		DomainAlbumListType.Random -> orderBy = "RANDOM()"
		// What Home's Recently played shelf is the first twelve of: played albums only (audit W-11).
		DomainAlbumListType.Recent -> {
			where += "lastPlayedAt IS NOT NULL"
			orderBy = "lastPlayedAt DESC"
		}

		DomainAlbumListType.Year -> {
			orderBy = "year DESC"
		}

		is DomainAlbumListType.ByGenre -> {
			where += "genre = ?"
			orderBy = "LOWER(name) ASC"
			args.add(genre)
		}

		is DomainAlbumListType.ByYear -> {
			where += "COALESCE(year, 0) BETWEEN ? AND ?"
			orderBy = "LOWER(name) ASC"
			args.add(fromYear)
			args.add(toYear)
		}
	}
	if (starredOnly) where += "starredAt IS NOT NULL"
	if (downloadedSongIdsJson != null) {
		where += "NOT EXISTS (SELECT 1 FROM SongEntity s WHERE s.belongsToAlbumId = AlbumEntity.albumId " +
			"AND s.songId NOT IN (SELECT value FROM json_each(?)))"
		args.add(downloadedSongIdsJson)
	}

	// Ties in the table's own order, as before, whichever index the filters make SQLite use.
	val tieBreak = if (this == DomainAlbumListType.Random) "" else ", rowid ASC"
	val whereClause = if (where.isEmpty()) "" else " WHERE " + where.joinToString(" AND ")
	val sql = "SELECT $select FROM AlbumEntity$whereClause ORDER BY $orderBy$tieBreak" +
		(limit?.let { " LIMIT $it" } ?: "")
	return SqlQuery(sql, args)
}

fun DomainAlbumListType.toSqlQuery(limit: Int? = null): RoomRawQuery =
	albumListSql(limit).toRoomRawQuery()
