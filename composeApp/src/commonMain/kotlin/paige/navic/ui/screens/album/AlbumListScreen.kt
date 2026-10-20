package paige.navic.ui.screens.album

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import kotlinx.collections.immutable.toImmutableList
import navic.composeapp.generated.resources.Res
import navic.composeapp.generated.resources.title_albums
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import paige.navic.di.LocalBottomBarScrollManager
import paige.navic.di.LocalPlatformContext
import paige.navic.di.isLandscape
import paige.navic.domain.manager.PreferenceManager
import paige.navic.domain.models.DomainAlbumListType
import paige.navic.domain.models.DomainSongCollection
import paige.navic.domain.models.settings.BottomBarVisibilityMode
import paige.navic.domain.models.settings.ListViewMode
import paige.navic.shared.MediaPlayerViewModel
import paige.navic.ui.components.common.AlphabeticalScroller
import paige.navic.ui.components.common.SegmentedListItemDefaults
import paige.navic.ui.components.layouts.ArtGrid
import paige.navic.ui.components.layouts.NestedTopBar
import paige.navic.ui.components.layouts.PullToRefreshBox
import paige.navic.ui.components.layouts.RootBottomBar
import paige.navic.ui.components.layouts.RootTopBar
import paige.navic.ui.components.snackbars.ErrorSnackBar
import paige.navic.ui.core.UiState
import paige.navic.ui.navigation.PersistentViewModelStoreOwner
import paige.navic.ui.screens.album.components.AlbumListScreenSortButton
import paige.navic.ui.screens.album.components.albumListScreenContent
import paige.navic.ui.screens.album.viewmodels.AlbumListViewModel
import paige.navic.ui.screens.share.dialogs.ShareDialog
import paige.navic.ui.util.withoutTop
import paige.navic.ui.viewmodel.RootViewModel
import kotlin.time.Duration

/**
 * The album list's view model, by the store-owner rule (CLAUDE.md §2 rule 14): pushed (`nested`) it
 * dies with its entry; otherwise (the Library's Albums chip, P5) it lives in the persistent store,
 * so switching chips keeps its scroll and its sort.
 */
@Composable
fun albumListViewModel(nested: Boolean, listType: DomainAlbumListType): AlbumListViewModel =
	koinViewModel(
		key = listType.toString(),
		parameters = { parametersOf(listType) },
		viewModelStoreOwner = if (nested) {
			LocalViewModelStoreOwner.current!!
		} else {
			koinInject<PersistentViewModelStoreOwner>()
		}
	)

@Composable
fun AlbumListScreen(
	nested: Boolean = false,
	listType: DomainAlbumListType
) {
	val platformContext = LocalPlatformContext.current
	val preferenceManager = koinInject<PreferenceManager>()
	val viewModel = albumListViewModel(nested, listType)
	val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

	val actions: @Composable RowScope.() -> Unit = {
		AlbumListSortAction(viewModel, nested)
	}

	Scaffold(
		topBar = {
			if (!nested) {
				RootTopBar(
					{ Text(stringResource(Res.string.title_albums)) },
					scrollBehavior,
					actions
				)
			} else {
				NestedTopBar({ Text(stringResource(Res.string.title_albums)) }, actions = actions)
			}
		},
		bottomBar = {
			val scrollManager = LocalBottomBarScrollManager.current
			val preferVisible = preferenceManager.bottomBarVisibilityMode == BottomBarVisibilityMode.AllScreens
			if (!nested || (!platformContext.isLandscape() && preferVisible)) {
				RootBottomBar(scrolled = scrollManager.isTriggered)
			}
		}
	) { innerPadding ->
		AlbumListBody(
			viewModel = viewModel,
			contentPadding = innerPadding,
			scrollConnection = if (!nested) scrollBehavior.nestedScrollConnection else null
		)
	}
}

/** The album list's sort menu (with its filters and the list / grid setting). */
@Composable
fun AlbumListSortAction(viewModel: AlbumListViewModel, nested: Boolean) {
	val preferenceManager = koinInject<PreferenceManager>()
	val selectedSorting by viewModel.listType.collectAsStateWithLifecycle()
	val selectedReversed by viewModel.selectedReversed.collectAsStateWithLifecycle()
	val selectedFilters by viewModel.selectedFilters.collectAsStateWithLifecycle()
	AlbumListScreenSortButton(
		nested = nested,
		selectedSorting = selectedSorting,
		onSetSorting = { viewModel.setListType(it) },
		selectedReversed = selectedReversed,
		onSetReversed = { viewModel.setReversed(it) },
		selectedViewMode = preferenceManager.albumListViewMode,
		onSetViewMode = { preferenceManager.albumListViewMode = it },
		selectedFilters = selectedFilters,
		onToggleFilter = { viewModel.toggleFilter(it) }
	)
}

/**
 * The album list itself (P5): pull to refresh, the grid or list, the alphabetical scroller, the
 * long-press sheet, the share dialog and the error. Hosted by [AlbumListScreen] and by the Library's
 * Albums chip; [contentPadding]'s top is applied above it and the rest inside the grid.
 */
@Composable
fun AlbumListBody(
	viewModel: AlbumListViewModel,
	contentPadding: PaddingValues,
	modifier: Modifier = Modifier,
	scrollConnection: NestedScrollConnection? = null
) {
	val preferenceManager = koinInject<PreferenceManager>()
	val selectedViewMode = preferenceManager.albumListViewMode
	val player = koinInject<MediaPlayerViewModel>()
	val selectedSorting by viewModel.listType.collectAsStateWithLifecycle()
	val albumsState by viewModel.albumsState.collectAsStateWithLifecycle()
	val selectedAlbum by viewModel.selectedAlbum.collectAsStateWithLifecycle()
	val starred by viewModel.starred.collectAsStateWithLifecycle()
	val rating by viewModel.rating.collectAsStateWithLifecycle()
	var shareId by remember { mutableStateOf<String?>(null) }
	var shareExpiry by remember { mutableStateOf<Duration?>(null) }

	val rootViewModel = koinViewModel<RootViewModel>()
	LaunchedEffect(Unit) {
		rootViewModel.events.collect { event ->
			if (event is RootViewModel.Event.ScrollToTop) {
				viewModel.gridState.animateScrollToItem(0)
			}
		}
	}

	PullToRefreshBox(
		modifier = modifier
			.padding(top = contentPadding.calculateTopPadding()),
		finished = albumsState !is UiState.Loading,
		onRefresh = { viewModel.refreshAlbums(true) },
		key = albumsState
	) {
		val grouped = remember(albumsState.data) {
			albumsState.data.orEmpty().groupBy { it.name?.firstOrNull()?.uppercaseChar() ?: '#' }
				.toList()
				.sortedBy { it.first }
		}

		val headerIndices = remember(grouped) {
			var currentIndex = 0
			grouped.map { (letter, albums) ->
				val pos = currentIndex
				currentIndex += albums.size + 1
				letter.toString() to pos
			}.toImmutableList()
		}

		Box {
			ArtGrid(
				modifier = if (scrollConnection != null)
					Modifier.nestedScroll(scrollConnection)
				else Modifier,
				state = viewModel.gridState,
				contentPadding = contentPadding.withoutTop(),
				verticalArrangement = if (albumsState.data?.isEmpty() == true) {
					Arrangement.Center
				} else if (selectedViewMode == ListViewMode.List) {
					// The Library's rows, 3 dp apart (P5 round 2).
					Arrangement.spacedBy(SegmentedListItemDefaults.SegmentedGap)
				} else {
					Arrangement.spacedBy(12.dp)
				},
				selectedViewMode = selectedViewMode
			) {
				albumListScreenContent(
					state = albumsState,
					starred = starred,
					selectedSorting = selectedSorting,
					selectedAlbum = selectedAlbum,
					selectedAlbumRating = rating,
					selectedViewMode = selectedViewMode,
					onPlayNext = { if (selectedAlbum != null) player.playNext(selectedAlbum as DomainSongCollection) },
					onAddToQueue = { if (selectedAlbum != null) player.addToQueue(selectedAlbum as DomainSongCollection) },
					onUpdateSelection = { viewModel.selectAlbum(it) },
					onClearSelection = { viewModel.clearSelection() },
					onSetShareId = { newShareId ->
						shareId = newShareId
					},
					onSetStarred = { viewModel.starAlbum(it) },
					onRateSelectedAlbum = { viewModel.setRating(it) }
				)
			}
			if (selectedSorting == DomainAlbumListType.AlphabeticalByName) {
				AlphabeticalScroller(
					state = viewModel.gridState,
					headers = headerIndices,
					modifier = Modifier.align(Alignment.TopEnd),
					bottomPadding = contentPadding.calculateBottomPadding()
				)
			}
		}
	}

	ErrorSnackBar(
		error = (albumsState as? UiState.Error)?.error,
		onClearError = { viewModel.clearError() }
	)

	ShareDialog(
		id = shareId,
		onIdClear = { shareId = null },
		expiry = shareExpiry,
		onExpiryChange = { shareExpiry = it }
	)
}
