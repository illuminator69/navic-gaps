package paige.navic.ui.screens.playlist.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.collections.immutable.toImmutableList
import navic.composeapp.generated.resources.Res
import navic.composeapp.generated.resources.action_cancel
import navic.composeapp.generated.resources.action_save
import navic.composeapp.generated.resources.auto_download_budget
import navic.composeapp.generated.resources.auto_download_delete_files
import navic.composeapp.generated.resources.auto_download_delete_files_hint
import navic.composeapp.generated.resources.auto_download_format
import navic.composeapp.generated.resources.auto_download_format_original
import navic.composeapp.generated.resources.auto_download_mode
import navic.composeapp.generated.resources.auto_download_mode_off
import navic.composeapp.generated.resources.auto_download_mode_permanent
import navic.composeapp.generated.resources.auto_download_mode_rolling
import navic.composeapp.generated.resources.auto_download_quality
import navic.composeapp.generated.resources.auto_download_quality_original
import navic.composeapp.generated.resources.auto_download_rolling_limit
import navic.composeapp.generated.resources.option_download_kbps
import navic.composeapp.generated.resources.title_auto_download
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import paige.navic.domain.manager.PlaylistDownloadManager
import paige.navic.domain.manager.PlaylistDownloadPolicy
import paige.navic.icons.Icons
import paige.navic.icons.outlined.Download
import paige.navic.ui.components.common.SegmentedListButton
import paige.navic.ui.components.common.SegmentedListButtonDefaults
import paige.navic.ui.components.common.SegmentedListItemDefaults
import paige.navic.ui.components.dialogs.FormDialog
import paige.navic.ui.screens.settings.components.SettingsChoiceItem
import paige.navic.ui.screens.settings.components.SettingsToggleItem

private val QUALITIES = listOf(0, 320, 192, 128)
private val FORMATS = listOf("", "opus", "mp3")
// Decimal GB, matching how storage sizes are shown to users elsewhere.
private const val BYTES_PER_GB = 1_000_000_000.0

/** Off / keep everything / rolling cache. An enum-ish index kept out of the UI. */
private const val MODE_OFF = 0
private const val MODE_PERMANENT = 1
private const val MODE_ROLLING = 2

/**
 * Per-playlist auto-download settings: off / everything / rolling cache, with
 * transcode quality and format for the files it keeps. Pairs with server-side
 * smart playlists for self-refreshing offline mixes.
 *
 * **Built on rows, not on segmented buttons, and that is the fix rather than a
 * restyle.** It used to stack three `SingleChoiceSegmentedButtonRow`s whose labels
 * were plain `Text` with no `maxLines` and no overflow. Material sizes every
 * segment to the widest label and `SegmentedButton` additionally reserves room for
 * a selection checkmark *on top of* the label — so one long word ("Permanent",
 * "Original") set the width of the whole row, and four of them overflowed the
 * dialog and came out squeezed. That is the "text offsetting the button sizes".
 *
 * `SettingSelectionRow` is a full-width row with a dropdown, so a label's length
 * cannot affect the layout at all. It is also the idiom the smart-playlist editor
 * next door already uses, which is where a user arrives from.
 */
@Composable
fun PlaylistDownloadDialog(
	playlistId: String,
	playlistName: String,
	onDismissRequest: () -> Unit
) {
	val manager = koinInject<PlaylistDownloadManager>()
	val policies by manager.policies.collectAsState()
	val existing = policies[playlistId]

	var mode by remember {
		mutableStateOf(
			when (existing?.mode) {
				PlaylistDownloadPolicy.MODE_PERMANENT -> MODE_PERMANENT
				PlaylistDownloadPolicy.MODE_ROLLING -> MODE_ROLLING
				else -> MODE_OFF
			}
		)
	}
	var rollingLimit by remember { mutableStateOf((existing?.rollingLimit ?: 50).toString()) }
	var budgetGb by remember {
		mutableStateOf(
			existing?.budgetBytes
				?.takeIf { it > 0 }
				?.let { (it.toDouble() / BYTES_PER_GB).toString() }
				?: ""
		)
	}
	var quality by remember {
		mutableStateOf(QUALITIES.getOrElse(QUALITIES.indexOf(existing?.maxBitRate ?: 0)) { 0 })
	}
	var format by remember {
		mutableStateOf(FORMATS.firstOrNull { it == (existing?.format ?: "") } ?: "")
	}
	var deleteOnDisable by remember { mutableStateOf(true) }

	val originalQuality = stringResource(Res.string.auto_download_quality_original)
	val originalFormat = stringResource(Res.string.auto_download_format_original)
	val modeLabels = mapOf(
		MODE_OFF to stringResource(Res.string.auto_download_mode_off),
		MODE_PERMANENT to stringResource(Res.string.auto_download_mode_permanent),
		MODE_ROLLING to stringResource(Res.string.auto_download_mode_rolling)
	)

	val save = {
		if (mode == MODE_OFF) {
			manager.removePolicy(playlistId, deleteDownloads = deleteOnDisable)
		} else {
			manager.setPolicy(
				PlaylistDownloadPolicy(
					playlistId = playlistId,
					playlistName = playlistName,
					mode = if (mode == MODE_PERMANENT) {
						PlaylistDownloadPolicy.MODE_PERMANENT
					} else {
						PlaylistDownloadPolicy.MODE_ROLLING
					},
					rollingLimit = rollingLimit.toIntOrNull()?.coerceAtLeast(1) ?: 50,
					budgetBytes = budgetGb.trim().toDoubleOrNull()
						?.takeIf { it > 0 }
						?.let { (it * BYTES_PER_GB).toLong() }
						?: 0L,
					maxBitRate = quality,
					format = format
				)
			)
		}
		onDismissRequest()
	}

	// Upstream's dialog (FormDialog: icon, title, content, stacked segmented buttons) holding
	// upstream's settings rows. It was an AlertDialog of the Form rows upstream removed, with
	// text buttons, and a body that did not scroll: with the keyboard up in landscape the
	// rolling-cache fields ran off the dialog.
	FormDialog(
		onDismissRequest = onDismissRequest,
		icon = { Icon(Icons.Outlined.Download, contentDescription = null) },
		title = { Text(stringResource(Res.string.title_auto_download)) },
		buttons = {
			SegmentedListButton(
				modifier = Modifier.fillMaxWidth(),
				onClick = save,
				colors = SegmentedListButtonDefaults.primaryColors(),
				shapes = SegmentedListButtonDefaults.shapes(index = 0, count = 2)
			) {
				Text(stringResource(Res.string.action_save))
			}
			SegmentedListButton(
				modifier = Modifier.fillMaxWidth(),
				onClick = onDismissRequest,
				shapes = SegmentedListButtonDefaults.shapes(index = 1, count = 2)
			) {
				Text(stringResource(Res.string.action_cancel))
			}
		}
	) {
		Column(
			modifier = Modifier
				.fillMaxWidth()
				.heightIn(max = 420.dp)
				.verticalScroll(rememberScrollState()),
			verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)
		) {
			Text(
				playlistName,
				style = MaterialTheme.typography.bodyMedium,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
				modifier = Modifier.padding(bottom = 8.dp)
			)
			val rows = when {
				mode == MODE_ROLLING -> 3
				mode != MODE_OFF -> 3
				existing != null -> 2
				else -> 1
			}
			SettingsChoiceItem(
				choices = listOf(MODE_OFF, MODE_PERMANENT, MODE_ROLLING).toImmutableList(),
				selectedChoice = mode,
				onChoiceSelected = { mode = it },
				label = { modeLabels.getValue(it) },
				content = { Text(stringResource(Res.string.auto_download_mode)) },
				shapes = SegmentedListItemDefaults.segmentedShapes(index = 0, count = rows)
			)
			if (mode != MODE_OFF) {
				SettingsChoiceItem(
					choices = QUALITIES.toImmutableList(),
					selectedChoice = quality,
					onChoiceSelected = { quality = it },
					label = {
						if (it == 0) originalQuality
						else stringResource(Res.string.option_download_kbps, it)
					},
					content = { Text(stringResource(Res.string.auto_download_quality)) },
					shapes = SegmentedListItemDefaults.segmentedShapes(index = 1, count = rows)
				)
				SettingsChoiceItem(
					choices = FORMATS.toImmutableList(),
					selectedChoice = format,
					onChoiceSelected = { format = it },
					label = { it.ifBlank { originalFormat } },
					content = { Text(stringResource(Res.string.auto_download_format)) },
					shapes = SegmentedListItemDefaults.segmentedShapes(index = 2, count = rows)
				)
			} else if (existing != null) {
				SettingsToggleItem(
					checked = deleteOnDisable,
					onCheckedChange = { deleteOnDisable = it },
					content = { Text(stringResource(Res.string.auto_download_delete_files)) },
					supportingContent = {
						Text(stringResource(Res.string.auto_download_delete_files_hint))
					},
					shapes = SegmentedListItemDefaults.segmentedShapes(index = 1, count = rows)
				)
			}
			if (mode == MODE_ROLLING) {
				// Number keyboards and validation: a typo used to become fifty songs silently.
				OutlinedTextField(
					value = rollingLimit,
					onValueChange = { rollingLimit = it.filter(Char::isDigit) },
					label = { Text(stringResource(Res.string.auto_download_rolling_limit)) },
					singleLine = true,
					keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
					modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
				)
				OutlinedTextField(
					value = budgetGb,
					onValueChange = { budgetGb = it },
					label = { Text(stringResource(Res.string.auto_download_budget)) },
					singleLine = true,
					keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
					modifier = Modifier.fillMaxWidth()
				)
			}
		}
	}
}
