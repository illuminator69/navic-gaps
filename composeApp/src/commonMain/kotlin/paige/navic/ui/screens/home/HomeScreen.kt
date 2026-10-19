package paige.navic.ui.screens.home

import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.dropUnlessResumed
import kotlinx.coroutines.launch
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
import navic.composeapp.generated.resources.title_home
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import paige.navic.data.database.entities.SavedQueueEntity
import paige.navic.di.LocalBottomBarScrollManager
import paige.navic.di.LocalNavStack
import paige.navic.domain.manager.LbBotManager
import paige.navic.domain.manager.RadioManager
import paige.navic.domain.models.DomainAlbumListType
import paige.navic.domain.models.DomainSongCollection
import paige.navic.icons.Icons
import paige.navic.icons.outlined.Check
import paige.navic.icons.outlined.Download
import paige.navic.icons.outlined.Person
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
import paige.navic.ui.theme.defaultFont
import paige.navic.ui.util.COVER_FADE_MS
import paige.navic.ui.util.placeholderBrush
import paige.navic.ui.util.rememberArrivalGate
import paige.navic.ui.util.rememberPlayerRestored
import paige.navic.ui.util.withoutTop
import paige.navic.ui.viewmodel.RootViewModel

/** How many tinted boxes a shelf shows while its first read is out. */
private const val RECENT_PLACEHOLDERS = 5
private const val FRESH_PLACEHOLDERS = 4

/**
 * Home (P1): where the app opens. Under the root bars, in this order: Continue listening (a capsule
 * that rolls out into the saved queues), Recently added, Fresh (where lb-bot is on), Your mix (where
 * the station engine is). A root page shaped like `AlbumListScreen`: `Washed` by App.kt and named in
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
	val radioManager = koinInject<RadioManager>()
	val scope = rememberCoroutineScope()
	val gate = rememberArrivalGate()

	val recent by viewModel.recent.collectAsStateWithLifecycle()
	val fresh by viewModel.fresh.collectAsStateWithLifecycle()
	val freshAvailable by viewModel.freshAvailable.collectAsStateWithLifecycle()
	val freshLoading by viewModel.freshLoading.collectAsStateWithLifecycle()
	val queues by viewModel.queues.collectAsStateWithLifecycle()
	val playingQueueId by viewModel.playingQueueId.collectAsStateWithLifecycle()
	val nothingPlaying by viewModel.nothingPlaying.collectAsStateWithLifecycle()
	val yourMixAvailable by viewModel.yourMixAvailable.collectAsStateWithLifecycle()
	val syncing by viewModel.syncing.collectAsStateWithLifecycle()
	val selectedAlbum by viewModel.selectedAlbum.collectAsStateWithLifecycle()
	val starred by viewModel.starred.collectAsStateWithLifecycle()
	val rating by viewModel.rating.collectAsStateWithLifecycle()
	var shareId by remember { mutableStateOf<String?>(null) }
	var shareExpiry by remember { mutableStateOf<Duration?>(null) }

	val continueQueues = remember(queues, playingQueueId) { continueListening(queues, playingQueueId) }
	val recentAlbums = recent.data.orEmpty()
	val sections = homeSections(
		queueCount = queues.size,
		recentCount = recentAlbums.size,
		freshAvailable = freshAvailable,
		freshCount = fresh.size,
		yourMixAvailable = yourMixAvailable
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
	// The first shelf drawn staggers its first five tiles; the next arrives together.
	val freshShelfIndex = if (showRecent) 1 else 0

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
			if (HomeSection.ContinueListening in sections) {
				item(key = "continue") {
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

			if (showRecent) {
				item(key = "recent") {
					if (recentAlbums.isNotEmpty()) {
						HomeShelf(
							modifier = Modifier.animateItem(),
							title = recentTitle,
							items = recentAlbums,
							key = { it.id },
							gate = gate,
							shelfIndex = 0,
							moreLabel = seeAll,
							more = Screen.AlbumList(nested = true, listType = DomainAlbumListType.Newest)
						) { album, arrival ->
							// The library lists' own tile, so a long press opens the album's sheet as it does there.
							AlbumListScreenGridItem(
								modifier = arrival,
								tab = RECENT_TAB,
								album = album,
								selected = album == selectedAlbum,
								starred = starred,
								rating = rating,
								onSelect = { viewModel.selectAlbum(album) },
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

			if (showFresh) {
				item(key = "fresh") {
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

			if (HomeSection.YourMix in sections) {
				item(key = "mix") {
					YourMixSection(
						modifier = Modifier.animateItem(),
						title = yourMixTitle,
						onClick = { scope.launch { radioManager.playYourMix(yourMixTitle) } }
					)
				}
			}

			if (showEmptyLine) {
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

/** The shared-element key's list part for Recently added, which the album page rebuilds from it. */
private const val RECENT_TAB = "homeRecent"

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

/** "Your mix": a wide card, a person on a tint (Your mix wears a person, not the clock), and one line. */
@Composable
private fun YourMixSection(title: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
	val line = stringResource(Res.string.home_your_mix_line)
	Column(modifier) {
		HomeShelfHeader(title, null, null)
		Column(
			modifier = Modifier
				.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp)
				.width(YOUR_MIX_WIDTH)
				.clickable(role = Role.Button, onClick = onClick)
				.semantics { contentDescription = "$title. $line" }
		) {
			Box(
				modifier = Modifier
					.fillMaxWidth()
					.aspectRatio(1f)
					.clip(MaterialTheme.shapes.large)
					.background(placeholderBrush(title)),
				contentAlignment = Alignment.Center
			) {
				Icon(
					Icons.Outlined.Person,
					contentDescription = null,
					tint = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.85f),
					modifier = Modifier.size(56.dp)
				)
			}
			Text(
				text = title,
				style = MaterialTheme.typography.titleSmallEmphasized,
				modifier = Modifier.padding(top = 6.dp),
				maxLines = 1,
				overflow = TextOverflow.Ellipsis
			)
			Text(
				text = line,
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
				fontFamily = defaultFont(),
				maxLines = 2,
				overflow = TextOverflow.Ellipsis
			)
		}
	}
}

/** The board's wide tile (164 dp). */
private val YOUR_MIX_WIDTH = 164.dp
