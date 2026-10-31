package paige.navic.data.database

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READWRITE
import paige.navic.util.Logger
import java.io.File

/**
 * A corrupt `cache.db` is dropped before Room opens it, never crashed on (B-167).
 *
 * The bundled SQLite driver bypasses the framework's `DatabaseErrorHandler` (which would delete a
 * corrupt file), and `fallbackToDestructiveMigration` covers a version change only. So a cache
 * left malformed by a kill mid-write threw `SQLiteException … (code 11)` from the first DAO read
 * (it was `LbBotManager`'s, in a coroutine nothing caught) on every launch.
 *
 * What it costs: the cache only. The library re-syncs, the lb-bot mirror re-pulls, and saved
 * queues come back from the hub; queued offline stars and scrobbles that never reached the
 * server are lost with it. `downloads.db` and the credentials are never touched here.
 */
internal sealed interface CacheDbCheck {
	/** No file yet (a new install, or the last one was moved aside). */
	data object Missing : CacheDbCheck

	/** `quick_check` said `ok`: the file is left exactly as it was. */
	data object Healthy : CacheDbCheck

	/** Not a database or malformed: moved to [movedTo], and Room will create a fresh one. */
	data class Quarantined(val movedTo: File, val why: String) : CacheDbCheck

	/** The check itself failed for another reason (busy, I/O): the file is left for Room. */
	data class Unchecked(val why: String) : CacheDbCheck
}

/** SQLite's CORRUPT (11) and NOTADB (26), in the bundled driver's words, the framework's and sqlite-jdbc's. */
private val CORRUPTION = Regex(
	"""error code: (11|26)\b|\(code (11|26)\b|sqlite_(corrupt|notadb)|malformed|not a database""",
	RegexOption.IGNORE_CASE
)

/** Does [t] (or a cause of it) say the file is corrupt or not a database? */
internal fun isSqliteCorruption(t: Throwable): Boolean {
	var current: Throwable? = t
	var depth = 0
	while (current != null && depth < 16) {
		if (CORRUPTION.containsMatchIn(current.message ?: "")) return true
		current = current.cause
		depth++
	}
	return false
}

/**
 * Check [db] with [quickCheck] (which runs `PRAGMA quick_check(1)` and returns its first row), and
 * move it aside when it is not a sound database.
 *
 * Moved, never deleted: `cache.db` becomes `cache.db.corrupt-<nowMs>` (its `-wal` and `-shm` beside
 * it, named so SQLite would pair them), and only the newest such copy is kept, so a crash loop
 * cannot fill the disk.
 */
internal fun guardCacheDb(db: File, nowMs: Long, quickCheck: (File) -> String): CacheDbCheck {
	if (!db.exists()) return CacheDbCheck.Missing
	val why = try {
		val answer = quickCheck(db)
		if (answer.trim().equals("ok", ignoreCase = true)) return CacheDbCheck.Healthy
		"quick_check: ${answer.take(200)}"
	} catch (e: Exception) {
		if (!isSqliteCorruption(e)) return CacheDbCheck.Unchecked("${e::class.simpleName}: ${e.message}")
		"${e::class.simpleName}: ${e.message}"
	}
	return CacheDbCheck.Quarantined(quarantineCacheDb(db, nowMs), why)
}

/** Move [db] and its `-wal` / `-shm` aside as `<name>.corrupt-<nowMs>`; drop older such copies. */
internal fun quarantineCacheDb(db: File, nowMs: Long): File {
	val dir = db.absoluteFile.parentFile
	val prefix = "${db.name}.corrupt-"
	dir.listFiles()?.filter { it.name.startsWith(prefix) }?.forEach { it.delete() }
	val target = File(dir, "$prefix$nowMs")
	for (suffix in listOf("", "-wal", "-shm", "-journal")) {
		val from = File(dir, db.name + suffix)
		if (!from.exists()) continue
		val to = File(dir, target.name + suffix)
		if (!from.renameTo(to)) {
			// A rename that fails leaves the file where Room would open it again: delete it.
			Logger.w("CacheDb", "could not move ${from.name} aside; deleting it")
			from.delete()
		}
	}
	return target
}

/** `PRAGMA quick_check(1)` through the driver Room uses, on a connection closed before Room opens. */
internal fun bundledQuickCheck(file: File): String =
	BundledSQLiteDriver().open(file.absolutePath, SQLITE_OPEN_READWRITE).use { connection ->
		connection.prepare("PRAGMA quick_check(1)").use { statement ->
			if (statement.step()) statement.getText(0) else ""
		}
	}

/**
 * The check as the app runs it, on the thread that first asks Koin for `CacheDatabase`, before
 * any DAO exists. One log line under the tag `CacheDb` with what it found and how long it took.
 */
internal fun checkCacheDbAtOpen(db: File) {
	val started = System.nanoTime()
	val sizeKb = if (db.exists()) db.length() / 1024 else 0
	val result = try {
		guardCacheDb(db, System.currentTimeMillis(), ::bundledQuickCheck)
	} catch (e: Exception) {
		CacheDbCheck.Unchecked("${e::class.simpleName}: ${e.message}")
	}
	val ms = (System.nanoTime() - started) / 1_000_000
	val where = Thread.currentThread().name
	when (result) {
		CacheDbCheck.Missing -> Logger.i("CacheDb", "cache.db check: no file yet ($ms ms, thread $where)")
		CacheDbCheck.Healthy -> Logger.i("CacheDb", "cache.db check: ok in $ms ms ($sizeKb KiB, thread $where)")
		is CacheDbCheck.Quarantined -> Logger.w(
			"CacheDb",
			"cache.db failed its check (${result.why}); moved aside to ${result.movedTo.name}, " +
				"starting an empty cache ($ms ms, $sizeKb KiB, thread $where)"
		)
		is CacheDbCheck.Unchecked -> Logger.w("CacheDb", "cache.db could not be checked (${result.why}); left as it is ($ms ms, thread $where)")
	}
}
