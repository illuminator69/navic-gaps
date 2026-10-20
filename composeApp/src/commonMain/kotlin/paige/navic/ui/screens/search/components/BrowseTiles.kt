package paige.navic.ui.screens.search.components

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import navic.composeapp.generated.resources.Res
import navic.composeapp.generated.resources.search_browse
import navic.composeapp.generated.resources.search_tile_charts
import navic.composeapp.generated.resources.search_tile_charts_line
import navic.composeapp.generated.resources.search_tile_discover_line
import navic.composeapp.generated.resources.search_tile_fresh_line
import navic.composeapp.generated.resources.search_tile_genres_line
import navic.composeapp.generated.resources.search_tile_picks
import navic.composeapp.generated.resources.search_tile_picks_line
import navic.composeapp.generated.resources.title_discover
import navic.composeapp.generated.resources.title_fresh
import navic.composeapp.generated.resources.title_genres
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import paige.navic.ui.components.common.GroupHeader
import paige.navic.ui.screens.search.BrowseTile
import paige.navic.ui.screens.search.PlacedTile
import paige.navic.ui.util.ArrivalGate
import paige.navic.ui.util.arrive

private fun BrowseTile.words(): Pair<StringResource, StringResource> = when (this) {
	BrowseTile.GENRES -> Res.string.title_genres to Res.string.search_tile_genres_line
	BrowseTile.CHARTS -> Res.string.search_tile_charts to Res.string.search_tile_charts_line
	BrowseTile.PICKS -> Res.string.search_tile_picks to Res.string.search_tile_picks_line
	BrowseTile.FRESH -> Res.string.title_fresh to Res.string.search_tile_fresh_line
	BrowseTile.DISCOVER -> Res.string.title_discover to Res.string.search_tile_discover_line
}

/**
 * "Browse" under an empty field (P3): a two-column grid of cards, in the order and with the gates
 * [paige.navic.ui.screens.search.browseTiles] decides. The cards arrive once per visit.
 */
fun LazyGridScope.browseSection(
	tiles: List<PlacedTile>,
	gate: ArrivalGate,
	onOpen: (BrowseTile) -> Unit
) {
	if (tiles.isEmpty()) return
	item(span = { GridItemSpan(maxLineSpan) }, key = "browse-header", contentType = "header") {
		GroupHeader(
			title = stringResource(Res.string.search_browse),
			modifier = Modifier.animateItem().padding(horizontal = 16.dp)
		)
	}
	var column = 0
	tiles.forEachIndexed { index, placed ->
		val left = placed.fullWidth || column == 0
		val right = placed.fullWidth || column == 1
		column = if (placed.fullWidth) 0 else (column + 1) % 2
		item(
			span = { GridItemSpan(if (placed.fullWidth) maxLineSpan else 1) },
			key = "browse-${placed.tile.name}",
			contentType = "tile"
		) {
			val (title, line) = placed.tile.words()
			BrowseTileCard(
				title = stringResource(title),
				line = stringResource(line),
				onClick = { onOpen(placed.tile) },
				modifier = Modifier
					.animateItem()
					.padding(start = if (left) 16.dp else 6.dp, end = if (right) 16.dp else 6.dp, top = 6.dp, bottom = 6.dp)
					.arrive(gate, "browse-${placed.tile.name}", index)
			)
		}
	}
}
