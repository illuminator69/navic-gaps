package paige.navic.ui.screens.savedqueues.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import navic.composeapp.generated.resources.Res
import navic.composeapp.generated.resources.action_delete_queue
import navic.composeapp.generated.resources.action_preview_queue
import navic.composeapp.generated.resources.action_rename
import navic.composeapp.generated.resources.action_restore_queue
import navic.composeapp.generated.resources.action_resume_queue
import navic.composeapp.generated.resources.action_save_as_playlist
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import paige.navic.data.database.entities.SavedQueueEntity
import paige.navic.domain.manager.PreferenceManager
import paige.navic.icons.Icons
import paige.navic.icons.filled.Play
import paige.navic.icons.outlined.Badge
import paige.navic.icons.outlined.Delete
import paige.navic.icons.outlined.List
import paige.navic.icons.outlined.PlaylistAdd
import paige.navic.icons.outlined.Queue
import paige.navic.ui.components.common.CoverArt
import paige.navic.ui.components.common.MarqueeText
import paige.navic.ui.components.sheets.ModalBottomSheet
import paige.navic.ui.screens.savedqueues.savedQueueCoverArtId
import paige.navic.ui.screens.savedqueues.savedQueueTitle
import paige.navic.ui.util.rememberCoverAmbient

/**
 * What can be done with one saved queue, laid out as upstream's `CollectionSheet` lays out an
 * album's: the art of the track it resumes on and its name over a divider, then the actions as
 * rows, washed in that cover's colours. A long press on the row (or its "more" button) opens it,
 * in place of the dropdown the list used to carry.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SavedQueueSheet(
	queue: SavedQueueEntity,
	subtitle: String,
	onDismissRequest: () -> Unit,
	onRestore: () -> Unit,
	onResume: () -> Unit,
	onPreview: () -> Unit,
	/** Null where the page has no dialog for it (the library home's card). */
	onSaveAsPlaylist: (() -> Unit)? = null,
	/** Null where the page has no dialog for it (the library home's card). */
	onRename: (() -> Unit)? = null,
	onDelete: () -> Unit
) {
	val preferenceManager = koinInject<PreferenceManager>()
	val coverArtId = savedQueueCoverArtId(queue)
	val ambient = rememberCoverAmbient(coverArtId)
	val colors = ListItemDefaults.colors(
		containerColor = Color.Transparent,
		headlineColor = ambient.scheme.onSurface,
		leadingIconColor = ambient.scheme.onSurfaceVariant,
		supportingColor = ambient.scheme.onSurfaceVariant,
		trailingIconColor = ambient.scheme.onSurface
	)
	val contentPadding = PaddingValues(horizontal = 16.dp)

	@Composable
	fun Action(label: StringResource, icon: ImageVector, run: () -> Unit) {
		ListItem(
			content = { Text(stringResource(label)) },
			leadingContent = { Icon(icon, null) },
			onClick = {
				onDismissRequest()
				run()
			},
			colors = colors,
			contentPadding = contentPadding
		)
	}

	ModalBottomSheet(
		onDismissRequest = onDismissRequest,
		dragHandle = null,
		ambient = ambient,
		contentWindowInsets = {
			BottomSheetDefaults.modalWindowInsets.add(WindowInsets(left = 8.dp, right = 8.dp))
		}
	) {
		Spacer(Modifier.height(16.dp))
		ListItem(
			leadingContent = {
				CoverArt(
					coverArtId = coverArtId,
					modifier = Modifier.size(50.dp),
					shape = preferenceManager.coverArtShape.decreasedShape
				)
			},
			headlineContent = {
				MarqueeText(savedQueueTitle(queue), style = MaterialTheme.typography.bodyLarge)
			},
			supportingContent = { MarqueeText(subtitle) },
			colors = colors
		)
		HorizontalDivider(Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
		Column(Modifier.padding(bottom = 8.dp)) {
			Action(Res.string.action_restore_queue, Icons.Outlined.Queue, onRestore)
			Action(Res.string.action_resume_queue, Icons.Filled.Play, onResume)
			Action(Res.string.action_preview_queue, Icons.Outlined.List, onPreview)
			onSaveAsPlaylist?.let { Action(Res.string.action_save_as_playlist, Icons.Outlined.PlaylistAdd, it) }
			onRename?.let { Action(Res.string.action_rename, Icons.Outlined.Badge, it) }
			Action(Res.string.action_delete_queue, Icons.Outlined.Delete, onDelete)
		}
	}
}
