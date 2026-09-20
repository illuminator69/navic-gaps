package paige.navic.data.database

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Add a column only if the table does not already have it.
 *
 * Every ALTER here used to be wrapped in `catch (_: Throwable)` on the assumption that the only
 * thing that could go wrong was "duplicate column name" — a database CAN reach these migrations
 * already carrying the columns, because a fresh install of the build that shipped the expanded
 * entity created the table complete at the old version number. But that catch also swallowed
 * malformed SQL, a locked or corrupt database, storage I/O errors and coroutine cancellation, so
 * a migration could report success having applied none of it, and the damage then surfaced much
 * later as an unrelated crash in DAO code against a half-upgraded schema.
 *
 * Asking first removes the need to catch anything: the duplicate case stops being an error, and a
 * real error is allowed to fail the migration where it can be seen.
 */
private suspend fun SQLiteConnection.addColumnIfMissing(
	table: String,
	column: String,
	ddl: String
) {
	val existing = mutableSetOf<String>()
	val statement = prepare("PRAGMA table_info(`$table`)")
	try {
		// PRAGMA table_info columns: 0 = cid, 1 = name, 2 = type, ...
		while (statement.step()) existing.add(statement.getText(1))
	} finally {
		statement.close()
	}
	if (column in existing) return
	execSQL(ddl)
}

private suspend fun SQLiteConnection.addDownloadCenterColumns() {
	listOf(
		"maxBitRate" to "INTEGER NOT NULL DEFAULT 0",
		"format" to "TEXT",
		"fileSize" to "INTEGER NOT NULL DEFAULT 0",
		"error" to "TEXT",
		"retryCount" to "INTEGER NOT NULL DEFAULT 0",
		"sourcePolicy" to "TEXT NOT NULL DEFAULT 'manual'",
		"createdAt" to "INTEGER NOT NULL DEFAULT 0",
		"updatedAt" to "INTEGER NOT NULL DEFAULT 0"
	).forEach { (column, type) ->
		addColumnIfMissing(
			"DownloadEntity", column,
			"ALTER TABLE DownloadEntity ADD COLUMN $column $type"
		)
	}
}

/**
 * v3 → v4: the download center's columns.
 *
 * This has to be a REAL migration. The databases are built with
 * `fallbackToDestructiveMigration(true)`, so simply bumping the version would drop the table —
 * and the audio files it points at would stay on disk as orphans, invisible to the app but still
 * eating the user's storage. Every added column is nullable or has a default, so existing rows
 * carry over as-is and keep working.
 *
 * `fileSize` backfills as 0 for pre-existing downloads rather than being measured here: this runs
 * on the DB thread during open, and stat-ing every file would block startup. [DownloadManager]
 * fills it in lazily instead.
 */
val MIGRATION_DOWNLOAD_3_4 = object : Migration(3, 4) {
	override suspend fun migrate(connection: SQLiteConnection) {
		connection.addDownloadCenterColumns()
	}
}

/**
 * v15 → v16: the SAME columns, on the cache database's copy of the table.
 *
 * `DownloadEntity` is declared as an entity of BOTH [CacheDatabase] and [DownloadDatabase], so
 * expanding it changed the cache schema as well. Room noticed on launch — "changed schema but
 * forgot to update the version number" — because the version had stayed at 15.
 *
 * This migration exists so the fix doesn't cost the user their whole library: the cache DB also
 * falls back to a destructive rebuild, and bumping the version without it would drop every cached
 * album, song and playlist and force a full re-sync from the server.
 */
val MIGRATION_CACHE_15_16 = object : Migration(15, 16) {
	override suspend fun migrate(connection: SQLiteConnection) {
		connection.addDownloadCenterColumns()
	}
}

/**
 * v16 → v17: the `SavedQueueEntity` table backing automatic saved queues.
 *
 * A pure additive migration — a new, initially-empty table — so nothing existing is touched and the
 * user keeps their cached library instead of the destructive fallback wiping it. The column list
 * (identifiers, types, NOT NULL, primary key) must match Room's generated schema for
 * [paige.navic.data.database.entities.SavedQueueEntity] exactly, or Room's post-migration validation
 * fails on launch. `IF NOT EXISTS` tolerates a fresh install that already created the table at v17.
 */
val MIGRATION_CACHE_16_17 = object : Migration(16, 17) {
	override suspend fun migrate(connection: SQLiteConnection) {
		connection.execSQL(
			"CREATE TABLE IF NOT EXISTS `SavedQueueEntity` (" +
				"`id` TEXT NOT NULL, " +
				"`name` TEXT, " +
				"`sourceName` TEXT, " +
				"`queueJson` TEXT NOT NULL, " +
				"`currentIndex` INTEGER NOT NULL, " +
				"`currentSongId` TEXT, " +
				"`positionMs` INTEGER NOT NULL, " +
				"`shuffle` INTEGER NOT NULL, " +
				"`repeatMode` INTEGER NOT NULL, " +
				"`songCount` INTEGER NOT NULL, " +
				"`createdAt` INTEGER NOT NULL, " +
				"`updatedAt` INTEGER NOT NULL, " +
				"PRIMARY KEY(`id`))"
		)
	}
}

/**
 * v17 → v18: `SavedQueueEntity.sourceKind` (how each queue was created — album / playlist / radio /
 * Mood Flow / journey / manual, so the list can group generated sessions) and `coverArtId` (the
 * current track's cover, cached for the "Continue listening" row).
 *
 * Additive columns — `sourceKind` NOT NULL with a default so existing rows read `manual`, `coverArtId`
 * nullable. Each ALTER asks [addColumnIfMissing] first, so a fresh install that already created the
 * table complete at v18 is a no-op rather than a swallowed exception.
 */
val MIGRATION_CACHE_17_18 = object : Migration(17, 18) {
	override suspend fun migrate(connection: SQLiteConnection) {
		listOf(
			"sourceKind" to "TEXT NOT NULL DEFAULT 'manual'",
			"coverArtId" to "TEXT"
		).forEach { (column, type) ->
			connection.addColumnIfMissing(
				"SavedQueueEntity", column,
				"ALTER TABLE SavedQueueEntity ADD COLUMN $column $type"
			)
		}
	}
}

/**
 * v18 → v19: `SavedQueueEntity.currentSongName` (so a row can show what it left off on) and
 * `songIdsCsv` (so the repository can answer "do I already have a record for this queue?" without
 * decoding twenty `queueJson` blobs). Both exist to keep the list render and the identity check off
 * the expensive path this table was built to avoid.
 *
 * Additive and nullable — existing rows read `null` until their next capture, and `songIdsCsv` is
 * backfilled lazily by `SavedQueueRepository.primeIndex()` — with the same [addColumnIfMissing]
 * idempotency as [MIGRATION_CACHE_17_18].
 */
val MIGRATION_CACHE_18_19 = object : Migration(18, 19) {
	override suspend fun migrate(connection: SQLiteConnection) {
		listOf(
			"currentSongName" to "TEXT",
			"songIdsCsv" to "TEXT"
		).forEach { (column, type) ->
			connection.addColumnIfMissing(
				"SavedQueueEntity", column,
				"ALTER TABLE SavedQueueEntity ADD COLUMN $column $type"
			)
		}
	}
}
