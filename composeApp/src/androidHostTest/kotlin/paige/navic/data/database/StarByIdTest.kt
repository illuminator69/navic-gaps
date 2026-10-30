package paige.navic.data.database

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Row 10 (adapted from upstream ce5861ed … 33e28839): starring and rating write ONE column by id.
 * The DAOs' own UPDATE statements run on a real SQLite (the schema from 24.json) over a row with
 * every column set, and only the starred / rated column may move.
 */
class StarByIdTest {
	private val db = DriverManager.getConnection("jdbc:sqlite::memory:")
	private val schema: JsonObject = Json.parseToJsonElement(
		File("schemas/paige.navic.data.database.CacheDatabase/24.json").readText()
	).jsonObject["database"]!!.jsonObject
	private val daoDir = File("src/commonMain/kotlin/paige/navic/data/database/dao")

	private fun entity(table: String) = schema["entities"]!!.jsonArray.map { it.jsonObject }
		.first { it["tableName"]!!.jsonPrimitive.content == table }

	/** The @Query above `fun [name]` in [dao], its `:params` in the order they appear. */
	private fun query(dao: String, name: String): Pair<String, List<String>> {
		val text = File(daoDir, dao).readText()
		val sql = Regex("""@Query\("([^"]+)"\)\s+suspend fun $name\(""").find(text)!!.groupValues[1]
		val params = Regex(""":(\w+)""").findAll(sql).map { it.groupValues[1] }.toList()
		return sql.replace(Regex(""":\w+"""), "?") to params
	}

	/** A row with every column set to something that is not a default. */
	private fun fullRow(table: String, idColumn: String, id: String): Map<String, Any> {
		val e = entity(table)
		db.createStatement().use { it.execute(e["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table)) }
		val row = e["fields"]!!.jsonArray.map { it.jsonObject }.associate { f ->
			val column = f["columnName"]!!.jsonPrimitive.content
			column to when {
				column == idColumn -> id
				f["affinity"]!!.jsonPrimitive.content == "INTEGER" -> 7
				f["affinity"]!!.jsonPrimitive.content == "REAL" -> 0.5
				else -> "kept-$column"
			}
		}
		db.prepareStatement("INSERT INTO `$table` (${row.keys.joinToString { "`$it`" }}) VALUES (${row.keys.joinToString { "?" }})").use { ps ->
			row.values.forEachIndexed { i, v -> ps.setObject(i + 1, v) }
			ps.executeUpdate()
		}
		return row
	}

	private fun read(table: String, idColumn: String, id: String): Map<String, Any?> =
		db.prepareStatement("SELECT * FROM `$table` WHERE `$idColumn` = ?").use { ps ->
			ps.setString(1, id)
			ps.executeQuery().use { rs ->
				rs.next()
				(1..rs.metaData.columnCount).associate { rs.metaData.getColumnName(it) to rs.getObject(it) }
			}
		}

	private fun run(dao: String, name: String, args: Map<String, Any?>): Int {
		val (sql, params) = query(dao, name)
		return db.prepareStatement(sql).use { ps ->
			params.forEachIndexed { i, p -> ps.setObject(i + 1, args.getValue(p)) }
			ps.executeUpdate()
		}
	}

	private fun assertOnly(table: String, idColumn: String, before: Map<String, Any>, changed: String, value: Any?) {
		val after = read(table, idColumn, before.getValue(idColumn) as String)
		before.forEach { (column, was) ->
			if (column == changed) assertEquals(value, after[column], column)
			else assertEquals(was.toString(), after[column].toString(), "$column was erased")
		}
	}

	@Test
	fun starringAndRatingAnAlbumKeepEveryOtherColumn() {
		val row = fullRow("AlbumEntity", "albumId", "al1")
		assertEquals(1, run("AlbumDao.kt", "setAlbumStarredAt", mapOf("albumId" to "al1", "starredAtMillis" to 1234L)))
		assertOnly("AlbumEntity", "albumId", row, "starredAt", 1234)
		assertEquals(1, run("AlbumDao.kt", "setAlbumStarredAt", mapOf("albumId" to "al1", "starredAtMillis" to null)))
		assertOnly("AlbumEntity", "albumId", row, "starredAt", null)
		assertEquals(1, run("AlbumDao.kt", "setAlbumRating", mapOf("albumId" to "al1", "rating" to 4)))
		assertEquals(4, read("AlbumEntity", "albumId", "al1")["userRating"])
		assertEquals("kept-name", read("AlbumEntity", "albumId", "al1")["name"])
		assertEquals(0, run("AlbumDao.kt", "setAlbumRating", mapOf("albumId" to "nope", "rating" to 4)))
	}

	@Test
	fun starringAndRatingASongKeepEveryOtherColumn() {
		val row = fullRow("SongEntity", "songId", "s1")
		assertEquals(1, run("SongDao.kt", "setSongStarredAt", mapOf("songId" to "s1", "starredAtMillis" to 99L)))
		assertOnly("SongEntity", "songId", row, "starredAt", 99)
		assertEquals(1, run("SongDao.kt", "setSongRating", mapOf("songId" to "s1", "rating" to 2)))
		assertEquals(2, read("SongEntity", "songId", "s1")["userRating"])
		assertEquals("kept-title", read("SongEntity", "songId", "s1")["title"])
	}

	@Test
	fun starringAnArtistKeepsTheBiography() {
		val row = fullRow("ArtistEntity", "artistId", "ar1")
		assertEquals(1, run("ArtistDao.kt", "setArtistStarredAt", mapOf("artistId" to "ar1", "starredAtMillis" to 5L)))
		assertOnly("ArtistEntity", "artistId", row, "starredAt", 5)
		assertEquals("kept-biography", read("ArtistEntity", "artistId", "ar1")["biography"])
	}

	@Test
	fun theRepositoriesWriteByIdAndInsertOnlyAMissingRow() {
		val repos = File("src/commonMain/kotlin/paige/navic/domain/repositories")
		for ((file, setters) in mapOf(
			"AlbumRepository.kt" to listOf("setAlbumStarredAt", "setAlbumRating"),
			"SongRepository.kt" to listOf("setSongStarredAt", "setSongRating"),
			"ArtistRepository.kt" to listOf("setArtistStarredAt")
		)) {
			val lines = File(repos, file).readLines()
			setters.forEach { setter -> assertTrue(lines.any { "$setter(" in it && "== 0) {" in it }, "$file $setter") }
			// Every whole-row write left in these files is the fallback for a row that is not there.
			lines.forEachIndexed { i, line ->
				if (Regex("""(insertAlbum|insertSong|insertArtist)\(""").containsMatchIn(line)) {
					assertTrue("== 0) {" in lines[i - 1], "$file:${i + 1} writes a whole row: $line")
				}
			}
		}
	}
}
