package paige.navic.ui.components.sheets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kyant.capsule.ContinuousCapsule
import navic.composeapp.generated.resources.Res
import navic.composeapp.generated.resources.action_add_to_queue
import navic.composeapp.generated.resources.action_play
import navic.composeapp.generated.resources.mix_tracks_count
import org.jetbrains.compose.resources.stringResource
import paige.navic.domain.models.DomainSong
import paige.navic.domain.models.creditText
import paige.navic.ui.components.common.CoverArt
import paige.navic.ui.components.common.SegmentedListItem
import paige.navic.ui.components.common.SegmentedListItemDefaults
import paige.navic.ui.components.common.SegmentedListItemDefaults.segmentedShapes
import paige.navic.ui.components.common.SongRowDefaults
import paige.navic.ui.util.CoverAmbient

/**
 * navi-connect: a queue something generated (a mood search, a mix's sample), shown before it
 * plays: what it is over a divider, Play and Add to queue, then the tracks, so the user reviews
 * the matches rather than having them start on a tap.
 *
 * Upstream's tall-sheet layout (the song sheet's): the header and the buttons stay put and ONE
 * list scrolls under them. Both sheets used to be a column with the tracks in a list capped at
 * 360dp, and the title in the sheet's own drag area where nothing showed it. The rows are the
 * queue's, with their covers.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun GeneratedQueueSheet(
	title: String,
	subtitle: String,
	songs: List<DomainSong>,
	loading: Boolean,
	emptyText: String,
	ambient: CoverAmbient?,
	onPlay: () -> Unit,
	onAddToQueue: () -> Unit,
	onDismissRequest: () -> Unit,
	/** A line under the buttons: what the list is a sample OF, where it is one. */
	note: String? = null
) {
	ModalBottomSheet(onDismissRequest = onDismissRequest, ambient = ambient) {
		SheetHeader(title = title, subtitle = subtitle)
		when {
			loading -> Box(
				Modifier.fillMaxWidth().padding(vertical = 32.dp),
				contentAlignment = Alignment.Center
			) { ContainedLoadingIndicator(Modifier.size(48.dp)) }

			songs.isEmpty() -> Text(
				emptyText,
				style = MaterialTheme.typography.bodyMedium,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
				modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)
			)

			else -> {
				Row(
					modifier = Modifier
						.fillMaxWidth()
						.padding(horizontal = 20.dp)
						.padding(top = 12.dp),
					horizontalArrangement = Arrangement.spacedBy(8.dp)
				) {
					Button(
						onClick = {
							onPlay()
							onDismissRequest()
						},
						shape = ContinuousCapsule,
						modifier = Modifier.weight(1f)
					) { Text(stringResource(Res.string.action_play)) }
					OutlinedButton(
						onClick = {
							onAddToQueue()
							onDismissRequest()
						},
						shape = ContinuousCapsule,
						modifier = Modifier.weight(1f)
					) { Text(stringResource(Res.string.action_add_to_queue), maxLines = 1) }
				}
				Text(
					listOfNotNull(
						stringResource(Res.string.mix_tracks_count, songs.size),
						note
					).joinToString(" · "),
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
				)
				LazyColumn(
					modifier = Modifier
						.fillMaxWidth()
						.weight(1f, fill = false)
						.padding(horizontal = 12.dp),
					verticalArrangement = Arrangement.spacedBy(SegmentedListItemDefaults.SegmentedGap)
				) {
					itemsIndexed(songs, key = { index, song -> "$index-${song.id}" }) { index, song ->
						val container = SongRowDefaults.containerColor(false)
						SegmentedListItem(
							onClick = {},
							enabled = false,
							shapes = segmentedShapes(index = index, count = songs.size),
							colors = ListItemDefaults.colors(
								containerColor = container,
								disabledContainerColor = container,
								disabledContentColor = SongRowDefaults.contentColor(false),
								disabledLeadingContentColor = SongRowDefaults.contentColor(false),
								disabledSupportingContentColor = SongRowDefaults.supportingContentColor(false)
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
							content = { Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
							supportingContent = {
								Text(song.creditText, maxLines = 1, overflow = TextOverflow.Ellipsis)
							}
						)
					}
				}
			}
		}
		Box(Modifier.padding(bottom = 16.dp))
	}
}
