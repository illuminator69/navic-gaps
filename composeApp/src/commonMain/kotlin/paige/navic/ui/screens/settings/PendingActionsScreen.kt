package paige.navic.ui.screens.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import navic.composeapp.generated.resources.Res
import navic.composeapp.generated.resources.action_remove_pending_action
import navic.composeapp.generated.resources.action_sync_now
import navic.composeapp.generated.resources.info_pending_action_queued
import navic.composeapp.generated.resources.info_pending_actions_empty
import navic.composeapp.generated.resources.info_pending_actions_hint
import navic.composeapp.generated.resources.info_pending_actions_offline
import navic.composeapp.generated.resources.option_pending_actions
import navic.composeapp.generated.resources.pending_action_delete_playlist
import navic.composeapp.generated.resources.pending_action_rate
import navic.composeapp.generated.resources.pending_action_scrobble
import navic.composeapp.generated.resources.pending_action_star
import navic.composeapp.generated.resources.pending_action_unstar
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import paige.navic.domain.manager.PendingActionKind
import paige.navic.icons.Icons
import paige.navic.icons.outlined.Delete
import paige.navic.ui.components.common.FormRow
import paige.navic.ui.components.layouts.NestedTopBar
import paige.navic.ui.screens.settings.viewmodels.PendingActionRow
import paige.navic.ui.screens.settings.viewmodels.PendingActionsViewModel

/**
 * What is still waiting to be sent to the server (I-020). The Data & Storage row used to be a
 * bare count; this is the queue itself — each action in words, the item it is about, and when it
 * was queued — with one row removable at a time and a way to send them now.
 *
 * "Clear pending sync actions" stays where it was, in Data & Storage's danger zone.
 */
@Composable
fun PendingActionsScreen() {
	val viewModel = koinViewModel<PendingActionsViewModel>()
	val rows by viewModel.rows.collectAsStateWithLifecycle()
	val isOnline by viewModel.isOnline.collectAsStateWithLifecycle()

	Scaffold(
		topBar = { NestedTopBar({ Text(stringResource(Res.string.option_pending_actions)) }) }
	) { innerPadding ->
		LazyColumn(
			modifier = Modifier.fillMaxSize().padding(top = innerPadding.calculateTopPadding()),
			contentPadding = PaddingValues(
				start = 16.dp,
				end = 16.dp,
				top = 8.dp,
				bottom = innerPadding.calculateBottomPadding() + 32.dp
			)
		) {
			item(key = "hint") {
				Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
					Text(
						stringResource(Res.string.info_pending_actions_hint),
						style = MaterialTheme.typography.bodyMedium,
						color = MaterialTheme.colorScheme.onSurfaceVariant
					)
					val queue = rows
					if (!queue.isNullOrEmpty()) {
						// Sending needs the server. Offline the button would do nothing, so it
						// says why instead of looking pressable.
						if (isOnline) {
							Button(
								onClick = { viewModel.syncNow() },
								modifier = Modifier.padding(top = 12.dp)
							) { Text(stringResource(Res.string.action_sync_now)) }
						} else {
							Text(
								stringResource(Res.string.info_pending_actions_offline),
								style = MaterialTheme.typography.bodySmall,
								color = MaterialTheme.colorScheme.onSurfaceVariant,
								modifier = Modifier.padding(top = 12.dp)
							)
						}
					}
				}
			}

			val queue = rows
			when {
				// Room has not answered yet. Brief, and genuinely a read in flight.
				queue == null -> item(key = "reading") {
					Box(
						Modifier.fillMaxWidth().padding(vertical = 48.dp),
						contentAlignment = Alignment.Center
					) { CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp) }
				}

				queue.isEmpty() -> item(key = "empty") {
					Text(
						stringResource(Res.string.info_pending_actions_empty),
						style = MaterialTheme.typography.bodyMedium,
						modifier = Modifier.padding(vertical = 24.dp)
					)
				}

				else -> items(queue, key = { it.id }) { row ->
					PendingRow(row, onRemove = { viewModel.remove(row.id) })
				}
			}
		}
	}
}

@Composable
private fun PendingRow(row: PendingActionRow, onRemove: () -> Unit) {
	FormRow(modifier = Modifier.padding(bottom = 3.dp)) {
		Column(Modifier.weight(1f)) {
			Text(
				when (val kind = row.kind) {
					PendingActionKind.Scrobble -> stringResource(Res.string.pending_action_scrobble)
					PendingActionKind.Star -> stringResource(Res.string.pending_action_star)
					PendingActionKind.Unstar -> stringResource(Res.string.pending_action_unstar)
					is PendingActionKind.Rate -> stringResource(Res.string.pending_action_rate, kind.stars)
					PendingActionKind.DeletePlaylist ->
						stringResource(Res.string.pending_action_delete_playlist)
				},
				style = MaterialTheme.typography.labelLarge,
				color = MaterialTheme.colorScheme.primary
			)
			Text(row.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
			if (row.queuedAt.isNotBlank()) {
				Text(
					stringResource(Res.string.info_pending_action_queued, row.queuedAt),
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant
				)
			}
		}
		IconButton(onClick = onRemove) {
			Icon(
				Icons.Outlined.Delete,
				contentDescription = stringResource(Res.string.action_remove_pending_action),
				modifier = Modifier.size(20.dp)
			)
		}
	}
}
