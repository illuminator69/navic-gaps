package paige.navic.data.database

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The hand-written index migrations (CacheDatabase 23 → 24, DownloadDatabase 4 → 5) must create
 * EXACTLY what Room's regenerated schema declares, or Room's post-migration validation fails on
 * launch, and must change nothing else (rule 5). Read from the exported schema JSONs, as 22 → 23
 * was checked by script.
 */
class CacheDatabase24MigrationTest {
	private val schemas = File("schemas")

	private fun schema(db: String, version: Int): JsonObject =
		Json.parseToJsonElement(File(schemas, "paige.navic.data.database.$db/$version.json").readText())
			.jsonObject["database"]!!.jsonObject

	private fun JsonObject.entities() = this["entities"]!!.jsonArray.map { it.jsonObject }

	private fun JsonObject.indexSql(): Set<String> = entities().flatMap { entity ->
		val table = entity["tableName"]!!.jsonPrimitive.content
		entity["indices"]?.jsonArray.orEmpty().map {
			it.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table)
		}
	}.toSet()

	private fun JsonObject.tableSql(): Map<String, String> = entities().associate {
		it["tableName"]!!.jsonPrimitive.content to it["createSql"]!!.jsonPrimitive.content
	}

	/** Runs [migration] on a connection that records every statement instead of executing it. */
	private fun recorded(migration: Migration): List<String> {
		val sql = mutableListOf<String>()
		val statement = Proxy.newProxyInstance(
			SQLiteStatement::class.java.classLoader, arrayOf(SQLiteStatement::class.java)
		) { _, method, _ ->
			when (method.returnType) {
				java.lang.Boolean.TYPE -> false
				java.lang.Integer.TYPE -> 0
				java.lang.Long.TYPE -> 0L
				else -> null
			}
		} as SQLiteStatement
		val connection = Proxy.newProxyInstance(
			SQLiteConnection::class.java.classLoader, arrayOf(SQLiteConnection::class.java)
		) { _, method, args ->
			when (method.name) {
				"prepare" -> { sql += args!![0] as String; statement }
				"execSQL" -> { sql += args!![0] as String; Unit }
				"inTransaction" -> false
				else -> null
			}
		} as SQLiteConnection
		runBlocking { migration.migrate(connection) }
		return sql
	}

	private fun assertMigrates(db: String, from: Int, migration: Migration) {
		val before = schema(db, from)
		val after = schema(db, from + 1)
		assertEquals(before.tableSql(), after.tableSql(), "$db ${from + 1} changes a table, not only indices")
		val added = after.indexSql() - before.indexSql()
		assertTrue(before.indexSql().all { it in after.indexSql() }, "$db ${from + 1} drops an index")
		assertTrue(added.isNotEmpty())
		assertEquals(added, recorded(migration).toSet())
	}

	@Test
	fun cache23to24CreatesExactlyTheNewIndices() =
		assertMigrates("CacheDatabase", 23, MIGRATION_CACHE_23_24)

	@Test
	fun download4to5CreatesExactlyTheNewIndex() =
		assertMigrates("DownloadDatabase", 4, MIGRATION_DOWNLOAD_4_5)

	@Test
	fun bothMigrationsAreRegistered() {
		listOf(
			"src/androidMain/kotlin/paige/navic/di/PlatformModule.android.kt"
		).forEach { path ->
			val text = File(path).readText()
			assertTrue("MIGRATION_CACHE_23_24\n" in text.substringAfter(".addMigrations("), path)
			assertTrue("MIGRATION_DOWNLOAD_4_5)" in text, path)
		}
	}
}
