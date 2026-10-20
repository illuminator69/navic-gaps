package paige.navic.ui.screens.search

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.insert
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.launch
import navic.composeapp.generated.resources.Res
import navic.composeapp.generated.resources.action_add_to_queue
import navic.composeapp.generated.resources.action_remove_from_history
import navic.composeapp.generated.resources.action_search_history
import navic.composeapp.generated.resources.label_in_library
import navic.composeapp.generated.resources.mood_mix_name
import navic.composeapp.generated.resources.notice_deleted_download
import navic.composeapp.generated.resources.notice_download_started
import navic.composeapp.generated.resources.search_add_from_link
import navic.composeapp.generated.resources.search_by_sound
import navic.composeapp.generated.resources.search_by_sound_line
import navic.composeapp.generated.resources.search_downloaded_here
import navic.composeapp.generated.resources.search_nothing_checked_both
import navic.composeapp.generated.resources.search_nothing_checked_library
import navic.composeapp.generated.resources.search_nothing_for
import navic.composeapp.generated.resources.search_offline_banner
import navic.composeapp.generated.resources.search_offline_none
import navic.composeapp.generated.resources.search_offline_outside
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import paige.navic.data.database.entities.DownloadStatus
import paige.navic.di.LocalBottomBarScrollManager
import paige.navic.di.LocalNavStack
import paige.navic.di.LocalPlatformContext
import paige.navic.di.isLandscape
import paige.navic.domain.manager.AudioMuseManager
import paige.navic.domain.manager.ClapAvailability
import paige.navic.domain.manager.DownloadManager
import paige.navic.domain.manager.LbBotManager
import paige.navic.domain.manager.PreferenceManager
import paige.navic.domain.manager.RadioManager
import paige.navic.domain.manager.ShellController
import paige.navic.domain.manager.SnackBarManager
import paige.navic.domain.models.DomainAlbum
import paige.navic.domain.models.DomainAlbumListType
import paige.navic.domain.models.DomainArtist
import paige.navic.domain.models.DomainArtistListType
import paige.navic.domain.models.DomainExplicitStatus
import paige.navic.domain.models.DomainPlaylist
import paige.navic.domain.models.DomainSong
import paige.navic.domain.models.DomainSongCollection
import paige.navic.domain.models.settings.BottomBarVisibilityMode
import paige.navic.domain.models.settings.ExplicitContentPlayback
import paige.navic.icons.Icons
import paige.navic.icons.outlined.Close
import paige.navic.icons.outlined.History
import paige.navic.icons.outlined.Info
import paige.navic.icons.outlined.Link
import paige.navic.icons.outlined.NoSearchResults
import paige.navic.icons.outlined.Offline
import paige.navic.icons.outlined.Queue
import paige.navic.icons.outlined.Sparkle
import paige.navic.shared.MediaPlayerViewModel
import paige.navic.ui.components.common.PageChips
import paige.navic.ui.components.common.SegmentedListItem
import paige.navic.ui.components.common.SegmentedListItemDefaults.segmentedShapes
import paige.navic.ui.components.common.SwipeToDismissBox
import paige.navic.ui.components.common.blur.LocalExpressiveBlur
import paige.navic.ui.components.common.blur.expressiveBlurEffect
import paige.navic.ui.components.dialogs.QueueDuplicateDialog
import paige.navic.ui.components.layouts.RootBottomBar
import paige.navic.ui.components.sheets.ArtistSheet
import paige.navic.ui.components.sheets.CollectionSheet
import paige.navic.ui.components.sheets.MoodSearchSheet
import paige.navic.ui.components.sheets.SongSheet
import paige.navic.ui.navigation.PersistentViewModelStoreOwner
import paige.navic.ui.navigation.Screen
import paige.navic.ui.screens.album.viewmodels.AlbumListViewModel
import paige.navic.ui.screens.artist.viewmodels.ArtistListViewModel
import paige.navic.ui.screens.playlist.dialogs.PlaylistUpdateDialog
import paige.navic.ui.screens.search.components.SearchAlbumRow
import paige.navic.ui.screens.search.components.SearchArtistRow
import paige.navic.ui.screens.search.components.SearchSectionHeader
import paige.navic.ui.screens.search.components.SearchPlaylistRow
import paige.navic.ui.screens.search.components.SearchScreenTopBar
import paige.navic.ui.screens.search.components.SearchSongRow
import paige.navic.ui.screens.search.components.browseSection
import paige.navic.ui.screens.search.components.externalSection
import paige.navic.ui.screens.search.viewmodels.SearchViewModel
import paige.navic.ui.util.ArrivalGate
import paige.navic.ui.util.Fold
import paige.navic.ui.util.arrive
import paige.navic.ui.util.rememberArrivalGate
import paige.navic.ui.viewmodel.RootViewModel

/**
 * Search (P3): the one place to find music. With the field empty: recent searches and the browse
 * cards. Typing: the library first ("In your library": Navidrome's search with the typo-tolerant pass
 * beside it), then lb-bot's MusicBrainz artists and albums arriving after it ("Not in your library").
 * "No results" is said only once every source has answered with nothing (`searchPhase`); offline, only
 * downloaded music is searched and the page says so. Every decision is in `SearchModel.kt` and
 * `SearchState.kt`; this draws.
 */
@Composable
fun SearchScreen(
	nested: Boolean
) {
	val platformContext = LocalPlatformContext.current
	val preferenceManager = koinInject<PreferenceManager>()

	val viewModel = koinViewModel<SearchViewModel>(
		viewModelStoreOwner = if (nested) {
			LocalViewModelStoreOwner.current!!
		} else {
			koinInject<PersistentViewModelStoreOwner>()
		}
	)
	val ui by viewModel.ui.collectAsStateWithLifecycle()
	val selectedSong by viewModel.selectedSong.collectAsStateWithLifecycle()
	val selectedSongIsStarred by viewModel.selectedSongIsStarred.collectAsStateWithLifecycle()
	val selectedSongRating by viewModel.selectedSongRating.collectAsStateWithLifecycle()

	// The library lists' own view models, for the album and artist sheets a long press opens.
	val artistListViewModel = koinViewModel<ArtistListViewModel> {
		parametersOf(DomainArtistListType.AlphabeticalByName)
	}
	val artistListSelection by artistListViewModel.selectedArtist.collectAsState()
	val artistListSelectionAlbums by artistListViewModel.selectedArtistAlbums.collectAsState()
	val artistListStarred by artistListViewModel.starred.collectAsState()

	val albumListViewModel = koinViewModel<AlbumListViewModel> {
		parametersOf(DomainAlbumListType.AlphabeticalByName)
	}
	val albumListSelection by albumListViewModel.selectedAlbum.collectAsState()
	val albumListStarred by albumListViewModel.starred.collectAsState()
	val selectedAlbumRating by albumListViewModel.rating.collectAsStateWithLifecycle()

	val query = viewModel.searchQuery
	val searchHistory by viewModel.searchHistory.collectAsState(initial = emptyList())
	val isOnline by viewModel.isOnline.collectAsState()
	val downloadedSongs by viewModel.downloadedSongs.collectAsState()

	val player = koinInject<MediaPlayerViewModel>()
	val radioManager = koinInject<RadioManager>()
	val audioMuseManager = koinInject<AudioMuseManager>()
	val lbBot = koinInject<LbBotManager>()
	val shell = koinInject<ShellController>()
	val backStack = LocalNavStack.current
	val gate = rememberArrivalGate()

	var selectedCategory by rememberSaveable { mutableStateOf(SearchCategory.ALL) }
	var songToQueue by remember { mutableStateOf<DomainSong?>(null) }
	var selectedPlaylist by remember { mutableStateOf<DomainPlaylist?>(null) }

	// upstream's "scroll up when tapping tabs" (alpha55), lost in a merge and restored (I-039).
	val rootViewModel = koinViewModel<RootViewModel>()
	LaunchedEffect(Unit) {
		rootViewModel.events.collect { event ->
			if (event is RootViewModel.Event.ScrollToTop) {
				viewModel.gridState.animateScrollToItem(0)
			}
		}
	}

	// The browse cards' gates: a cached answer, not a request per visit.
	val lbBotAvailable by lbBot.available.collectAsState()
	LaunchedEffect(Unit) { lbBot.ensureAvailability() }
	val tiles = remember(lbBotAvailable) {
		browseTiles(
			lbBotAvailable = lbBotAvailable,
			chartsRoute = lbBot.isConfigured && lbBot.advertisesRoute(ROUTE_DEEZER_CHART),
			picksRoute = lbBot.isConfigured && lbBot.advertisesRoute(ROUTE_DEEZER_EDITORIAL),
			audioMuseConfigured = audioMuseManager.isConfigured
		)
	}

	// CLAP text→mood search (AudioMuse Tier 2), "Search by sound" here. Probed for availability AND
	// reason: when Tier 2 is configured but unusable the row is shown disabled with what's wrong,
	// rather than vanishing. Keyed on the route config so changing it re-probes.
	var clapState by remember { mutableStateOf(ClapAvailability.NOT_CONFIGURED) }
	LaunchedEffect(audioMuseManager.routeSignature) {
		clapState = audioMuseManager.clapAvailability()
	}

	// Mood-search preview sheet: the row fetches the proposed queue and shows it before playing.
	val moodScope = rememberCoroutineScope()
	var showMoodSheet by remember { mutableStateOf(false) }
	var moodLoading by remember { mutableStateOf(false) }
	var moodQuery by remember { mutableStateOf("") }
	var moodSongs by remember { mutableStateOf<List<DomainSong>>(emptyList()) }
	val startMoodSearch = { q: String ->
		viewModel.addToSearchHistory(q)
		moodQuery = q
		moodSongs = emptyList()
		moodLoading = true
		showMoodSheet = true
		moodScope.launch {
			moodSongs = radioManager.fetchMoodSearchSongs(q)
			moodLoading = false
		}
		Unit
	}
	val reprobeClap = {
		moodScope.launch { clapState = audioMuseManager.clapAvailability() }
		Unit
	}

	// The album page's top-bar recipe: transparent at rest, a translucent surface over the frost once
	// rows are scrolled under it. NOT `by`: read in the draw phase, so a scroll recomposes nothing.
	val topBarScrim = animateFloatAsState(
		if (viewModel.gridState.canScrollBackward) 1f else 0f
	)
	val topBarScrimColor = MaterialTheme.colorScheme.surface

	val shown = capped(ui.hits, selectedCategory)
	val external = externalRows(ui.artists, ui.albums, selectedCategory)
	val categoryHasExternal = selectedCategory == SearchCategory.ALL ||
		selectedCategory == SearchCategory.ARTISTS || selectedCategory == SearchCategory.ALBUMS
	val externalShown = ui.online && ui.lbConfigured && externalLookupWanted(ui.query) && categoryHasExternal
	val lookupOut = ui.artists is Lookup.Looking || ui.albums is Lookup.Looking
	val lookupFailed = ui.artists is Lookup.Failed || ui.albums is Lookup.Failed

	// Rows below the fold appear at once when scrolled to, rather than each arriving on its own.
	LaunchedEffect(shown, external) {
		withFrameNanos { }
		gate.claimAll(libraryKeys(shown) + external.map { it.key })
	}

	Scaffold(
		topBar = {
			Column(
				modifier = Modifier
					.expressiveBlurEffect(LocalExpressiveBlur.current)
					.drawBehind {
						// Capped at 0.7 like the album page's, so the wash still shows through.
						drawRect(topBarScrimColor.copy(alpha = topBarScrim.value * 0.7f))
					}
					.padding(TopAppBarDefaults.windowInsets.asPaddingValues())
			) {
				SearchScreenTopBar(
					query = query,
					nested = nested,
					onSearch = { submittedQuery ->
						viewModel.addToSearchHistory(submittedQuery)
					}
				)
				// The chips come with the first character.
				Fold(visible = query.text.isNotEmpty()) {
					PageChips(
						options = SearchCategory.entries,
						selected = selectedCategory,
						label = { stringResource(it.res) },
						onSelect = {
							selectedCategory = it
							// A chip that swaps the rows resets the scroll in the same frame (§7).
							viewModel.gridState.requestScrollToItem(0)
						},
						modifier = Modifier.padding(bottom = 8.dp)
					)
				}
			}
		},
		bottomBar = {
			val scrollManager = LocalBottomBarScrollManager.current
			val preferVisible = preferenceManager.bottomBarVisibilityMode == BottomBarVisibilityMode.AllScreens
			if (!nested || (!platformContext.isLandscape() && preferVisible)) {
				RootBottomBar(scrolled = scrollManager.isTriggered)
			}
		}
	) { contentPadding ->
		LazyVerticalGrid(
			modifier = Modifier.fillMaxSize(),
			columns = GridCells.Fixed(2),
			contentPadding = contentPadding,
			state = viewModel.gridState
		) {
			// One item that is always first. The grid keeps its first visible KEY in place, so with
			// the unowned section's header first while the library is still out, the library's rows
			// landed above the view and the page opened scrolled down (seen on the AVD). Anchored on
			// this, what lands below the top pushes down and the page stays at its top.
			item(span = { GridItemSpan(maxLineSpan) }, key = "top", contentType = "top") {
				Spacer(Modifier.fillMaxWidth().height(1.dp))
			}
			when (ui.phase) {
				SearchPhase.Browse -> {
					historySection(
						history = searchHistory,
						onPick = { historyItem ->
							query.clearText()
							query.edit { insert(0, historyItem) }
						},
						onRemove = { viewModel.removeFromSearchHistory(it) }
					)
					browseSection(tiles, gate) { tile ->
						val destination = when (tile) {
							BrowseTile.GENRES -> Screen.GenreList(nested = true)
							BrowseTile.FRESH -> Screen.Fresh(nested = true)
							BrowseTile.DISCOVER -> Screen.Discover(nested = true)
							BrowseTile.CHARTS -> Screen.Charts
							BrowseTile.PICKS -> Screen.EditorsPicks
						}
						if (backStack.lastOrNull() != destination) backStack.add(destination)
					}
				}

				SearchPhase.Results, SearchPhase.Offline -> {
					val offline = ui.phase == SearchPhase.Offline
					if (offline) offlineBanner()
					librarySection(
						hits = shown,
						title = if (offline) Res.string.search_downloaded_here else Res.string.label_in_library,
						gate = gate,
						isOnline = isOnline,
						downloadedSongs = downloadedSongs,
						explicitLocked = { song ->
							song.explicitStatus == DomainExplicitStatus.Explicit &&
								preferenceManager.explicitContentPlayback != ExplicitContentPlayback.Allowed
						},
						onArtist = { backStack.add(Screen.ArtistDetail(it.id)) },
						onArtistMenu = { artistListViewModel.selectArtist(it) },
						onAlbum = { backStack.add(Screen.CollectionDetail(it.id, "search")) },
						onAlbumMenu = { albumListViewModel.selectAlbum(it) },
						onSong = { player.playNow(it) },
						onSongMenu = { viewModel.selectSong(it) },
						onSongSwiped = { song ->
							if (player.uiState.value.queue.any { it.id == song.id } && !preferenceManager.shushQueueDuplicateDialog) {
								songToQueue = song
							} else {
								player.addToQueueSingle(song)
							}
						},
						onPlaylist = { backStack.add(Screen.CollectionDetail(it.id, "search")) },
						onPlaylistMenu = { selectedPlaylist = it }
					)
					if (!offline) {
						moodRow(clapState, ui.query, onSearch = startMoodSearch, onReprobe = reprobeClap)
					}
					if (externalShown) {
						externalSection(
							rows = external,
							looking = lookupOut,
							failed = lookupFailed,
							gate = gate,
							onRetry = { viewModel.retryLookups() },
							onOpenArtist = { candidate ->
								backStack.add(
									Screen.ExternalArtist(
										artistMbid = candidate.candidate.mbid,
										name = candidate.candidate.name
									)
								)
							},
							onOpenAlbum = { candidate ->
								// An owned release-group opens the LIBRARY album: the external page's
								// redirect cannot fire for an album lb-bot filled itself until the
								// backfill resolves its Navidrome ids.
								backStack.add(
									if (candidate.releaseAlbumId.isNotBlank()) {
										Screen.CollectionDetail(candidate.releaseAlbumId, "search")
									} else {
										Screen.ExternalAlbum(
											rgid = candidate.rgid,
											artistName = candidate.artist,
											title = candidate.title
										)
									}
								)
							}
						)
					}
					if (offline && ui.lbConfigured) {
						item(span = { GridItemSpan(maxLineSpan) }, key = "offline-outside", contentType = "note") {
							InfoCard(
								text = stringResource(Res.string.search_offline_outside),
								modifier = Modifier.animateItem()
							)
						}
					}
				}

				SearchPhase.NoResults -> {
					item(span = { GridItemSpan(maxLineSpan) }, key = "nothing", contentType = "note") {
						NothingFound(
							query = ui.query,
							checkedBeyond = ui.lbConfigured && externalLookupWanted(ui.query),
							offerLink = lbBot.isConfigured && lbBot.supportsResolveLink,
							onAddFromLink = { shell.openAddFromLink() },
							modifier = Modifier.animateItem()
						)
					}
					moodRow(clapState, ui.query, onSearch = startMoodSearch, onReprobe = reprobeClap)
				}

				SearchPhase.OfflineNone -> {
					offlineBanner()
					item(span = { GridItemSpan(maxLineSpan) }, key = "offline-none", contentType = "note") {
						Text(
							stringResource(Res.string.search_offline_none),
							style = MaterialTheme.typography.titleMedium,
							textAlign = TextAlign.Center,
							modifier = Modifier.animateItem().fillMaxWidth().padding(horizontal = 32.dp, vertical = 40.dp)
						)
					}
				}
			}
		}
	}

	// ---- the sheets a long press opens ------------------------------------------------------ //

	selectedSong?.let { song ->
		SongSheet(
			onDismissRequest = { viewModel.clearSelectedSong() },
			onStartRadio = { radioManager.startRadio(song) },
			onStartJourney = player.uiState.value.currentSong?.takeIf {
				radioManager.sonicSimilarityAvailable.value && it.id != song.id
			}?.let { now -> { radioManager.startJourney(now.id, song.id) } },
			song = song,
			onPlayNext = {
				if (player.uiState.value.queue.any { it.id == song.id } && !preferenceManager.shushQueueDuplicateDialog) {
					songToQueue = song
				} else {
					player.playNextSingle(song)
				}
			},
			onAddToQueue = {
				if (player.uiState.value.queue.any { it.id == song.id } && !preferenceManager.shushQueueDuplicateDialog) {
					songToQueue = song
				} else {
					player.addToQueueSingle(song)
				}
			},
			downloadStatus = if (downloadedSongs.containsKey(song.id)) DownloadStatus.DOWNLOADED else null,
			onTrackInfo = dropUnlessResumed {
				backStack.add(Screen.SongDetailScreen(song.id, song.coverArtId))
			},
			onViewAlbum = song.albumId?.let { albumId ->
				dropUnlessResumed {
					backStack.add(Screen.CollectionDetail(collectionId = albumId, tab = "search"))
				}
			},
			starred = selectedSongIsStarred,
			onSetStarred = { viewModel.starSelectedSong(it) },
			rating = selectedSongRating,
			onSetRating = { viewModel.rateSelectedSong(it) }
		)
	}

	albumListSelection?.let { album ->
		SearchAlbumSheet(album, albumListStarred, selectedAlbumRating, albumListViewModel, player)
	}

	artistListSelection?.let { artist ->
		var playlistDialogShown by remember(artist.id) { mutableStateOf(false) }
		ArtistSheet(
			onDismissRequest = { artistListViewModel.clearSelection() },
			artist = artist,
			onPlayNext = { artistListViewModel.playArtistAlbumsNext(player) },
			onAddToQueue = { artistListViewModel.addArtistAlbumsToQueue(player) },
			onAddAllToPlaylist = { playlistDialogShown = true },
			starred = artistListStarred,
			onSetStarred = { artistListViewModel.starArtist(!artistListStarred) }
		)
		if (playlistDialogShown) {
			PlaylistUpdateDialog(
				songs = artistListSelectionAlbums?.flatMap { it.songs }.orEmpty().toPersistentList(),
				onDismissRequest = { playlistDialogShown = false }
			)
		}
	}

	selectedPlaylist?.let { playlist ->
		CollectionSheet(
			onDismissRequest = { selectedPlaylist = null },
			collection = playlist,
			onPlayNext = { player.playNext(playlist as DomainSongCollection) },
			onAddToQueue = { player.addToQueue(playlist as DomainSongCollection) }
		)
	}

	if (songToQueue != null) {
		QueueDuplicateDialog(
			onDismissRequest = { songToQueue = null },
			onConfirm = {
				songToQueue?.let { player.addToQueueSingle(it) }
			}
		)
	}

	if (showMoodSheet) {
		// Saved to history as a search's results, under the query (I-040), not as "Mood Flow".
		val moodMixName = stringResource(Res.string.mood_mix_name, moodQuery)
		MoodSearchSheet(
			query = moodQuery,
			songs = moodSongs,
			loading = moodLoading,
			onPlay = { radioManager.playMoodMix(moodSongs, moodMixName) },
			onAddToQueue = { radioManager.enqueueMoodMix(moodSongs) },
			onDismissRequest = { showMoodSheet = false }
		)
	}
}

/** The two Deezer routes the Charts and Editors' picks cards need (Discover's catalogue names the same). */
private const val ROUTE_DEEZER_CHART = "GET /lb/deezer/chart"
private const val ROUTE_DEEZER_EDITORIAL = "GET /lb/deezer/editorial"

/** Every library row's list key, as [librarySection] writes them. */
private fun libraryKeys(hits: LibraryHits): List<String> =
	hits.artists.map { "l:ar:${it.id}" } + hits.albums.map { "l:al:${it.id}" } +
		hits.songs.map { "l:so:${it.id}" } + hits.playlists.map { "l:pl:${it.id}" }

/** Recent searches (up to ten, each removable), above Browse; absent when there are none. */
private fun LazyGridScope.historySection(
	history: List<String>,
	onPick: (String) -> Unit,
	onRemove: (String) -> Unit
) {
	if (history.isEmpty()) return
	item(span = { GridItemSpan(maxLineSpan) }, key = "history-header", contentType = "header") {
		SearchSectionHeader(
			title = stringResource(Res.string.action_search_history),
			modifier = Modifier.animateItem()
		)
	}
	history.forEachIndexed { index, historyItem ->
		item(span = { GridItemSpan(maxLineSpan) }, key = "history:$historyItem", contentType = "history") {
			SegmentedListItem(
				modifier = Modifier.animateItem().padding(horizontal = 16.dp, vertical = 1.5.dp),
				onClick = { onPick(historyItem) },
				shapes = segmentedShapes(index, history.size),
				leadingContent = { Icon(Icons.Outlined.History, contentDescription = null) },
				trailingContent = {
					IconButton(onClick = { onRemove(historyItem) }) {
						Icon(
							Icons.Outlined.Close,
							contentDescription = stringResource(Res.string.action_remove_from_history)
						)
					}
				}
			) {
				Text(historyItem)
			}
		}
	}
}

/**
 * "In your library" (or, offline, "Downloaded on this device"): artists, albums, songs, playlists in
 * that order, one group of rows. A tap opens the row's page, a long press the app's own sheet; a song
 * plays on a tap and goes to the queue on a swipe. The rows arrive once per visit.
 */
private fun LazyGridScope.librarySection(
	hits: LibraryHits,
	title: StringResource,
	gate: ArrivalGate,
	isOnline: Boolean,
	downloadedSongs: Map<String, String>,
	explicitLocked: (DomainSong) -> Boolean,
	onArtist: (DomainArtist) -> Unit,
	onArtistMenu: (DomainArtist) -> Unit,
	onAlbum: (DomainAlbum) -> Unit,
	onAlbumMenu: (DomainAlbum) -> Unit,
	onSong: (DomainSong) -> Unit,
	onSongMenu: (DomainSong) -> Unit,
	onSongSwiped: (DomainSong) -> Unit,
	onPlaylist: (DomainPlaylist) -> Unit,
	onPlaylistMenu: (DomainPlaylist) -> Unit
) {
	val count = hits.artists.size + hits.albums.size + hits.songs.size + hits.playlists.size
	if (count == 0) return
	item(span = { GridItemSpan(maxLineSpan) }, key = "l-header", contentType = "header") {
		SearchSectionHeader(title = stringResource(title), modifier = Modifier.animateItem())
	}
	var index = 0
	hits.artists.forEach { artist ->
		val i = index++
		val key = "l:ar:${artist.id}"
		item(span = { GridItemSpan(maxLineSpan) }, key = key, contentType = "row") {
			SearchArtistRow(
				artist = artist, index = i, count = count,
				onClick = dropUnlessResumed { onArtist(artist) },
				onLongClick = { onArtistMenu(artist) },
				modifier = Modifier.animateItem().arrive(gate, key, i)
			)
		}
	}
	hits.albums.forEach { album ->
		val i = index++
		val key = "l:al:${album.id}"
		item(span = { GridItemSpan(maxLineSpan) }, key = key, contentType = "row") {
			SearchAlbumRow(
				album = album, index = i, count = count,
				onClick = dropUnlessResumed { onAlbum(album) },
				onLongClick = { onAlbumMenu(album) },
				modifier = Modifier.animateItem().arrive(gate, key, i)
			)
		}
	}
	hits.songs.forEach { song ->
		val i = index++
		val key = "l:so:${song.id}"
		item(span = { GridItemSpan(maxLineSpan) }, key = key, contentType = "song") {
			val dismissState = rememberSwipeToDismissBoxState()
			LaunchedEffect(dismissState.currentValue) {
				if (dismissState.currentValue == SwipeToDismissBoxValue.EndToStart) {
					onSongSwiped(song)
					dismissState.snapTo(SwipeToDismissBoxValue.Settled)
				}
			}
			val shapes = segmentedShapes(i, count, dismissDirection = dismissState.dismissDirection)
			SwipeToDismissBox(
				modifier = Modifier
					.animateItem()
					.arrive(gate, key, i)
					.padding(horizontal = 16.dp, vertical = 1.5.dp),
				state = dismissState,
				enableDismissFromStartToEnd = false,
				enableDismissFromEndToStart = true,
				backgroundContent = {
					val backgroundColor by animateColorAsState(
						if (dismissState.targetValue == SwipeToDismissBoxValue.EndToStart) MaterialTheme.colorScheme.primaryContainer
						else Color.Transparent
					)
					Box(
						modifier = Modifier
							.fillMaxSize()
							.clip(shapes.shape)
							.background(backgroundColor)
							.padding(horizontal = 20.dp),
						contentAlignment = Alignment.CenterEnd
					) {
						Icon(
							Icons.Outlined.Queue,
							contentDescription = stringResource(Res.string.action_add_to_queue),
							tint = MaterialTheme.colorScheme.onPrimaryContainer
						)
					}
				}
			) {
				SearchSongRow(
					song = song, index = i, count = count,
					explicitLocked = explicitLocked(song),
					unavailableOffline = !isOnline && !downloadedSongs.containsKey(song.id),
					onClick = { onSong(song) },
					onLongClick = { onSongMenu(song) }
				)
			}
		}
	}
	hits.playlists.forEach { playlist ->
		val i = index++
		val key = "l:pl:${playlist.id}"
		item(span = { GridItemSpan(maxLineSpan) }, key = key, contentType = "row") {
			SearchPlaylistRow(
				playlist = playlist, index = i, count = count,
				onClick = dropUnlessResumed { onPlaylist(playlist) },
				onLongClick = { onPlaylistMenu(playlist) },
				modifier = Modifier.animateItem().arrive(gate, key, i)
			)
		}
	}
}

/**
 * "Search by sound": AudioMuse's CLAP search over the typed words, as a row under the library's. When
 * Tier 2 is configured but unusable the row says why and a tap probes again; with no Tier 2 at all it
 * is absent, so someone who never set AudioMuse up has no dead row.
 */
private fun LazyGridScope.moodRow(
	clapState: ClapAvailability,
	query: String,
	onSearch: (String) -> Unit,
	onReprobe: () -> Unit
) {
	if (clapState == ClapAvailability.NOT_CONFIGURED) return
	item(span = { GridItemSpan(maxLineSpan) }, key = "mood", contentType = "mood") {
		SegmentedListItem(
			modifier = Modifier.animateItem().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 1.5.dp),
			onClick = { if (clapState.usable) onSearch(query) else onReprobe() },
			shapes = segmentedShapes(0, 1),
			leadingContent = {
				Box(modifier = Modifier.size(50.dp), contentAlignment = Alignment.Center) {
					Icon(Icons.Outlined.Sparkle, contentDescription = null)
				}
			},
			supportingContent = {
				Text(
					if (clapState.usable) stringResource(Res.string.search_by_sound_line, query)
					else when (clapState) {
						ClapAvailability.HUB_UNREACHABLE ->
							"Unavailable — the hub isn't answering AudioMuse requests, and no direct " +
								"AudioMuse server is configured."
						ClapAvailability.UNREACHABLE -> "Unavailable — can't reach the AudioMuse server."
						ClapAvailability.DISABLED_ON_SERVER -> "Unavailable — CLAP is switched off on the AudioMuse server."
						ClapAvailability.NOT_ANALYZED -> "Unavailable — your library hasn't been analyzed by AudioMuse yet."
						else -> "Unavailable."
					}
				)
			}
		) {
			Text(
				stringResource(Res.string.search_by_sound),
				color = if (clapState.usable) Color.Unspecified else MaterialTheme.colorScheme.onSurfaceVariant
			)
		}
	}
}

/** "Offline. Showing downloaded music only." */
private fun LazyGridScope.offlineBanner() {
	item(span = { GridItemSpan(maxLineSpan) }, key = "offline-banner", contentType = "note") {
		Row(
			modifier = Modifier
				.animateItem()
				.fillMaxWidth()
				.padding(horizontal = 16.dp, vertical = 8.dp)
				.clip(MaterialTheme.shapes.medium)
				.background(MaterialTheme.colorScheme.secondaryContainer)
				.padding(horizontal = 14.dp, vertical = 12.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(10.dp)
		) {
			Icon(Icons.Outlined.Offline, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
			Text(
				stringResource(Res.string.search_offline_banner),
				style = MaterialTheme.typography.bodyMedium,
				color = MaterialTheme.colorScheme.onSecondaryContainer
			)
		}
	}
}

@Composable
private fun InfoCard(text: String, modifier: Modifier = Modifier) {
	Row(
		modifier = modifier
			.fillMaxWidth()
			.padding(horizontal = 16.dp, vertical = 12.dp)
			.clip(MaterialTheme.shapes.medium)
			.background(MaterialTheme.colorScheme.surfaceContainer)
			.padding(horizontal = 14.dp, vertical = 12.dp),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(10.dp)
	) {
		Icon(Icons.Outlined.Info, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
		Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
	}
}

/**
 * "Nothing for “q”": what was checked (the library, and the artists beyond it only when lb-bot was
 * asked), and the way forward the board offers, "Add from a link", where lb-bot can resolve one.
 */
@Composable
private fun NothingFound(
	query: String,
	checkedBeyond: Boolean,
	offerLink: Boolean,
	onAddFromLink: () -> Unit,
	modifier: Modifier = Modifier
) {
	Column(
		modifier = modifier
			.fillMaxWidth()
			.padding(horizontal = 32.dp, vertical = 40.dp),
		horizontalAlignment = Alignment.CenterHorizontally,
		verticalArrangement = Arrangement.spacedBy(10.dp)
	) {
		Icon(
			Icons.Outlined.NoSearchResults,
			contentDescription = null,
			tint = MaterialTheme.colorScheme.onSurfaceVariant,
			modifier = Modifier.size(40.dp)
		)
		Text(
			stringResource(Res.string.search_nothing_for, query.trim()),
			style = MaterialTheme.typography.titleMedium,
			textAlign = TextAlign.Center
		)
		Text(
			stringResource(
				if (checkedBeyond) Res.string.search_nothing_checked_both else Res.string.search_nothing_checked_library
			),
			style = MaterialTheme.typography.bodyMedium,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
			textAlign = TextAlign.Center
		)
		if (offerLink) {
			Button(onClick = onAddFromLink, modifier = Modifier.padding(top = 6.dp)) {
				Icon(Icons.Outlined.Link, contentDescription = null, modifier = Modifier.size(18.dp))
				Text(stringResource(Res.string.search_add_from_link), modifier = Modifier.padding(start = 8.dp))
			}
		}
	}
}

/**
 * An album's sheet, as the album list's rows open it: queue, radio, download, star, rate, add to a
 * playlist, the artist.
 */
@Composable
private fun SearchAlbumSheet(
	album: DomainAlbum,
	starred: Boolean,
	rating: Int,
	albumListViewModel: AlbumListViewModel,
	player: MediaPlayerViewModel
) {
	val backStack = LocalNavStack.current
	val radioManager = koinInject<RadioManager>()
	val downloadManager = koinInject<DownloadManager>()
	val snackBarManager = koinInject<SnackBarManager>()
	val scope = rememberCoroutineScope()
	var playlistDialogShown by remember(album.id) { mutableStateOf(false) }
	val downloadStatus by downloadManager
		.getCollectionDownloadStatus(album.songs.map { it.id })
		.collectAsState(initial = DownloadStatus.NOT_DOWNLOADED)
	CollectionSheet(
		onDismissRequest = { albumListViewModel.clearSelection() },
		collection = album,
		onPlayNext = { player.playNext(album as DomainSongCollection) },
		onAddToQueue = { player.addToQueue(album as DomainSongCollection) },
		onStartRadio = { radioManager.startAlbumRadio(album.id, album.name.orEmpty()) },
		downloadStatus = downloadStatus,
		onDownloadAll = {
			scope.launch {
				downloadManager.downloadCollection(album)
				snackBarManager.notify(Res.string.notice_download_started)
			}
		},
		onCancelDownloadAll = {
			scope.launch { album.songs.forEach { downloadManager.cancelDownload(it.id) } }
		},
		onDeleteDownloadAll = {
			scope.launch {
				downloadManager.deleteDownloadedCollection(album)
				snackBarManager.notify(Res.string.notice_deleted_download)
			}
		},
		starred = starred,
		onSetStarred = { albumListViewModel.starAlbum(it) },
		onAddAllToPlaylist = { playlistDialogShown = true },
		onViewArtist = dropUnlessResumed { backStack.add(Screen.ArtistDetail(album.artistId)) },
		rating = rating,
		onSetRating = { albumListViewModel.setRating(it) }
	)
	if (playlistDialogShown) {
		PlaylistUpdateDialog(
			songs = album.songs.toPersistentList(),
			onDismissRequest = { playlistDialogShown = false }
		)
	}
}
