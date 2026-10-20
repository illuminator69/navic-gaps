package paige.navic.ui.screens.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import navic.composeapp.generated.resources.Res
import navic.composeapp.generated.resources.action_retry
import navic.composeapp.generated.resources.browse_charts_line
import navic.composeapp.generated.resources.browse_empty
import navic.composeapp.generated.resources.browse_failed
import navic.composeapp.generated.resources.browse_pick_genre
import navic.composeapp.generated.resources.browse_picks_line
import navic.composeapp.generated.resources.browse_rank
import navic.composeapp.generated.resources.browse_unresolved
import navic.composeapp.generated.resources.home_not_owned
import navic.composeapp.generated.resources.home_owned
import navic.composeapp.generated.resources.search_tile_charts
import navic.composeapp.generated.resources.search_tile_picks
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import paige.navic.di.LocalNavStack
import paige.navic.domain.manager.LbBrowseAlbum
import paige.navic.icons.Icons
import paige.navic.icons.outlined.Check
import paige.navic.icons.outlined.Download
import paige.navic.ui.components.common.PageChips
import paige.navic.ui.components.common.RemoteCoverArt
import paige.navic.ui.components.layouts.ArtGridItem
import paige.navic.ui.components.layouts.NestedTopBar
import paige.navic.ui.navigation.Screen
import paige.navic.ui.screens.discover.viewmodels.BrowseTarget
import paige.navic.ui.util.COVER_FADE_MS
import paige.navic.ui.util.arrive
import paige.navic.ui.util.placeholderBrush
import paige.navic.ui.util.rememberArrivalGate

private const val PLACEHOLDER_TILES = 8

/**
 * Charts and Editors' picks (P3), the browse cards' two Deezer pages: a title, one line saying where
 * the albums come from, the genre chips (absent when the hub offers no genre list, as Discover's are),
 * then a grid of albums with their rank on a chart and an Owned / Not owned badge. A tile opens what
 * [paige.navic.domain.manager.BrowseResolver] says it is: the library album or the unowned album page;
 * a tile nothing matches says so and opens nothing. While the first read is out, tinted tiles of the
 * final size; a chip change keeps the tiles until the new genre lands.
 */
@Composable
fun BrowseFeedScreen(kind: BrowseKind) {
	val viewModel = koinViewModel<BrowseFeedViewModel>(key = kind.name) { parametersOf(kind) }
	val state by viewModel.state.collectAsStateWithLifecycle()
	val backStack = LocalNavStack.current
	val scope = rememberCoroutineScope()
	val snackbarHostState = remember { SnackbarHostState() }
	val gate = rememberArrivalGate()
	val unresolved = stringResource(Res.string.browse_unresolved)

	val open: (LbBrowseAlbum) -> Unit = { album ->
		scope.launch {
			when (val target = viewModel.resolve(album)) {
				is BrowseTarget.Library -> backStack.add(Screen.CollectionDetail(target.albumId, "browse"))
				is BrowseTarget.External -> backStack.add(
					Screen.ExternalAlbum(rgid = target.rgid, artistName = album.artist, title = album.title)
				)
				null -> snackbarHostState.showSnackbar(unresolved)
			}
		}
	}

	val title = stringResource(if (kind == BrowseKind.CHARTS) Res.string.search_tile_charts else Res.string.search_tile_picks)
	val line = stringResource(if (kind == BrowseKind.CHARTS) Res.string.browse_charts_line else Res.string.browse_picks_line)
	val pickGenre = stringResource(Res.string.browse_pick_genre)

	Scaffold(
		topBar = { NestedTopBar({ Text(title) }) },
		snackbarHost = { SnackbarHost(snackbarHostState) }
	) { padding ->
		LazyVerticalGrid(
			modifier = Modifier.fillMaxSize(),
			columns = GridCells.Adaptive(150.dp),
			contentPadding = PaddingValues(
				start = 8.dp,
				end = 8.dp,
				top = padding.calculateTopPadding(),
				bottom = padding.calculateBottomPadding() + 16.dp
			)
		) {
			item(span = { GridItemSpan(maxLineSpan) }, key = "line", contentType = "line") {
				Text(
					if (state.genres.isNotEmpty()) "$line $pickGenre" else line,
					style = MaterialTheme.typography.bodyMedium,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
				)
			}
			if (state.genres.isNotEmpty()) {
				item(span = { GridItemSpan(maxLineSpan) }, key = "genres", contentType = "chips") {
					PageChips(
						options = state.genres.map { it.id },
						selected = state.genre,
						label = { id -> state.genres.firstOrNull { it.id == id }?.name ?: id },
						onSelect = { viewModel.setGenre(it) },
						contentPadding = PaddingValues(horizontal = 8.dp),
						modifier = Modifier.padding(vertical = 8.dp)
					)
				}
			}
			when {
				state.albums.isNotEmpty() -> itemsIndexed(
					state.albums,
					key = { _, album -> album.feedKey() },
					contentType = { _, _ -> "album" }
				) { index, album ->
					val key = album.feedKey()
					BrowseFeedTile(
						album = album,
						rank = if (kind == BrowseKind.CHARTS) index + 1 else null,
						onClick = { open(album) },
						modifier = Modifier.animateItem().arrive(gate, key, index)
					)
				}
				state.loading -> items(PLACEHOLDER_TILES, span = { GridItemSpan(1) }) { index ->
					PlaceholderTile(seed = "${kind.name}$index")
				}
				state.failed -> item(span = { GridItemSpan(maxLineSpan) }, key = "failed") {
					FeedNote(stringResource(Res.string.browse_failed), onRetry = { viewModel.retry() })
				}
				state.empty -> item(span = { GridItemSpan(maxLineSpan) }, key = "empty") {
					FeedNote(stringResource(Res.string.browse_empty), onRetry = null)
				}
			}
		}
	}
}

@Composable
private fun BrowseFeedTile(album: LbBrowseAlbum, rank: Int?, onClick: () -> Unit, modifier: Modifier) {
	ArtGridItem(
		modifier = modifier,
		onClick = onClick,
		coverArtId = null,
		title = album.title,
		subtitle = album.artist,
		id = album.deezerId.ifBlank { album.rgid },
		tab = "browse",
		cover = { source ->
			RemoteCoverArt(
				url = album.coverUrl.ifBlank { null },
				contentDescription = album.title,
				modifier = Modifier.fillMaxWidth(),
				interactionSource = source,
				crossfadeMs = COVER_FADE_MS,
				placeholderName = album.title
			)
		},
		coverOverlay = {
			if (rank != null) RankBadge(rank)
			OwnedBadge(album.releaseOwned)
		}
	)
}

@Composable
private fun BoxScope.RankBadge(rank: Int) {
	val words = stringResource(Res.string.browse_rank, rank)
	Box(
		modifier = Modifier
			.align(Alignment.TopStart)
			.padding(8.dp)
			.clip(MaterialTheme.shapes.small)
			.background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.6f))
			.semantics { contentDescription = words }
			.padding(horizontal = 8.dp, vertical = 2.dp)
	) {
		Text("$rank", style = MaterialTheme.typography.labelLarge, color = Color.White)
	}
}

/** A check where lb-bot's index says the release is on disk, an arrow where it is not. */
@Composable
private fun BoxScope.OwnedBadge(owned: Boolean) {
	val words = stringResource(if (owned) Res.string.home_owned else Res.string.home_not_owned)
	Box(
		modifier = Modifier
			.align(Alignment.TopEnd)
			.padding(8.dp)
			.size(26.dp)
			.clip(CircleShape)
			.background(
				if (owned) MaterialTheme.colorScheme.primary
				else MaterialTheme.colorScheme.scrim.copy(alpha = 0.6f)
			)
			.semantics { contentDescription = words },
		contentAlignment = Alignment.Center
	) {
		Icon(
			imageVector = if (owned) Icons.Outlined.Check else Icons.Outlined.Download,
			contentDescription = null,
			tint = if (owned) MaterialTheme.colorScheme.onPrimary else Color.White,
			modifier = Modifier.size(16.dp)
		)
	}
}

/** A tile's size and tint before its album is known. */
@Composable
private fun PlaceholderTile(seed: String) {
	val bar = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
	Column(Modifier.padding(8.dp)) {
		Box(
			Modifier
				.fillMaxWidth()
				.aspectRatio(1f)
				.clip(MaterialTheme.shapes.large)
				.background(placeholderBrush(seed))
		)
		Box(Modifier.padding(top = 8.dp).width(110.dp).height(14.dp).clip(MaterialTheme.shapes.extraSmall).background(bar))
		Box(Modifier.padding(top = 6.dp).width(72.dp).height(12.dp).clip(MaterialTheme.shapes.extraSmall).background(bar))
	}
}

@Composable
private fun FeedNote(text: String, onRetry: (() -> Unit)?) {
	Column(
		modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 40.dp),
		horizontalAlignment = Alignment.CenterHorizontally
	) {
		Text(
			text,
			style = MaterialTheme.typography.bodyLarge,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
			textAlign = TextAlign.Center
		)
		if (onRetry != null) {
			OutlinedButton(onClick = onRetry, modifier = Modifier.padding(top = 12.dp)) {
				Text(stringResource(Res.string.action_retry))
			}
		}
	}
}
