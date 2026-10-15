package paige.navic.ui.screens.downloads

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.plus
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import navic.composeapp.generated.resources.Res
import navic.composeapp.generated.resources.action_add_to_wishlist
import navic.composeapp.generated.resources.action_allow_mp3_retry
import navic.composeapp.generated.resources.action_cancel
import navic.composeapp.generated.resources.action_cancel_all
import navic.composeapp.generated.resources.action_cancel_download
import navic.composeapp.generated.resources.action_clear_failed
import navic.composeapp.generated.resources.action_delete_download
import navic.composeapp.generated.resources.action_dismiss
import navic.composeapp.generated.resources.action_open_album
import navic.composeapp.generated.resources.action_repair_downloads
import navic.composeapp.generated.resources.action_retry
import navic.composeapp.generated.resources.action_retry_all
import navic.composeapp.generated.resources.action_try_another_source
import navic.composeapp.generated.resources.banner_downloads_waiting
import navic.composeapp.generated.resources.download_source_album
import navic.composeapp.generated.resources.download_source_library
import navic.composeapp.generated.resources.download_source_manual
import navic.composeapp.generated.resources.download_source_playlist
import navic.composeapp.generated.resources.download_source_queue
import navic.composeapp.generated.resources.info_download_attempts
import navic.composeapp.generated.resources.info_no_downloads
import navic.composeapp.generated.resources.info_no_fills
import navic.composeapp.generated.resources.lbbot_fills_none_match
import navic.composeapp.generated.resources.lbbot_filter_all
import navic.composeapp.generated.resources.lbbot_filter_done
import navic.composeapp.generated.resources.lbbot_filter_failed
import navic.composeapp.generated.resources.lbbot_filter_running
import navic.composeapp.generated.resources.option_downloads_device
import navic.composeapp.generated.resources.option_downloads_fills
import navic.composeapp.generated.resources.option_paste_link
import navic.composeapp.generated.resources.section_downloads_active
import navic.composeapp.generated.resources.section_downloads_completed
import navic.composeapp.generated.resources.section_downloads_failed
import navic.composeapp.generated.resources.section_downloads_queued
import navic.composeapp.generated.resources.title_downloads
import navic.composeapp.generated.resources.title_settings
import navic.composeapp.generated.resources.title_wishlist
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import paige.navic.data.database.entities.DownloadSource
import paige.navic.di.LocalBottomBarScrollManager
import paige.navic.di.LocalNavStack
import paige.navic.di.LocalPlatformContext
import paige.navic.di.isLandscape
import paige.navic.domain.manager.LbBotManager
import paige.navic.domain.manager.LbFillEntry
import paige.navic.domain.manager.PreferenceManager
import paige.navic.domain.models.settings.BottomBarVisibilityMode
import paige.navic.icons.Icons
import paige.navic.icons.outlined.Close
import paige.navic.icons.outlined.Delete
import paige.navic.icons.outlined.Download
import paige.navic.icons.outlined.Link
import paige.navic.icons.outlined.Queue
import paige.navic.ui.components.common.ContentUnavailable
import paige.navic.ui.components.common.CoverArt
import paige.navic.ui.components.common.FillButton
import paige.navic.ui.components.common.FillProgressKind
import paige.navic.ui.components.common.GroupHeader
import paige.navic.ui.components.common.PageChips
import paige.navic.ui.components.common.RemoteCoverArt
import paige.navic.ui.components.common.SegmentedListItem
import paige.navic.ui.components.common.SegmentedListItemDefaults
import paige.navic.ui.components.common.SegmentedListItemDefaults.segmentedShapes
import paige.navic.ui.components.common.SongRowDefaults
import paige.navic.ui.components.common.describeFill
import paige.navic.ui.components.common.resolve
import paige.navic.ui.components.layouts.NestedTopBar
import paige.navic.ui.components.layouts.PullToRefreshBox
import paige.navic.ui.components.layouts.RootBottomBar
import paige.navic.ui.navigation.Screen
import paige.navic.ui.screens.downloads.viewmodels.DownloadCenterItem
import paige.navic.ui.screens.downloads.viewmodels.DownloadCenterState
import paige.navic.ui.screens.downloads.viewmodels.DownloadCenterViewModel
import paige.navic.ui.screens.downloads.viewmodels.WishlistViewModel
import paige.navic.ui.util.withoutTop
import kotlin.time.Clock

/** The Downloads page's chips, in render order. Part of [Screen.Downloads], so it opens on one. */
@Serializable
enum class DownloadsSection { Device, Fills, Wishlist }

/**
 * Downloads (I-039): everything the app holds offline, what lb-bot is getting from Soulseek, and
 * the wishlist, one chip each on a library page.
 *
 * It was the Download Center in Settings, with the wishlist a page of its own. Settings keeps what
 * is a setting (Wi-Fi only, charging only, how many at once: Data & storage); this page is the
 * data. The rows are upstream's: `SegmentedListItem`s in groups, 50dp art in the user's cover
 * shape, washed like the library's other pages.
 */
@Composable
fun DownloadsScreen(initial: DownloadsSection = DownloadsSection.Device) {
	val viewModel = koinViewModel<DownloadCenterViewModel>()
	val wishlistViewModel = koinViewModel<WishlistViewModel>()
	val state by viewModel.state.collectAsStateWithLifecycle()
	val constrained by viewModel.constrained.collectAsStateWithLifecycle()
	val fills by viewModel.fills.collectAsStateWithLifecycle()
	val wishlist by wishlistViewModel.state.collectAsStateWithLifecycle()
	val backStack = LocalNavStack.current
	val preferenceManager = koinInject<PreferenceManager>()
	val platformContext = LocalPlatformContext.current
	val scope = rememberCoroutineScope()

	var section by rememberSaveable { mutableStateOf(initial) }
	var pasting by rememberSaveable { mutableStateOf(false) }
	var refreshing by remember { mutableStateOf(false) }

	// What an action on a fill row answered. Dropped results were how a refused
	// Retry looked like a tap that did nothing.
	val snackbar = remember { SnackbarHostState() }
	LaunchedEffect(Unit) {
		viewModel.actionMessages.collect { snackbar.showSnackbar(it) }
	}

	// lb-bot's two chips exist wherever the lb-bot layer is on. Without it the page is this
	// device's downloads alone, and one chip is no choice at all.
	val sections = if (viewModel.lbConfigured) DownloadsSection.entries
	else listOf(DownloadsSection.Device)
	val shown = section.takeIf { it in sections } ?: DownloadsSection.Device

	Scaffold(
		topBar = {
			Column {
				NestedTopBar(
					title = { Text(stringResource(Res.string.title_downloads)) },
					actions = {
						if (viewModel.resolveLinkSupported) {
							IconButton(onClick = { pasting = true }) {
								Icon(
									Icons.Outlined.Link,
									contentDescription = stringResource(Res.string.option_paste_link)
								)
							}
						}
					}
				)
				if (sections.size > 1) {
					PageChips(
						options = sections,
						selected = shown,
						// A count only where there is something: three "(0)"s pushed the last
						// chip off a phone's width, and said nothing the empty list does not.
						label = { chip ->
							val n = when (chip) {
								DownloadsSection.Device -> state.active.size + state.queued.size +
									state.failed.size + state.completed.size
								DownloadsSection.Fills -> fills.size
								DownloadsSection.Wishlist -> wishlist.items.size
							}
							if (n > 0) "${stringResource(chip.label)} ($n)" else stringResource(chip.label)
						},
						onSelect = { section = it }
					)
				}
			}
		},
		bottomBar = {
			val scrollManager = LocalBottomBarScrollManager.current
			val preferVisible =
				preferenceManager.bottomBarVisibilityMode == BottomBarVisibilityMode.AllScreens
			if (!platformContext.isLandscape() && preferVisible) {
				RootBottomBar(scrolled = scrollManager.isTriggered)
			}
		},
		snackbarHost = { SnackbarHost(snackbar) }
	) { innerPadding ->
		// The wishlist is the one list here that is read on request: downloads are Room's and
		// the fills are lb-bot's poll. The pull ends when that read does, not on a change of
		// the list's size, which is how the old page's spinner stuck after a refresh that
		// changed nothing.
		PullToRefreshBox(
			isRefreshing = refreshing,
			onRefresh = {
				scope.launch {
					refreshing = true
					wishlistViewModel.load().join()
					refreshing = false
				}
			},
			modifier = Modifier.padding(top = innerPadding.calculateTopPadding())
		) {
			AnimatedContent(shown, modifier = Modifier.fillMaxSize()) { current ->
				LazyColumn(
					modifier = Modifier
						.fillMaxSize()
						.padding(horizontal = 16.dp),
					contentPadding = innerPadding.withoutTop() + PaddingValues(top = 8.dp, bottom = 24.dp),
					verticalArrangement = Arrangement.spacedBy(SegmentedListItemDefaults.SegmentedGap)
				) {
					when (current) {
						DownloadsSection.Device -> deviceSection(
							state = state,
							constrained = constrained,
							viewModel = viewModel,
							onOpenSettings = { backStack.add(Screen.Settings.DataStorage) }
						)
						DownloadsSection.Fills -> fillsSection(
							fills = fills,
							viewModel = viewModel,
							onOpenAlbum = { fill ->
								backStack.add(
									Screen.ExternalAlbum(
										rgid = fill.rgid,
										artistName = fill.artist,
										title = fill.album
									)
								)
							}
						)
						DownloadsSection.Wishlist -> wishlistSection(
							state = wishlist,
							onOpen = { item ->
								backStack.add(
									Screen.ExternalAlbum(
										rgid = item.rgid,
										artistName = item.artist,
										title = item.title
									)
								)
							},
							onRemove = { wishlistViewModel.remove(it.rgid) }
						)
					}
				}
			}
		}
	}

	if (pasting) {
		PasteLinkSheet(
			resolve = viewModel::resolveLink,
			onDismissRequest = { pasting = false },
			onResolved = { key ->
				pasting = false
				backStack.add(key)
			}
		)
	}
}

private val DownloadsSection.label: StringResource
	get() = when (this) {
		DownloadsSection.Device -> Res.string.option_downloads_device
		DownloadsSection.Fills -> Res.string.option_downloads_fills
		DownloadsSection.Wishlist -> Res.string.title_wishlist
	}

/** The washed list colours the queue's rows use, so a row reads as a card over the cover. */
@Composable
internal fun downloadsRowColors() = ListItemDefaults.colors(
	containerColor = SongRowDefaults.containerColor(false),
	supportingContentColor = SongRowDefaults.supportingContentColor(false)
)

// --- This device ---------------------------------------------------------------------------------

/**
 * Everything the app is holding offline, split by what it's actually DOING: transferring now,
 * accepted but waiting on a permit, failed (with the reason, and a retry that reuses the original
 * quality/format), and completed.
 */
private fun LazyListScope.deviceSection(
	state: DownloadCenterState,
	constrained: Boolean,
	viewModel: DownloadCenterViewModel,
	onOpenSettings: () -> Unit
) {
	// Kept at the top so the "why is my queue stuck?" answer is right above the (possibly
	// stalled) groups below, with the way to the settings that hold them.
	if (constrained) {
		item(key = "constrained") {
			ConstrainedBanner(onOpenSettings, Modifier.animateItem())
		}
	}

	val isEmpty = state.active.isEmpty() && state.queued.isEmpty() &&
		state.failed.isEmpty() && state.completed.isEmpty()
	if (isEmpty) {
		item(key = "empty") {
			ContentUnavailable(
				modifier = Modifier
					.fillMaxWidth()
					.padding(vertical = 64.dp),
				icon = Icons.Outlined.Queue,
				label = stringResource(Res.string.info_no_downloads)
			)
		}
		return
	}

	downloadGroup(
		key = "active",
		title = Res.string.section_downloads_active,
		items = state.active,
		actions = {
			TextButton(onClick = viewModel::cancelAll) {
				Text(stringResource(Res.string.action_cancel_all))
			}
		}
	) { item, shapes ->
		DownloadRow(
			item = item,
			shapes = shapes,
			// Progress is only meaningful for something actually transferring.
			progress = item.download.progress,
			trailing = {
				IconButton(onClick = { viewModel.cancel(item) }) {
					Icon(
						Icons.Outlined.Close,
						contentDescription = stringResource(Res.string.action_cancel_download)
					)
				}
			}
		)
	}

	downloadGroup(
		key = "queued",
		title = Res.string.section_downloads_queued,
		items = state.queued
	) { item, shapes ->
		DownloadRow(
			item = item,
			shapes = shapes,
			trailing = {
				IconButton(onClick = { viewModel.cancel(item) }) {
					Icon(
						Icons.Outlined.Close,
						contentDescription = stringResource(Res.string.action_cancel_download)
					)
				}
			}
		)
	}

	downloadGroup(
		key = "failed",
		title = Res.string.section_downloads_failed,
		items = state.failed,
		actions = {
			TextButton(onClick = viewModel::clearFailed) {
				Text(stringResource(Res.string.action_clear_failed))
			}
			TextButton(onClick = viewModel::retryAllFailed) {
				Text(stringResource(Res.string.action_retry_all))
			}
		}
	) { item, shapes ->
		DownloadRow(
			item = item,
			shapes = shapes,
			// The failure reason, not just a red icon: "Retry" is a coin flip otherwise.
			supporting = item.download.error,
			supportingIsError = true,
			trailing = {
				TextButton(onClick = { viewModel.retry(item) }) {
					Text(stringResource(Res.string.action_retry))
				}
			}
		)
	}

	downloadGroup(
		key = "completed",
		title = Res.string.section_downloads_completed,
		items = state.completed,
		subtitle = formatSize(state.totalSize),
		actions = {
			// Re-verifies files still exist and re-fetches any that vanished.
			TextButton(onClick = viewModel::repairMissing) {
				Text(stringResource(Res.string.action_repair_downloads))
			}
		}
	) { item, shapes ->
		DownloadRow(
			item = item,
			shapes = shapes,
			// Size plus WHO asked for it, so a playlist-managed or whole-library file is
			// distinguishable from one the user downloaded by hand.
			supporting = "${formatSize(item.download.fileSize)} · " +
				sourceLabel(item.download.sourcePolicy),
			trailing = {
				IconButton(onClick = { viewModel.delete(item) }) {
					Icon(
						Icons.Outlined.Delete,
						contentDescription = stringResource(Res.string.action_delete_download)
					)
				}
			}
		)
	}
}

/** One titled group of download rows, segmented like a settings group; nothing when empty. */
private fun LazyListScope.downloadGroup(
	key: String,
	title: StringResource,
	items: List<DownloadCenterItem>,
	subtitle: String? = null,
	actions: @Composable RowScope.() -> Unit = {},
	row: @Composable (DownloadCenterItem, ListItemShapes) -> Unit
) {
	if (items.isEmpty()) return
	item(key = "header-$key") {
		GroupHeader(
			title = stringResource(title) + (subtitle?.let { " · $it" } ?: ""),
			count = items.size,
			modifier = Modifier.animateItem(),
			actions = actions
		)
	}
	// Keyed by the song: a download that finishes moves from its group to "Downloaded" as the
	// same row, rather than one vanishing while another appears.
	itemsIndexed(items, key = { _, item -> item.download.songId }) { index, item ->
		Box(Modifier.animateItem()) {
			row(item, segmentedShapes(index = index, count = items.size))
		}
	}
}

@Composable
private fun DownloadRow(
	item: DownloadCenterItem,
	shapes: ListItemShapes,
	progress: Float? = null,
	supporting: String? = null,
	supportingIsError: Boolean = false,
	trailing: @Composable () -> Unit
) {
	val preferenceManager = koinInject<PreferenceManager>()
	SegmentedListItem(
		onClick = {},
		shapes = shapes,
		colors = downloadsRowColors(),
		verticalAlignment = Alignment.CenterVertically,
		contentPadding = SongRowDefaults.ContentPadding,
		leadingContent = {
			CoverArt(
				coverArtId = item.song?.coverArtId,
				fallbackAlbumId = item.song?.albumId,
				modifier = Modifier.size(50.dp),
				shape = preferenceManager.coverArtShape.decreasedShape
			)
		},
		content = {
			Text(
				// A row whose song isn't in the local library yet still has to say SOMETHING:
				// falling back to the id beats rendering a blank line.
				text = item.song?.title ?: item.download.songId,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis
			)
		},
		supportingContent = {
			Column {
				val subtitle = supporting ?: item.song?.artistName
				if (subtitle != null) {
					Text(
						text = subtitle,
						color = if (supportingIsError) MaterialTheme.colorScheme.error
						else MaterialTheme.colorScheme.onSurfaceVariant,
						maxLines = 2,
						overflow = TextOverflow.Ellipsis
					)
				}
				if (item.download.retryCount > 0) {
					Text(
						text = stringResource(
							Res.string.info_download_attempts,
							item.download.retryCount + 1
						),
						style = MaterialTheme.typography.bodySmall
					)
				}
				if (progress != null) {
					LinearProgressIndicator(
						progress = { progress },
						modifier = Modifier
							.fillMaxWidth()
							.padding(top = 8.dp)
					)
				}
			}
		},
		trailingContent = trailing
	)
}

/** Why nothing is moving, and the way to the two settings that hold it back. */
@Composable
private fun ConstrainedBanner(onOpenSettings: () -> Unit, modifier: Modifier = Modifier) {
	Row(
		modifier = modifier
			.fillMaxWidth()
			.padding(bottom = 8.dp)
			.background(
				MaterialTheme.colorScheme.secondaryContainer,
				MaterialTheme.shapes.large
			)
			.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
		verticalAlignment = Alignment.CenterVertically
	) {
		Text(
			stringResource(Res.string.banner_downloads_waiting),
			style = MaterialTheme.typography.bodyMedium,
			color = MaterialTheme.colorScheme.onSecondaryContainer,
			modifier = Modifier.weight(1f)
		)
		TextButton(onClick = onOpenSettings) {
			Text(stringResource(Res.string.title_settings))
		}
	}
}

// --- From Soulseek ---------------------------------------------------------------------------------

/** Which fills the list is showing. Order is render order. */
private enum class FillFilter(val label: StringResource) {
	ALL(Res.string.lbbot_filter_all),
	RUNNING(Res.string.lbbot_filter_running),
	FAILED(Res.string.lbbot_filter_failed),
	DONE(Res.string.lbbot_filter_done);

	// Predicates over what the ledger already carries (`settled` and `outcome`), so no new
	// vocabulary and nothing extra polled.
	fun matches(fill: LbFillEntry): Boolean = when (this) {
		ALL -> true
		RUNNING -> !fill.settled
		// Everything that ended in something other than success, cancellations and
		// "stopped tracking this one" included: the question is "what didn't I get",
		// not "what errored".
		FAILED -> fill.settled && fill.outcome != LbBotManager.OUTCOME_DONE
		DONE -> fill.settled && fill.outcome == LbBotManager.OUTCOME_DONE
	}
}

/**
 * Albums lb-bot is acquiring from Soulseek, and the ones that have finished trying: not files
 * cached for offline playback, but the same question ("what is downloading, and what went
 * wrong?").
 */
private fun LazyListScope.fillsSection(
	fills: List<LbFillEntry>,
	viewModel: DownloadCenterViewModel,
	onOpenAlbum: (LbFillEntry) -> Unit
) {
	if (fills.isEmpty()) {
		item(key = "empty") {
			ContentUnavailable(
				modifier = Modifier
					.fillMaxWidth()
					.padding(vertical = 64.dp),
				icon = Icons.Outlined.Download,
				label = stringResource(Res.string.info_no_fills)
			)
		}
		return
	}
	item(key = "fills") {
		FillsList(fills, viewModel, onOpenAlbum)
	}
}

/**
 * The fills, under their filter. One item rather than a row each, because the filter is this
 * group's own state: deliberately not a persisted preference, as "show me what didn't land" is a
 * question for the next thirty seconds, and a filter that survives a restart is a list that looks
 * empty for a reason the user has long forgotten choosing.
 */
@Composable
private fun FillsList(
	fills: List<LbFillEntry>,
	viewModel: DownloadCenterViewModel,
	onOpenAlbum: (LbFillEntry) -> Unit
) {
	val wishlisted by viewModel.wishlisted.collectAsStateWithLifecycle()
	var filter by rememberSaveable { mutableStateOf(FillFilter.ALL) }
	val shown = fills.filter(filter::matches)
	// A one-second clock for the countdowns and "last checked Ns ago". Read only inside the
	// rows, so a tick recomposes the rows and nothing above them.
	val now = remember { mutableLongStateOf(Clock.System.now().toEpochMilliseconds()) }
	LaunchedEffect(Unit) {
		while (true) {
			delay(1_000)
			now.longValue = Clock.System.now().toEpochMilliseconds()
		}
	}

	Column(verticalArrangement = Arrangement.spacedBy(SegmentedListItemDefaults.SegmentedGap)) {
		PageChips(
			options = FillFilter.entries,
			selected = filter,
			label = { option -> "${stringResource(option.label)} (${fills.count(option::matches)})" },
			onSelect = { filter = it },
			enabled = { option -> option == FillFilter.ALL || fills.any(option::matches) },
			contentPadding = PaddingValues(bottom = 8.dp)
		)
		if (shown.isEmpty()) {
			// A filter that hides everything must say it was the filter, not that there is
			// nothing here.
			Text(
				stringResource(Res.string.lbbot_fills_none_match, stringResource(filter.label)),
				style = MaterialTheme.typography.bodyMedium,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
				modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)
			)
		}
		shown.forEachIndexed { index, fill ->
			FillRow(
				fill = fill,
				now = now,
				shapes = segmentedShapes(index = index, count = shown.size),
				// The wishlist is for "nobody has it", nothing else; the vocabulary gates it on
				// the failure kind and the rgid.
				wishlistAvailable = viewModel.wishlistSupported && fill.key !in wishlisted,
				onAction = { button ->
					when (button) {
						FillButton.RETRY -> viewModel.retryFill(fill.key)
						FillButton.TRY_ANOTHER -> viewModel.retryAnotherSource(fill.key)
						FillButton.CANCEL -> viewModel.cancelFill(fill.key)
						FillButton.ALLOW_MP3 -> viewModel.allowMp3AndRetry(fill)
						FillButton.WISHLIST -> viewModel.addToWishlist(fill)
						FillButton.DISMISS -> viewModel.dismissFill(fill.key)
						// The album page is where "Download" lives: a cancel is restarted from
						// there, not with a Retry on the row.
						FillButton.OPEN_ALBUM -> onOpenAlbum(fill)
					}
				}
			)
		}
	}
}

/**
 * One album lb-bot is fetching, or has finished trying to fetch.
 *
 * Not [DownloadRow]: that one is built around a `DownloadCenterItem` (a song row with a cached
 * file behind it) and these have no song, no file and no local id. What they have instead is a
 * release-group, cover art from the Cover Art Archive, and lb-bot's own sentence for why it
 * failed. Its buttons sit under the text rather than beside it, so a row with three of them
 * keeps its title.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FillRow(
	fill: LbFillEntry,
	now: State<Long>,
	shapes: ListItemShapes,
	wishlistAvailable: Boolean,
	onAction: (FillButton) -> Unit
) {
	val preferenceManager = koinInject<PreferenceManager>()
	// Every word and every button comes from the shared vocabulary (PROTOCOL §15.2), the table
	// Feishin renders from too.
	val view = describeFill(fill, now.value, wishlistAvailable)
	val failed = fill.settled && !fill.succeeded
	SegmentedListItem(
		onClick = {},
		shapes = shapes,
		colors = downloadsRowColors(),
		contentPadding = SongRowDefaults.ContentPadding,
		leadingContent = {
			RemoteCoverArt(
				url = fill.coverUrl,
				modifier = Modifier.size(50.dp),
				shape = preferenceManager.coverArtShape.decreasedShape
			)
		},
		content = {
			Text(
				text = fill.album.ifBlank { fill.artist }.ifBlank { fill.key },
				maxLines = 1,
				overflow = TextOverflow.Ellipsis
			)
		},
		supportingContent = {
			Column {
				Text(
					text = view.headline.resolve(),
					color = if (failed) MaterialTheme.colorScheme.error
					else MaterialTheme.colorScheme.onSurfaceVariant,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis
				)
				// lb-bot's reason, verbatim, is among these: it is the only thing that
				// distinguishes "no peer had it" from "every source was rejected for format".
				view.sublines.forEach { line ->
					Text(
						text = line.resolve(),
						style = MaterialTheme.typography.bodySmall,
						maxLines = 3,
						overflow = TextOverflow.Ellipsis
					)
				}
				view.explain?.let { line ->
					Text(text = line.resolve(), style = MaterialTheme.typography.bodySmall)
				}
				when (view.progress) {
					FillProgressKind.DETERMINATE -> LinearProgressIndicator(
						progress = { view.percent / 100f },
						modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
					)
					// Searching, or waiting for the peer: a bar sitting at zero reads as a stall.
					FillProgressKind.INDETERMINATE -> LinearProgressIndicator(
						modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
					)
					FillProgressKind.NONE -> Unit
				}
				val labelled = view.buttons.filter { it != FillButton.DISMISS }
				if (labelled.isNotEmpty()) {
					FlowRow(
						modifier = Modifier.padding(top = 4.dp),
						horizontalArrangement = Arrangement.spacedBy(4.dp)
					) {
						labelled.forEach { button ->
							TextButton(onClick = { onAction(button) }) {
								Text(stringResource(button.label))
							}
						}
					}
				}
			}
		},
		trailingContent = if (FillButton.DISMISS in view.buttons) {
			{
				IconButton(onClick = { onAction(FillButton.DISMISS) }) {
					Icon(
						Icons.Outlined.Close,
						contentDescription = stringResource(Res.string.action_dismiss)
					)
				}
			}
		} else null
	)
}

private val FillButton.label: StringResource
	get() = when (this) {
		FillButton.CANCEL -> Res.string.action_cancel
		FillButton.RETRY -> Res.string.action_retry
		FillButton.TRY_ANOTHER -> Res.string.action_try_another_source
		FillButton.ALLOW_MP3 -> Res.string.action_allow_mp3_retry
		FillButton.WISHLIST -> Res.string.action_add_to_wishlist
		FillButton.OPEN_ALBUM -> Res.string.action_open_album
		FillButton.DISMISS -> Res.string.action_dismiss
	}

/** Human label for a download's [DownloadSource]; unknown values fall back to the raw string. */
@Composable
private fun sourceLabel(source: String): String = when (source) {
	DownloadSource.MANUAL -> stringResource(Res.string.download_source_manual)
	DownloadSource.ALBUM -> stringResource(Res.string.download_source_album)
	DownloadSource.PLAYLIST -> stringResource(Res.string.download_source_playlist)
	DownloadSource.LIBRARY -> stringResource(Res.string.download_source_library)
	DownloadSource.QUEUE -> stringResource(Res.string.download_source_queue)
	else -> source.replaceFirstChar { it.uppercase() }
}

/** Bytes as MB/GB: the same rounding the storage settings row uses. */
private fun formatSize(bytes: Long): String {
	val mb = bytes.toDouble() / (1024 * 1024)
	return if (mb > 1024) {
		"${((mb / 1024) * 100).toInt() / 100.0} GB"
	} else {
		"${mb.toInt()} MB"
	}
}
