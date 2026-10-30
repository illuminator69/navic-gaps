package paige.navic.util

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import paige.navic.domain.models.DomainAlbumListType
import paige.navic.domain.models.DomainFilter
import paige.navic.domain.models.DomainSongListType
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Row 4a: the Albums and Songs lists sort and filter in SQL now. On a fixture in a real SQLite,
 * built from the exported schema (`24.json`), every sort × reversed × filter returns the same
 * rows in the same order as the old path: the old SQL with the songs relation, then the old
 * Kotlin filter, sort and reversal (kept here as the oracle).
 */
class SongAndAlbumListSqlTest {
	private val db: Connection = DriverManager.getConnection("jdbc:sqlite::memory:")

	@AfterTest
	fun close() = db.close()

	private val schema: JsonObject = Json.parseToJsonElement(
		File("schemas/paige.navic.data.database.CacheDatabase/24.json").readText()
	).jsonObject["database"]!!.jsonObject

	private fun entity(table: String) = schema["entities"]!!.jsonArray.map { it.jsonObject }
		.first { it["tableName"]!!.jsonPrimitive.content == table }

	private fun create(table: String) {
		val e = entity(table)
		db.createStatement().use { st ->
			st.execute(e["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
			e["indices"]?.jsonArray.orEmpty().forEach {
				st.execute(it.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
			}
		}
	}

	/** Inserts a row, filling every NOT NULL column the fixture leaves out with a zero value. */
	private fun insert(table: String, values: Map<String, Any?>) {
		val fields = entity(table)["fields"]!!.jsonArray.map { it.jsonObject }
		val row = fields.associate { f ->
			val column = f["columnName"]!!.jsonPrimitive.content
			column to if (column in values) values[column] else if (f["notNull"]?.jsonPrimitive?.boolean == true) {
				when (f["affinity"]!!.jsonPrimitive.content) {
					"INTEGER" -> 0
					"REAL" -> 0.0
					else -> ""
				}
			} else null
		}
		val sql = "INSERT INTO `$table` (${row.keys.joinToString { "`$it`" }}) VALUES (${row.keys.joinToString { "?" }})"
		db.prepareStatement(sql).use { ps ->
			row.values.forEachIndexed { i, v -> ps.setObject(i + 1, v) }
			ps.executeUpdate()
		}
	}

	private fun ids(sql: String, args: List<Any> = emptyList(), column: String): List<String> =
		db.prepareStatement(sql).use { ps ->
			args.forEachIndexed { i, v -> ps.setObject(i + 1, v) }
			ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(column)) } }
		}

	private fun rows(sql: String): List<Map<String, Any?>> = db.createStatement().use { st ->
		st.executeQuery(sql).use { rs ->
			val n = rs.metaData.columnCount
			buildList {
				while (rs.next()) add((1..n).associate { rs.metaData.getColumnName(it) to rs.getObject(it) })
			}
		}
	}

	// ---- the fixture: inserted out of id order, so the table's order is not the ids' ----

	private val downloaded = listOf("s01", "s02", "s03", "s10", "s20", "s21", "s30")

	init {
		create("AlbumEntity")
		create("SongEntity")
		data class A(val id: String, val name: String?, val artist: String?, val artistId: String, val year: Int?,
			val created: Long, val starred: Long?, val played: Long?, val plays: Int, val rating: Int?, val genre: String?)
		listOf(
			A("al3", "beta", "Zed", "ar2", 2001, 300, null, 50, 4, null, "Rock"),
			A("al1", "Alpha", "zed", "ar1", null, 300, 10, null, 0, 3, "Jazz"),
			A("al5", "alpha", "Ann", "ar1", 1999, 100, null, 70, 4, 5, "Rock"),
			A("al2", null, null, "ar3", 2001, 200, 20, 60, 2, 3, null),
			A("al4", "Gamma", "Bob", "ar2", 1999, 300, null, null, 0, null, "Rock"),
			A("al6", "delta", "Ann", "ar1", 2010, 400, 30, 80, 9, 1, "Pop"),
			A("al7", "empty", "Ann", "ar1", 2010, 50, null, null, 0, null, "Rock")
		).forEach {
			insert("AlbumEntity", mapOf(
				"albumId" to it.id, "name" to it.name, "artistName" to it.artist, "artistId" to it.artistId,
				"year" to it.year, "createdAt" to it.created, "starredAt" to it.starred,
				"lastPlayedAt" to it.played, "playCount" to it.plays, "userRating" to it.rating,
				"genre" to it.genre, "genres" to (it.genre ?: "")
			))
		}
		data class S(val id: String, val album: String?, val artistId: String, val plays: Int, val year: Int?,
			val rating: Int?, val starred: Long?, val genre: String?, val genres: String)
		listOf(
			S("s20", "al2", "ar3", 3, 2001, null, null, "Rock", "Rock||Pop"),
			S("s01", "al1", "ar1", 5, null, 4, 1, "Jazz", "Jazz"),
			S("s10", "al3", "ar2", 5, 2001, 2, null, null, "Pop"),
			S("s02", "al1", "ar1", 0, 1999, null, null, "Jazz", ""),
			S("s30", null, "ar9", 7, 2010, 5, 2, "Pop", "Pop||Rock"),
			S("s11", "al3", "ar2", 3, 2001, 4, null, "Rock", "Rock"),
			S("s03", "al1", "ar1", 5, null, null, 3, "Jazz", "Jazz||Popular"),
			S("s21", "al2", "ar1", 1, 2001, 1, null, "Pop", "Pop"),
			S("s40", "gone", "ar2", 2, 1987, 3, null, "Rock", "Rock"),
			S("s12", "al3", "ar2", 0, null, null, 4, "Popular", "Popular"),
			S("s50", "al5", "ar1", 3, 1999, 4, null, "Rock", "Rock"),
			S("s51", "al5", "ar1", 9, 1999, null, null, "Rock", "Rock||Jazz"),
			S("s60", "al6", "ar1", 1, 2010, 2, 5, "Pop", "Pop"),
			S("s41", "al4", "ar2", 5, 1999, 2, null, "Rock", "Rock")
		).forEach {
			insert("SongEntity", mapOf(
				"songId" to it.id, "belongsToAlbumId" to it.album, "artistId" to it.artistId, "title" to it.id,
				"playCount" to it.plays, "year" to it.year, "userRating" to it.rating, "starredAt" to it.starred,
				"genre" to it.genre, "genres" to it.genres
			))
		}
	}

	private val filterSets = listOf(
		emptySet(), setOf(DomainFilter.Starred), setOf(DomainFilter.Downloaded),
		setOf(DomainFilter.Starred, DomainFilter.Downloaded)
	)

	// ---- albums ----

	/** The old path: the old SQL (`getAlbumsByQuery`), the songs it carried, the Kotlin filters. */
	private fun oldAlbums(type: DomainAlbumListType, reversed: Boolean, filters: Set<DomainFilter>): List<String> {
		val (where, order) = when (type) {
			DomainAlbumListType.AlphabeticalByArtist -> null to "LOWER(artistName) ASC"
			DomainAlbumListType.AlphabeticalByName -> null to "LOWER(name) ASC"
			DomainAlbumListType.Frequent -> "playCount != 0" to "playCount DESC"
			DomainAlbumListType.Highest -> null to "userRating DESC"
			DomainAlbumListType.Newest -> null to "createdAt DESC"
			DomainAlbumListType.Recent -> "lastPlayedAt IS NOT NULL" to "lastPlayedAt DESC"
			DomainAlbumListType.Year -> null to "year DESC"
			else -> error("not in the fixture: $type")
		}
		val albums = rows("SELECT * FROM AlbumEntity" + (where?.let { " WHERE $it" } ?: "") + " ORDER BY $order")
		val songsOf = rows("SELECT songId, belongsToAlbumId FROM SongEntity")
			.groupBy({ it["belongsToAlbumId"] as String? }, { it["songId"] as String })
		return albums.filter { album ->
			filters.all {
				when (it) {
					DomainFilter.Starred -> album["starredAt"] != null
					DomainFilter.Downloaded -> downloaded.containsAll(songsOf[album["albumId"]].orEmpty())
				}
			}
		}.map { it["albumId"] as String }.let { if (reversed) it.asReversed() else it }
	}

	/** The new path, as `AlbumRepository.getLocalData` runs it. */
	private fun newAlbums(type: DomainAlbumListType, reversed: Boolean, filters: Set<DomainFilter>): List<String> {
		val q = type.albumListSql(
			starredOnly = DomainFilter.Starred in filters,
			downloadedSongIdsJson = if (DomainFilter.Downloaded in filters) idsAsJson(downloaded) else null
		)
		return ids(q.sql, q.args, "albumId").let { if (reversed) it.asReversed() else it }
	}

	@Test
	fun everyAlbumSortAndFilterReturnsTheOldRowsInTheOldOrder() {
		val types = listOf(
			DomainAlbumListType.AlphabeticalByArtist, DomainAlbumListType.AlphabeticalByName,
			DomainAlbumListType.Frequent, DomainAlbumListType.Highest, DomainAlbumListType.Newest,
			DomainAlbumListType.Recent, DomainAlbumListType.Year
		)
		for (type in types) for (reversed in listOf(false, true)) for (filters in filterSets) {
			assertEquals(oldAlbums(type, reversed, filters), newAlbums(type, reversed, filters), "$type reversed=$reversed $filters")
		}
	}

	@Test
	fun theDownloadedFilterIsWholeAlbumsOnly() {
		// al1 (s01–s03) whole; al2 (s20, s21) whole; al3 half; al7 has no songs, which the old
		// containsAll let through and still does.
		assertEquals(listOf("al1", "al2", "al7"), newAlbums(DomainAlbumListType.AlphabeticalByName, false, setOf(DomainFilter.Downloaded)).sorted())
	}

	@Test
	fun theAlbumQueryNeverReadsSongsUnlessFilteringDownloads() {
		for (type in listOf(DomainAlbumListType.AlphabeticalByName, DomainAlbumListType.Newest, DomainAlbumListType.Random)) {
			assertTrue("SongEntity" !in type.albumListSql(starredOnly = true).sql)
		}
		assertTrue(DomainAlbumListType.AlphabeticalByName.albumListSql(downloadedSongIdsJson = "[]").args == listOf("[]"))
	}

	// ---- songs ----

	/** The old path: every song in the table's order, the Kotlin filters, the old stable sort. */
	private fun oldSongs(
		type: DomainSongListType,
		reversed: Boolean,
		filters: Set<DomainFilter>,
		scope: List<String>? = null
	): List<String> {
		val added = rows("SELECT albumId, createdAt FROM AlbumEntity").associate { it["albumId"] as String to (it["createdAt"] as Number).toLong() }
		val songs = rows("SELECT * FROM SongEntity")
			.filter { scope == null || it["belongsToAlbumId"] in scope }
			.filter { song ->
				filters.all {
					when (it) {
						DomainFilter.Starred -> song["starredAt"] != null
						DomainFilter.Downloaded -> song["songId"] in downloaded
					}
				}
			}
		fun Map<String, Any?>.int(column: String) = (this[column] as Number?)?.toInt()
		val sorted = when (type) {
			DomainSongListType.FrequentlyPlayed -> songs.sortedByDescending { it.int("playCount") }
			DomainSongListType.Newest -> songs.sortedByDescending { song -> (song["belongsToAlbumId"] as String?)?.let { added[it] } }
			DomainSongListType.Rating -> songs.sortedByDescending { it.int("userRating") ?: 0 }
			DomainSongListType.Year -> songs.sortedByDescending { it.int("year") }
			is DomainSongListType.ByArtist -> songs.filter { it["artistId"] == type.artistId }.sortedByDescending { it.int("playCount") }
			is DomainSongListType.ByGenre -> songs.filter {
				it["genre"] == type.genre || type.genre in (it["genres"] as String).split("||").filter(String::isNotEmpty)
			}.sortedByDescending { it.int("playCount") }
			DomainSongListType.Random -> songs
		}.map { it["songId"] as String }
		return if (reversed) sorted.reversed() else sorted
	}

	private fun newSongs(
		type: DomainSongListType,
		reversed: Boolean,
		filters: Set<DomainFilter>,
		scope: List<String>? = null
	): List<String> {
		val q = type.songListSql(
			reversed = reversed,
			starredOnly = DomainFilter.Starred in filters,
			downloadedSongIdsJson = if (DomainFilter.Downloaded in filters) idsAsJson(downloaded) else null,
			albumIdsJson = scope?.let(::idsAsJson)
		)
		return ids(q.sql, q.args, "songId")
	}

	private val songTypes = listOf(
		DomainSongListType.FrequentlyPlayed, DomainSongListType.Newest, DomainSongListType.Rating,
		DomainSongListType.Year, DomainSongListType.ByArtist("ar2"), DomainSongListType.ByGenre("Pop"),
		DomainSongListType.ByGenre("Rock"), DomainSongListType.ByGenre("")
	)

	@Test
	fun everySongSortAndFilterReturnsTheOldRowsInTheOldOrder() {
		for (type in songTypes) for (reversed in listOf(false, true)) for (filters in filterSets) {
			assertEquals(oldSongs(type, reversed, filters), newSongs(type, reversed, filters), "$type reversed=$reversed $filters")
		}
	}

	@Test
	fun randomIsTheSameRowsInSomeOrder() {
		for (filters in filterSets) {
			assertEquals(
				oldSongs(DomainSongListType.Random, false, filters).sorted(),
				newSongs(DomainSongListType.Random, false, filters).sorted()
			)
			assertEquals(
				oldAlbums(DomainAlbumListType.AlphabeticalByName, false, filters).sorted(),
				newAlbums(DomainAlbumListType.Random, false, filters).sorted()
			)
		}
	}

	@Test
	fun anArtistsSongsAreTheSongsOfTheirAlbums() {
		val scope = listOf("al1", "al5", "al6", "al7")
		for (type in songTypes) for (reversed in listOf(false, true)) for (filters in filterSets) {
			assertEquals(oldSongs(type, reversed, filters, scope), newSongs(type, reversed, filters, scope), "$type $filters")
		}
		assertEquals(emptyList(), newSongs(DomainSongListType.FrequentlyPlayed, false, emptySet(), emptyList()))
	}

	@Test
	fun aGenreIsMatchedWholeNotAsAPrefix() {
		// "Pop" is not "Popular" (s03, s12), and an empty genre matches only an empty `genre`.
		assertEquals(listOf("s30", "s10", "s21", "s60", "s20").sorted(), newSongs(DomainSongListType.ByGenre("Pop"), false, emptySet()).sorted())
	}

	@Test
	fun idsAsJsonQuotesWhatItMust() {
		assertEquals("""["a","b\"c","d\\e"]""", idsAsJson(listOf("a", "b\"c", "d\\e")))
		assertEquals(listOf("b\"c"), ids("SELECT value FROM json_each(?) WHERE value = ?", listOf(idsAsJson(listOf("a", "b\"c")), "b\"c"), "value"))
	}

	@Test
	fun theListsReadNoSongsRelation() {
		val repo = File("src/commonMain/kotlin/paige/navic/domain/repositories/AlbumRepository.kt").readText()
		val albums = repo.substringAfter("private suspend fun getLocalData(").substringBefore("suspend fun getAlbumWithSongs")
		assertTrue("getAlbumRowsByQuery" in albums && "getAlbumsByQuery" !in albums && ".songs" !in albums && "AlbumWithSongs" !in albums, albums)
		val dao = File("src/commonMain/kotlin/paige/navic/data/database/dao/AlbumDao.kt").readText()
		val rowsQuery = dao.substringBefore("suspend fun getAlbumRowsByQuery").substringAfterLast("*/")
		assertTrue("@Transaction" !in rowsQuery, rowsQuery)
		assertTrue("suspend fun getAlbumRowsByQuery(query: RoomRawQuery): List<AlbumEntity>" in dao)
		val songs = File("src/commonMain/kotlin/paige/navic/domain/repositories/SongRepository.kt").readText()
			.substringAfter("private suspend fun getLocalData(").substringBefore("private suspend fun refreshLocalData(")
		assertTrue("getAllSongs" !in songs && "getAlbumsByArtist(" !in songs && ".songs" !in songs, songs)
	}
}
