package paige.navic.di

import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module
import paige.navic.domain.models.DomainSong
import paige.navic.ui.components.dialogs.DeletionViewModel
import paige.navic.ui.components.sheets.ChangelogViewModel
import paige.navic.ui.screens.album.viewmodels.AlbumListViewModel
import paige.navic.ui.screens.artist.viewmodels.ArtistDetailViewModel
import paige.navic.ui.screens.artist.viewmodels.ArtistListViewModel
import paige.navic.ui.screens.collection.viewmodels.CollectionDetailViewModel
import paige.navic.ui.screens.external.viewmodels.ExternalAlbumViewModel
import paige.navic.ui.screens.external.viewmodels.ExternalArtistViewModel
import paige.navic.ui.screens.fresh.viewmodels.FreshViewModel
import paige.navic.ui.screens.genre.viewmodels.GenreListViewModel
import paige.navic.ui.screens.login.viewmodels.LoginViewModel
import paige.navic.ui.screens.lyrics.viewmodels.LyricsScreenViewModel
import paige.navic.ui.screens.nowPlaying.viewmodels.NowPlayingViewModel
import paige.navic.ui.screens.playlist.viewmodels.PlaylistCreateDialogViewModel
import paige.navic.ui.screens.playlist.viewmodels.PlaylistListViewModel
import paige.navic.ui.screens.playlist.viewmodels.PlaylistUpdateDialogViewModel
import paige.navic.ui.screens.queue.viewmodels.QueueViewModel
import paige.navic.ui.screens.queue.viewmodels.RelatedSongsViewModel
import paige.navic.ui.screens.savedqueues.viewmodels.SavedQueuesViewModel
import paige.navic.ui.screens.radio.viewmodels.RadioCreateDialogViewModel
import paige.navic.ui.screens.radio.viewmodels.RadioListViewModel
import paige.navic.ui.screens.search.viewmodels.SearchViewModel
import paige.navic.ui.screens.settings.viewmodels.LyricsPriorityViewModel
import paige.navic.ui.screens.settings.viewmodels.NavtabsViewModel
import paige.navic.ui.screens.settings.viewmodels.DownloadCenterViewModel
import paige.navic.ui.screens.settings.viewmodels.SettingsDataStorageViewModel
import paige.navic.ui.screens.share.viewmodels.ShareDialogViewModel
import paige.navic.ui.screens.share.viewmodels.ShareListViewModel
import paige.navic.ui.screens.song.viewmodels.SongDetailViewModel
import paige.navic.ui.screens.song.viewmodels.SongListViewModel

val viewModelModule = module {
	viewModelOf(::ArtistDetailViewModel)
	viewModelOf(::FreshViewModel)

	// Parameterised on their route keys — a MusicBrainz artist mbid and a
	// release-group id, neither of which is resolvable from the graph.
	viewModel { (artistMbid: String, name: String) ->
		ExternalArtistViewModel(artistMbid = artistMbid, artistName = name, lbBotManager = get())
	}
	viewModel { (rgid: String, artistMbid: String, artistName: String) ->
		ExternalAlbumViewModel(
			rgid = rgid,
			artistMbid = artistMbid,
			artistName = artistName,
			lbBotManager = get()
		)
	}

	viewModel { (song: DomainSong?) ->
		LyricsScreenViewModel(
			song = song,
			repository = get()
		)
	}

	viewModel { (songs: List<DomainSong>, playlistToExclude: String?) ->
		PlaylistUpdateDialogViewModel(
			songs = songs,
			playlistToExclude = playlistToExclude,
			sessionManager = get(),
			snackBarManager = get()
		)
	}

	viewModelOf(::AlbumListViewModel)
	viewModel { params ->
		SongListViewModel(
			initialListType = get(),
			artistId = params.getOrNull(),
			repository = get(),
			downloadManager = get(),
			connectivityManager = get(),
			sessionManager = get()
		)
	}
	viewModelOf(::ArtistListViewModel)
	viewModelOf(::SearchViewModel)
	viewModelOf(::GenreListViewModel)
	viewModelOf(::RadioListViewModel)
	viewModelOf(::RadioCreateDialogViewModel)
	viewModelOf(::PlaylistListViewModel)
	viewModelOf(::LoginViewModel)
	viewModelOf(::QueueViewModel)
	viewModelOf(::RelatedSongsViewModel)
	viewModelOf(::SavedQueuesViewModel)
	viewModelOf(::ShareListViewModel)
	viewModelOf(::DeletionViewModel)
	viewModelOf(::ShareDialogViewModel)
	viewModel { (songs: List<DomainSong>) ->
		PlaylistCreateDialogViewModel(
			songs = songs,
			playlistDao = get(),
			sessionManager = get(),
			snackBarManager = get()
		)
	}
	viewModel { params ->
		CollectionDetailViewModel(
			collectionId = params.get(),
			repository = get(),
			songRepository = get(),
			albumRepository = get(),
			downloadManager = get(),
			sessionManager = get(),
			snackBarManager = get(),
			connectivityManager = get()
		)
	}
	viewModelOf(::SongDetailViewModel)
	viewModelOf(::SettingsDataStorageViewModel)
	viewModelOf(::DownloadCenterViewModel)
	viewModelOf(::ChangelogViewModel)
	viewModel { params ->
		NowPlayingViewModel(
			player = params.get(),
			songRepository = get()
		)
	}
	viewModelOf(::NavtabsViewModel)
	viewModelOf(::LyricsPriorityViewModel)
}
