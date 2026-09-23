package paige.navic.di

import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module
import paige.navic.domain.manager.AudioMuseManager
import paige.navic.domain.manager.CastScrobbler
import paige.navic.domain.manager.AlbumModeSmartPlaylists
import paige.navic.domain.manager.DownloadManager
import paige.navic.domain.manager.EqualiserManager
import paige.navic.domain.manager.HubManager
import paige.navic.domain.manager.LbBotManager
import paige.navic.domain.manager.LoginManager
import paige.navic.domain.manager.NativeApiManager
import paige.navic.domain.manager.PlaylistDownloadManager
import paige.navic.domain.manager.PreferenceManager
import paige.navic.domain.manager.PreviewManager
import paige.navic.domain.manager.RadioManager
import paige.navic.domain.manager.SessionManager
import paige.navic.domain.manager.SleepTimerManager
import paige.navic.domain.manager.SnackBarManager
import paige.navic.domain.manager.SyncManager
import paige.navic.ui.util.AmbientColorHolder

val managerModule = module {
	singleOf(::AmbientColorHolder)
	singleOf(::SleepTimerManager)
	single(createdAtStart = true) {
		SyncManager(get(), get(), get(), get(), get(), get(), get()).apply {
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
	single(createdAtStart = true) {
		LbBotManager(get()).apply { resumeWatches() }
	}
	single(createdAtStart = true) {
		HubManager(get(), get(), get(), get(), get(), get()).apply { start() }
	}
	// createdAtStart, and never lazily: a cast session is precisely the case where no screen is
	// open and no local player is running, so nothing else would ever construct this.
	single(createdAtStart = true) {
		CastScrobbler(get(), get(), get(), get(), get(), get()).apply { start() }
	}
	// createdAtStart so the autoplay observer is running before the first radio use.
	single(createdAtStart = true) { RadioManager(get(), get(), get(), get(), get(), get()) }
	singleOf(::NativeApiManager)
	single(createdAtStart = true) { PlaylistDownloadManager(get(), get(), get(), get()) }
	singleOf(::SnackBarManager)
	singleOf(::LoginManager)
	singleOf(::EqualiserManager)
}
