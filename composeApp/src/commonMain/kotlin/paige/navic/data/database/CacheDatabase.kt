package paige.navic.data.database

import androidx.room3.ColumnTypeConverters
import androidx.room3.ConstructedBy
import androidx.room3.Database
import androidx.room3.RoomDatabase
import androidx.room3.RoomDatabaseConstructor
import paige.navic.data.database.dao.AlbumDao
import paige.navic.data.database.dao.ArtistDao
import paige.navic.data.database.dao.DownloadDao
import paige.navic.data.database.dao.GenreDao
import paige.navic.data.database.dao.LyricDao
import paige.navic.data.database.dao.PlaylistDao
import paige.navic.data.database.dao.RadioDao
import paige.navic.data.database.dao.SavedQueueDao
import paige.navic.data.database.dao.SongDao
import paige.navic.data.database.dao.SyncActionDao
import paige.navic.data.database.entities.AlbumEntity
import paige.navic.data.database.entities.ArtistEntity
import paige.navic.data.database.entities.DownloadEntity
import paige.navic.data.database.entities.GenreEntity
import paige.navic.data.database.entities.LyricEntity
import paige.navic.data.database.entities.PlaylistEntity
import paige.navic.data.database.entities.PlaylistSongCrossRef
import paige.navic.data.database.entities.RadioEntity
import paige.navic.data.database.entities.SavedQueueEntity
import paige.navic.data.database.entities.SongEntity
import paige.navic.data.database.entities.SyncActionEntity
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Database(
	// NOTE: [DownloadEntity] is an entity of BOTH databases, so changing it changes THIS schema
	// too. Forgetting to bump this version is exactly how you get Room's "changed schema but
	// forgot to update the version number" identity-hash crash on launch.
	//
	// 22, above BOTH parents' 21: alpha59 bumped upstream to 21 for SyncActionEntity.time while
	// this fork was already at 21 for SavedQueueEntity, so the two 21s are DIFFERENT schemas and
	// a merged build sitting at 21 would match an installed database against the wrong one. The
	// cache is rebuilt destructively on a version change by design — upstream writes no
	// migrations for it — and saved queues are an offline cache that syncSavedQueues reconciles
	// from the hub on reconnect.
	version = 22,
	entities = [
		AlbumEntity::class,
		GenreEntity::class,
		PlaylistEntity::class,
		PlaylistSongCrossRef::class,
		SongEntity::class,
		ArtistEntity::class,
		RadioEntity::class,
		LyricEntity::class,
		SyncActionEntity::class,
		DownloadEntity::class,
		SavedQueueEntity::class
	]
)
@ColumnTypeConverters(Converters::class)
@ConstructedBy(CacheDatabaseConstructor::class)
abstract class CacheDatabase : RoomDatabase() {
	abstract fun albumDao(): AlbumDao
	abstract fun genreDao(): GenreDao
	abstract fun downloadDao(): DownloadDao
	abstract fun playlistDao(): PlaylistDao
	abstract fun songDao(): SongDao
	abstract fun artistDao(): ArtistDao
	abstract fun radioDao(): RadioDao
	abstract fun lyricDao(): LyricDao
	abstract fun syncActionDao(): SyncActionDao
	abstract fun savedQueueDao(): SavedQueueDao
}

@Suppress("KotlinNoActualForExpect")
expect object CacheDatabaseConstructor : RoomDatabaseConstructor<CacheDatabase> {
	override fun initialize(): CacheDatabase
}
