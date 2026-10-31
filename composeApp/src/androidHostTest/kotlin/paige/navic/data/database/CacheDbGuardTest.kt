package paige.navic.data.database

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/*
 * B-167: a corrupt cache.db is moved aside before Room opens it, and a sound one is not touched.
 * The check runs through sqlite-jdbc here (the bundled driver's natives are Android's); the app
 * runs the same PRAGMA through BundledSQLiteDriver (`bundledQuickCheck`).
 */
class CacheDbGuardTest {
	private val dir: File = Files.createTempDirectory("cachedb").toFile()

	@AfterTest
	fun cleanUp() {
		dir.deleteRecursively()
	}

	private fun jdbcQuickCheck(file: File): String =
		DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { connection ->
			connection.createStatement().use { statement ->
				statement.executeQuery("PRAGMA quick_check(1)").use { rows -> if (rows.next()) rows.getString(1) else "" }
			}
		}

	/** Tables only: an index (a TEXT primary key's autoindex) is a row of sqlite_master too. */
	private fun tableCount(file: File): Int =
		DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { connection ->
			connection.createStatement().use { statement ->
				statement.executeQuery("SELECT count(*) FROM sqlite_master WHERE type = 'table'").use { rows -> rows.next(); rows.getInt(1) }
			}
		}

	@Test
	fun fourMiBOfGarbageIsMovedAsideAndAFreshCacheOpens() {
		val db = File(dir, "cache.db")
		val garbage = Random(167).nextBytes(4 * 1024 * 1024)
		db.writeBytes(garbage)
		File(dir, "cache.db-wal").writeBytes(Random(1).nextBytes(1024))
		// A copy moved aside by an earlier launch: only the newest is kept.
		File(dir, "cache.db.corrupt-1000").writeBytes(byteArrayOf(1, 2, 3))

		val result = guardCacheDb(db, nowMs = 2000, quickCheck = ::jdbcQuickCheck)

		val moved = assertIs<CacheDbCheck.Quarantined>(result)
		assertEquals("cache.db.corrupt-2000", moved.movedTo.name)
		assertFalse(db.exists(), "cache.db must be gone for Room to create it afresh")
		assertFalse(File(dir, "cache.db-wal").exists())
		assertContentEquals(garbage, File(dir, "cache.db.corrupt-2000").readBytes())
		// A -wal that is not a valid log is dropped by SQLite itself when the check's connection
		// closes; either way none is left beside the fresh cache (asserted above).
		assertFalse(File(dir, "cache.db.corrupt-1000").exists())

		// What Room then opens is an empty, sound database.
		assertEquals(0, tableCount(db))
		assertEquals("ok", jdbcQuickCheck(db))
	}

	@Test
	fun aHealthyCacheIsLeftExactlyAsItWas() {
		val db = File(dir, "cache.db")
		DriverManager.getConnection("jdbc:sqlite:${db.absolutePath}").use { connection ->
			connection.createStatement().use { it.executeUpdate("CREATE TABLE album(id TEXT PRIMARY KEY)") }
			connection.createStatement().use { it.executeUpdate("INSERT INTO album VALUES ('a1')") }
		}
		val stamp = 1_600_000_000_000L
		assertTrue(db.setLastModified(stamp))
		val bytes = db.readBytes()

		val result = guardCacheDb(db, nowMs = 2000, quickCheck = ::jdbcQuickCheck)

		assertEquals(CacheDbCheck.Healthy, result)
		assertEquals(stamp, db.lastModified())
		assertContentEquals(bytes, db.readBytes())
		assertTrue(dir.listFiles()!!.none { it.name.contains("corrupt") })
		assertEquals(1, tableCount(db))
	}

	@Test
	fun noFileIsNothingToCheck() {
		assertEquals(CacheDbCheck.Missing, guardCacheDb(File(dir, "cache.db"), 1, ::jdbcQuickCheck))
	}

	@Test
	fun aCheckThatFailsForAnotherReasonLeavesTheFile() {
		val db = File(dir, "cache.db")
		db.writeBytes(byteArrayOf(9, 9, 9))
		val result = guardCacheDb(db, 1) { throw IllegalStateException("Error code: 5, message: database is locked") }
		assertIs<CacheDbCheck.Unchecked>(result)
		assertTrue(db.exists())
	}

	@Test
	fun corruptionIsReadInEveryDriversWords() {
		assertTrue(isSqliteCorruption(IllegalStateException("Error code: 11, message: database disk image is malformed")))
		assertTrue(isSqliteCorruption(RuntimeException("wrap", IllegalStateException("Error code: 26, message: file is not a database"))))
		assertTrue(isSqliteCorruption(IllegalStateException("[SQLITE_NOTADB] File opened that is not a database file (file is not a database)")))
		assertTrue(isSqliteCorruption(IllegalStateException("database disk image is malformed (code 11 SQLITE_CORRUPT)")))
		assertFalse(isSqliteCorruption(IllegalStateException("Error code: 19, message: UNIQUE constraint failed: album.id")))
		assertFalse(isSqliteCorruption(IllegalStateException("Error code: 110, message: something")))
		assertFalse(isSqliteCorruption(IOException("No space left on device")))
	}
}
