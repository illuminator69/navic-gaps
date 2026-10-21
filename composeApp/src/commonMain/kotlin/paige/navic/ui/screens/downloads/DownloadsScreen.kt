package paige.navic.ui.screens.downloads

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.serialization.Serializable
import navic.composeapp.generated.resources.Res
import navic.composeapp.generated.resources.action_cancel_all
import navic.composeapp.generated.resources.action_cancel_download
import navic.composeapp.generated.resources.action_clear_failed
import navic.composeapp.generated.resources.action_delete_download
import navic.composeapp.generated.resources.action_repair_downloads
import navic.composeapp.generated.resources.action_retry
import navic.composeapp.generated.resources.action_retry_all
import navic.composeapp.generated.resources.banner_downloads_waiting
import navic.composeapp.generated.resources.download_source_album
import navic.composeapp.generated.resources.download_source_library
import navic.composeapp.generated.resources.download_source_manual
import navic.composeapp.generated.resources.download_source_playlist
import navic.composeapp.generated.resources.download_source_queue
import navic.composeapp.generated.resources.info_download_attempts
import navic.composeapp.generated.resources.info_no_downloads
import navic.composeapp.generated.resources.option_paste_link
import navic.composeapp.generated.resources.section_downloads_active
import navic.composeapp.generated.resources.section_downloads_completed
import navic.composeapp.generated.resources.section_downloads_failed
import navic.composeapp.generated.resources.section_downloads_queued
import navic.composeapp.generated.resources.title_downloads
import navic.composeapp.generated.resources.title_settings
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import paige.navic.data.database.entities.DownloadSource
import paige.navic.di.LocalBottomBarScrollManager
import paige.navic.di.LocalNavStack
import paige.navic.di.LocalPlatformContext
import paige.navic.di.isLandscape
import paige.navic.domain.manager.PreferenceManager
import paige.navic.domain.models.settings.BottomBarVisibilityMode
import paige.navic.icons.Icons
import paige.navic.icons.outlined.Close
import paige.navic.icons.outlined.Delete
import paige.navic.icons.outlined.Link
import paige.navic.icons.outlined.Queue
import paige.navic.ui.components.common.ContentUnavailable
import paige.navic.ui.components.common.GroupHeader
import paige.navic.ui.components.common.SegmentedListItem
import paige.navic.ui.components.common.SegmentedListItemDefaults
import paige.navic.ui.components.common.SegmentedListItemDefaults.segmentedShapes
import paige.navic.ui.components.common.SongRowDefaults
import paige.navic.ui.components.common.LibraryRowCover
import paige.navic.ui.components.common.fillRowColors
import paige.navic.ui.components.common.resolve
import paige.navic.ui.components.layouts.NestedTopBar
import paige.navic.ui.components.layouts.RootBottomBar
import paige.navic.ui.navigation.Screen
import paige.navic.ui.screens.downloads.viewmodels.DownloadCenterItem
import paige.navic.ui.screens.downloads.viewmodels.DownloadCenterState
import paige.navic.ui.screens.downloads.viewmodels.DownloadCenterViewModel
import paige.navic.ui.util.withoutTop

/**
 * The Downloads page's chips, as they were (I-039). Since P6 the page is this device's downloads
 * only: lb-bot's fills and the wishlist are Source history's chips. The values are kept so a saved
 * back stack or an old link still opens; App.kt sends `Fills` and `Wishlist` to Source history.
 */
@Serializable
enum class DownloadsSection { Device, Fills, Wishlist }

/**
 * Downloads (I-039, P6): everything this device holds offline. What lb-bot is getting from Soulseek
 * and the wishlist moved to Source history in the avatar menu's Library tools (P6, the owner's
 * ruling of 2026-10-21); Settings keeps what is a setting (Wi-Fi only, charging only, how many at
 * once: Data & storage). The rows are upstream's: `SegmentedListItem`s in groups, 50dp art in the
 * user's cover shape, washed like the library's other pages.
 */
@Composable
fun DownloadsScreen() {
	val viewModel = koinViewModel<DownloadCenterViewModel>()
	val state by viewModel.state.collectAsStateWithLifecycle()
	val constrained by viewModel.constrained.collectAsStateWithLifecycle()
	val backStack = LocalNavStack.current
	val preferenceManager = koinInject<PreferenceManager>()
	val platformContext = LocalPlatformContext.current

	var pasting by rememberSaveable { mutableStateOf(false) }

	Scaffold(
		topBar = {
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
		},
		bottomBar = {
			val scrollManager = LocalBottomBarScrollManager.current
			val preferVisible =
				preferenceManager.bottomBarVisibilityMode == BottomBarVisibilityMode.AllScreens
			if (!platformContext.isLandscape() && preferVisible) {
				RootBottomBar(scrolled = scrollManager.isTriggered)
			}
		}
	) { innerPadding ->
		LazyColumn(
			modifier = Modifier
				.fillMaxSize()
				.padding(top = innerPadding.calculateTopPadding())
				.padding(horizontal = 16.dp),
			contentPadding = innerPadding.withoutTop() + PaddingValues(top = 8.dp, bottom = 24.dp),
			verticalArrangement = Arrangement.spacedBy(SegmentedListItemDefaults.SegmentedGap)
		) {
			deviceSection(
				state = state,
				constrained = constrained,
				viewModel = viewModel,
				onOpenSettings = { backStack.add(Screen.Settings.DataStorage) }
			)
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

/** The washed list colours the queue's rows use, so a row reads as a card over the cover. */
@Composable
internal fun downloadsRowColors() = fillRowColors()

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
			title = stringResource(title),
			count = items.size,
			modifier = Modifier.animateItem(),
			// The group's size sits with its actions, not in the title: "Downloaded · 77 MB 2"
			// put the count after the size, and at a large font cut the title short.
			actions = {
				if (subtitle != null) {
					Text(
						subtitle,
						style = MaterialTheme.typography.bodyMedium,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
						maxLines = 1
					)
				}
				actions()
			}
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
			LibraryRowCover(item.song?.coverArtId, item.song?.title, fallbackAlbumId = item.song?.albumId)
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
