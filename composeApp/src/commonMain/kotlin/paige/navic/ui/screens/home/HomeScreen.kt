package paige.navic.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.dropUnlessResumed
import navic.composeapp.generated.resources.Res
import navic.composeapp.generated.resources.action_see_all
import navic.composeapp.generated.resources.home_fresh
import navic.composeapp.generated.resources.home_fresh_new_releases
import navic.composeapp.generated.resources.home_library_empty
import navic.composeapp.generated.resources.home_library_error
import navic.composeapp.generated.resources.home_not_owned
import navic.composeapp.generated.resources.home_owned
import navic.composeapp.generated.resources.home_recently_added
import navic.composeapp.generated.resources.home_your_mix
import navic.composeapp.generated.resources.home_your_mix_line
import navic.composeapp.generated.resources.home_your_mixes
import navic.composeapp.generated.resources.home_recently_played
import navic.composeapp.generated.resources.home_frequently_played
import androidx.navigation3.runtime.NavKey
import paige.navic.domain.manager.ShellController
import paige.navic.domain.models.DomainAlbum
import paige.navic.domain.models.Mix
import paige.navic.domain.models.MixKind
import paige.navic.domain.models.YOUR_MIX_ID
import paige.navic.ui.navigation.ShellTab
import paige.navic.ui.navigation.onShellTabTapped
import paige.navic.ui.screens.library.LibraryChip
import paige.navic.ui.screens.mixes.MixArtwork
import paige.navic.ui.screens.mixes.mixKindLabel
import paige.navic.ui.util.ArrivalGate
import navic.composeapp.generated.resources.title_home
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import paige.navic.data.database.entities.SavedQueueEntity
import paige.navic.di.LocalBottomBarScrollManager
import paige.navic.di.LocalNavStack
import paige.navic.domain.manager.LbBotManager
import paige.navic.domain.models.DomainAlbumListType
import paige.navic.domain.models.DomainSongCollection
import paige.navic.icons.Icons
import paige.navic.icons.outlined.Check
import paige.navic.icons.outlined.Download
import paige.navic.shared.MediaPlayerViewModel
import paige.navic.ui.components.layouts.ArtGridItem
import paige.navic.ui.screens.album.components.AlbumListScreenGridItem
import paige.navic.ui.screens.share.dialogs.ShareDialog
import kotlin.time.Duration
import paige.navic.ui.components.layouts.RootBottomBar
import paige.navic.ui.components.layouts.RootTopBar
import paige.navic.ui.components.common.RemoteCoverArt
import paige.navic.ui.core.UiState
import paige.navic.ui.navigation.PersistentViewModelStoreOwner
import paige.navic.ui.navigation.Screen
import paige.navic.ui.screens.savedqueues.components.SavedQueuePreviewSheet
import paige.navic.ui.screens.savedqueues.components.SavedQueueSheet
import paige.navic.ui.screens.savedqueues.rememberSavedQueueActions
import paige.navic.ui.screens.savedqueues.savedQueueSubtitle
import paige.navic.ui.screens.savedqueues.viewmodels.SavedQueuesViewModel
import paige.navic.ui.util.COVER_FADE_MS
import paige.navic.ui.util.rememberArrivalGate
import paige.navic.ui.util.rememberPlayerRestored
import paige.navic.ui.util.withoutTop
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import paige.navic.domain.manager.MoodEntry
import paige.navic.domain.manager.MoodVariant
import paige.navic.domain.manager.chipsVisible
import paige.navic.domain.manager.homeMoods
import paige.navic.domain.manager.queuesFromMood
import paige.navic.ui.util.Fold
import paige.navic.ui.viewmodel.RootViewModel

/** How many tinted boxes a shelf shows while its first read is out. */
private const val RECENT_PLACEHOLDERS = 5
private const val FRESH_PLACEHOLDERS = 4

/**
 * Home (P1, P6): where the app opens. Under the root bars, in this order: Continue listening (a capsule
 * that rolls out into the saved queues), Recently added, Fresh (where lb-bot is on), Recently played,
 * Frequently played, Your mixes (Your mix where the station engine is, then every hub mix). A root page shaped like `AlbumListScreen`: `Washed` by App.kt and named in
 * `WashedBrowsingScreens`, its ViewModel in the persistent store.
 *
 * Every sentence on it is true: a section with nothing to say is absent and leaves no gap, a failed
 * Fresh read keeps the shelf it has, and "nothing in your library" is said only when the read worked
 * and found nothing. Tiles arrive once per visit (`Arrival.kt`), a tint first and the cover over it.
 */
@Composable
fun HomeScreen() {
	val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
	val listState = rememberLazyListState()

	// Always the persistent store: a tab switch clears the back stack, and this page's ViewModel must
	// outlive that (CLAUDE.md §2 rule 14). It reads on every visit.
	val persistentOwner = koinInject<PersistentViewModelStoreOwner>()
	val viewModel = koinViewModel<HomeViewModel>(viewModelStoreOwner = persistentOwner)
	LaunchedEffect(viewModel) { viewModel.revalidate() }

	val rootViewModel = koinViewModel<RootViewModel>()
	LaunchedEffect(Unit) {
		rootViewModel.events.collect { event ->
			if (event is RootViewModel.Event.ScrollToTop) {
				listState.animateScrollToItem(0)
			}
		}
	}

	val backStack = LocalNavStack.current
	val player = koinInject<MediaPlayerViewModel>()
	val gate = rememberArrivalGate()

	val recent by viewModel.recent.collectAsStateWithLifecycle()
	val fresh by viewModel.fresh.collectAsStateWithLifecycle()
	val freshAvailable by viewModel.freshAvailable.collectAsStateWithLifecycle()
	val freshLoading by viewModel.freshLoading.collectAsStateWithLifecycle()
	val queues by viewModel.queues.collectAsStateWithLifecycle()
	val playingQueueId by viewModel.playingQueueId.collectAsStateWithLifecycle()
	val nothingPlaying by viewModel.nothingPlaying.collectAsStateWithLifecycle()
	val played by viewModel.played.collectAsStateWithLifecycle()
	val frequent by viewModel.frequent.collectAsStateWithLifecycle()
	val mixTiles by viewModel.mixTiles.collectAsStateWithLifecycle()
	val shell = koinInject<ShellController>()
	val syncing by viewModel.syncing.collectAsStateWithLifecycle()
	val selectedAlbum by viewModel.selectedAlbum.collectAsStateWithLifecycle()
	val moodCatalogue by viewModel.moodCatalogue.collectAsStateWithLifecycle()
	val moodSelection by viewModel.moodSelection.collectAsStateWithLifecycle()
	val moodBusy by viewModel.moodBusy.collectAsStateWithLifecycle()
	val starred by viewModel.starred.collectAsStateWithLifecycle()
	val rating by viewModel.rating.collectAsStateWithLifecycle()
	// Which shelf's tile the open album sheet belongs to: an album can be on three shelves at once,
	// and only the one long-pressed opens its sheet.
	var selectedShelf by remember { mutableStateOf<String?>(null) }
	var shareId by remember { mutableStateOf<String?>(null) }
	var shareExpiry by remember { mutableStateOf<Duration?>(null) }

	val continueQueues = remember(queues, playingQueueId) { continueListening(queues, playingQueueId) }
	val recentAlbums = recent.data.orEmpty()
	val sections = homeSections(
		queueCount = queues.size,
		recentCount = recentAlbums.size,
		freshAvailable = freshAvailable,
		freshCount = fresh.size,
		playedCount = played.size,
		frequentCount = frequent.size,
		mixTileCount = mixTiles.size
	)
	// Waiting is not empty: a section whose first read is out shows tinted boxes of its final size.
	val recentWaiting = recentAlbums.isEmpty() && (recent is UiState.Loading || syncing)
	val freshWaiting = freshAvailable && freshLoading && fresh.isEmpty()
	val showRecent = HomeSection.RecentlyAdded in sections || recentWaiting
	val showFresh = HomeSection.Fresh in sections || freshWaiting
	val showEmptyLine = sections.isEmpty() && !recentWaiting && !freshWaiting

	// The capsule's first state is decided once, when the player has said whether something is
	// playing and there are queues to show; after that it is the user's.
	val restored = rememberPlayerRestored()
	var continueOpen by rememberSaveable { mutableStateOf<Boolean?>(null) }
	LaunchedEffect(restored, queues.isNotEmpty()) {
		if (continueOpen == null && restored && queues.isNotEmpty()) {
			continueOpen = continueStartsExpanded(nothingPlaying, queues.size)
		}
	}

	val savedQueuesViewModel = koinViewModel<SavedQueuesViewModel>(viewModelStoreOwner = persistentOwner)
	val savedQueueActions = rememberSavedQueueActions(savedQueuesViewModel, player)
	var previewQueue by remember { mutableStateOf<SavedQueueEntity?>(null) }
	var menuQueue by remember { mutableStateOf<SavedQueueEntity?>(null) }

	val recentTitle = stringResource(Res.string.home_recently_added)
	val freshTitle = stringResource(Res.string.home_fresh)
	val seeAll = stringResource(Res.string.action_see_all)
	val newReleases = stringResource(Res.string.home_fresh_new_releases)
	val yourMixTitle = stringResource(Res.string.home_your_mix)
	val yourMixesTitle = stringResource(Res.string.home_your_mixes)
	val playedTitle = stringResource(Res.string.home_recently_played)
	val frequentTitle = stringResource(Res.string.home_frequently_played)
	// Moods (P7): the chip row only where AudioMuse is there; a chip selected folds the shelves away
	// and the mood's body in. The body keeps the last mood while it folds out.
	val chipsVisible = moodCatalogue.chipsVisible()
	val homeMoods = remember(moodCatalogue) { moodCatalogue.homeMoods() }
	val selectedMood = if (chipsVisible) homeMoods.firstOrNull { it.id == moodSelection.moodId } else null
	var shownMood by remember { mutableStateOf<MoodEntry?>(null) }
	if (selectedMood != null && selectedMood != shownMood) shownMood = selectedMood
	val shelves = selectedMood == null
	val lifecycle = LocalLifecycleOwner.current.lifecycle
	// System Back clears the chip before it leaves Home (P7 call 9).
	NavigationBackHandler(
		state = rememberNavigationEventState(NavigationEventInfo.None),
		isBackEnabled = selectedMood != null,
		onBackCompleted = { viewModel.clearMood() }
	)

	// The first shelf drawn staggers its first five tiles; the ones after it arrive together.
	val freshShelfIndex = if (showRecent) 1 else 0
	val laterShelfIndex = if (showRecent || showFresh) 1 else 0

	// One album shelf: the library's own tile and long-press sheet, as Recently added has (P6).
	val albumShelf: @Composable (Modifier, String, List<DomainAlbum>, String, Int, NavKey) -> Unit =
		{ modifier, title, albums, tab, shelfIndex, more ->
			HomeShelf(
				modifier = modifier,
				title = title,
				items = albums,
				key = { it.id },
				gate = gate,
				shelfIndex = shelfIndex,
				moreLabel = seeAll,
				more = more
			) { album, arrival ->
				AlbumListScreenGridItem(
					modifier = arrival,
					tab = tab,
					album = album,
					selected = album == selectedAlbum && selectedShelf == tab,
					starred = starred,
					rating = rating,
					onSelect = {
						selectedShelf = tab
						viewModel.selectAlbum(album)
					},
					onDeselect = { viewModel.clearSelection() },
					onSetStarred = { viewModel.starAlbum(it) },
					onSetShareId = { shareId = it },
					onPlayNext = { selectedAlbum?.let { player.playNext(it as DomainSongCollection) } },
					onAddToQueue = { selectedAlbum?.let { player.addToQueue(it as DomainSongCollection) } },
					onSetRating = { viewModel.setRating(it) },
					placeholderName = album.name ?: album.id,
					crossfadeMs = COVER_FADE_MS
				)
			}
		}

	Scaffold(
		topBar = {
			RootTopBar({ Text(stringResource(Res.string.title_home)) }, scrollBehavior)
		},
		bottomBar = {
			val scrollManager = LocalBottomBarScrollManager.current
			RootBottomBar(scrolled = scrollManager.isTriggered)
		}
	) { innerPadding ->
		LazyColumn(
			modifier = Modifier
				.padding(top = innerPadding.calculateTopPadding())
				.nestedScroll(scrollBehavior.nestedScrollConnection),
			state = listState,
			contentPadding = innerPadding.withoutTop()
		) {
			if (chipsVisible) {
				item(key = "moods") {
					MoodChipRow(
						modifier = Modifier.animateItem(),
						moods = homeMoods,
						selected = selectedMood?.id,
						onTap = { viewModel.tapMood(it) },
						onClear = { viewModel.clearMood() }
					)
				}
			}

			item(key = "mood-body") {
				Fold(visible = selectedMood != null, tall = true) {
					shownMood?.let { mood ->
						MoodBody(
							mood = mood,
							queues = remember(queues, mood) { queuesFromMood(queues, mood) },
							playingQueueId = playingQueueId,
							busy = moodBusy?.takeIf { it.startsWith("${mood.id}/") }?.substringAfter('/'),
							gate = gate,
							// dropUnlessResumed's rule, for a tap that names its tile: a second tap
							// during the push does not push twice.
							onOpen = { variant: MoodVariant ->
								if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
									backStack.add(Screen.MoodMix(mood.id, variant.id))
								}
							},
							onPlay = { variant -> viewModel.playMoodTile(mood, variant) },
							onResume = { savedQueueActions.resume(it) },
							onMenu = { menuQueue = it }
						)
					}
				}
			}

			if (HomeSection.ContinueListening in sections) {
				item(key = "continue") {
					Fold(visible = shelves) {
					ContinueListeningPill(
						modifier = Modifier.animateItem(),
						queues = continueQueues,
						totalCount = queues.size,
						playingId = playingQueueId,
						expanded = continueOpen == true,
						onToggle = { continueOpen = continueOpen != true },
						onResume = { savedQueueActions.resume(it) },
						onMenu = { menuQueue = it },
						onSeeAll = dropUnlessResumed { backStack.add(Screen.SavedQueues) }
					)
					}
				}
			}

			if (showRecent) {
				item(key = "recent") {
					Fold(visible = shelves, tall = true) {
					if (recentAlbums.isNotEmpty()) {
						albumShelf(
							Modifier.animateItem(), recentTitle, recentAlbums, RECENT_TAB, 0,
							Screen.AlbumList(nested = true, listType = DomainAlbumListType.Newest)
						)
					} else {
						HomeShelfPlaceholder(
							modifier = Modifier.animateItem(),
							title = recentTitle,
							tiles = RECENT_PLACEHOLDERS,
							seed = "recent"
						)
					}
					}
				}
			}

			if (showFresh) {
				item(key = "fresh") {
					Fold(visible = shelves, tall = true) {
					if (fresh.isNotEmpty()) {
						HomeShelf(
							modifier = Modifier.animateItem(),
							title = freshTitle,
							items = fresh,
							key = { it.key },
							gate = gate,
							shelfIndex = freshShelfIndex,
							moreLabel = newReleases,
							more = Screen.Fresh(nested = true)
						) { tile, arrival ->
							HomeFreshTile(
								tile = tile,
								modifier = arrival,
								onClick = dropUnlessResumed { freshTarget(tile)?.let { backStack.add(it) } }
							)
						}
					} else {
						HomeShelfPlaceholder(
							modifier = Modifier.animateItem(),
							title = freshTitle,
							tiles = FRESH_PLACEHOLDERS,
							seed = "fresh"
						)
					}
					}
				}
			}

			if (HomeSection.RecentlyPlayed in sections) {
				item(key = "played") {
					Fold(visible = shelves, tall = true) {
					albumShelf(
						Modifier.animateItem(), playedTitle, played, PLAYED_TAB, laterShelfIndex,
						Screen.AlbumList(nested = true, listType = DomainAlbumListType.Recent)
					)
					}
				}
			}

			if (HomeSection.FrequentlyPlayed in sections) {
				item(key = "frequent") {
					Fold(visible = shelves, tall = true) {
					albumShelf(
						Modifier.animateItem(), frequentTitle, frequent, FREQUENT_TAB, laterShelfIndex,
						Screen.AlbumList(nested = true, listType = DomainAlbumListType.Frequent)
					)
					}
				}
			}

			if (HomeSection.YourMixes in sections) {
				item(key = "mixes") {
					Fold(visible = shelves, tall = true) {
					YourMixesShelf(
						modifier = Modifier.animateItem(),
						title = yourMixesTitle,
						tiles = mixTiles,
						yourMixName = yourMixTitle,
						gate = gate,
						shelfIndex = laterShelfIndex,
						seeAll = seeAll,
						// "See all" is the Library on its Mixes chip: the Library tab, as a tab tap opens it.
						onSeeAll = dropUnlessResumed {
							shell.showLibraryChip(LibraryChip.Mixes.name)
							onShellTabTapped(ShellTab.Library, backStack, rootViewModel, shell)
						},
						// A tile opens the mix's page (P5 round 2); it never starts the mix.
						onOpen = { tile -> backStack.add(Screen.MixDetail(tile.key)) }
					)
					}
				}
			}

			if (showEmptyLine && shelves) {
				item(key = "empty") {
					Text(
						text = stringResource(
							if (recent is UiState.Error) Res.string.home_library_error else Res.string.home_library_empty
						),
						style = MaterialTheme.typography.bodyMedium,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
						modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 24.dp)
					)
				}
			}
		}
	}

	ShareDialog(
		id = shareId,
		onIdClear = { shareId = null },
		expiry = shareExpiry,
		onExpiryChange = { shareExpiry = it }
	)

	menuQueue?.let { target ->
		SavedQueueSheet(
			queue = target,
			subtitle = savedQueueSubtitle(target, target.id == playingQueueId),
			onDismissRequest = { menuQueue = null },
			onRestore = { savedQueueActions.restore(target) },
			onResume = { savedQueueActions.resume(target) },
			onPreview = { previewQueue = target },
			onDelete = { savedQueueActions.delete(target) }
		)
	}

	previewQueue?.let { target ->
		SavedQueuePreviewSheet(
			queue = target,
			onResume = {
				previewQueue = null
				savedQueueActions.resume(target)
			},
			onDismissRequest = { previewQueue = null }
		)
	}
}

/** The shared-element key's list part per shelf, which the album page rebuilds from it (unique per list). */
private const val RECENT_TAB = "homeRecent"
private const val PLAYED_TAB = "homePlayed"
private const val FREQUENT_TAB = "homeFrequent"

/**
 * A new release by an artist the library holds. The cover is Cover Art Archive's (a release nobody owns
 * has no Navidrome art) and fades in over a tint of the title; the badge says whether THIS release is on
 * disk (lb-bot's index), which is what the tap follows too.
 */
@Composable
private fun HomeFreshTile(tile: FreshTile, modifier: Modifier, onClick: () -> Unit) {
	val cover = tile.coverUrl ?: tile.rgid.takeIf { it.isNotBlank() }?.let { LbBotManager.caaCoverUrl(it) }
	ArtGridItem(
		modifier = modifier,
		onClick = onClick,
		coverArtId = null,
		title = tile.title,
		subtitle = tile.artist,
		id = tile.key,
		tab = "homeFresh",
		cover = { source ->
			RemoteCoverArt(
				url = cover,
				contentDescription = tile.title,
				modifier = Modifier.fillMaxWidth(),
				interactionSource = source,
				crossfadeMs = COVER_FADE_MS,
				placeholderName = tile.title
			)
		},
		coverOverlay = { OwnedBadge(tile.owned) }
	)
}

/** A check where the release is on disk, an arrow where it is not: the words are for a screen reader. */
@Composable
private fun BoxScope.OwnedBadge(owned: Boolean) {
	val words = stringResource(if (owned) Res.string.home_owned else Res.string.home_not_owned)
	Box(
		modifier = Modifier
			.align(Alignment.TopEnd)
			.padding(8.dp)
			.size(26.dp)
			.clip(CircleShape)
			.background(
				if (owned) MaterialTheme.colorScheme.primary
				else MaterialTheme.colorScheme.scrim.copy(alpha = 0.6f)
			)
			.semantics { contentDescription = words },
		contentAlignment = Alignment.Center
	) {
		Icon(
			imageVector = if (owned) Icons.Outlined.Check else Icons.Outlined.Download,
			contentDescription = null,
			tint = if (owned) MaterialTheme.colorScheme.onPrimary else androidx.compose.ui.graphics.Color.White,
			modifier = Modifier.size(16.dp)
		)
	}
}

/**
 * Your mixes (P6): Your mix first while the engine is there, then every mix the hub holds, as a shelf
 * of the mixes' own artwork (`MixArtwork`: a custom radio's first artist, a kind glyph over the tint
 * for a mix with no cover, a person for Your mix), the name and the kind line. A tile opens the mix's
 * page; "See all" opens the Library on its Mixes chip.
 */
@Composable
private fun YourMixesShelf(
	title: String,
	tiles: List<MixTile>,
	yourMixName: String,
	gate: ArrivalGate,
	shelfIndex: Int,
	seeAll: String,
	onSeeAll: () -> Unit,
	onOpen: (MixTile) -> Unit,
	modifier: Modifier = Modifier
) {
	val yourMixLine = stringResource(Res.string.home_your_mix_line)
	HomeShelf(
		modifier = modifier,
		title = title,
		items = tiles,
		key = { it.key },
		gate = gate,
		shelfIndex = shelfIndex,
		moreLabel = seeAll,
		onMore = onSeeAll
	) { tile, arrival ->
		val mix = tile.mix ?: Mix(id = YOUR_MIX_ID, name = yourMixName, kind = MixKind.STATION)
		ArtGridItem(
			modifier = arrival,
			onClick = dropUnlessResumed { onOpen(tile) },
			coverArtId = null,
			title = if (tile.yourMix) yourMixName else mix.name,
			subtitle = if (tile.yourMix) yourMixLine else mixKindLabel(mix),
			id = tile.key,
			tab = "homeMixes",
			cover = { source ->
				MixArtwork(mix = mix, modifier = Modifier.fillMaxWidth(), interactionSource = source)
			}
		)
	}
}
