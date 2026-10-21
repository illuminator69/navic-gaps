package paige.navic.di

import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module
import paige.navic.domain.models.DomainAlbumListType
import paige.navic.domain.models.DomainArtistListType
import paige.navic.domain.models.DomainFilter
import paige.navic.domain.models.DomainSong
import paige.navic.domain.models.DomainSongListType
import paige.navic.ui.components.dialogs.DeletionViewModel
import paige.navic.ui.components.sheets.ChangelogViewModel
import paige.navic.ui.screens.album.viewmodels.AlbumListViewModel
import paige.navic.ui.screens.artist.viewmodels.ArtistDetailViewModel
import paige.navic.ui.screens.artist.viewmodels.ArtistListViewModel
import paige.navic.ui.screens.collection.viewmodels.CollectionDetailViewModel
import paige.navic.ui.screens.external.viewmodels.ExternalAlbumViewModel
import paige.navic.ui.screens.external.viewmodels.ExternalArtistViewModel
import paige.navic.ui.screens.browse.BrowseFeedViewModel
import paige.navic.ui.screens.browse.BrowseKind
import paige.navic.ui.screens.discover.viewmodels.DiscoverViewModel
import paige.navic.ui.screens.fresh.viewmodels.FreshViewModel
import paige.navic.ui.screens.home.HomeViewModel
import paige.navic.ui.screens.mixes.viewmodels.MixListViewModel
import paige.navic.ui.screens.radio.custom.CustomRadioViewModel
import paige.navic.ui.screens.mixes.viewmodels.MixDetailViewModel
import paige.navic.ui.screens.library.viewmodels.LibraryViewModel
import paige.navic.ui.screens.listening.viewmodels.ListeningCardViewModel
import paige.navic.ui.screens.listening.viewmodels.ListeningViewModel
import paige.navic.ui.screens.downloads.viewmodels.WishlistViewModel
import paige.navic.ui.screens.genre.viewmodels.GenreListViewModel
import paige.navic.ui.screens.lyrics.viewmodels.LyricsScreenViewModel
import paige.navic.ui.screens.nowPlaying.viewmodels.NowPlayingViewModel
import paige.navic.ui.components.sheets.AddToPlaylistViewModel
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
import paige.navic.ui.screens.downloads.viewmodels.DownloadCenterViewModel
import paige.navic.ui.screens.attention.AttentionViewModel
import paige.navic.ui.screens.sourcehistory.SourceHistoryViewModel
import paige.navic.ui.screens.matches.viewmodels.LibraryMatchesViewModel
import paige.navic.ui.screens.settings.viewmodels.PendingActionsViewModel
import paige.navic.ui.screens.settings.viewmodels.SettingsDataStorageViewModel
import paige.navic.ui.screens.share.viewmodels.ShareDialogViewModel
import paige.navic.ui.screens.share.viewmodels.ShareListViewModel
import paige.navic.ui.screens.song.viewmodels.SongDetailViewModel
import paige.navic.ui.screens.song.viewmodels.SongListViewModel
import paige.navic.domain.repositories.SongListArtist
import paige.navic.ui.screens.stats.viewmodels.StatisticsViewModel
import paige.navic.ui.viewmodel.RootViewModel

val viewModelModule = module {
	viewModelOf(::ArtistDetailViewModel)
	viewModelOf(::FreshViewModel)
	viewModelOf(::HomeViewModel)
	viewModelOf(::DiscoverViewModel)
	// P3: Charts and Editors' picks, one view model each, keyed by which.
	viewModel { (kind: BrowseKind) -> BrowseFeedViewModel(kind, get(), get(), get()) }
	viewModelOf(::MixListViewModel)
	viewModelOf(::CustomRadioViewModel)
	viewModel { params ->
		MixDetailViewModel(
			mixId = params.get(),
			hubManager = get(),
			radioManager = get(),
			songRepository = get(),
			downloadManager = get()
		)
	}
	viewModelOf(::LibraryViewModel)
	viewModelOf(::WishlistViewModel)
	viewModelOf(::ListeningViewModel)
	viewModelOf(::ListeningCardViewModel)

	// Parameterised on their route keys — a MusicBrainz artist mbid and a
	// release-group id, neither of which is resolvable from the graph.
	viewModel { (artistMbid: String, name: String) ->
		ExternalArtistViewModel(
			artistMbid = artistMbid,
			artistName = name,
			lbBotManager = get(),
			lbIndexSync = get(),
			connectivityManager = get()
		)
	}
	viewModel { (rgid: String, artistMbid: String, artistName: String, artistId: String, title: String) ->
		ExternalAlbumViewModel(
			rgid = rgid,
			artistMbid = artistMbid,
			artistName = artistName,
			artistId = artistId,
			title = title,
			artistDao = get(),
			albumDao = get(),
			dbRepository = get(),
			lbBotManager = get(),
			lbIndexSync = get(),
			previewManager = get(),
			mediaPlayer = get(),
			fillActions = get(),
			connectivityManager = get()
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

	viewModel { params ->
		AlbumListViewModel(
			initialListType = params.getOrNull<DomainAlbumListType>() ?: DomainAlbumListType.AlphabeticalByArtist,
			initialFilters = params.getOrNull<Set<DomainFilter>>(),
			repository = get(),
			sessionManager = get(),
			preferenceManager = get()
		)
	}
	viewModel { params ->
		SongListViewModel(
			initialListType = params.getOrNull<DomainSongListType>() ?: DomainSongListType.FrequentlyPlayed,
			initialFilters = params.getOrNull<Set<DomainFilter>>(),
			artist = params.getOrNull<SongListArtist>(),
			repository = get(),
			downloadManager = get(),
			sessionManager = get(),
			preferenceManager = get(),
			connectivityManager = get()
		)
	}
	viewModel { params ->
		ArtistListViewModel(
			initialListType = params.getOrNull<DomainArtistListType>() ?: DomainArtistListType.AlphabeticalByName,
			initialFilters = params.getOrNull<Set<DomainFilter>>(),
			repository = get(),
			albumDao = get(),
			sessionManager = get(),
			preferenceManager = get()
		)
	}
	viewModelOf(::SearchViewModel)
	viewModelOf(::GenreListViewModel)
	viewModelOf(::RadioListViewModel)
	viewModelOf(::RadioCreateDialogViewModel)
	viewModelOf(::PlaylistListViewModel)
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
			lbBotManager = get(),
			lbIndexSync = get(),
			connectivityManager = get()
		)
	}
	viewModelOf(::SongDetailViewModel)
	viewModelOf(::SettingsDataStorageViewModel)
	viewModelOf(::PendingActionsViewModel)
	viewModelOf(::LibraryMatchesViewModel)
	viewModelOf(::DownloadCenterViewModel)
	viewModelOf(::AttentionViewModel)
	viewModelOf(::SourceHistoryViewModel)
	viewModelOf(::ChangelogViewModel)
	viewModel { params ->
		NowPlayingViewModel(
			player = params.get(),
			songRepository = get(),
			savedQueueRepository = get(),
			hubManager = get()
		)
	}
	viewModel { params ->
		AddToPlaylistViewModel(
			song = params.get(),
			playlistDao = get(),
			dbRepository = get(),
			sessionManager = get()
		)
	}
	viewModelOf(::NavtabsViewModel)
	viewModelOf(::LyricsPriorityViewModel)
	viewModelOf(::RootViewModel)
	viewModelOf(::StatisticsViewModel)
}
