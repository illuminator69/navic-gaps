package paige.navic.di

import androidx.media3.common.util.UnstableApi
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import org.koin.android.ext.koin.androidApplication
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module
import paige.navic.data.database.CacheDatabase
import paige.navic.data.database.DownloadDatabase
import paige.navic.data.database.MIGRATION_CACHE_15_16
import paige.navic.data.database.MIGRATION_CACHE_16_17
import paige.navic.data.database.MIGRATION_CACHE_17_18
import paige.navic.data.database.MIGRATION_CACHE_18_19
import paige.navic.data.database.MIGRATION_DOWNLOAD_3_4
import paige.navic.domain.manager.AppIconManager
import paige.navic.domain.manager.AudioGainManager
import paige.navic.domain.manager.CastBridgeStatus
import paige.navic.domain.manager.cast.CastBridgeManager
import paige.navic.domain.manager.ConnectivityManager
import paige.navic.domain.manager.LinkManager
import paige.navic.domain.manager.LogManager
import paige.navic.domain.manager.PermissionManager
import paige.navic.domain.manager.NotificationManager
import paige.navic.domain.manager.ShareManager
import paige.navic.domain.manager.StorageManager
import paige.navic.exoplayer.AudioGainProcessor
import paige.navic.shared.AndroidMediaPlayerViewModel
import paige.navic.shared.MediaPlayerViewModel

@UnstableApi
actual val platformModule = module {
	single { PlatformType.Android }

	single<CacheDatabase> {
		val dbPath = androidApplication()
			.getDatabasePath("cache.db")
			.absolutePath
		Room
			.databaseBuilder<CacheDatabase>(get(), dbPath)
			.setDriver(BundledSQLiteDriver())
			// Without this, bumping the cache version for the DownloadEntity change would drop the
			// user's entire cached library and force a full re-sync.
			.addMigrations(
				MIGRATION_CACHE_15_16,
				MIGRATION_CACHE_16_17,
				MIGRATION_CACHE_17_18,
				MIGRATION_CACHE_18_19
			)
			.fallbackToDestructiveMigration(true)
			.build()
	}

	single<DownloadDatabase> {
		val dbPath = androidApplication()
			.getDatabasePath("downloads.db")
			.absolutePath
		Room
			.databaseBuilder<DownloadDatabase>(get(), dbPath)
			.setDriver(BundledSQLiteDriver())
			// Unlike the cache DB, this one must NOT be dropped on upgrade: its rows are the only
			// record of which audio files exist on disk. Dropping it would orphan every downloaded
			// file — invisible to the app, still occupying the user's storage. The destructive
			// fallback stays only as a last resort for versions with no migration path.
			.addMigrations(MIGRATION_DOWNLOAD_3_4)
			.fallbackToDestructiveMigration(true)
			.build()
	}

	single<MediaPlayerViewModel> {
		AndroidMediaPlayerViewModel(
			application = androidApplication(),
			stateRepository = get(),
			songRepository = get(),
			albumDao = get(),
			downloadManager = get(),
			connectivityManager = get(),
			sessionManager = get(),
			preferenceManager = get(),
			audioGainManager = get(),
			savedQueueRepository = get(),
			snackBarManager = get()
		)
	}

	singleOf(::AppIconManager)
	singleOf(::AudioGainManager)
	singleOf(::AudioGainProcessor)
	singleOf(::LinkManager)
	singleOf(::PermissionManager)
	singleOf(::ShareManager)
	singleOf(::NotificationManager)
	singleOf(::StorageManager)
	singleOf(::ConnectivityManager)
	singleOf(::LogManager)

	// Eager: discovery has to already be running when the picker opens, and the bridge has to be
	// up for the speaker to appear in OTHER clients' pickers whether or not this phone's UI is
	// on screen. Same reason HubManager is createdAtStart.
	single(createdAtStart = true) {
		CastBridgeManager(androidApplication(), get(), get()).apply { start() }
	}
	single<CastBridgeStatus> { get<CastBridgeManager>() }
}
