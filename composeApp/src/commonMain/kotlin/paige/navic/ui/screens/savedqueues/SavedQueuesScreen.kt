@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package paige.navic.ui.screens.savedqueues

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.plus
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.OutlinedTextField
import navic.composeapp.generated.resources.action_more
import paige.navic.di.LocalBottomBarScrollManager
import paige.navic.di.LocalPlatformContext
import paige.navic.di.isLandscape
import paige.navic.domain.manager.PreferenceManager
import paige.navic.domain.models.settings.BottomBarVisibilityMode
import paige.navic.ui.components.common.PageChips
import paige.navic.ui.components.common.SegmentedListButton
import paige.navic.ui.components.common.SegmentedListButtonDefaults
import paige.navic.ui.components.common.SegmentedListItem
import paige.navic.ui.components.common.SegmentedListItemDefaults
import paige.navic.ui.components.common.SegmentedListItemDefaults.segmentedShapes
import paige.navic.ui.components.common.SongRowDefaults
import paige.navic.ui.components.layouts.RootBottomBar
import paige.navic.ui.components.layouts.TopBarButton
import paige.navic.ui.screens.savedqueues.components.SavedQueueSheet
import paige.navic.ui.util.withoutTop
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.dropUnlessResumed
import navic.composeapp.generated.resources.Res
import navic.composeapp.generated.resources.action_cancel
import navic.composeapp.generated.resources.action_clear_queue_history
import navic.composeapp.generated.resources.action_delete_other_queues
import navic.composeapp.generated.resources.action_ok
import navic.composeapp.generated.resources.filter_all
import navic.composeapp.generated.resources.info_no_saved_queues
import navic.composeapp.generated.resources.info_no_saved_queues_hint
import navic.composeapp.generated.resources.message_clear_queue_history
import navic.composeapp.generated.resources.message_delete_other_queues
import navic.composeapp.generated.resources.message_restore_queue_failed
import navic.composeapp.generated.resources.message_save_playlist_failed
import navic.composeapp.generated.resources.message_saved_as_playlist
import navic.composeapp.generated.resources.option_playlist_name
import navic.composeapp.generated.resources.option_queue_name
import navic.composeapp.generated.resources.title_clear_queue_history
import navic.composeapp.generated.resources.title_delete_other_queues
import navic.composeapp.generated.resources.title_rename_queue
import navic.composeapp.generated.resources.title_save_as_playlist
import navic.composeapp.generated.resources.title_saved_queues
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import paige.navic.di.LocalNavStack
import paige.navic.data.database.entities.SavedQueueEntity
import paige.navic.domain.models.SavedQueueSource
import paige.navic.icons.Icons
import paige.navic.icons.outlined.Delete
import paige.navic.icons.outlined.MoreVert
import paige.navic.icons.outlined.Queue
import paige.navic.shared.MediaPlayerViewModel
import paige.navic.ui.components.common.ContentUnavailable
import paige.navic.ui.components.common.CoverArt
import paige.navic.ui.components.dialogs.FormDialog
import paige.navic.ui.components.layouts.NestedTopBar
import paige.navic.ui.navigation.Screen
import paige.navic.ui.screens.savedqueues.components.SavedQueuePreviewSheet
import paige.navic.ui.screens.savedqueues.viewmodels.SavedQueueMessage
import paige.navic.ui.screens.savedqueues.viewmodels.SavedQueuesViewModel
import kotlinx.coroutines.isActive

/**
 * The Symfonium-style "select media queue" list: every queue Navic has auto-captured (rolling cache
 * of the 20 most recent), most-recent first, with the one playing right now pinned to the top and
 * marked. A chip row filters by how the queue was made (album / playlist / radio / Mood Flow /
 * journey / manual). Tapping a row restores it PAUSED where it left off; a per-row overflow previews
 * its tracks, resumes it playing, saves it as a Navidrome playlist, renames, or deletes.
 *
 * The history itself is shared through the hub, so this screen and Feishin's are two views of one
 * list — which is why the presentation (art, three lines, "Now playing", preview, clear all) is kept
 * deliberately in step with it.
 */
@Composable
fun SavedQueuesScreen() {
	val viewModel = koinViewModel<SavedQueuesViewModel>()
	val mediaPlayer = koinInject<MediaPlayerViewModel>()
	val backStack = LocalNavStack.current

	val queues by viewModel.queues.collectAsStateWithLifecycle()
	val message by viewModel.message.collectAsStateWithLifecycle()
	val restoreFailed by mediaPlayer.restoreFailed.collectAsStateWithLifecycle()
	val actions = rememberSavedQueueActions(viewModel, mediaPlayer)
	val activeId = actions.activeId

	var renameTarget by remember { mutableStateOf<SavedQueueEntity?>(null) }
	var saveTarget by remember { mutableStateOf<SavedQueueEntity?>(null) }
	var previewTarget by remember { mutableStateOf<SavedQueueEntity?>(null) }
	var deleteOthersOpen by remember { mutableStateOf(false) }
	var clearAllOpen by remember { mutableStateOf(false) }
	// null = "All"; otherwise a SavedQueueSource kind.
	var kindFilter by remember { mutableStateOf<String?>(null) }

	val snackbarHostState = remember { SnackbarHostState() }
	val savedMsg = stringResource(Res.string.message_saved_as_playlist)
	val failedMsg = stringResource(Res.string.message_save_playlist_failed)
	val restoreFailedMsg = stringResource(Res.string.message_restore_queue_failed)
	LaunchedEffect(message) {
		when (message) {
			SavedQueueMessage.SavedAsPlaylist -> snackbarHostState.showSnackbar(savedMsg)
			SavedQueueMessage.Error -> snackbarHostState.showSnackbar(failedMsg)
			null -> {}
		}
		if (message != null) viewModel.clearMessage()
	}
	// A restore that fails used to be invisible — the screen closes itself on tap, so the user just
	// saw it go away with nothing playing.
	LaunchedEffect(restoreFailed) {
		if (restoreFailed) {
			snackbarHostState.showSnackbar(restoreFailedMsg)
			mediaPlayer.clearRestoreFailed()
		}
	}

	val rows = queues.orEmpty()
	// Kinds actually present, in a stable canonical order, so the chip row only offers real options.
	val presentKinds = remember(rows) {
		SavedQueueSource.ALL.filter { kind -> rows.any { it.sourceKind == kind } }
	}
	val visibleQueues = remember(rows, kindFilter, activeId) {
		val filtered = kindFilter?.let { k -> rows.filter { it.sourceKind == k } } ?: rows
		// Current queue pinned to the top (stable sort keeps the rest updatedAt-DESC).
		filtered.sortedByDescending { it.id == activeId }
	}

	// A chip tap shows another list, and the list starts at its top: rows that leave fade out, the ones
	// that stay slide up or down, rows that come in fade in, wherever the page was scrolled to. Without
	// the reset the list stayed anchored on the row it was showing (LazyColumn keeps the first visible
	// KEY in place), so every row that came in above it did so out of sight and the chip looked inert;
	// and rows above the view flew in through the top bar (B-161).
	val listState = rememberLazyListState()

	var sheetTarget by remember { mutableStateOf<SavedQueueEntity?>(null) }
	val preferenceManager = koinInject<PreferenceManager>()
	val platformContext = LocalPlatformContext.current

	Scaffold(
		topBar = {
			Column {
				NestedTopBar(
					title = { Text(stringResource(Res.string.title_saved_queues)) },
					// The two bulk deletes live behind the bar's overflow, as upstream keeps a
					// page's rarer actions: two slabs under the list read as an alarm.
					actions = {
						if (rows.isNotEmpty()) {
							var menuOpen by remember { mutableStateOf(false) }
							Box {
								TopBarButton(onClick = { menuOpen = true }) {
									Icon(
										Icons.Outlined.MoreVert,
										contentDescription = stringResource(Res.string.action_more)
									)
								}
								DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
									if (activeId != null && rows.size > 1) {
										DropdownMenuItem(
											text = { Text(stringResource(Res.string.action_delete_other_queues)) },
											onClick = {
												menuOpen = false
												deleteOthersOpen = true
											}
										)
									}
									DropdownMenuItem(
										text = { Text(stringResource(Res.string.action_clear_queue_history)) },
										onClick = {
											menuOpen = false
											clearAllOpen = true
										}
									)
								}
							}
						}
					}
				)
				// Only worth a filter row when there's more than one kind to choose between.
				if (presentKinds.size > 1) {
					PageChips(
						options = listOf<String?>(null) + presentKinds,
						selected = kindFilter,
						label = { kind ->
							if (kind == null) stringResource(Res.string.filter_all) else queueKindLabel(kind)
						},
						onSelect = {
							listState.requestScrollToItem(0)
							kindFilter = it
						},
						contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 8.dp)
					)
				}
			}
		},
		snackbarHost = { SnackbarHost(snackbarHostState) },
		bottomBar = {
			val scrollManager = LocalBottomBarScrollManager.current
			val preferVisible =
				preferenceManager.bottomBarVisibilityMode == BottomBarVisibilityMode.AllScreens
			if (!platformContext.isLandscape() && preferVisible) {
				RootBottomBar(scrolled = scrollManager.isTriggered)
			}
		}
	) { innerPadding ->
		when {
			// Room hasn't emitted yet. Without this the empty state flashed on every entry.
			queues == null -> Box(
				Modifier
					.padding(innerPadding)
					.fillMaxSize(),
				contentAlignment = Alignment.Center
			) { ContainedLoadingIndicator() }

			rows.isEmpty() -> ContentUnavailable(
				modifier = Modifier
					.padding(innerPadding)
					.fillMaxSize(),
				icon = Icons.Outlined.Queue,
				label = stringResource(Res.string.info_no_saved_queues),
				description = stringResource(Res.string.info_no_saved_queues_hint)
			)

			else -> LazyColumn(
				state = listState,
				modifier = Modifier
					.padding(top = innerPadding.calculateTopPadding())
					.fillMaxSize()
					// A row animating out or in draws wherever it is going to or from, which was the top bar when
					// the page was scrolled: the list shows nothing outside itself.
					.clipToBounds()
					.padding(horizontal = 16.dp),
				contentPadding = innerPadding.withoutTop() + PaddingValues(top = 8.dp, bottom = 24.dp),
				verticalArrangement = Arrangement.spacedBy(SegmentedListItemDefaults.SegmentedGap)
			) {
				itemsIndexed(visibleQueues, key = { _, queue -> queue.id }) { index, queue ->
					SavedQueueRow(
						queue = queue,
						shapes = segmentedShapes(index = index, count = visibleQueues.size),
						isActive = queue.id == activeId,
						onClick = dropUnlessResumed {
							actions.restore(queue)
							backStack.remove(Screen.SavedQueues)
						},
						onMenu = { sheetTarget = queue },
						modifier = Modifier.animateItem()
					)
				}
			}
		}
	}

	sheetTarget?.let { target ->
		SavedQueueSheet(
			queue = target,
			subtitle = savedQueueSubtitle(target, target.id == activeId),
			onDismissRequest = { sheetTarget = null },
			onRestore = {
				actions.restore(target)
				backStack.remove(Screen.SavedQueues)
			},
			onResume = {
				actions.resume(target)
				backStack.remove(Screen.SavedQueues)
			},
			onPreview = { previewTarget = target },
			onSaveAsPlaylist = { saveTarget = target },
			onRename = { renameTarget = target },
			onDelete = { actions.delete(target) }
		)
	}

	renameTarget?.let { target ->
		QueueNameDialog(
			title = stringResource(Res.string.title_rename_queue),
			label = stringResource(Res.string.option_queue_name),
			initial = target.name ?: target.sourceName.orEmpty(),
			onConfirm = { newName ->
				actions.rename(target.id, newName)
				renameTarget = null
			},
			onDismissRequest = { renameTarget = null }
		)
	}

	saveTarget?.let { target ->
		QueueNameDialog(
			title = stringResource(Res.string.title_save_as_playlist),
			label = stringResource(Res.string.option_playlist_name),
			initial = target.name ?: target.sourceName.orEmpty(),
			onConfirm = { name ->
				viewModel.saveAsPlaylist(target.id, name)
				saveTarget = null
			},
			onDismissRequest = { saveTarget = null }
		)
	}

	previewTarget?.let { target ->
		SavedQueuePreviewSheet(
			queue = target,
			onResume = {
				previewTarget = null
				actions.resume(target)
				backStack.remove(Screen.SavedQueues)
			},
			onDismissRequest = { previewTarget = null }
		)
	}

	if (deleteOthersOpen && activeId != null) {
		ConfirmDialog(
			title = stringResource(Res.string.title_delete_other_queues),
			message = stringResource(Res.string.message_delete_other_queues),
			confirmLabel = stringResource(Res.string.action_delete_other_queues),
			onConfirm = {
				actions.deleteOthers(rows, activeId)
				deleteOthersOpen = false
			},
			onDismissRequest = { deleteOthersOpen = false }
		)
	}

	if (clearAllOpen) {
		ConfirmDialog(
			title = stringResource(Res.string.title_clear_queue_history),
			message = stringResource(Res.string.message_clear_queue_history),
			confirmLabel = stringResource(Res.string.action_clear_queue_history),
			onConfirm = {
				actions.clearAll(rows)
				clearAllOpen = false
			},
			onDismissRequest = { clearAllOpen = false }
		)
	}
}

/**
 * One saved queue, as a segmented library row: the art of the track it resumes on (matching the
 * title, never a generic glyph nor a peer's cover URL, which points at ITS server with ITS auth),
 * its name, its kind and size, and where it came from when the title is not already saying it.
 * The live queue is marked as the queue marks its playing track, and says so in words.
 */
@Composable
private fun SavedQueueRow(
	queue: SavedQueueEntity,
	shapes: ListItemShapes,
	isActive: Boolean,
	onClick: () -> Unit,
	onMenu: () -> Unit,
	modifier: Modifier = Modifier
) {
	val preferenceManager = koinInject<PreferenceManager>()
	val sourceLine = savedQueueSourceLine(queue)
	SegmentedListItem(
		selected = isActive,
		onClick = onClick,
		onLongClick = onMenu,
		shapes = shapes,
		modifier = modifier,
		colors = ListItemDefaults.colors(
			containerColor = SongRowDefaults.containerColor(false),
			supportingContentColor = SongRowDefaults.supportingContentColor(false),
			selectedContainerColor = SongRowDefaults.containerColor(true),
			selectedContentColor = SongRowDefaults.contentColor(true),
			selectedSupportingContentColor = SongRowDefaults.supportingContentColor(true)
		),
		verticalAlignment = Alignment.CenterVertically,
		contentPadding = SongRowDefaults.ContentPadding,
		leadingContent = {
			CoverArt(
				coverArtId = savedQueueCoverArtId(queue),
				contentDescription = null,
				modifier = Modifier.size(50.dp),
				shape = preferenceManager.coverArtShape.decreasedShape
			)
		},
		content = {
			Text(
				savedQueueTitle(queue),
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
				fontWeight = if (isActive) FontWeight.SemiBold else null
			)
		},
		supportingContent = {
			Column {
				Text(savedQueueSubtitle(queue, isActive), maxLines = 1, overflow = TextOverflow.Ellipsis)
				sourceLine?.let { line ->
					Text(
						line,
						style = MaterialTheme.typography.bodySmall,
						maxLines = 1,
						overflow = TextOverflow.Ellipsis
					)
				}
			}
		},
		trailingContent = {
			IconButton(onClick = onMenu) {
				Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(Res.string.action_more))
			}
		}
	)
}

@Composable
private fun ConfirmDialog(
	title: String,
	message: String,
	confirmLabel: String,
	onConfirm: () -> Unit,
	onDismissRequest: () -> Unit
) {
	FormDialog(
		onDismissRequest = onDismissRequest,
		icon = { Icon(Icons.Outlined.Delete, null) },
		title = { Text(title) },
		buttons = {
			SegmentedListButton(
				modifier = Modifier.fillMaxWidth(),
				onClick = onConfirm,
				shapes = SegmentedListButtonDefaults.shapes(index = 0, count = 2),
				colors = SegmentedListButtonDefaults.errorColors()
			) { Text(confirmLabel) }
			SegmentedListButton(
				modifier = Modifier.fillMaxWidth(),
				onClick = onDismissRequest,
				shapes = SegmentedListButtonDefaults.shapes(index = 1, count = 2)
			) { Text(stringResource(Res.string.action_cancel)) }
		},
		content = { Text(message) }
	)
}

@Composable
private fun QueueNameDialog(
	title: String,
	label: String,
	initial: String,
	onConfirm: (String) -> Unit,
	onDismissRequest: () -> Unit
) {
	val nameState = rememberTextFieldState(initial)

	FormDialog(
		onDismissRequest = onDismissRequest,
		title = { Text(title) },
		buttons = {
			SegmentedListButton(
				modifier = Modifier.fillMaxWidth(),
				onClick = { onConfirm(nameState.text.toString()) },
				enabled = nameState.text.isNotBlank(),
				shapes = SegmentedListButtonDefaults.shapes(index = 0, count = 2),
				colors = SegmentedListButtonDefaults.primaryColors()
			) { Text(stringResource(Res.string.action_ok)) }
			SegmentedListButton(
				modifier = Modifier.fillMaxWidth(),
				onClick = onDismissRequest,
				shapes = SegmentedListButtonDefaults.shapes(index = 1, count = 2)
			) { Text(stringResource(Res.string.action_cancel)) }
		},
		content = {
			OutlinedTextField(
				state = nameState,
				label = { Text(label) },
				lineLimits = TextFieldLineLimits.SingleLine
			)
		}
	)
}
