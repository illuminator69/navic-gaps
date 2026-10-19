package paige.navic.di

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module
import paige.navic.domain.manager.AudioMuseManager
import paige.navic.domain.manager.CastScrobbler
import paige.navic.domain.manager.AlbumModeSmartPlaylists
import paige.navic.domain.manager.DownloadManager
import paige.navic.domain.manager.EqualiserManager
import paige.navic.domain.manager.HubManager
import paige.navic.domain.manager.LbBotManager
import paige.navic.domain.manager.LbIndexSync
import paige.navic.domain.manager.LoginManager
import paige.navic.domain.manager.NativeApiManager
import paige.navic.domain.manager.PlaylistDownloadManager
import paige.navic.domain.manager.PreferenceManager
import paige.navic.domain.manager.PreviewManager
import paige.navic.domain.manager.RadioManager
import paige.navic.domain.manager.SessionManager
import paige.navic.domain.manager.SleepTimerManager
import paige.navic.domain.manager.SnackBarManager
import paige.navic.domain.manager.StationManager
import paige.navic.domain.manager.SyncManager
import paige.navic.ui.util.AmbientColorHolder
import paige.navic.ui.util.CoverSeedPrefetcher
import paige.navic.ui.util.CoverSeedStore

val managerModule = module {
	singleOf(::AmbientColorHolder)
	// I-043: cover seed colours, persisted. An explicit lambda: singleOf would try to resolve the
	// constructor's default parameters. Main-thread only, so its delayed write runs on Main too.
	single {
		CoverSeedStore(
			load = { get<PreferenceManager>().coverSeeds },
			save = { get<PreferenceManager>().coverSeeds = it },
			scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
		)
	}
	// I-043: fills the store ahead of need (tiles on screen, the next songs).
	single { CoverSeedPrefetcher(get(), get(), get()) }
	singleOf(::SleepTimerManager)
	single(createdAtStart = true) {
		SyncManager(get(), get(), get(), get(), get(), get(), get(), get(), get()).apply {
			startPeriodicSync()
		}
	}
	singleOf(::DownloadManager)
	singleOf(::AlbumModeSmartPlaylists)
	singleOf(::SessionManager)
	singleOf(::PreferenceManager)
	singleOf(::AudioMuseManager)
	// Lazy, unlike LbBotManager below: previews have no background work to resume —
	// nothing is watched, nothing outlives the process — so the first screen that
	// asks about them is early enough.
	singleOf(::PreviewManager)
	// createdAtStart so a fill that outlived the process is picked back up without
	// waiting for the user to open the artist page it was started from.
	// The second get() is LbIndexDao, for the `lb_response_cache` half only (cachedGet); the
	// third is DbRepository, for what follows an album override job (I-022).
	single(createdAtStart = true) {
		LbBotManager(get(), get(), get()).apply { resumeWatches() }
	}
	// The lb-bot index mirror's sync worker. Constructed by HubManager and SyncManager (both
	// createdAtStart), which own its three triggers: welcome, the `index` frame, the 15-min cycle.
	singleOf(::LbIndexSync)
	// The last two get()s are fork-only bindings each platform module registers:
	// HubSocketClientFactory and HubLocalDevice.
	single(createdAtStart = true) {
		HubManager(get(), get(), get(), get(), get(), get(), get(), get(), get()).apply { start() }
	}
	// createdAtStart, and never lazily: a cast session is precisely the case where no screen is
	// open and no local player is running, so nothing else would ever construct this.
	single(createdAtStart = true) {
		CastScrobbler(get(), get(), get(), get(), get(), get()).apply { start() }
	}
	// The hub's station engine (PROTOCOL §18): HTTP, availability, and the record of the station
	// that built this device's queue. Constructed by RadioManager below at start, after HubManager,
	// whose queue publish it supplies the bound station id to.
	singleOf(::StationManager)
	// createdAtStart so the autoplay observer is running before the first radio use. The last two
	// get()s are StationManager and SongRepository (its star events steer a bound station).
	single(createdAtStart = true) { RadioManager(get(), get(), get(), get(), get(), get(), get(), get()) }
	singleOf(::NativeApiManager)
	single(createdAtStart = true) { PlaylistDownloadManager(get(), get(), get(), get()) }
	singleOf(::SnackBarManager)
	singleOf(::LoginManager)
	singleOf(::EqualiserManager)
}
