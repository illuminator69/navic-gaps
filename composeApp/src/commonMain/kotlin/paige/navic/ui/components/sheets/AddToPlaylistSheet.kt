package paige.navic.ui.components.sheets

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.launch
import navic.composeapp.generated.resources.Res
import navic.composeapp.generated.resources.count_songs
import navic.composeapp.generated.resources.staged_add_to_this_playlist
import navic.composeapp.generated.resources.staged_done
import navic.composeapp.generated.resources.staged_new_playlist
import navic.composeapp.generated.resources.staged_no_playlists
import navic.composeapp.generated.resources.staged_remove_from_this_playlist
import navic.composeapp.generated.resources.staged_save_to_playlist
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import paige.navic.domain.models.DomainSong
import paige.navic.icons.Icons
import paige.navic.icons.outlined.Add
import paige.navic.icons.outlined.Delete
import paige.navic.ui.components.common.CoverArt
import paige.navic.ui.screens.playlist.dialogs.PlaylistCreateDialog
import paige.navic.ui.util.rememberNowPlayingCoverAmbient

/**
 * "Save to playlist" for one [song] (P2 notes, [AddToPlaylistViewModel]): every playlist it can go
 * in with a + that adds it and becomes a bin that takes it out again. Adding does not close the
 * sheet. "New playlist" (bottom left) makes one with the song in it, shown first; "Done" (bottom
 * right) closes. Both stay in view however long the list is: the list scrolls between the header
 * and them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddToPlaylistSheet(song: DomainSong, onDismissRequest: () -> Unit) {
	val viewModel = koinViewModel<AddToPlaylistViewModel>(
		key = "addToPlaylist:${song.id}",
		parameters = { parametersOf(song) }
	)
	val choices by viewModel.choices.collectAsState()
	var creating by remember { mutableStateOf(false) }
	val ambient = rememberNowPlayingCoverAmbient()
	val scope = rememberCoroutineScope()
	val sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
	val close: () -> Unit = {
		scope.launch { sheetState.hide() }.invokeOnCompletion { onDismissRequest() }
	}

	ModalBottomSheet(
		onDismissRequest = onDismissRequest,
		sheetState = sheetState,
		ambient = ambient,
		sheetTitle = stringResource(Res.string.staged_save_to_playlist)
	) {
		SheetHeader(title = stringResource(Res.string.staged_save_to_playlist), subtitle = song.title)
		val list = choices
		Box(Modifier.fillMaxWidth().weight(1f, fill = false)) {
			when {
				list == null -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
					CircularProgressIndicator(Modifier.size(24.dp))
				}

				list.isEmpty() -> Text(
					text = stringResource(Res.string.staged_no_playlists),
					style = MaterialTheme.typography.bodyMedium,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)
				)

				else -> LazyColumn(Modifier.fillMaxWidth()) {
					items(list, key = { it.playlist.id }) { choice ->
						PlaylistChoiceRow(
							choice = choice,
							onAdd = { viewModel.add(choice.playlist.id) },
							onRemove = { viewModel.remove(choice.playlist.id) },
							modifier = Modifier.animateItem()
						)
					}
				}
			}
		}
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.padding(start = 12.dp, end = 20.dp, top = 8.dp, bottom = 12.dp),
			verticalAlignment = Alignment.CenterVertically
		) {
			TextButton(onClick = { creating = true }) {
				Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
				Spacer(Modifier.width(6.dp))
				Text(stringResource(Res.string.staged_new_playlist))
			}
			Spacer(Modifier.weight(1f))
			Button(onClick = close) {
				Text(stringResource(Res.string.staged_done))
			}
		}
	}

	if (creating) {
		PlaylistCreateDialog(
			onDismissRequest = { creating = false },
			onRefresh = {},
			songs = persistentListOf(song),
			navigateAfterwards = false,
			onCreated = { viewModel.onCreated(it) }
		)
	}
}

@Composable
private fun PlaylistChoiceRow(
	choice: PlaylistChoice,
	onAdd: () -> Unit,
	onRemove: () -> Unit,
	modifier: Modifier = Modifier
) {
	val name = choice.playlist.name.orEmpty()
	Row(
		modifier = modifier
			.fillMaxWidth()
			.heightIn(min = 64.dp)
			.padding(start = 20.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(12.dp)
	) {
		CoverArt(
			coverArtId = choice.playlist.coverArtId,
			modifier = Modifier.size(48.dp),
			shape = RoundedCornerShape(8.dp)
		)
		Column(Modifier.weight(1f)) {
			Text(name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
			Text(
				pluralStringResource(Res.plurals.count_songs, choice.playlist.songCount, choice.playlist.songCount),
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
				maxLines = 1
			)
		}
		AnimatedContent(
			targetState = when {
				choice.busy -> 0
				choice.holdsSong -> 2
				else -> 1
			},
			transitionSpec = { (fadeIn() + scaleIn(initialScale = 0.8f)) togetherWith (fadeOut() + scaleOut(targetScale = 0.8f)) },
			label = "playlistChoice"
		) { shown ->
			Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
				when (shown) {
					0 -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
					2 -> IconButton(onClick = onRemove) {
						Icon(Icons.Outlined.Delete, contentDescription = stringResource(Res.string.staged_remove_from_this_playlist, name))
					}

					else -> IconButton(onClick = onAdd) {
						Icon(Icons.Outlined.Add, contentDescription = stringResource(Res.string.staged_add_to_this_playlist, name))
					}
				}
			}
		}
	}
}
