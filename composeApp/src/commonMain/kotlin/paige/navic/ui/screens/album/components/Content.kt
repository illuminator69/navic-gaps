package paige.navic.ui.screens.album.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import navic.composeapp.generated.resources.Res
import navic.composeapp.generated.resources.info_no_albums
import org.jetbrains.compose.resources.stringResource
import paige.navic.domain.models.DomainAlbum
import paige.navic.domain.models.DomainAlbumListType
import paige.navic.domain.models.settings.ListViewMode
import paige.navic.icons.Icons
import paige.navic.icons.outlined.Album
import paige.navic.ui.components.common.ContentUnavailable
import paige.navic.ui.components.common.LetterHeader
import paige.navic.ui.components.layouts.artGridPlaceholder
import paige.navic.ui.core.UiState
import paige.navic.ui.screens.album.components.AlbumListScreenGridItem

fun LazyGridScope.albumListScreenContent(
	state: UiState<List<DomainAlbum>>,
	starred: Boolean,
	selectedSorting: DomainAlbumListType,
	selectedAlbum: DomainAlbum?,
	selectedAlbumRating: Int,
	selectedViewMode: ListViewMode,
	onUpdateSelection: (DomainAlbum) -> Unit,
	onClearSelection: () -> Unit,
	onSetShareId: (String) -> Unit,
	onSetStarred: (Boolean) -> Unit,
	onPlayNext: () -> Unit,
	onAddToQueue: () -> Unit,
	onRateSelectedAlbum: (Int) -> Unit
) {
	val data = state.data.orEmpty()
	if (data.isNotEmpty()) {
		if (selectedSorting == DomainAlbumListType.AlphabeticalByName) {
			val grouped = data.groupBy { it.name?.firstOrNull()?.uppercaseChar() ?: '#' }
				.toList()
				.sortedBy { it.first }

			grouped.forEach { (letter, albums) ->
				// Sticky over a grid; in place over the rows (as the artists' list).
				if (selectedViewMode == ListViewMode.Grid) stickyHeader { LetterHeader(letter.toString(), grid = true) }
				else item(span = { GridItemSpan(maxLineSpan) }) { LetterHeader(letter.toString()) }
				itemsIndexed(albums, { _, it -> it.id }) { index, album ->
					if (selectedViewMode == ListViewMode.Grid) {
						AlbumListScreenGridItem(
							modifier = Modifier.animateItem(),
							tab = "albums",
							album = album,
							selected = album == selectedAlbum,
							starred = starred,
							onSelect = { onUpdateSelection(album) },
							onDeselect = { onClearSelection() },
							onSetStarred = { onSetStarred(it) },
							onSetShareId = onSetShareId,
							onPlayNext = onPlayNext,
							onAddToQueue = onAddToQueue,
							rating = selectedAlbumRating,
							onSetRating = onRateSelectedAlbum
						)
					} else {
						AlbumListScreenListItem(
							modifier = Modifier.animateItem(),
							index = index,
							count = albums.size,
							album = album,
							selected = album == selectedAlbum,
							starred = starred,
							onSelect = { onUpdateSelection(album) },
							onDeselect = { onClearSelection() },
							onSetStarred = { onSetStarred(it) },
							onSetShareId = onSetShareId,
							onPlayNext = onPlayNext,
							onAddToQueue = onAddToQueue,
							rating = selectedAlbumRating,
							onSetRating = onRateSelectedAlbum
						)
					}
				}
			}
		} else {
			itemsIndexed(data, { _, it -> it.id }) { index, album ->
				if (selectedViewMode == ListViewMode.Grid) {
					AlbumListScreenGridItem(
						modifier = Modifier.animateItem(),
						tab = "albums",
						album = album,
						selected = album == selectedAlbum,
						starred = starred,
						onSelect = { onUpdateSelection(album) },
						onDeselect = { onClearSelection() },
						onSetStarred = { onSetStarred(it) },
						onSetShareId = onSetShareId,
						onPlayNext = onPlayNext,
						onAddToQueue = onAddToQueue,
						rating = selectedAlbumRating,
						onSetRating = onRateSelectedAlbum
					)
				} else {
					AlbumListScreenListItem(
						modifier = Modifier.animateItem(),
						index = index,
						count = data.size,
						album = album,
						selected = album == selectedAlbum,
						starred = starred,
						onSelect = { onUpdateSelection(album) },
						onDeselect = { onClearSelection() },
						onSetStarred = { onSetStarred(it) },
						onSetShareId = onSetShareId,
						onPlayNext = onPlayNext,
						onAddToQueue = onAddToQueue,
						rating = selectedAlbumRating,
						onSetRating = onRateSelectedAlbum
					)
				}
			}
		}
	} else {
		when (state) {
			is UiState.Loading -> {
				artGridPlaceholder(viewMode = selectedViewMode)
			}

			else -> {
				item(span = { GridItemSpan(maxLineSpan) }) {
					ContentUnavailable(
						icon = Icons.Outlined.Album,
						label = stringResource(Res.string.info_no_albums)
					)
				}
			}
		}
	}
}
