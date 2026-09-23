package paige.navic.ui.screens.playlist.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ListItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.dropUnlessResumed
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.launch
import navic.composeapp.generated.resources.Res
import navic.composeapp.generated.resources.count_songs
import navic.composeapp.generated.resources.notice_deleted_download
import navic.composeapp.generated.resources.notice_download_started
import org.jetbrains.compose.resources.pluralStringResource
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import org.koin.compose.koinInject
import paige.navic.data.database.entities.DownloadStatus
import paige.navic.di.LocalNavStack
import paige.navic.domain.manager.DownloadManager
import paige.navic.domain.manager.PreferenceManager
import paige.navic.domain.manager.SnackBarManager
import paige.navic.domain.models.DomainPlaylist
import paige.navic.ui.components.common.CoverArt
import paige.navic.ui.components.common.MarqueeText
import paige.navic.domain.manager.AlbumModeSmartPlaylists
import paige.navic.domain.manager.NativeApiManager
import paige.navic.domain.models.displayName
import paige.navic.ui.components.sheets.CollectionSheet
import paige.navic.ui.navigation.Screen
import paige.navic.ui.screens.playlist.dialogs.PlaylistDownloadDialog
import paige.navic.ui.screens.playlist.dialogs.PlaylistUpdateDialog
import paige.navic.ui.util.appendBulletPoint

@Composable
fun PlaylistListScreenListItem(
	modifier: Modifier = Modifier,
	playlist: DomainPlaylist,
	selected: Boolean,
	onPlayNext: () -> Unit,
	onAddToQueue: () -> Unit,
	onSelect: () -> Unit,
	onDeselect: () -> Unit,
	onSetShareId: (String) -> Unit,
	onSetDeletionId: (String) -> Unit
) {
	val backStack = LocalNavStack.current
	val preferenceManager = koinInject<PreferenceManager>()
	val snackBarManager = koinInject<SnackBarManager>()
	val scope = rememberCoroutineScope()

	var playlistDialogShown by rememberSaveable { mutableStateOf(false) }
	val downloadManager = koinInject<DownloadManager>()
	val nativeApi = koinInject<NativeApiManager>()
	val albumMode = koinInject<AlbumModeSmartPlaylists>()
	val downloadStatus by downloadManager
		.getCollectionDownloadStatus(playlist.songs.map { it.id })
		.collectAsState(initial = DownloadStatus.NOT_DOWNLOADED)

	Box(modifier) {
		ListItem(
			leadingContent = {
				CoverArt(
					coverArtId = playlist.coverArtId,
					modifier = Modifier.size(50.dp),
					shape = preferenceManager.coverArtShape.decreasedShape
				)
			},
			content = { MarqueeText(playlist.name ?: "[unknown playlist]") },
			supportingContent = {
				MarqueeText(
					text = buildAnnotatedString {
						append(
							pluralStringResource(
								Res.plurals.count_songs,
								playlist.songCount,
								playlist.songCount
							)
						)
						playlist.comment?.let {
							appendBulletPoint()
							append(it)
						}
					}
				)
			},
			onClick = dropUnlessResumed {
				scope.launch {
					backStack.add(Screen.CollectionDetail(playlist.id, ""))
				}
			},
			onLongClick = onSelect
		)
		// One probe per opened sheet, not per row. Fails soft: an unreachable native
		// API hides the edit row rather than offering an edit that cannot load.
		var hasRules by remember(playlist.id) { mutableStateOf(false) }
		var autoDownloadShown by remember { mutableStateOf(false) }
		LaunchedEffect(playlist.id) {
			// An album-mode playlist is an ordinary playlist on the server, so
			// Navidrome rightly answers that it has no rules — its recipe is stored
			// locally. Checked first, and it costs no network call.
			hasRules = albumMode.isAlbumMode(playlist.id) ||
				nativeApi.fetchPlaylistRules(playlist.id).getOrNull() != null
		}
		if (selected) {
			CollectionSheet(
				onDismissRequest = onDeselect,
				collection = playlist,
				onShare = { onSetShareId(playlist.id) },
				onDelete = { onSetDeletionId(playlist.id) },
				onPlayNext = onPlayNext,
				onAddToQueue = onAddToQueue,
				onAddAllToPlaylist = { playlistDialogShown = true },
				downloadStatus = downloadStatus,
				onDownloadAll = {
					scope.launch {
						downloadManager.downloadCollection(playlist)
						snackBarManager.notify(Res.string.notice_download_started)
					}
				},
				onCancelDownloadAll = {
					scope.launch {
						playlist.songs.forEach { downloadManager.cancelDownload(it.id) }
					}
				},
				onDeleteDownloadAll = {
					scope.launch {
						downloadManager.deleteDownloadedCollection(playlist)
						snackBarManager.notify(Res.string.notice_deleted_download)
					}
				},
				// Reachable from the list at last: this sheet never passed it, so
				// auto-download could only be set from inside the playlist.
				onAutoDownload = { autoDownloadShown = true },
				// Only a smart playlist has rules, and only Navidrome knows which is
				// which. Probed once when the sheet opens rather than per row — a
				// list of forty playlists must not be forty native-API calls.
				onEditRules = if (hasRules) {
					{ backStack.add(Screen.SmartPlaylistEditor(playlist.id)) }
				} else null
			)
		}

		if (playlistDialogShown) {
			PlaylistUpdateDialog(
				songs = playlist.songs.toPersistentList(),
				onDismissRequest = { playlistDialogShown = false }
			)
		}

		if (autoDownloadShown) {
			PlaylistDownloadDialog(
				playlistId = playlist.id,
				playlistName = playlist.displayName,
				onDismissRequest = { autoDownloadShown = false }
			)
		}
	}
}
