package paige.navic.ui.screens.collection.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.dropUnlessResumed
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.launch
import navic.composeapp.generated.resources.Res
import navic.composeapp.generated.resources.info_similar_albums_because
import navic.composeapp.generated.resources.title_similar_albums
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import paige.navic.data.database.entities.DownloadStatus
import paige.navic.di.LocalNavStack
import paige.navic.domain.manager.DownloadManager
import paige.navic.domain.manager.LbSimilarAlbums
import paige.navic.domain.models.DomainAlbum
import paige.navic.ui.components.common.RemoteCoverArt
import paige.navic.ui.components.layouts.ArtCarousel
import paige.navic.ui.components.layouts.ArtCarouselItem
import paige.navic.ui.components.sheets.CollectionSheet
import paige.navic.ui.navigation.Screen
import paige.navic.ui.screens.playlist.dialogs.PlaylistUpdateDialog

/**
 * "Similar albums": records already in the library by artists lb-bot (ListenBrainz,
 * cross-checked with Last.fm) puts near this one. Rediscovery, not a shopping list.
 *
 * Built as upstream's "More by" row on this screen is: `ArtCarousel` of `ArtCarouselItem`s,
 * the library's own cover for each (from [libraryAlbums], one Room read for the row), a tap to
 * the album and a long press to its sheet. It used to be a third row primitive of its own,
 * with the Cover Art Archive's art for albums the library holds and one-line captions.
 *
 * `because` is always shown: a recommendation names the thing that justifies it. Absent
 * rather than empty when lb-bot has nothing (no hub, an unindexed library, no similar artist).
 */
fun LazyListScope.collectionDetailScreenSimilarAlbumsRow(
	similar: LbSimilarAlbums?,
	libraryAlbums: Map<String, DomainAlbum>,
	selectedAlbum: DomainAlbum?,
	onSetShareId: (String) -> Unit,
	onPlayNext: (() -> Unit)?,
	onAddToQueue: (() -> Unit)?,
	selectedAlbumStarred: Boolean,
	selectedAlbumRating: Int,
	onSetAlbumRating: (Int) -> Unit,
	onSetAlbumStarred: (Boolean) -> Unit,
	onSelect: (DomainAlbum) -> Unit,
	onDeselect: () -> Unit,
) {
	val albums = similar?.albums.orEmpty()
	if (albums.isEmpty()) return
	item {
		val backStack = LocalNavStack.current
		val scope = rememberCoroutineScope()
		val downloadManager = koinInject<DownloadManager>()
		var albumToAddToPlaylist by remember { mutableStateOf<DomainAlbum?>(null) }

		ArtCarousel(
			title = stringResource(Res.string.title_similar_albums),
			items = albums.toImmutableList(),
			because = similar?.because?.takeIf { it.isNotBlank() }
				?.let { stringResource(Res.string.info_similar_albums_because, it) }
		) { row ->
			val album = libraryAlbums[row.albumId]
			ArtCarouselItem(
				coverArtId = album?.coverArtId,
				title = row.title,
				subtitle = row.artist,
				contentDescription = row.title,
				// A row Room does not hold (not synced yet): lb-bot's own cover for it.
				cover = if (album == null) {
					{ interactionSource ->
						RemoteCoverArt(
							url = row.coverUrl,
							contentDescription = row.title,
							interactionSource = interactionSource,
							modifier = Modifier.fillMaxWidth()
						)
					}
				} else null,
				onSelect = { album?.let(onSelect) },
				// Straight to the library album; the external screen only for a row lb-bot
				// could not resolve to one.
				onClick = dropUnlessResumed {
					backStack.add(
						if (row.albumId.isNotBlank()) {
							Screen.CollectionDetail(row.albumId, "similar")
						} else {
							Screen.ExternalAlbum(
								rgid = row.rgid,
								artistName = row.artist,
								title = row.title,
								artistId = row.artistId
							)
						}
					)
				}
			)
			if (album != null && selectedAlbum != null && selectedAlbum == album) {
				val downloadStatus by downloadManager
					.getCollectionDownloadStatus(album.songs.map { it.id })
					.collectAsState(initial = DownloadStatus.NOT_DOWNLOADED)
				CollectionSheet(
					onDismissRequest = onDeselect,
					collection = album,
					onDownloadAll = { scope.launch { downloadManager.downloadCollection(album) } },
					onCancelDownloadAll = {
						scope.launch { album.songs.forEach { downloadManager.cancelDownload(it.id) } }
					},
					onDeleteDownloadAll = {
						scope.launch { downloadManager.deleteDownloadedCollection(album) }
					},
					downloadStatus = downloadStatus,
					onShare = { onSetShareId(album.id) },
					onPlayNext = onPlayNext,
					onAddToQueue = onAddToQueue,
					onAddAllToPlaylist = { albumToAddToPlaylist = album },
					onViewArtist = dropUnlessResumed { backStack.add(Screen.ArtistDetail(album.artistId)) },
					rating = selectedAlbumRating,
					onSetRating = onSetAlbumRating,
					starred = selectedAlbumStarred,
					onSetStarred = { onSetAlbumStarred(!selectedAlbumStarred) }
				)
			}
		}

		albumToAddToPlaylist?.let {
			PlaylistUpdateDialog(
				songs = it.songs.toPersistentList(),
				onDismissRequest = { albumToAddToPlaylist = null }
			)
		}
	}
}
