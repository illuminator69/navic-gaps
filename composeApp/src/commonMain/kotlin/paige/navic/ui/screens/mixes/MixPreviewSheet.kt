package paige.navic.ui.screens.mixes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import navic.composeapp.generated.resources.Res
import navic.composeapp.generated.resources.action_add_to_queue
import navic.composeapp.generated.resources.action_play
import navic.composeapp.generated.resources.mix_preview_empty
import navic.composeapp.generated.resources.mix_preview_regenerates
import navic.composeapp.generated.resources.mix_tracks_count
import org.jetbrains.compose.resources.stringResource
import paige.navic.domain.models.DomainSong
import paige.navic.domain.models.Mix
import paige.navic.domain.models.creditText
import paige.navic.ui.components.sheets.ModalBottomSheet
import paige.navic.ui.util.rememberNowPlayingCoverAmbient

/**
 * What a mix would play, before it plays it.
 *
 * A mix has **no fixed contents** — that is the entire feature — so "what is in
 * this" has no answer until the engine has been run. Until now the only way to find
 * out was to tap it, which replaces whatever you were listening to. That is a
 * strange amount of commitment to ask for a thing whose contents you cannot see.
 *
 * Deliberately built on the same shape as `MoodSearchSheet`: that screen had this
 * exact problem first — a generated queue you would want to look at before
 * committing to — and solved it by showing the proposed tracks with Play and Add to
 * queue. Two generated-queue surfaces that behave differently would be the drift
 * this tree keeps paying for.
 *
 * The reason line matters. These tracks are a **sample**, not a tracklist: playing
 * runs the recipe again and gets a different answer, which is exactly what
 * distinguishes a mix from a saved queue. A preview that quietly implied otherwise
 * would teach the wrong model of the feature.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MixPreviewSheet(
	mix: Mix,
	songs: List<DomainSong>,
	loading: Boolean,
	onPlay: () -> Unit,
	onAddToQueue: () -> Unit,
	onDismissRequest: () -> Unit
) {
	ModalBottomSheet(
		onDismissRequest = onDismissRequest,
		ambient = rememberNowPlayingCoverAmbient(),
		sheetTitle = mix.name
	) {
		Column(
			modifier = Modifier.padding(horizontal = 24.dp),
			verticalArrangement = Arrangement.spacedBy(12.dp)
		) {
			Text(
				mixKindLabel(mix),
				style = MaterialTheme.typography.bodyMedium,
				color = MaterialTheme.colorScheme.onSurfaceVariant
			)

			when {
				loading -> Row(
					modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
					horizontalArrangement = Arrangement.Center
				) {
					CircularProgressIndicator()
				}

				songs.isEmpty() -> Text(
					stringResource(Res.string.mix_preview_empty),
					style = MaterialTheme.typography.bodyMedium,
					color = MaterialTheme.colorScheme.onSurfaceVariant
				)

				else -> {
					Text(
						stringResource(Res.string.mix_tracks_count, songs.size),
						style = MaterialTheme.typography.labelLarge,
						color = MaterialTheme.colorScheme.onSurfaceVariant
					)
					Row(
						modifier = Modifier.fillMaxWidth(),
						horizontalArrangement = Arrangement.spacedBy(12.dp),
						verticalAlignment = Alignment.CenterVertically
					) {
						Button(onClick = {
							onPlay()
							onDismissRequest()
						}) { Text(stringResource(Res.string.action_play)) }
						OutlinedButton(onClick = {
							onAddToQueue()
							onDismissRequest()
						}) { Text(stringResource(Res.string.action_add_to_queue)) }
					}
					Text(
						stringResource(Res.string.mix_preview_regenerates),
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant
					)
					LazyColumn(
						modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
						verticalArrangement = Arrangement.spacedBy(4.dp)
					) {
						items(songs, key = { it.id }) { song ->
							Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
								Text(
									song.title,
									style = MaterialTheme.typography.bodyLarge,
									maxLines = 1,
									overflow = TextOverflow.Ellipsis
								)
								Text(
									song.creditText,
									style = MaterialTheme.typography.bodySmall,
									color = MaterialTheme.colorScheme.onSurfaceVariant,
									maxLines = 1,
									overflow = TextOverflow.Ellipsis
								)
							}
						}
					}
				}
			}
			Spacer(Modifier.height(24.dp))
		}
	}
}
