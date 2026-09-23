package paige.navic.ui.screens.collection.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.capsule.ContinuousCapsule
import kotlinx.coroutines.launch
import navic.composeapp.generated.resources.Res
import navic.composeapp.generated.resources.action_cancel
import navic.composeapp.generated.resources.auto_download_ellipsis
import navic.composeapp.generated.resources.download_now
import navic.composeapp.generated.resources.action_delete_download
import navic.composeapp.generated.resources.action_play
import navic.composeapp.generated.resources.action_shuffle
import navic.composeapp.generated.resources.info_download_failed
import navic.composeapp.generated.resources.notice_deleted_download
import navic.composeapp.generated.resources.notice_download_started
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import paige.navic.data.database.entities.DownloadStatus
import paige.navic.domain.manager.DownloadManager
import paige.navic.domain.manager.SnackBarManager
import paige.navic.domain.models.DomainSongCollection
import paige.navic.icons.Icons
import paige.navic.icons.filled.Play
import paige.navic.icons.outlined.Close
import paige.navic.icons.outlined.Delete
import paige.navic.icons.outlined.Download
import paige.navic.icons.outlined.Sort
import paige.navic.icons.outlined.DownloadOff
import paige.navic.icons.outlined.Shuffle
import paige.navic.shared.MediaPlayerViewModel
import paige.navic.ui.components.common.Dropdown
import paige.navic.ui.components.common.DropdownItem
import paige.navic.ui.theme.defaultFont

@Composable
fun CollectionDetailScreenHeadingRowButtons(
	collection: DomainSongCollection,
	/**
	 * Opens the auto-download settings, on a playlist. Null on an album, which has
	 * no policy to set — and null is also what keeps the button a single tap there
	 * rather than a menu with one item in it.
	 *
	 * This exists because the auto-download dialog was reachable from exactly one
	 * place: the overflow menu, two taps in, next to Share and Add to playlist. The
	 * download button beside Play is where a user looks for it.
	 */
	onAutoDownload: (() -> Unit)? = null
) {
	val player = koinInject<MediaPlayerViewModel>()
	val snackBarManager = koinInject<SnackBarManager>()
	val downloadManager = koinInject<DownloadManager>()
	val scope = rememberCoroutineScope()

	val downloadStatus by downloadManager
		.getCollectionDownloadStatus(collection.songs.map { it.id })
		.collectAsState(initial = DownloadStatus.NOT_DOWNLOADED)

	var downloadMenuOpen by remember { mutableStateOf(false) }

	// The one-shot action the button has always had, pulled out so the menu item
	// and the direct tap cannot drift apart.
	val runDownloadAction: () -> Unit = {
		scope.launch {
			when (downloadStatus) {
				DownloadStatus.NOT_DOWNLOADED, DownloadStatus.FAILED -> {
					downloadManager.downloadCollection(collection)
					snackBarManager.notify(Res.string.notice_download_started)
				}
				// Queued work is cancellable exactly like an in-flight transfer.
				DownloadStatus.DOWNLOADING, DownloadStatus.QUEUED -> {
					downloadManager.cancelCollectionDownload(collection)
				}

				DownloadStatus.DOWNLOADED -> {
					downloadManager.deleteDownloadedCollection(collection)
					snackBarManager.notify(Res.string.notice_deleted_download)
				}
			}
		}
		Unit
	}

	Row(
		modifier = Modifier.padding(horizontal = 31.dp, vertical = 10.dp),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(
			10.dp,
			alignment = Alignment.CenterHorizontally
		)
	) {
		val buttonShape = ContinuousCapsule
		val buttonHeight = 44.dp
		// Translucent glass fill so the secondary controls read as frosted pills floating
		// over the ambient wash (cover-tinted under the detail scheme), matching the mockup.
		val glassColors = ButtonDefaults.outlinedButtonColors(
			containerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.5f)
		)
		OutlinedButton(
			modifier = Modifier.size(width = 52.dp, height = buttonHeight),
			onClick = {
				player.shufflePlay(collection)
			},
			shape = buttonShape,
			colors = glassColors,
			contentPadding = PaddingValues(0.dp),
			enabled = collection.songs.isNotEmpty()
		) {
			Icon(
				Icons.Outlined.Shuffle,
				contentDescription = stringResource(Res.string.action_shuffle),
				modifier = Modifier.size(24.dp)
			)
		}
		Button(
			modifier = Modifier.weight(1f).height(buttonHeight),
			onClick = {
				player.playNow(collection)
			},
			shape = buttonShape,
			enabled = collection.songs.isNotEmpty()
		) {
			Icon(
				Icons.Filled.Play,
				null,
				modifier = Modifier.size(25.dp).padding(end = 3.dp)
			)
			Text(
				stringResource(Res.string.action_play),
				maxLines = 1,
				autoSize = TextAutoSize.StepBased(
					minFontSize = 1.sp,
					maxFontSize = 15.sp
				),
				fontWeight = FontWeight.SemiBold,
				fontFamily = defaultFont(round = 100f)
			)
		}
		Box {
			OutlinedButton(
				modifier = Modifier.size(width = 52.dp, height = buttonHeight),
				colors = glassColors,
				// On a playlist the button is a menu, because there are two genuinely
				// different things a download icon can mean there: fetch this snapshot
				// now, or keep it offline from now on. On an album there is only one, so
				// it stays a direct tap.
				onClick = {
					if (onAutoDownload != null) downloadMenuOpen = true else runDownloadAction()
				},
				shape = buttonShape,
				enabled = collection.songs.isNotEmpty() ||
					(downloadStatus == DownloadStatus.DOWNLOADED || downloadStatus == DownloadStatus.DOWNLOADING),
				contentPadding = PaddingValues(0.dp)
			) {
				when (downloadStatus) {
					DownloadStatus.DOWNLOADING -> {
						Box(contentAlignment = Alignment.Center) {
							CircularProgressIndicator(
								modifier = Modifier.size(24.dp),
								strokeWidth = 2.dp,
								color = MaterialTheme.colorScheme.primary
							)
							Icon(
								imageVector = Icons.Outlined.Close,
								contentDescription = "Cancel",
								modifier = Modifier.size(12.dp),
								tint = MaterialTheme.colorScheme.primary
							)
						}
					}

					DownloadStatus.DOWNLOADED -> {
						Icon(
							imageVector = Icons.Outlined.Delete,
							contentDescription = stringResource(Res.string.action_delete_download),
							modifier = Modifier.size(24.dp),
							tint = MaterialTheme.colorScheme.primary
						)
					}

					DownloadStatus.FAILED -> {
						Icon(
							imageVector = Icons.Outlined.DownloadOff,
							contentDescription = stringResource(Res.string.info_download_failed),
							modifier = Modifier.size(24.dp),
							tint = MaterialTheme.colorScheme.error
						)
					}

					else -> {
						Icon(
							imageVector = Icons.Outlined.Download,
							contentDescription = null,
							modifier = Modifier.size(24.dp)
						)
					}
				}
			}
			// Anchored inside this Box so the menu opens on the button rather than
			// at the row's origin.
			Dropdown(expanded = downloadMenuOpen, onDismissRequest = { downloadMenuOpen = false }) {
				DropdownItem(
					text = {
						Text(
							stringResource(
								when (downloadStatus) {
									DownloadStatus.DOWNLOADED -> Res.string.action_delete_download
									DownloadStatus.DOWNLOADING, DownloadStatus.QUEUED ->
										Res.string.action_cancel
									else -> Res.string.download_now
								}
							)
						)
					},
					onClick = {
						downloadMenuOpen = false
						runDownloadAction()
					},
					leadingIcon = { Icon(Icons.Outlined.Download, contentDescription = null) }
				)
				DropdownItem(
					text = { Text(stringResource(Res.string.auto_download_ellipsis)) },
					onClick = {
						downloadMenuOpen = false
						onAutoDownload?.invoke()
					},
					leadingIcon = { Icon(Icons.Outlined.Sort, contentDescription = null) }
				)
			}
		}
	}
}
