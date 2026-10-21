package paige.navic.ui.components.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import navic.composeapp.generated.resources.Res
import navic.composeapp.generated.resources.action_add_to_wishlist
import navic.composeapp.generated.resources.action_allow_mp3_retry
import navic.composeapp.generated.resources.action_cancel
import navic.composeapp.generated.resources.action_dismiss
import navic.composeapp.generated.resources.action_open_album
import navic.composeapp.generated.resources.action_retry
import navic.composeapp.generated.resources.action_try_another_source
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import paige.navic.domain.manager.LbFillEntry
import paige.navic.domain.manager.PreferenceManager
import paige.navic.icons.Icons
import paige.navic.icons.outlined.Close
import kotlin.time.Clock

/*
 * navi-connect P6: a fill's row, as the Downloads page drew it (I-039), now drawn by Source history
 * and Needs attention. Moved out of `DownloadsScreen.kt` without a change of behaviour; the one thing
 * added is [FillRow]'s `allowed`, which a row only lb-bot knows uses to keep Open album alone.
 */

/** The washed list colours the queue's rows use, so a row reads as a card over the cover. */
@Composable
fun fillRowColors() = ListItemDefaults.colors(
	containerColor = SongRowDefaults.containerColor(false),
	supportingContentColor = SongRowDefaults.supportingContentColor(false)
)

/**
 * A one-second clock for the countdowns and "last checked Ns ago". Read only inside the rows, so a
 * tick recomposes the rows and nothing above them.
 */
@Composable
fun rememberFillClock(): State<Long> {
	val now = remember { mutableLongStateOf(Clock.System.now().toEpochMilliseconds()) }
	LaunchedEffect(Unit) {
		while (true) {
			delay(1_000)
			now.longValue = Clock.System.now().toEpochMilliseconds()
		}
	}
	return now
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
fun FillRow(
	fill: LbFillEntry,
	now: State<Long>,
	shapes: ListItemShapes,
	wishlistAvailable: Boolean,
	onAction: (FillButton) -> Unit,
	/**
	 * Which of the vocabulary's buttons this row may draw. Every one on a row this device's ledger
	 * holds; Open album alone on a row only lb-bot knows (P6: nothing here can retry, cancel or
	 * dismiss what the ledger does not hold).
	 */
	allowed: (FillButton) -> Boolean = { true },
	modifier: Modifier = Modifier
) {
	val preferenceManager = koinInject<PreferenceManager>()
	// Every word and every button comes from the shared vocabulary (PROTOCOL §15.2), the table
	// Feishin renders from too.
	val described = describeFill(fill, now.value, wishlistAvailable)
	val view = described.copy(buttons = described.buttons.filter(allowed))
	val failed = fill.settled && !fill.succeeded
	SegmentedListItem(
		onClick = {},
		modifier = modifier,
		shapes = shapes,
		colors = fillRowColors(),
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

val FillButton.label: StringResource
	get() = when (this) {
		FillButton.CANCEL -> Res.string.action_cancel
		FillButton.RETRY -> Res.string.action_retry
		FillButton.TRY_ANOTHER -> Res.string.action_try_another_source
		FillButton.ALLOW_MP3 -> Res.string.action_allow_mp3_retry
		FillButton.WISHLIST -> Res.string.action_add_to_wishlist
		FillButton.OPEN_ALBUM -> Res.string.action_open_album
		FillButton.DISMISS -> Res.string.action_dismiss
	}

