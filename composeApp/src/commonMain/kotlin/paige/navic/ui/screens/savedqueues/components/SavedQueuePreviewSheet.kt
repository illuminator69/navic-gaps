package paige.navic.ui.screens.savedqueues.components

import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.OutlinedButton
import org.koin.compose.koinInject
import paige.navic.domain.manager.PreferenceManager
import paige.navic.ui.components.common.SegmentedListItem
import paige.navic.ui.components.common.SegmentedListItemDefaults
import paige.navic.ui.components.common.SegmentedListItemDefaults.segmentedShapes
import paige.navic.ui.components.common.SongRowDefaults
import paige.navic.ui.theme.defaultFont
import paige.navic.ui.util.rememberCoverAmbient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import navic.composeapp.generated.resources.Res
import navic.composeapp.generated.resources.action_close
import navic.composeapp.generated.resources.action_resume_queue
import navic.composeapp.generated.resources.queue_resumes_here
import org.jetbrains.compose.resources.stringResource
import paige.navic.data.database.entities.SavedQueueEntity
import paige.navic.domain.models.DomainSong
import paige.navic.domain.repositories.SavedQueueRepository
import paige.navic.ui.components.common.CoverArt
import paige.navic.ui.components.sheets.ModalBottomSheet
import paige.navic.ui.screens.savedqueues.queueKindLabel
import paige.navic.ui.screens.savedqueues.savedQueueDuration
import paige.navic.ui.screens.savedqueues.savedQueueTitle
import paige.navic.ui.screens.savedqueues.savedQueueCoverArtId
import paige.navic.ui.screens.savedqueues.trackCountLabel
import paige.navic.ui.screens.savedqueues.trackDuration

/**
 * "Preview queue": what's actually in a saved queue, and where resuming it would land, without
 * committing to replacing what's playing. Matches Feishin's preview modal — the track list with the
 * resume row marked, the total runtime, and Resume / Close.
 *
 * Nothing is fetched: the record already carries its songs, so this only has to decode the blob.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SavedQueuePreviewSheet(
	queue: SavedQueueEntity,
	onResume: () -> Unit,
	onDismissRequest: () -> Unit
) {
	val repository = koinInject<SavedQueueRepository>()
	// Decoding is off the main thread's critical path and only happens while the sheet is open.
	val songs by produceState(initialValue = emptyList<DomainSong>(), queue.id) {
		value = repository.decodeQueue(queue)
	}
	val resumeIndex = remember(queue.currentIndex, songs.size) {
		if (songs.isEmpty()) -1 else queue.currentIndex.coerceIn(0, songs.lastIndex)
	}
	val listState = rememberLazyListState()
	// Open ON the resume row rather than at the top: it's the one row the user came here to see.
	LaunchedEffect(resumeIndex) {
		if (resumeIndex > 2) listState.scrollToItem(resumeIndex - 2)
	}

	val preferenceManager = koinInject<PreferenceManager>()
	val coverArtId = savedQueueCoverArtId(queue)
	// Washed in the resume track's cover, as an album's sheet is in its own.
	val ambient = rememberCoverAmbient(coverArtId)

	ModalBottomSheet(onDismissRequest = onDismissRequest, ambient = ambient) {
		// A fixed header over one scrolling list (upstream's tall-sheet layout, as the song
		// sheet's): the list used to be capped at 360dp inside a sheet that did not scroll.
		Row(
			Modifier.fillMaxWidth().padding(horizontal = 20.dp),
			verticalAlignment = Alignment.CenterVertically
		) {
			CoverArt(
				coverArtId = coverArtId,
				modifier = Modifier.size(56.dp),
				shape = preferenceManager.coverArtShape.decreasedShape
			)
			Spacer(Modifier.width(14.dp))
			Column(Modifier.weight(1f)) {
				Text(
					savedQueueTitle(queue),
					style = MaterialTheme.typography.titleLarge,
					fontFamily = defaultFont(round = 100f),
					maxLines = 1,
					overflow = TextOverflow.Ellipsis
				)
				Text(
					"${queueKindLabel(queue.sourceKind)} · ${trackCountLabel(songs.size)}" +
						if (songs.isEmpty()) "" else " · ${savedQueueDuration(songs)}",
					style = MaterialTheme.typography.bodyMedium,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis
				)
			}
		}
		HorizontalDivider(Modifier.padding(horizontal = 8.dp, vertical = 12.dp))

		LazyColumn(
			state = listState,
			modifier = Modifier
				.fillMaxWidth()
				.weight(1f, fill = false)
				.padding(horizontal = 12.dp),
			verticalArrangement = Arrangement.spacedBy(SegmentedListItemDefaults.SegmentedGap)
		) {
			itemsIndexed(songs, key = { i, song -> "$i-${song.id}" }) { i, song ->
				PreviewTrackRow(
					song = song,
					shapes = segmentedShapes(index = i, count = songs.size),
					isResumePoint = i == resumeIndex
				)
			}
		}

		Row(
			Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp),
			horizontalArrangement = Arrangement.spacedBy(8.dp)
		) {
			OutlinedButton(onClick = onDismissRequest, modifier = Modifier.weight(1f)) {
				Text(stringResource(Res.string.action_close))
			}
			Button(onClick = onResume, modifier = Modifier.weight(1f)) {
				Text(stringResource(Res.string.action_resume_queue))
			}
		}
	}
}

/** One track, as the queue draws it; the resume point marked as the queue marks what is playing. */
@Composable
private fun PreviewTrackRow(song: DomainSong, shapes: ListItemShapes, isResumePoint: Boolean) {
	// Inert rows (a preview plays nothing), so Material draws them in their DISABLED colours and
	// ignores `selected`: the resume point's lift has to be the disabled colour of its own row.
	SegmentedListItem(
		onClick = {},
		enabled = false,
		shapes = shapes,
		colors = ListItemDefaults.colors(
			containerColor = SongRowDefaults.containerColor(isResumePoint),
			disabledContainerColor = SongRowDefaults.containerColor(isResumePoint),
			disabledContentColor = SongRowDefaults.contentColor(isResumePoint),
			disabledLeadingContentColor = SongRowDefaults.contentColor(isResumePoint),
			disabledTrailingContentColor = SongRowDefaults.supportingContentColor(isResumePoint),
			disabledSupportingContentColor = SongRowDefaults.supportingContentColor(isResumePoint)
		),
		verticalAlignment = Alignment.CenterVertically,
		contentPadding = SongRowDefaults.ContentPadding,
		leadingContent = {
			CoverArt(
				modifier = Modifier.size(SongRowDefaults.CoverSize),
				coverArtId = song.coverArtId,
				fallbackAlbumId = song.albumId,
				shape = SongRowDefaults.CoverShape
			)
		},
		content = {
			Text(
				song.title,
				fontWeight = if (isResumePoint) FontWeight.SemiBold else null,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis
			)
		},
		supportingContent = {
			Text(
				// "Resumes here" first: last, it was the part a one-line row cut off.
				listOfNotNull(
					stringResource(Res.string.queue_resumes_here).takeIf { isResumePoint },
					song.artistName?.takeIf { it.isNotBlank() },
					song.albumTitle?.takeIf { it.isNotBlank() }
				).joinToString(" · "),
				maxLines = 1,
				overflow = TextOverflow.Ellipsis
			)
		},
		trailingContent = { Text(trackDuration(song), style = MaterialTheme.typography.bodySmall) }
	)
}
