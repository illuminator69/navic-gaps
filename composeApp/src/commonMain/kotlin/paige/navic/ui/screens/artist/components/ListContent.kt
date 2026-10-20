package paige.navic.ui.screens.artist.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import navic.composeapp.generated.resources.Res
import navic.composeapp.generated.resources.count_artists
import navic.composeapp.generated.resources.info_no_artists
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import paige.navic.domain.models.DomainAlbum
import paige.navic.domain.models.DomainArtist
import paige.navic.domain.models.DomainArtistListType
import paige.navic.domain.models.settings.ListViewMode
import paige.navic.icons.Icons
import paige.navic.icons.outlined.Artist
import paige.navic.ui.components.common.AlphabeticalScroller
import paige.navic.ui.components.common.ContentUnavailable
import paige.navic.ui.components.common.LetterHeader
import paige.navic.ui.components.common.LibraryRowDefaults
import paige.navic.ui.components.common.SegmentedListItemDefaults
import paige.navic.ui.components.layouts.ArtGrid
import paige.navic.ui.core.UiState
import paige.navic.ui.screens.artist.ArtistListScreenGridItem
import paige.navic.ui.util.withoutTop

@Composable
fun ArtistListScreenContent(
	state: UiState<ImmutableList<DomainArtist>>,
	starred: Boolean,
	selectedSorting: DomainArtistListType,
	gridState: LazyGridState,
	/** The page's top bar's, when it collapses on scroll; null in the Library (P5). */
	scrollConnection: NestedScrollConnection?,
	innerPadding: PaddingValues,
	showCount: Boolean = true,
	selectedArtist: DomainArtist?,
	selectedArtistAlbums: ImmutableList<DomainAlbum>?,
	selectedViewMode: ListViewMode,
	onUpdateSelection: (DomainArtist) -> Unit,
	onClearSelection: () -> Unit,
	onSetStarred: (Boolean) -> Unit,
	onPlayNext: () -> Unit,
	onAddToQueue: () -> Unit,
) {

	val data = state.data.orEmpty()

	val totalArtistCount = data.size

	val grouped = data.groupBy { it.name.firstOrNull()?.uppercaseChar() ?: '#' }
		.toList()
		.sortedBy { it.first }

	val headerIndices = remember(grouped, showCount) {
		var currentIndex = if (showCount && totalArtistCount != 0) 1 else 0
		grouped.map { (letter, artists) ->
			val pos = currentIndex
			currentIndex += artists.size + 1
			letter.toString() to pos
		}.toImmutableList()
	}

	Box {
		ArtGrid(
			modifier = if (scrollConnection != null)
				Modifier.fillMaxSize()
					.nestedScroll(scrollConnection)
			else Modifier.fillMaxSize(),
			state = gridState,
			contentPadding = innerPadding.withoutTop(),
			verticalArrangement = if (grouped.isEmpty()) {
				Arrangement.Center
			} else if (selectedViewMode == ListViewMode.List) {
				// The Library's rows, 3 dp apart (P5 round 2).
				Arrangement.spacedBy(SegmentedListItemDefaults.SegmentedGap)
			} else {
				Arrangement.spacedBy(12.dp)
			},
			selectedViewMode = selectedViewMode
		) {
			if (showCount && totalArtistCount != 0) {
				item(span = { GridItemSpan(maxLineSpan) }) {
					Row(
						// Transparent: these headers sit in the page's cover wash, and an opaque
						// band read as a white bar cut across a coloured background. In the rows'
						// gutter (the owner's note: the text sat against the left edge).
						modifier = Modifier.padding(
							start = if (selectedViewMode == ListViewMode.List) LibraryRowDefaults.Gutter + 4.dp else 0.dp,
							top = 4.dp,
							bottom = 4.dp
						),
						verticalAlignment = Alignment.CenterVertically
					) {
						Text(
							pluralStringResource(
								Res.plurals.count_artists,
								totalArtistCount,
								totalArtistCount
							),
							color = MaterialTheme.colorScheme.onSurfaceVariant
						)
					}
				}
			}
			if (selectedSorting == DomainArtistListType.AlphabeticalByName) {
				grouped.forEach { (letter, artists) ->
					// Sticky over a grid; in place over the rows, where a stuck pill covered the first row's
					// picture (the scrollbar's bubble says the letter while dragging).
					if (selectedViewMode == ListViewMode.Grid) stickyHeader { LetterHeader(letter.toString(), grid = true) }
					else item(span = { GridItemSpan(maxLineSpan) }) { LetterHeader(letter.toString()) }
					itemsIndexed(artists, { _, it -> it.id }) { index, artist ->
						if (selectedViewMode == ListViewMode.Grid) {
							ArtistListScreenGridItem(
								modifier = Modifier.animateItem(),
								tab = "artists",
								artist = artist,
								selected = artist == selectedArtist,
								selectedArtistAlbums = selectedArtistAlbums,
								starred = starred,
								onSelect = { onUpdateSelection(artist) },
								onDeselect = { onClearSelection() },
								onSetStarred = { onSetStarred(it) },
								onPlayNext = onPlayNext,
								onAddToQueue = onAddToQueue
							)
						} else {
							ArtistListScreenListItem(
								modifier = Modifier.animateItem(),
								index = index,
								count = artists.size,
								artist = artist,
								selected = artist == selectedArtist,
								selectedArtistAlbums = selectedArtistAlbums,
								starred = starred,
								onSelect = { onUpdateSelection(artist) },
								onDeselect = { onClearSelection() },
								onSetStarred = { onSetStarred(it) },
								onPlayNext = onPlayNext,
								onAddToQueue = onAddToQueue
							)
						}
					}
				}
			} else {
				itemsIndexed(data, { _, it -> it.id }) { index, artist ->
					if (selectedViewMode == ListViewMode.Grid) {
						ArtistListScreenGridItem(
							modifier = Modifier.animateItem(),
							tab = "artists",
							artist = artist,
							selected = artist == selectedArtist,
							selectedArtistAlbums = selectedArtistAlbums,
							starred = starred,
							onSelect = { onUpdateSelection(artist) },
							onDeselect = { onClearSelection() },
							onSetStarred = { onSetStarred(it) },
							onPlayNext = onPlayNext,
							onAddToQueue = onAddToQueue
						)
					} else {
						ArtistListScreenListItem(
							modifier = Modifier.animateItem(),
							index = index,
							count = data.size,
							artist = artist,
							selected = artist == selectedArtist,
							selectedArtistAlbums = selectedArtistAlbums,
							starred = starred,
							onSelect = { onUpdateSelection(artist) },
							onDeselect = { onClearSelection() },
							onSetStarred = { onSetStarred(it) },
							onPlayNext = onPlayNext,
							onAddToQueue = onAddToQueue
						)
					}
				}
			}

			if (grouped.isEmpty()) {
				item(span = { GridItemSpan(maxLineSpan) }) {
					ContentUnavailable(
						icon = Icons.Outlined.Artist,
						label = stringResource(Res.string.info_no_artists)
					)
				}
			}
		}
		if (selectedSorting == DomainArtistListType.AlphabeticalByName) {
			AlphabeticalScroller(
				state = gridState,
				headers = headerIndices,
				modifier = Modifier.align(Alignment.TopEnd),
				bottomPadding = innerPadding.calculateBottomPadding()
			)
		}
	}
}
