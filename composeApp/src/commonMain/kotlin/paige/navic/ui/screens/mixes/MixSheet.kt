package paige.navic.ui.screens.mixes

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
import androidx.compose.ui.unit.dp
import navic.composeapp.generated.resources.Res
import navic.composeapp.generated.resources.action_delete
import navic.composeapp.generated.resources.action_rename
import org.jetbrains.compose.resources.stringResource
import paige.navic.domain.models.Mix
import paige.navic.icons.Icons
import paige.navic.icons.outlined.Badge
import paige.navic.icons.outlined.Delete
import paige.navic.ui.components.common.MarqueeText
import paige.navic.ui.components.sheets.ModalBottomSheet
import paige.navic.ui.util.rememberCoverAmbient

/**
 * What can be done with one mix, as upstream's `CollectionSheet` lays out an album's: its face and
 * name over a divider, then the actions as rows. Opened by a long press on the row (or its "more"
 * button), as every library row's sheet is, rather than the dropdown the list used to carry.
 *
 * What is in a mix is its page's (P5 round 2: a tap opens it), so the sheet holds no preview.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MixSheet(
	mix: Mix,
	onDismissRequest: () -> Unit,
	onRename: () -> Unit,
	onDelete: () -> Unit
) {
	// A stamped seed cover washes the sheet as an album's does; a seedless mix keeps the app's.
	val ambient = rememberCoverAmbient(mix.coverArtId.ifBlank { null })
	val colors = ListItemDefaults.colors(
		containerColor = Color.Transparent,
		headlineColor = ambient.scheme.onSurface,
		leadingIconColor = ambient.scheme.onSurfaceVariant,
		supportingColor = ambient.scheme.onSurfaceVariant,
		trailingIconColor = ambient.scheme.onSurface
	)
	val contentPadding = PaddingValues(horizontal = 16.dp)
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
			leadingContent = { MixArtwork(mix = mix, modifier = Modifier.size(50.dp)) },
			headlineContent = {
				MarqueeText(mix.name, style = MaterialTheme.typography.bodyLarge)
			},
			supportingContent = { Text(mixKindLabel(mix)) },
			colors = colors
		)
		HorizontalDivider(Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
		Column(Modifier.padding(bottom = 8.dp)) {
			ListItem(
				content = { Text(stringResource(Res.string.action_rename)) },
				leadingContent = { Icon(Icons.Outlined.Badge, null) },
				onClick = {
					onDismissRequest()
					onRename()
				},
				colors = colors,
				contentPadding = contentPadding
			)
			ListItem(
				content = { Text(stringResource(Res.string.action_delete)) },
				leadingContent = { Icon(Icons.Outlined.Delete, null) },
				onClick = {
					onDismissRequest()
					onDelete()
				},
				colors = colors,
				contentPadding = contentPadding
			)
		}
	}
}
