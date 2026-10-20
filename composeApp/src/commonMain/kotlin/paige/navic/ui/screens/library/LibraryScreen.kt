package paige.navic.ui.screens.library

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import navic.composeapp.generated.resources.Res
import navic.composeapp.generated.resources.title_search
import navic.composeapp.generated.resources.library_add_radio
import navic.composeapp.generated.resources.library_chip_downloaded
import navic.composeapp.generated.resources.library_chip_mixes
import navic.composeapp.generated.resources.library_recents
import navic.composeapp.generated.resources.title_albums
import navic.composeapp.generated.resources.title_artists
import navic.composeapp.generated.resources.title_library
import navic.composeapp.generated.resources.title_new
import navic.composeapp.generated.resources.title_playlists
import navic.composeapp.generated.resources.title_radios
import navic.composeapp.generated.resources.title_rename_mix
import navic.composeapp.generated.resources.title_songs
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import paige.navic.di.LocalBottomBarScrollManager
import paige.navic.di.LocalNavStack
import paige.navic.di.LocalSnackBarState
import paige.navic.data.database.entities.SavedQueueEntity
import paige.navic.domain.manager.RadioManager
import paige.navic.domain.manager.ShellController
import paige.navic.domain.models.DomainAlbum
import paige.navic.domain.models.DomainAlbumListType
import paige.navic.domain.models.DomainArtist
import paige.navic.domain.models.DomainArtistListType
import paige.navic.domain.models.DomainPlaylist
import paige.navic.domain.models.DomainSongCollection
import paige.navic.domain.models.DomainSongListType
import paige.navic.domain.models.Mix
import paige.navic.icons.Icons
import paige.navic.icons.outlined.Add
import paige.navic.icons.outlined.Search
import paige.navic.icons.outlined.Sort
import paige.navic.shared.MediaPlayerViewModel
import paige.navic.ui.components.common.PageChips
import paige.navic.ui.components.layouts.PullToRefreshBox
import paige.navic.ui.components.layouts.RootBottomBar
import paige.navic.ui.components.layouts.RootTopBar
import paige.navic.ui.components.sheets.ArtistSheet
import paige.navic.ui.components.sheets.CollectionSheet
import paige.navic.ui.navigation.PersistentViewModelStoreOwner
import paige.navic.ui.navigation.Screen
import paige.navic.ui.screens.album.AlbumListBody
import paige.navic.ui.screens.album.AlbumListSortAction
import paige.navic.ui.screens.album.albumListViewModel
import paige.navic.ui.screens.artist.ArtistListBody
import paige.navic.ui.screens.artist.ArtistListSortAction
import paige.navic.ui.screens.artist.artistListViewModel
import paige.navic.ui.screens.downloads.DeviceDownloadsBody
import paige.navic.ui.screens.downloads.viewmodels.DownloadCenterViewModel
import paige.navic.ui.screens.library.viewmodels.LibraryViewModel
import paige.navic.ui.screens.mixes.MixListBody
import paige.navic.ui.screens.mixes.MixNameDialog
import paige.navic.ui.screens.mixes.MixSheet
import paige.navic.ui.screens.mixes.mixListViewModel
import paige.navic.ui.screens.playlist.PlaylistListBody
import paige.navic.ui.screens.playlist.PlaylistListSortAction
import paige.navic.ui.screens.playlist.playlistListViewModel
import paige.navic.ui.screens.radio.RadioListBody
import paige.navic.ui.screens.radio.radioListViewModel
import paige.navic.ui.screens.savedqueues.components.SavedQueueSheet
import paige.navic.ui.screens.savedqueues.rememberSavedQueueActions
import paige.navic.ui.screens.savedqueues.savedQueueSubtitle
import paige.navic.ui.screens.savedqueues.viewmodels.SavedQueuesViewModel
import paige.navic.ui.screens.song.SongListBody
import paige.navic.ui.screens.song.SongListSortAction
import paige.navic.ui.screens.song.songListViewModel
import paige.navic.ui.components.common.BrowsingAmbient
import paige.navic.ui.viewmodel.RootViewModel
import kotlin.time.Clock

/*
 * The Library (P5, board Library): the avatar, "Library", Search and New; one row of chips
 * (Playlists, Albums, Artists, Mixes, Downloaded, Songs, Radios); a sort row; and one list. With no
 * chip it is Recents, Liked pinned above it; each chip is that list's own body (the pushed pages'
 * bodies, so sort, filters, view mode, sheets and the scroller are theirs), under its sort menu.
 * Tapping the chosen chip clears it. The old home's carousels, statistics card and buttons are gone:
 * each has a home of its own (CLAUDE.md §1).
 */

/** How long the bodies cross-fade on a chip change. */
private const val CHIP_FADE_MS = 220

/** A second tap of the Library tab within this long of the first clears the chip. */
private const val SECOND_TAP_MS = 2_500L

@Composable
fun LibraryScreen() {
	val persistent = koinInject<PersistentViewModelStoreOwner>()
	val shell = koinInject<ShellController>()
	val backStack = LocalNavStack.current
	val viewModel = koinViewModel<LibraryViewModel>(viewModelStoreOwner = persistent)
	val recents by viewModel.recents.collectAsStateWithLifecycle()

	var chip by rememberSaveable { mutableStateOf<LibraryChip?>(null) }
	val requested by shell.libraryChip.collectAsStateWithLifecycle()
	LaunchedEffect(requested) {
		requested?.let { name ->
			chip = LibraryChip.entries.firstOrNull { it.name == name }
			shell.libraryChipShown()
		}
	}

	val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
	val recentsState = rememberLazyListState()
	val songsState = rememberLazyListState()
	// The tab tapped while on it scrolls the list to the top (each body listens for that), and a
	// second tap soon after clears the chip.
	val rootViewModel = koinViewModel<RootViewModel>()
	LaunchedEffect(Unit) {
		var lastTap = 0L
		rootViewModel.events.collect { event ->
			if (event !is RootViewModel.Event.ScrollToTop) return@collect
			val now = Clock.System.now().toEpochMilliseconds()
			if (chip != null && now - lastTap < SECOND_TAP_MS) chip = null
			else recentsState.animateScrollToItem(0)
			lastTap = now
		}
	}

	// Each chip's body keeps its own view model in the persistent store, so switching chips keeps
	// each list's scroll, sort and filters.
	var radioAdd by rememberSaveable { mutableStateOf(false) }

	BrowsingAmbient {
		Scaffold(
			containerColor = Color.Transparent,
			topBar = {
				Column {
					RootTopBar(
						{ Text(stringResource(Res.string.title_library)) },
						scrollBehavior,
						actions = {
							IconButton(onClick = { backStack.add(Screen.Search()) }) {
								Icon(Icons.Outlined.Search, contentDescription = stringResource(Res.string.title_search))
							}
							IconButton(onClick = { shell.openNew() }) {
								Icon(Icons.Outlined.Add, contentDescription = stringResource(Res.string.title_new))
							}
						}
					)
					PageChips(
						options = LibraryChip.entries,
						selected = chip,
						label = { option -> option?.let { stringResource(it.label) }.orEmpty() },
						onSelect = { chip = if (it == chip) null else it }
					)
					SortRow(chip, onAddRadio = { radioAdd = true })
				}
			},
			bottomBar = {
				val scrollManager = LocalBottomBarScrollManager.current
				RootBottomBar(scrolled = scrollManager.isTriggered)
			}
		) { inner ->
			val connection = scrollBehavior.nestedScrollConnection
			AnimatedContent(
				targetState = chip,
				transitionSpec = { fadeIn(tween(CHIP_FADE_MS)) togetherWith fadeOut(tween(CHIP_FADE_MS)) },
				label = "libraryChip"
			) { shown ->
				when (shown) {
					null -> RecentsBody(viewModel, recents, inner, recentsState, connection)
					LibraryChip.Playlists -> PlaylistListBody(playlistListViewModel(false), inner, scrollConnection = connection)
					LibraryChip.Albums -> AlbumListBody(
						albumListViewModel(false, LIBRARY_ALBUMS), inner, scrollConnection = connection
					)
					LibraryChip.Artists -> ArtistListBody(
						artistListViewModel(false, LIBRARY_ARTISTS), inner, scrollConnection = connection
					)
					LibraryChip.Mixes -> MixListBody(
						mixListViewModel(false), inner, LocalSnackBarState.current, scrollConnection = connection
					)
					LibraryChip.Downloaded -> DeviceDownloadsBody(
						koinViewModel<DownloadCenterViewModel>(viewModelStoreOwner = persistent), inner,
						scrollConnection = connection
					)
					LibraryChip.Songs -> SongListBody(
						songListViewModel(false, LIBRARY_SONGS), inner, scrollConnection = connection,
						listState = songsState
					)
					LibraryChip.Radios -> RadioListBody(
						radioListViewModel(false), inner, scrollConnection = connection,
						createDialogShown = radioAdd, onCreateDialogDismissed = { radioAdd = false }
					)
				}
			}
		}
	}
}

private val LIBRARY_ALBUMS = DomainAlbumListType.AlphabeticalByArtist
private val LIBRARY_ARTISTS = DomainArtistListType.AlphabeticalByName
private val LIBRARY_SONGS = DomainSongListType.FrequentlyPlayed

private val LibraryChip.label: StringResource
	get() = when (this) {
		LibraryChip.Playlists -> Res.string.title_playlists
		LibraryChip.Albums -> Res.string.title_albums
		LibraryChip.Artists -> Res.string.title_artists
		LibraryChip.Mixes -> Res.string.library_chip_mixes
		LibraryChip.Downloaded -> Res.string.library_chip_downloaded
		LibraryChip.Songs -> Res.string.title_songs
		LibraryChip.Radios -> Res.string.title_radios
	}

/**
 * The row under the chips: "Recents" with no chip; the chip's own sort menu (with its filters and
 * the list / grid setting) where it has one; Radios' "Add".
 */
@Composable
private fun SortRow(chip: LibraryChip?, onAddRadio: () -> Unit) {
	Row(
		Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(start = 16.dp, end = 4.dp),
		verticalAlignment = Alignment.CenterVertically
	) {
		Icon(Icons.Outlined.Sort, contentDescription = null, modifier = Modifier.size(18.dp))
		Text(
			if (chip == null) stringResource(Res.string.library_recents) else stringResource(chip.label),
			style = MaterialTheme.typography.titleSmall,
			fontWeight = FontWeight.Bold,
			modifier = Modifier.padding(start = 8.dp)
		)
		Spacer(Modifier.weight(1f))
		when (chip) {
			LibraryChip.Playlists -> PlaylistListSortAction(playlistListViewModel(false), nested = true)
			LibraryChip.Albums -> AlbumListSortAction(albumListViewModel(false, LIBRARY_ALBUMS), nested = true)
			LibraryChip.Artists -> ArtistListSortAction(artistListViewModel(false, LIBRARY_ARTISTS), nested = true)
			LibraryChip.Songs -> SongListSortAction(songListViewModel(false, LIBRARY_SONGS), nested = true)
			LibraryChip.Radios -> TextButton(onClick = onAddRadio) {
				Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
				Text(stringResource(Res.string.library_add_radio), modifier = Modifier.padding(start = 6.dp))
			}
			LibraryChip.Mixes, LibraryChip.Downloaded, null -> Unit
		}
	}
}

/** Recents with its taps, its sheets and a pull that refreshes its sources. */
@Composable
private fun RecentsBody(
	viewModel: LibraryViewModel,
	recents: List<LibraryEntry>?,
	contentPadding: PaddingValues,
	listState: androidx.compose.foundation.lazy.LazyListState,
	connection: androidx.compose.ui.input.nestedscroll.NestedScrollConnection
) {
	val backStack = LocalNavStack.current
	val player = koinInject<MediaPlayerViewModel>()
	val radioManager = koinInject<RadioManager>()
	val persistent = koinInject<PersistentViewModelStoreOwner>()
	val mixes = mixListViewModel(false)
	val busyMix by mixes.busyId.collectAsStateWithLifecycle()
	val savedQueues = koinViewModel<SavedQueuesViewModel>(viewModelStoreOwner = persistent)
	val queueActions = rememberSavedQueueActions(savedQueues, player)
	val albums = albumListViewModel(false, LIBRARY_ALBUMS)
	val playlists = playlistListViewModel(false)
	val scope = rememberCoroutineScope()

	var sheet by remember { mutableStateOf<Any?>(null) }
	var renaming by remember { mutableStateOf<Mix?>(null) }
	var refreshing by remember { mutableStateOf(false) }

	PullToRefreshBox(
		isRefreshing = refreshing,
		onRefresh = {
			// The sources Recents reads: the albums' and the playlists' rows in Room. Mixes and
			// saved queues are the hub's and arrive on their own.
			refreshing = true
			albums.refreshAlbums(true)
			playlists.refreshPlaylists(true)
			refreshing = false
		},
		modifier = Modifier.fillMaxSize()
	) {
		LibraryRecents(
			entries = recents,
			contentPadding = contentPadding,
			listState = listState,
			busyKey = busyMix?.let { "mx:$it" },
			scrollConnection = connection,
			onOpen = { entry ->
				when (val source = entry.source) {
					null -> backStack.add(Screen.Starred(nested = true))
					is Mix -> mixes.play(source)
					is SavedQueueEntity -> queueActions.resume(source)
					is DomainPlaylist -> backStack.add(Screen.CollectionDetail(source.id, ""))
					is DomainAlbum -> if (entry.kind == EntryKind.Artist) {
						backStack.add(Screen.ArtistDetail(source.artistId))
					} else {
						backStack.add(Screen.CollectionDetail(source.id, ""))
					}
				}
			},
			onMenu = { entry ->
				// The whole item for its sheet: Recents holds rows, not their songs. One Room no
				// longer holds opens nothing.
				scope.launch {
					sheet = when (val source = entry.source) {
						is Mix, is SavedQueueEntity -> source
						is DomainPlaylist -> viewModel.playlist(source.id)
						is DomainAlbum -> if (entry.kind == EntryKind.Artist) viewModel.artist(source.artistId)
						else viewModel.album(source.id)
						else -> null
					}
				}
			}
		)
	}

	when (val target = sheet) {
		is Mix -> MixSheet(
			mix = target,
			onDismissRequest = { sheet = null },
			onPreview = null,
			onRename = { renaming = target },
			onDelete = { mixes.delete(target.id) }
		)
		is SavedQueueEntity -> SavedQueueSheet(
			queue = target,
			subtitle = savedQueueSubtitle(target, target.id == queueActions.activeId),
			onDismissRequest = { sheet = null },
			onRestore = { queueActions.restore(target) },
			onResume = { queueActions.resume(target) },
			onPreview = { queueActions.resume(target) },
			onDelete = { queueActions.delete(target) }
		)
		is DomainSongCollection -> CollectionSheet(
			onDismissRequest = { sheet = null },
			collection = target,
			onPlayNext = { player.playNext(target) },
			onAddToQueue = { player.addToQueue(target) },
			onViewArtist = (target as? DomainAlbum)?.let { album ->
				{ backStack.add(Screen.ArtistDetail(album.artistId)) }
			}
		)
		is DomainArtist -> ArtistSheet(
			onDismissRequest = { sheet = null },
			artist = target,
			onStartRadio = { radioManager.startArtistRadio(target.id, target.name) }
		)
		else -> Unit
	}

	renaming?.let { target ->
		MixNameDialog(
			title = stringResource(Res.string.title_rename_mix),
			initial = target.name,
			onConfirm = { name ->
				mixes.rename(target.id, name)
				renaming = null
			},
			onDismissRequest = { renaming = null }
		)
	}
}
