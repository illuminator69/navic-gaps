package paige.navic.ui.screens.sourcehistory

import paige.navic.ui.util.placeholderBrush
import paige.navic.domain.manager.historyRereadWanted
import paige.navic.domain.manager.historyRead
import paige.navic.domain.manager.historyEmptyState
import paige.navic.domain.manager.formatListenedAt
import paige.navic.domain.manager.HistoryEmpty
import navic.composeapp.generated.resources.attention_notice_unreachable
import kotlinx.datetime.TimeZone
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.background
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.plus
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import navic.composeapp.generated.resources.Res
import navic.composeapp.generated.resources.source_history_earlier
import navic.composeapp.generated.resources.source_history_empty
import navic.composeapp.generated.resources.source_history_notice_older
import navic.composeapp.generated.resources.source_history_notice_unreachable
import navic.composeapp.generated.resources.source_history_requests
import navic.composeapp.generated.resources.source_history_running
import navic.composeapp.generated.resources.source_history_transfer_line
import navic.composeapp.generated.resources.source_history_transfers
import navic.composeapp.generated.resources.title_source_history
import navic.composeapp.generated.resources.title_wishlist
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import paige.navic.di.LocalBottomBarScrollManager
import paige.navic.di.LocalNavStack
import paige.navic.di.LocalPlatformContext
import paige.navic.di.isLandscape
import paige.navic.domain.manager.ActivityTransfer
import paige.navic.domain.manager.HistoryRow
import paige.navic.domain.manager.LbFillEntry
import paige.navic.domain.manager.PreferenceManager
import paige.navic.domain.manager.SourceHistorySection
import paige.navic.domain.manager.asLedgerRow
import paige.navic.domain.manager.transfersShown
import paige.navic.domain.models.settings.BottomBarVisibilityMode
import paige.navic.icons.Icons
import paige.navic.icons.outlined.Download
import paige.navic.ui.components.common.ContentUnavailable
import paige.navic.ui.components.common.FillButton
import paige.navic.ui.components.common.FillRow
import paige.navic.ui.components.common.GroupHeader
import paige.navic.ui.components.common.PageChips
import paige.navic.ui.components.common.SegmentedListItem
import paige.navic.ui.components.common.SegmentedListItemDefaults
import paige.navic.ui.components.common.SegmentedListItemDefaults.segmentedShapes
import paige.navic.ui.components.common.SongRowDefaults
import paige.navic.ui.components.common.fillRowColors
import paige.navic.ui.components.common.rememberFillClock
import paige.navic.ui.components.layouts.NestedTopBar
import paige.navic.ui.components.layouts.PullToRefreshBox
import paige.navic.ui.components.layouts.RootBottomBar
import paige.navic.ui.navigation.Screen
import paige.navic.ui.screens.downloads.viewmodels.DownloadCenterViewModel
import paige.navic.ui.screens.downloads.viewmodels.WishlistViewModel
import paige.navic.ui.screens.downloads.wishlistSection
import paige.navic.ui.util.withoutTop
import navic.composeapp.generated.resources.attention_failed
import navic.composeapp.generated.resources.attention_needs_pick
import navic.composeapp.generated.resources.source_history_gave_up
import navic.composeapp.generated.resources.source_history_cancelled
import navic.composeapp.generated.resources.source_history_verified
import navic.composeapp.generated.resources.source_history_placed
import paige.navic.domain.manager.LbBotManager
import paige.navic.ui.components.common.FillText
import paige.navic.ui.components.common.PillTone
import paige.navic.ui.components.common.Pill

/**
 * Source history (P6; it was the Downloads page's Fills and Wishlist chips, "Activity" in the avatar
 * menu): *Requests* (Running over Earlier, lb-bot's ledger joined with this device's), *Transfers*
 * (the file-level rows, drawn only when lb-bot lists any) and *Wishlist* (`WishlistSection`, moved
 * whole). A row this device holds has its buttons; a row only lb-bot knows has Open album alone.
 */
@Composable
fun SourceHistoryScreen(initial: SourceHistorySection = SourceHistorySection.Requests) {
	val viewModel = koinViewModel<SourceHistoryViewModel>()
	val actions = koinViewModel<DownloadCenterViewModel>()
	val wishlistViewModel = koinViewModel<WishlistViewModel>()
	val groups by viewModel.groups.collectAsStateWithLifecycle()
	val server by viewModel.server.collectAsStateWithLifecycle()
	val transfers by viewModel.transfers.collectAsStateWithLifecycle()
	val wishlist by wishlistViewModel.state.collectAsStateWithLifecycle()
	val wishlisted by actions.wishlisted.collectAsStateWithLifecycle()
	val backStack = LocalNavStack.current
	val preferenceManager = koinInject<PreferenceManager>()
	val platformContext = LocalPlatformContext.current
	val loading by viewModel.loading.collectAsStateWithLifecycle()
	val zone = remember { TimeZone.currentSystemDefault() }
	val lifecycleOwner = LocalLifecycleOwner.current
	val scope = rememberCoroutineScope()
	val now = rememberFillClock()

	var section by rememberSaveable { mutableStateOf(initial) }
	var refreshing by remember { mutableStateOf(false) }
	val snackbar = remember { SnackbarHostState() }
	LaunchedEffect(Unit) { actions.actionMessages.collect { snackbar.showSnackbar(it) } }

	// lb-bot's half: on opening, then again every few seconds only while one of ITS rows is running
	// and the page is on screen. The device's rows move with the ledger and need no read.
	val serverRunning = historyRereadWanted(groups)
	LaunchedEffect(lifecycleOwner, serverRunning) {
		lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
			viewModel.load().join()
			while (serverRunning) {
				delay(SourceHistoryViewModel.RUNNING_REREAD_MS)
				viewModel.load().join()
			}
		}
	}

	val sections = buildList {
		add(SourceHistorySection.Requests)
		if (transfersShown(transfers)) add(SourceHistorySection.Transfers)
		add(SourceHistorySection.Wishlist)
	}
	val shown = section.takeIf { it in sections } ?: SourceHistorySection.Requests

	val openAlbum = { row: HistoryRow ->
		if (row.rgid.isNotBlank()) {
			backStack.add(Screen.ExternalAlbum(rgid = row.rgid, artistName = row.artist, title = row.album))
		}
	}

	Scaffold(
		topBar = {
			Column {
				NestedTopBar(title = { Text(stringResource(Res.string.title_source_history)) })
				PageChips(
					options = sections,
					selected = shown,
					label = { chip ->
						val n = when (chip) {
							SourceHistorySection.Requests -> groups.running.size + groups.earlier.size
							SourceHistorySection.Transfers -> transfers.size
							SourceHistorySection.Wishlist -> wishlist.items.size
						}
						if (n > 0) "${stringResource(chip.label)} ($n)" else stringResource(chip.label)
					},
					onSelect = { section = it }
				)
			}
		},
		bottomBar = {
			val scrollManager = LocalBottomBarScrollManager.current
			if (!platformContext.isLandscape() &&
				preferenceManager.bottomBarVisibilityMode == BottomBarVisibilityMode.AllScreens
			) {
				RootBottomBar(scrolled = scrollManager.isTriggered)
			}
		},
		snackbarHost = { SnackbarHost(snackbar) }
	) { innerPadding ->
		PullToRefreshBox(
			isRefreshing = refreshing,
			onRefresh = {
				scope.launch {
					refreshing = true
					viewModel.load().join()
					wishlistViewModel.load().join()
					refreshing = false
				}
			},
			modifier = Modifier.padding(top = innerPadding.calculateTopPadding())
		) {
			AnimatedContent(shown, modifier = Modifier.fillMaxSize()) { current ->
				LazyColumn(
					modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
					contentPadding = innerPadding.withoutTop() + PaddingValues(top = 8.dp, bottom = 24.dp),
					verticalArrangement = Arrangement.spacedBy(SegmentedListItemDefaults.SegmentedGap)
				) {
					when (current) {
						SourceHistorySection.Requests -> {
							// B4 (L4b-08): "No requests yet" only once lb-bot has answered with none.
							val empty = historyEmptyState(
								historyRead(server.status, loading),
								rows = groups.running.size + groups.earlier.size,
								asOf = server.lastAt.takeIf { server.last != null }
							)
							if (empty == HistoryEmpty.Older || empty is HistoryEmpty.Unreachable) {
								item(key = "notice") {
									val asOf = (empty as? HistoryEmpty.Unreachable)?.asOf
									val notice = when {
										empty == HistoryEmpty.Older -> stringResource(Res.string.source_history_notice_older)
										asOf != null -> stringResource(
											Res.string.attention_notice_unreachable, formatListenedAt(asOf / 1000.0, zone)
										)
										else -> stringResource(Res.string.source_history_notice_unreachable)
									}
									Text(
										notice,
										style = MaterialTheme.typography.bodyMedium,
										color = MaterialTheme.colorScheme.onSurfaceVariant,
										modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp).animateItem()
									)
								}
							}
							if (empty == HistoryEmpty.NoRequests) {
								item(key = "empty") {
									ContentUnavailable(
										modifier = Modifier.fillMaxWidth().padding(vertical = 64.dp),
										icon = Icons.Outlined.Download,
										label = stringResource(Res.string.source_history_empty)
									)
								}
							}
							if (empty == HistoryEmpty.Skeleton) {
								items(SKELETON_ROWS, key = { "skeleton-$it" }) { index ->
									Box(
										Modifier
											.fillMaxWidth()
											.height(72.dp)
											.clip(MaterialTheme.shapes.medium)
											.background(placeholderBrush("history-row-$index"))
									)
								}
							}
							historyGroup("running", Res.string.source_history_running, groups.running, now,
								actions, wishlisted, openAlbum)
							historyGroup("earlier", Res.string.source_history_earlier, groups.earlier, now,
								actions, wishlisted, openAlbum)
						}
						SourceHistorySection.Transfers -> transferRows(transfers)
						SourceHistorySection.Wishlist -> wishlistSection(
							state = wishlist,
							onOpen = { item ->
								backStack.add(Screen.ExternalAlbum(rgid = item.rgid, artistName = item.artist, title = item.title))
							},
							onRemove = { wishlistViewModel.remove(it.rgid) }
						)
					}
				}
			}
		}
	}
}

/** Placeholder rows while the first read of lb-bot is out. */
private const val SKELETON_ROWS = 3

private val SourceHistorySection.label
	get() = when (this) {
		SourceHistorySection.Requests -> Res.string.source_history_requests
		SourceHistorySection.Transfers -> Res.string.source_history_transfers
		SourceHistorySection.Wishlist -> Res.string.title_wishlist
	}

/**
 * Running or Earlier: a header and its rows, keyed by the request, so a fill that finishes moves from
 * one group to the other as the same row.
 */
private fun LazyListScope.historyGroup(
	key: String,
	title: org.jetbrains.compose.resources.StringResource,
	rows: List<HistoryRow>,
	now: State<Long>,
	actions: DownloadCenterViewModel,
	wishlisted: Set<String>,
	onOpenAlbum: (HistoryRow) -> Unit
) {
	if (rows.isEmpty()) return
	item(key = "header-$key") {
		GroupHeader(stringResource(title), count = rows.size, modifier = Modifier.animateItem())
	}
	itemsIndexed(rows, key = { _, row -> "request:${row.key}" }) { index, row ->
		val entry: LbFillEntry = row.entry ?: row.server!!.asLedgerRow()
		val mine = row.entry != null
		FillRow(
			fill = entry,
			now = now,
			shapes = segmentedShapes(index = index, count = rows.size),
			wishlistAvailable = mine && actions.wishlistSupported && entry.key !in wishlisted,
			// A row only lb-bot knows is opened, never retried, cancelled or dismissed from here:
			// it expires in lb-bot by itself, 6 to 24 hours on.
			lbBotOnly = !mine,
			pill = historyPill(entry),
			onAction = { button ->
				when (button) {
					FillButton.RETRY -> actions.retryFill(entry.key)
					FillButton.TRY_ANOTHER -> actions.retryAnotherSource(entry.key)
					FillButton.CANCEL -> actions.cancelFill(entry.key)
					FillButton.ALLOW_MP3 -> actions.allowMp3AndRetry(entry)
					FillButton.WISHLIST -> actions.addToWishlist(entry)
					FillButton.DISMISS -> actions.dismissFill(entry.key)
					FillButton.OPEN_ALBUM -> onOpenAlbum(row)
				}
			},
			modifier = Modifier.animateItem()
		)
	}
}

/** The Activity board's pill for a request that has ended; a running one has its bar instead. */
private fun historyPill(entry: LbFillEntry): Pill? {
	if (!entry.settled) return null
	val (words, tone) = when {
		entry.outcome == LbBotManager.OUTCOME_DONE && entry.verifyGaveUp -> Res.string.source_history_placed to PillTone.OK
		entry.outcome == LbBotManager.OUTCOME_DONE -> Res.string.source_history_verified to PillTone.GOOD
		entry.outcome == LbBotManager.OUTCOME_CANCELLED -> Res.string.source_history_cancelled to PillTone.MUTE
		entry.outcome == LbBotManager.OUTCOME_GAVE_UP -> Res.string.source_history_gave_up to PillTone.MUTE
		entry.outcome == LbBotManager.OUTCOME_NEEDS_PICK -> Res.string.attention_needs_pick to PillTone.WARN
		else -> Res.string.attention_failed to PillTone.BAD
	}
	return Pill(FillText.Res(words), tone)
}

/** The file-level transfers lb-bot lists: what, how far, from whom. Never a filename. */
private fun LazyListScope.transferRows(transfers: List<ActivityTransfer>) {
	itemsIndexed(transfers, key = { _, t -> "transfer:${t.id}" }) { index, t ->
		SegmentedListItem(
			onClick = {},
			modifier = Modifier.animateItem(),
			shapes = segmentedShapes(index = index, count = transfers.size),
			colors = fillRowColors(),
			contentPadding = SongRowDefaults.ContentPadding,
			verticalAlignment = Alignment.CenterVertically,
			leadingContent = {
				Box(Modifier.size(50.dp), contentAlignment = Alignment.Center) {
					Icon(Icons.Outlined.Download, contentDescription = null)
				}
			},
			supportingContent = {
				Column {
					Text(
						stringResource(Res.string.source_history_transfer_line, "${t.pct}%", t.peer),
						maxLines = 1,
						overflow = TextOverflow.Ellipsis
					)
					if (t.state == "active") {
						LinearProgressIndicator(
							progress = { t.pct / 100f },
							modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
						)
					}
				}
			}
		) {
			Text(
				listOf(t.artist, t.album).filter { it.isNotBlank() }.joinToString(" — ").ifBlank { t.id },
				maxLines = 1,
				overflow = TextOverflow.Ellipsis
			)
		}
	}
}
