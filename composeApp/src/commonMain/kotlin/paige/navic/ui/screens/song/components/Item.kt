package paige.navic.ui.screens.song.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import paige.navic.ui.components.common.SongRowDefaults
import paige.navic.ui.components.common.SegmentedListItemDefaults.segmentedShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.dropUnlessResumed
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.launch
import navic.composeapp.generated.resources.Res
import navic.composeapp.generated.resources.action_add_to_queue
import navic.composeapp.generated.resources.action_play_next
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import paige.navic.di.LocalNavStack
import paige.navic.domain.manager.RadioManager
import paige.navic.data.database.entities.DownloadEntity
import paige.navic.data.database.entities.DownloadStatus
import paige.navic.di.LocalNavStack
import paige.navic.domain.manager.PreferenceManager
import paige.navic.domain.models.DomainExplicitStatus
import paige.navic.domain.models.DomainSong
import paige.navic.icons.Icons
import paige.navic.icons.outlined.Queue
import paige.navic.icons.outlined.QueuePlayNext
import paige.navic.ui.components.common.MarqueeText
import paige.navic.ui.components.common.LibraryRowCover
import paige.navic.ui.components.common.SegmentedListItem
import paige.navic.ui.components.common.SongRowStatus
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import paige.navic.ui.components.common.SmallRatingRow
import paige.navic.ui.components.common.SwipeToDismissBox
import paige.navic.ui.components.sheets.SongSheet
import paige.navic.ui.navigation.Screen
import paige.navic.ui.screens.playlist.dialogs.PlaylistUpdateDialog
import paige.navic.ui.util.InlineExplicitIcon
import paige.navic.ui.util.buildSongInfoString

@Composable
fun SongListScreenItem(
	modifier: Modifier,
	/** Where the row sits in the list, for its rounded segment's corners. */
	index: Int,
	count: Int,
	song: DomainSong,
	selected: Boolean,
	starred: Boolean,
	rating: Int,
	onSelect: () -> Unit,
	onDeselect: () -> Unit,
	onSetStarred: (starred: Boolean) -> Unit,
	onSetShareId: (String) -> Unit,
	onPlayNext: () -> Unit,
	onAddToQueue: () -> Unit,
	onClick: () -> Unit,
	onSetRating: (Int) -> Unit,
	download: DownloadEntity?,
	onDownload: () -> Unit,
	onCancelDownload: () -> Unit,
	onDeleteDownload: () -> Unit,
) {
	val backStack = LocalNavStack.current
	val radioManagerItem = org.koin.compose.koinInject<RadioManager>()
	val playerItem = org.koin.compose.koinInject<paige.navic.shared.MediaPlayerViewModel>()
	val dismissState = rememberSwipeToDismissBoxState()
	val scope = rememberCoroutineScope()
	var playlistDialogShown by rememberSaveable { mutableStateOf(false) }
	val preferenceManager = koinInject<PreferenceManager>()

	// Rounded segments in a 16 dp gutter, 3 dp apart, as the Library's rows (P5 round 2), instead of
	// screen-wide slabs. A row being swiped rounds all its corners, as the segmented lists elsewhere do.
	val shape = segmentedShapes(index, count, dismissDirection = dismissState.dismissDirection).shape
	// steadyState: one collector per visible row, none of which draws the playhead (as SongRow).
	val playerState by playerItem.steadyState.collectAsStateWithLifecycle()
	val isCurrentTrack = playerState.currentSong?.id == song.id
	SwipeToDismissBox(
		modifier = modifier.padding(horizontal = 16.dp).clip(shape),
		state = dismissState,
		onDismiss = {
			if (it == SwipeToDismissBoxValue.StartToEnd) onAddToQueue()
			if (it == SwipeToDismissBoxValue.EndToStart) onPlayNext()
			scope.launch { dismissState.reset() }
		},
		backgroundContent = {
			Box(
				modifier = Modifier
					.fillMaxSize()
					.clip(MaterialTheme.shapes.extraSmall)
					// Only while a swipe is under way: the row is translucent, and a backing drawn at
					// rest tinted every row (the owner's note: the Songs cards were another colour).
					.then(
						if (dismissState.dismissDirection == SwipeToDismissBoxValue.Settled) Modifier
						else Modifier.background(MaterialTheme.colorScheme.primaryContainer)
					)
					.padding(horizontal = 20.dp),
				contentAlignment = Alignment.CenterEnd
			) {
				when (dismissState.dismissDirection) {
					SwipeToDismissBoxValue.StartToEnd -> {
						Icon(
							imageVector = Icons.Outlined.Queue,
							contentDescription = stringResource(Res.string.action_add_to_queue),
							tint = MaterialTheme.colorScheme.onPrimaryContainer,
							modifier = Modifier.align(Alignment.CenterStart)
						)
					}

					SwipeToDismissBoxValue.EndToStart -> {
						Icon(
							imageVector = Icons.Outlined.QueuePlayNext,
							contentDescription = stringResource(Res.string.action_play_next),
							tint = MaterialTheme.colorScheme.onPrimaryContainer,
							modifier = Modifier.align(Alignment.CenterEnd)
						)
					}

					else -> {}
				}
			}
		}
	) {
		Box {
			// The Library's row (P5 round 2, the owner's note: the Songs chip's cards were another
			// colour): Recents' tone, padding and 50 dp art, with the song rows' status at the end.
			SegmentedListItem(
				onClick = onClick,
				onLongClick = onSelect,
				shapes = segmentedShapes(index, count, dismissDirection = dismissState.dismissDirection),
				colors = ListItemDefaults.colors(
					containerColor = SongRowDefaults.containerColor(isCurrentTrack),
					contentColor = SongRowDefaults.contentColor(isCurrentTrack),
					supportingContentColor = SongRowDefaults.supportingContentColor(isCurrentTrack)
				),
				verticalAlignment = Alignment.CenterVertically,
				contentPadding = SongRowDefaults.ContentPadding,
				content = {
					MarqueeText(
						text = buildAnnotatedString {
							append(song.title)
							if (song.explicitStatus == DomainExplicitStatus.Explicit) {
								append(" ")
								appendInlineContent("InlineExplicitIcon")
							}
						},
						inlineContent = InlineExplicitIcon,
					)
				},
				supportingContent = {
					Column {
						MarqueeText(
							buildSongInfoString(
								song = song,
								onClickArtist = { backStack.add(Screen.ArtistDetail(it)) }
							)
						)
						if (song.userRating != null && song.userRating != 0) {
							SmallRatingRow(rating = song.userRating)
						}
					}
				},
				leadingContent = {
					LibraryRowCover(song.coverArtId, song.title, fallbackAlbumId = song.albumId)
				},
				trailingContent = {
					SongRowStatus(
						isStarred = starred,
						downloadStatus = download?.status,
						downloadProgress = download?.progress ?: 0f,
						isCurrentTrack = isCurrentTrack,
						isPlaying = !playerState.isPaused
					)
				}
			)
			if (selected) {
				SongSheet(
					onDismissRequest = onDeselect,
					song = song,
					starred = starred,
					rating = rating,
					onSetStarred = onSetStarred,
					onShare = { onSetShareId(song.id) },
					onStartRadio = { radioManagerItem.startRadio(song) },
				onStartJourney = playerItem.uiState.value.currentSong?.takeIf {
					radioManagerItem.sonicSimilarityAvailable.value && it.id != song.id
				}?.let { now -> { radioManagerItem.startJourney(now.id, song.id) } },
					onPlayNext = onPlayNext,
					onAddToQueue = onAddToQueue,
					onTrackInfo = dropUnlessResumed {
						backStack.add(Screen.SongDetailScreen(song.id, song.coverArtId))
					},
					onViewAlbum = song.albumId?.let { albumId ->
						dropUnlessResumed {
							backStack.add(
								Screen.CollectionDetail(
									collectionId = albumId,
									tab = "library"
								)
							)
						}
					},
					onAddToPlaylist = {
						playlistDialogShown = true
					},
					onSetRating = onSetRating,
					downloadStatus = download?.status ?: DownloadStatus.NOT_DOWNLOADED,
					onDownload = onDownload,
					onCancelDownload = onCancelDownload,
					onDeleteDownload = onDeleteDownload
				)
			}
		}
	}

	if (playlistDialogShown) {
		PlaylistUpdateDialog(
			songs = persistentListOf(song),
			onDismissRequest = { playlistDialogShown = false }
		)
	}
}
