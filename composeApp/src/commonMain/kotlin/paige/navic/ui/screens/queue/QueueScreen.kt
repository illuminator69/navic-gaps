package paige.navic.ui.screens.queue

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.dropUnlessResumed
import com.kyant.capsule.ContinuousRoundedRectangle
import kotlin.time.DurationUnit
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.launch
import navic.composeapp.generated.resources.Res
import navic.composeapp.generated.resources.action_cancel
import navic.composeapp.generated.resources.action_clear_queue
import navic.composeapp.generated.resources.action_delete_download
import navic.composeapp.generated.resources.action_download_next
import navic.composeapp.generated.resources.action_download_queue
import navic.composeapp.generated.resources.action_undo
import navic.composeapp.generated.resources.count_remaining_songs
import navic.composeapp.generated.resources.count_songs
import navic.composeapp.generated.resources.info_duration_left
import navic.composeapp.generated.resources.info_no_queue
import navic.composeapp.generated.resources.info_no_songs
import navic.composeapp.generated.resources.queue_generator_audiomuse
import navic.composeapp.generated.resources.queue_generator_fingerprint
import navic.composeapp.generated.resources.queue_generator_mood_flow
import navic.composeapp.generated.resources.tab_related
import navic.composeapp.generated.resources.tab_up_next
import navic.composeapp.generated.resources.undo_queue_cleared
import navic.composeapp.generated.resources.undo_queue_moved
import navic.composeapp.generated.resources.undo_queue_removed
import navic.composeapp.generated.resources.undo_queue_replaced
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import paige.navic.data.database.entities.DownloadEntity
import paige.navic.data.database.entities.DownloadSource
import paige.navic.data.database.entities.DownloadStatus
import paige.navic.di.LocalNavStack
import paige.navic.di.LocalPlatformContext
import paige.navic.di.LocalSheetState
import paige.navic.domain.manager.AudioMuseManager
import paige.navic.domain.manager.DownloadManager
import paige.navic.domain.manager.HubManager
import paige.navic.domain.manager.PreferenceManager
import paige.navic.domain.manager.RadioManager
import paige.navic.domain.models.DomainSong
import paige.navic.domain.models.settings.AutoplayMode
import paige.navic.domain.models.settings.QueueInfoType
import paige.navic.icons.Icons
import paige.navic.icons.outlined.Close
import paige.navic.icons.outlined.Delete
import paige.navic.icons.outlined.Download
import paige.navic.icons.outlined.Note
import paige.navic.icons.outlined.PlaylistRemove
import paige.navic.shared.MediaPlayerViewModel
import paige.navic.shared.QueueUndoKind
import paige.navic.ui.components.common.ContentUnavailable
import paige.navic.ui.components.sheets.SongSheet
import paige.navic.ui.core.UiState
import paige.navic.ui.navigation.Screen
import paige.navic.ui.screens.playlist.dialogs.PlaylistUpdateDialog
import paige.navic.ui.screens.queue.components.QueueScreenItem
import paige.navic.ui.screens.queue.components.RelatedSongItem
import paige.navic.ui.screens.queue.viewmodels.QueueViewModel
import paige.navic.ui.screens.queue.viewmodels.RelatedSongsViewModel
import paige.navic.ui.screens.share.dialogs.ShareDialog
import paige.navic.ui.theme.defaultFont
import paige.navic.ui.util.draggableItemsIndexed
import paige.navic.ui.util.rememberDraggableListState

/** A queue slot with a stable id so reorders animate (see [QueueScreen]). */
private data class QueueEntry(val uid: Long, val song: DomainSong)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun QueueScreen() {
	val viewModel = koinViewModel<QueueViewModel>()
	val platformContext = LocalPlatformContext.current
	val backStack = LocalNavStack.current
	val player = koinInject<MediaPlayerViewModel>()
	val hubManager = koinInject<HubManager>()
	val isRemoteActive by hubManager.isRemoteActive.collectAsState()
	val playerState by player.steadyState.collectAsStateWithLifecycle()
	val isOnline by viewModel.isOnline.collectAsStateWithLifecycle()
	val downloadedSongs by viewModel.downloadedSongs.collectAsStateWithLifecycle()
	val queue = playerState.queue

	// Per-row sheet state (see the SongSheet at the end of this composable).
	val radioManager = koinInject<RadioManager>()
	val selectedQueueSong by viewModel.selectedSong.collectAsStateWithLifecycle()
	val selectedStarred by viewModel.starred.collectAsStateWithLifecycle()
	val selectedRating by viewModel.selectedSongRating.collectAsStateWithLifecycle()
	val allDownloads by viewModel.allDownloads.collectAsStateWithLifecycle()
	var queueShareId by remember { mutableStateOf<String?>(null) }
	var queuePlaylistSong by remember { mutableStateOf<DomainSong?>(null) }

	/**
	 * Navigate out of the queue.
	 *
	 * The queue is a bottom sheet, and so is the player it is usually opened from — leaving either
	 * on the stack would park it over the destination. Both are dropped before the push.
	 */
	fun leaveQueueFor(screen: Screen) {
		backStack.remove(Screen.Queue)
		backStack.remove(Screen.NowPlaying)
		backStack.add(screen)
	}

	// On-screen mirror of the queue, reordered SYNCHRONOUSLY during a drag for
	// smooth visual feedback. The actual reorder is committed ONCE on release
	// (below) — moving the player's media items on every drag step made the main
	// player visibly jump to whatever song was dragged over.
	//
	// Entries carry a STABLE per-slot uid (a queue can hold the same song twice,
	// so song.id isn't unique): the uid travels with the item across a reorder so
	// `animateItem` can animate the move — index keys would defeat that animation.
	val uidBox = remember { longArrayOf(0L) }
	val displayQueue = remember {
		mutableStateListOf<QueueEntry>().apply {
			queue.forEach { add(QueueEntry(uidBox[0]++, it)) }
		}
	}
	// Net move of the in-progress drag: origin = where the item started, target =
	// its latest position. Committed when the drag ends.
	val dragOrigin = remember { mutableStateOf<Int?>(null) }
	val dragTarget = remember { mutableStateOf(0) }

	// Which mirror row is the playing one, tracked by its stable uid rather than by
	// playerState.currentIndex.
	//
	// The reorder is committed on RELEASE, so during a drag the mirror has already moved while
	// currentIndex still describes the old order: highlighting by index painted "now playing" on
	// whatever row happened to occupy that slot, and the marker jumped back the instant the commit
	// landed. That flicker is what a drag looked like from the queue.
	var playingUid by remember { mutableStateOf<Long?>(null) }

	val haptic = LocalHapticFeedback.current
	val draggableState = rememberDraggableListState(viewModel.listState) { from, to ->
		if (from in displayQueue.indices && to in displayQueue.indices) {
			if (dragOrigin.value == null) dragOrigin.value = from
			dragTarget.value = to
			displayQueue.add(to, displayQueue.removeAt(from))
			haptic.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
		}
	}

	// Commit the net reorder once, on release — to the HUB SESSION when another
	// device is active (the hub broadcasts queueChanged back, which re-syncs the
	// mirror), else to the local player.
	LaunchedEffect(draggableState.draggingItemIndex) {
		if (draggableState.draggingItemIndex == null) {
			val from = dragOrigin.value
			val to = dragTarget.value
			dragOrigin.value = null
			if (from != null && from != to) {
				player.captureQueueUndo(QueueUndoKind.MOVE)
				if (hubManager.isRemoteActive.value) hubManager.actMoveQueueItem(from, to)
				else player.moveQueueItem(from, to)
			}
		}
	}

	// Re-sync the mirror from the player/remote queue on EXTERNAL changes (autoplay
	// top-up, remote queueChanged). Keyed on queue only (not the drag index) so the
	// drag-release transition doesn't momentarily revert to the pre-commit order.
	//
	// KEY-PRESERVING, and that is the whole point. Committing a drag changes `queue`, so this
	// effect re-ran on every drop; clearing and re-adding minted a fresh uid for every row, so
	// every LazyColumn key changed, every item was disposed, and the dropped item's
	// graphicsLayer node was destroyed mid-spring — the drop snapped instead of settling. The
	// post-commit re-run is not an external change at all: the mirror already holds exactly this
	// order, so it has to be a no-op.
	LaunchedEffect(queue) {
		if (draggableState.draggingItemIndex != null) return@LaunchedEffect
		if (displayQueue.size == queue.size &&
			displayQueue.indices.all { displayQueue[it].song.id == queue[it].id }
		) {
			return@LaunchedEffect
		}
		// Genuinely different: rebuild, but let each surviving slot keep its uid so rows that
		// merely moved animate instead of being torn down. A queue can hold the same song twice,
		// so match greedily and consume each old entry once.
		val spare = mutableMapOf<String, ArrayDeque<Long>>()
		displayQueue.forEach { spare.getOrPut(it.song.id) { ArrayDeque() }.addLast(it.uid) }
		val rebuilt = queue.map { song ->
			QueueEntry(spare[song.id]?.removeFirstOrNull() ?: uidBox[0]++, song)
		}
		displayQueue.clear()
		displayQueue.addAll(rebuilt)
	}

	// Re-anchor the playing row whenever the player itself moves on. Never while dragging: the
	// mirror is ahead of the player there, and re-reading currentIndex is exactly the stale answer
	// this exists to avoid.
	LaunchedEffect(playerState.currentIndex, playerState.currentSong?.id, displayQueue.size) {
		if (draggableState.draggingItemIndex != null) return@LaunchedEffect
		playingUid = displayQueue.getOrNull(playerState.currentIndex)?.uid
	}
	// -1 (uid gone after an external rebuild, or nothing captured yet) falls back to the index.
	val playingIndex = playingUid
		?.let { uid -> displayQueue.indexOfFirst { it.uid == uid } }
		?.takeIf { it >= 0 }
		?: playerState.currentIndex

	// Auto-scroll on a genuine TRACK CHANGE only — keyed on the song, not the index.
	//
	// Keyed on currentIndex, any queue edit that shifted the playing track re-ran this: drop a
	// card and the list instantly jumped so the playing song sat at the top. The reorder animation
	// was fine by then; the whole VIEW snapping is what still read as a snap. Moving cards around
	// must not move the viewport — only the music moving on should.
	LaunchedEffect(playerState.currentSong?.id) {
		// Belt and braces: never yank the list mid-drag either.
		if (draggableState.draggingItemIndex != null) return@LaunchedEffect
		runCatching {
			if (queue.isNotEmpty()) {
				draggableState.listState.scrollToItem(playingIndex.coerceAtLeast(0))
			}
		}
	}

	// Upstream's queue info: a tap on it switches between the whole queue and what is left of
	// it (`queueInfoType`). The fork had dropped the switch with the line it rebuilt.
	val preferenceManager = koinInject<PreferenceManager>()
	val infoType = preferenceManager.queueInfoType
	val fromIndex = playingIndex.coerceIn(0, (queue.size - 1).coerceAtLeast(0))
	val songsText = when (infoType) {
		QueueInfoType.Full -> pluralStringResource(Res.plurals.count_songs, queue.size, queue.size)
		QueueInfoType.Remaining -> pluralStringResource(
			Res.plurals.count_remaining_songs,
			queue.size - fromIndex,
			queue.size - fromIndex
		)
	}
	val durationText = remember(queue, infoType, fromIndex, playerState.progress) {
		var totalMillis = queue.sumOf { it.duration.inWholeMilliseconds }
		if (infoType == QueueInfoType.Remaining) {
			val pastMillis = queue.take(fromIndex).sumOf { it.duration.inWholeMilliseconds }
			val current = queue.getOrNull(fromIndex)
			val elapsedCurrent = (current?.duration?.inWholeMilliseconds ?: 0L) * playerState.progress
			totalMillis -= pastMillis + elapsedCurrent.toLong()
		}
		val totalSeconds = totalMillis.coerceAtLeast(0L) / 1000
		val hours = totalSeconds / 3600
		val minutes = (totalSeconds % 3600) / 60
		val seconds = totalSeconds % 60
		buildString {
			if (hours > 0) append("${hours}h ")
			if (minutes > 0 || hours > 0) append("${minutes}m ")
			append("${seconds}s")
		}
	}
	val totalDurationText = when (infoType) {
		QueueInfoType.Full -> durationText
		QueueInfoType.Remaining -> stringResource(Res.string.info_duration_left, durationText)
	}

	// Short-lived undo for the last destructive queue edit (clear/remove/move/replace). The VM
	// owns the ~6 s lifetime; this just surfaces it and routes the Undo tap back.
	val undo by player.queueUndo.collectAsStateWithLifecycle()
	val undoSnackbarState = remember { SnackbarHostState() }
	val undoClearedMsg = stringResource(Res.string.undo_queue_cleared)
	val undoRemovedMsg = stringResource(Res.string.undo_queue_removed)
	val undoMovedMsg = stringResource(Res.string.undo_queue_moved)
	val undoReplacedMsg = stringResource(Res.string.undo_queue_replaced)
	val undoActionLabel = stringResource(Res.string.action_undo)
	LaunchedEffect(undo?.id) {
		val snapshot = undo ?: return@LaunchedEffect
		val message = when (snapshot.kind) {
			QueueUndoKind.CLEAR -> undoClearedMsg
			QueueUndoKind.REMOVE -> undoRemovedMsg
			QueueUndoKind.MOVE -> undoMovedMsg
			QueueUndoKind.REPLACE -> undoReplacedMsg
		}
		// Indefinite: the VM expiry nulls the flow at ~6 s, which re-keys this effect and cancels
		// the pending snackbar. That keeps a single source of truth for the timeout.
		val result = undoSnackbarState.showSnackbar(
			message = message,
			actionLabel = undoActionLabel,
			duration = SnackbarDuration.Indefinite
		)
		if (result == SnackbarResult.ActionPerformed) player.performQueueUndo()
		else player.dismissQueueUndo()
	}

	Box(modifier = Modifier.fillMaxSize()) {
	// No ambient wash here: the sheet HOST (BottomSheetScene) already tints this sheet from
	// the now-playing cover and themes its content. Painting a second one in here just fought
	// the real one. The rows stay translucent and let that single surface through — which is
	// the point: a queue mixes albums, so per-row cover extraction would give clashing cards.
	Column(
		modifier = Modifier
			.fillMaxSize()
			.clip(ContinuousRoundedRectangle(topStart = 16.dp, topEnd = 16.dp))
	) {
		var selectedTab by rememberSaveable { mutableStateOf(0) }

		// Up Next / Related switch (YouTube-Music-style). Only shown when there's a queue to
		// derive related songs from; an empty queue drops straight to the Up Next empty state.
		if (queue.isNotEmpty()) {
			SingleChoiceSegmentedButtonRow(
				modifier = Modifier
					.fillMaxWidth()
					.padding(horizontal = 24.dp, vertical = 8.dp)
			) {
				SegmentedButton(
					shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
					onClick = { selectedTab = 0 },
					selected = selectedTab == 0,
					label = { Text(stringResource(Res.string.tab_up_next)) }
				)
				SegmentedButton(
					shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
					onClick = { selectedTab = 1 },
					selected = selectedTab == 1,
					label = { Text(stringResource(Res.string.tab_related)) }
				)
			}
		}

		// Slides toward the tab being opened (with a fade), so the two read as side by side
		// rather than one list being swapped for another. An empty queue only has Up Next.
		val spatialSpec = MaterialTheme.motionScheme.defaultSpatialSpec<IntOffset>()
		val effectsSpec = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
		AnimatedContent(
			targetState = if (queue.isEmpty()) 0 else selectedTab,
			modifier = Modifier.weight(1f),
			transitionSpec = {
				val direction = if (targetState > initialState) 1 else -1
				(slideInHorizontally(spatialSpec) { it / 4 * direction } + fadeIn(effectsSpec))
					.togetherWith(
						slideOutHorizontally(spatialSpec) { -it / 4 * direction } + fadeOut(effectsSpec)
					)
			},
			label = "queueTab"
		) { shownTab ->
			if (shownTab == 0) {
				Column(modifier = Modifier.fillMaxSize()) {
					if (queue.isNotEmpty()) {
						// Upstream's row (a tappable count and a 36dp tonal Clear queue), with the
						// AudioMuse generator on a line of its own under it. In the same row its
						// label ("Sonic Fingerprint • 100 songs • 6h 40m 12s") had no weight and
						// no line limit: measured first, it took the width and squeezed Clear
						// queue to a wrapped sliver.
						val radioManager = koinInject<RadioManager>()
						val audioMuseManager = koinInject<AudioMuseManager>()
						val isAudioMuseMix by radioManager.isAudioMuseMix.collectAsState()
						val autoplayMode by radioManager.autoplayMode.collectAsState()
						val moodCentroid by audioMuseManager.lastMoodCentroid.collectAsState()
						Column(
							modifier = Modifier
								.fillMaxWidth()
								.padding(horizontal = 24.dp, vertical = 8.dp)
						) {
							Row(
								modifier = Modifier.fillMaxWidth(),
								horizontalArrangement = Arrangement.SpaceBetween,
								verticalAlignment = Alignment.CenterVertically
							) {
								Row(
									modifier = Modifier
										.weight(1f)
										.heightIn(min = 36.dp)
										.clickable {
											preferenceManager.queueInfoType = when (infoType) {
												QueueInfoType.Full -> QueueInfoType.Remaining
												QueueInfoType.Remaining -> QueueInfoType.Full
											}
										},
									verticalAlignment = Alignment.CenterVertically
								) {
									Text(
										text = "$songsText • $totalDurationText",
										style = MaterialTheme.typography.titleMedium,
										fontWeight = FontWeight.SemiBold,
										fontFamily = defaultFont(round = 100f),
										color = MaterialTheme.colorScheme.onSurfaceVariant,
										maxLines = 1,
										overflow = TextOverflow.Ellipsis
									)
								}
								Row(verticalAlignment = Alignment.CenterVertically) {
									// Downloads are local files, so this is meaningless while another device holds
									// the session (its queue can be remote placeholders not in our library).
									if (!isRemoteActive) {
										QueueDownloadMenuButton(
											queue = queue,
											fromIndex = playerState.currentIndex
										)
									}
									FilledTonalButton(
										onClick = {
											haptic.performHapticFeedback(HapticFeedbackType.LongPress)
											player.captureQueueUndo(QueueUndoKind.CLEAR)
											if (isRemoteActive) hubManager.actClearQueue()
											else player.clearQueue()
										},
										colors = ButtonDefaults.filledTonalButtonColors(),
										contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
										// At least upstream's 36dp: a fixed 36 clipped the label's
										// descenders at 1.3x font.
										modifier = Modifier.heightIn(min = 36.dp)
									) {
										Text(stringResource(Res.string.action_clear_queue), maxLines = 1)
									}
								}
							}
							if (isAudioMuseMix) {
								// Which AudioMuse generator fills the queue. Its colour (the AudioMuse
								// palette; for Mood Flow the live mood's hue) is a dot; the words are in
								// the sheet's own colours.
								val generatorLabel = stringResource(
									when (autoplayMode) {
										AutoplayMode.Adaptive -> Res.string.queue_generator_mood_flow
										AutoplayMode.Fingerprint -> Res.string.queue_generator_fingerprint
										else -> Res.string.queue_generator_audiomuse
									}
								)
								val generatorColor = moodCentroid?.takeIf {
									autoplayMode == AutoplayMode.Adaptive && it.size >= 2
								}?.let {
									val hue = ((kotlin.math.atan2(it[1], it[0]) * 180f /
										kotlin.math.PI.toFloat()) + 360f) % 360f
									Color.hsv(hue, 0.45f, 0.85f)
								} ?: Color(0xFFEE7B90)
								Row(
									verticalAlignment = Alignment.CenterVertically,
									horizontalArrangement = Arrangement.spacedBy(6.dp)
								) {
									Box(
										Modifier
											.size(8.dp)
											.background(generatorColor, CircleShape)
									)
									Text(
										generatorLabel,
										style = MaterialTheme.typography.labelLarge,
										color = MaterialTheme.colorScheme.onSurfaceVariant,
										maxLines = 1,
										overflow = TextOverflow.Ellipsis
									)
								}
							}
						}
					}

					// Up Next: the draggable live queue (also the sole content when there's no queue).
					LazyColumn(
						modifier = Modifier
							.padding(horizontal = 12.dp)
							.fillMaxWidth()
							.weight(1f),
						state = draggableState.listState,
						contentPadding = WindowInsets.systemBars
							.only(WindowInsetsSides.Bottom)
							.asPaddingValues(),
						verticalArrangement = if (queue.isNotEmpty())
							Arrangement.spacedBy(ListItemDefaults.SegmentedGap)
						else Arrangement.Center
					) {
						draggableItemsIndexed(
							state = draggableState,
							items = displayQueue,
							key = { _, entry -> entry.uid }
						) { index, entry, isDragging ->
							val song = entry.song
							QueueScreenItem(
								index = index,
								dragKey = entry.uid,
								count = queue.count(),
								song = song,
								isPlaying = playingIndex == index && !playerState.isPaused,
								isSelected = playingIndex == index,
								isDragging = isDragging,
								draggableState = draggableState,
								dragEnabled = true,
								onClick = {
									platformContext.clickSound()
									if (playingIndex != index) {
										// Stay on the queue after jumping so newly auto-queued
										// songs (autoplay top-up) remain visible — the list
										// follows currentIndex via the LaunchedEffect above.
										if (isRemoteActive) hubManager.actJump(index) else player.playAt(index)
									}
								},
								onLongClick = {
									haptic.performHapticFeedback(HapticFeedbackType.LongPress)
									viewModel.selectSong(song)
								},
								onRemove = {
									haptic.performHapticFeedback(HapticFeedbackType.LongPress)
									player.captureQueueUndo(QueueUndoKind.REMOVE)
									// The hub owns the remote queue and broadcasts queueChanged back,
									// which re-syncs the mirror — same split as the reorder commit above.
									if (isRemoteActive) hubManager.actRemoveQueueItem(index)
									else player.removeFromQueue(index)
								},
								isOffline = !isOnline,
								isDownloaded = downloadedSongs.containsKey(song.id)
							)
						}
						if (queue.isEmpty()) {
							item {
								ContentUnavailable(
									icon = Icons.Outlined.PlaylistRemove,
									label = stringResource(Res.string.info_no_queue)
								)
							}
						}
					}
				}
			} else {
				RelatedTab(
					isOnline = isOnline,
					allDownloads = allDownloads,
					onSelect = { viewModel.selectSong(it) }
				)
			}
		}
	}

		SnackbarHost(
			hostState = undoSnackbarState,
			modifier = Modifier
				.align(Alignment.BottomCenter)
				.padding(horizontal = 12.dp, vertical = 16.dp)
		)
	}

	// Long-press sheet for ANY queued song, and for the Related tab's rows. The queue rows used to be the one song list in the app
	// with no per-row actions at all — the only reachable sheet was the playing track's, through the
	// player's own "more" button.
	selectedQueueSong?.let { selected ->
		val download = allDownloads.find { it.songId == selected.id }
		val selectedIsQueued = queue.any { it.id == selected.id }
		SongSheet(
			onDismissRequest = { viewModel.clearSelection() },
			song = selected,
			starred = selectedStarred,
			rating = selectedRating,
			onSetStarred = { viewModel.starSelectedSong(it) },
			onShare = { queueShareId = selected.id },
			onStartRadio = { radioManager.startRadio(selected.id, selected) },
			// Offered for a song that isn't queued, i.e. a Related row; a queued row's sheet still
			// doesn't offer to queue it a second time.
			onPlayNext = if (selectedIsQueued) null else { { player.playNextSingle(selected) } },
			onAddToQueue = if (selectedIsQueued) null else { { player.addToQueueSingle(selected) } },
			onStartJourney = playerState.currentSong?.takeIf {
				radioManager.sonicSimilarityAvailable.value && it.id != selected.id
			}?.let { now -> { radioManager.startJourney(now.id, selected.id) } },
			onTrackInfo = dropUnlessResumed { leaveQueueFor(Screen.SongDetailSheet(selected.id)) },
			onViewAlbum = selected.albumId?.let { albumId ->
				dropUnlessResumed { leaveQueueFor(Screen.CollectionDetail(albumId, "library")) }
			},
			onViewArtist = dropUnlessResumed {
				leaveQueueFor(Screen.ArtistDetail(selected.artistId))
			},
			onViewArtistId = { id -> leaveQueueFor(Screen.ArtistDetail(id)) },
			onAddToPlaylist = { queuePlaylistSong = selected },
			onSetRating = { viewModel.rateSelectedSong(it) },
			downloadStatus = download?.status ?: DownloadStatus.NOT_DOWNLOADED,
			onDownload = { viewModel.downloadSong(selected) },
			onCancelDownload = { viewModel.cancelDownload(selected.id) },
			onDeleteDownload = { viewModel.deleteDownload(selected.id) }
		)
	}

	ShareDialog(
		id = queueShareId,
		onIdClear = { queueShareId = null },
		expiry = null,
		onExpiryChange = { }
	)

	queuePlaylistSong?.let { song ->
		PlaylistUpdateDialog(
			songs = persistentListOf(song),
			onDismissRequest = { queuePlaylistSong = null }
		)
	}
}

/**
 * The queue sheet's "Related" tab: songs derived from the whole queue (and NOT already queued), at
 * most 20 (see [RelatedSongsViewModel]).
 *
 * Drawn as the same list as Up Next — same card, gap and insets — rather than as a generic song list,
 * which tinted its cards with an always-painted swipe background and opened its sheet's pages
 * underneath the queue. Tap plays the song without discarding the queue, + / swipe right adds it,
 * swipe left plays it next, and long-press opens the queue's own sheet via [onSelect].
 */
@Composable
private fun RelatedTab(
	isOnline: Boolean,
	allDownloads: List<DownloadEntity>,
	onSelect: (DomainSong) -> Unit
) {
	val viewModel = koinViewModel<RelatedSongsViewModel>()
	val player = koinInject<MediaPlayerViewModel>()
	val platformContext = LocalPlatformContext.current
	val haptic = LocalHapticFeedback.current

	val songsState by viewModel.songsState.collectAsStateWithLifecycle()
	val songs = songsState.data.orEmpty()

	Box(modifier = Modifier.fillMaxSize()) {
		LazyColumn(
			modifier = Modifier
				.padding(horizontal = 12.dp)
				.fillMaxSize(),
			contentPadding = WindowInsets.systemBars
				.only(WindowInsetsSides.Bottom)
				.asPaddingValues(),
			verticalArrangement = if (songs.isNotEmpty())
				Arrangement.spacedBy(ListItemDefaults.SegmentedGap)
			else Arrangement.Center
		) {
			// Keyed by id, so a song added to the queue animates out and the next one moves up.
			itemsIndexed(songs, key = { _, song -> song.id }) { index, song ->
				val download = allDownloads.find { it.songId == song.id }
				RelatedSongItem(
					modifier = Modifier.animateItem(),
					index = index,
					count = songs.size,
					song = song,
					canPlay = isOnline || download?.status == DownloadStatus.DOWNLOADED,
					downloadStatus = download?.status,
					downloadProgress = download?.progress ?: 0f,
					onClick = {
						platformContext.clickSound()
						player.playNowKeepingQueue(song)
					},
					onLongClick = {
						haptic.performHapticFeedback(HapticFeedbackType.LongPress)
						onSelect(song)
					},
					onAddToQueue = { player.addToQueueSingle(song) },
					onPlayNext = { player.playNextSingle(song) }
				)
			}
			if (songsState is UiState.Success && songs.isEmpty()) {
				item {
					ContentUnavailable(
						icon = Icons.Outlined.Note,
						label = stringResource(Res.string.info_no_songs)
					)
				}
			}
		}

		// Only before the first answer: a refetch keeps the current list up until it lands.
		if (songsState is UiState.Loading) {
			CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
		}
	}
}

private const val DOWNLOAD_NEXT_COUNT = 10

/**
 * A single download control for the queue: one icon (reflecting the whole-queue download state) that
 * opens a menu with "Download whole queue" (download → cancel while in flight → delete once held) and
 * "Download next N". Consolidates what used to be two adjacent icon buttons.
 */
@Composable
private fun QueueDownloadMenuButton(queue: List<DomainSong>, fromIndex: Int) {
	val downloadManager = koinInject<DownloadManager>()
	val scope = rememberCoroutineScope()
	val songIds = remember(queue) { queue.map { it.id }.distinct() }
	val status by downloadManager
		.getCollectionDownloadStatus(songIds)
		.collectAsState(initial = DownloadStatus.NOT_DOWNLOADED)
	var menuOpen by remember { mutableStateOf(false) }

	Box {
		IconButton(enabled = queue.isNotEmpty(), onClick = { menuOpen = true }) {
			when (status) {
				DownloadStatus.DOWNLOADING, DownloadStatus.QUEUED ->
					CircularProgressIndicator(
						modifier = Modifier.size(20.dp),
						strokeWidth = 2.dp
					)

				DownloadStatus.DOWNLOADED -> Icon(
					Icons.Outlined.Delete,
					contentDescription = stringResource(Res.string.action_delete_download),
					modifier = Modifier.size(22.dp)
				)

				else -> Icon(
					Icons.Outlined.Download,
					contentDescription = stringResource(Res.string.action_download_queue),
					modifier = Modifier.size(22.dp)
				)
			}
		}
		paige.navic.ui.components.common.Dropdown(
			expanded = menuOpen,
			onDismissRequest = { menuOpen = false }
		) {
			// Whole-queue action — label + behaviour follow the current state.
			val (wholeLabel, wholeIcon) = when (status) {
				DownloadStatus.DOWNLOADING, DownloadStatus.QUEUED ->
					Res.string.action_cancel to Icons.Outlined.Close
				DownloadStatus.DOWNLOADED ->
					Res.string.action_delete_download to Icons.Outlined.Delete
				else -> Res.string.action_download_queue to Icons.Outlined.Download
			}
			paige.navic.ui.components.common.DropdownItem(
				text = { Text(stringResource(wholeLabel)) },
				onClick = {
					menuOpen = false
					scope.launch {
						when (status) {
							DownloadStatus.NOT_DOWNLOADED, DownloadStatus.FAILED ->
								downloadManager.downloadSongs(
									queue.distinctBy { it.id },
									source = DownloadSource.QUEUE
								)
							DownloadStatus.DOWNLOADING, DownloadStatus.QUEUED ->
								downloadManager.cancelDownloads(songIds)
							DownloadStatus.DOWNLOADED ->
								downloadManager.deleteDownloads(songIds)
						}
					}
				},
				leadingIcon = { Icon(wholeIcon, null) }
			)
			paige.navic.ui.components.common.DropdownItem(
				text = { Text(stringResource(Res.string.action_download_next, DOWNLOAD_NEXT_COUNT)) },
				onClick = {
					menuOpen = false
					scope.launch {
						downloadManager.downloadNextSongs(queue, fromIndex, DOWNLOAD_NEXT_COUNT)
					}
				},
				leadingIcon = { Icon(Icons.Outlined.Download, null) }
			)
		}
	}
}
