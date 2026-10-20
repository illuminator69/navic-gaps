package paige.navic.ui.screens.search.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import navic.composeapp.generated.resources.Res
import navic.composeapp.generated.resources.label_in_library
import navic.composeapp.generated.resources.lbbot_fill_placed
import navic.composeapp.generated.resources.search_looking
import navic.composeapp.generated.resources.search_lookup_failed
import navic.composeapp.generated.resources.search_lookup_retry
import navic.composeapp.generated.resources.search_not_in_library
import org.jetbrains.compose.resources.stringResource
import paige.navic.domain.manager.LbAlbumCandidate
import paige.navic.ui.components.common.GroupHeader
import paige.navic.ui.screens.search.ExternalArtist
import paige.navic.ui.screens.search.ExternalRow
import paige.navic.ui.util.ArrivalGate
import paige.navic.ui.util.Fold
import paige.navic.ui.util.arrive

/** How many ghost rows stand in while a lookup is out: the first answer usually fills them. */
private const val GHOST_ROWS = 3

/**
 * "Not in your library" (P3): lb-bot's MusicBrainz artists and albums, under the library's own rows.
 * Its header says what the lookups are doing: Looking (a spinner), "Couldn't reach lb-bot" (a tap asks
 * again, once), or nothing. While a lookup is out, three ghost rows of the final size hold the place;
 * when the answer lands the real rows take the ghosts' places and the ghosts left over fold away, so
 * nothing below jumps. Absent with lb-bot off, and for a query too short to ask about (the caller).
 */
fun LazyGridScope.externalSection(
	rows: List<ExternalRow>,
	looking: Boolean,
	failed: Boolean,
	gate: ArrivalGate,
	onRetry: () -> Unit,
	onOpenArtist: (ExternalArtist) -> Unit,
	onOpenAlbum: (LbAlbumCandidate) -> Unit
) {
	if (rows.isEmpty() && !looking && !failed) return
	val count = if (looking) maxOf(GHOST_ROWS, rows.size) else rows.size

	item(span = { GridItemSpan(maxLineSpan) }, key = "x-header", contentType = "header") {
		GroupHeader(
			title = stringResource(Res.string.search_not_in_library),
			modifier = Modifier.animateItem().padding(horizontal = 16.dp)
		) {
			if (looking) SearchPill(stringResource(Res.string.search_looking), busy = true)
		}
	}
	// On a line of its own: beside the title it cut "Not in your library" short (seen on the AVD).
	if (failed && !looking) {
		item(span = { GridItemSpan(maxLineSpan) }, key = "x-failed", contentType = "note") {
			Box(Modifier.animateItem().fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp)) {
				SearchPill(
					stringResource(Res.string.search_lookup_failed),
					failed = true,
					onClick = onRetry,
					clickLabel = stringResource(Res.string.search_lookup_retry)
				)
			}
		}
	}

	// The first rows live in fixed slots, so an answer replaces its ghost in place.
	for (slot in 0 until GHOST_ROWS) {
		val row = rows.getOrNull(slot)
		item(span = { GridItemSpan(maxLineSpan) }, key = "x-slot-$slot", contentType = "external") {
			Fold(visible = row != null || looking, modifier = Modifier.animateItem()) {
				AnimatedContent(
					targetState = row,
					transitionSpec = { fadeIn() togetherWith fadeOut() },
					contentKey = { it?.key }
				) { shown ->
					if (shown == null) {
						GhostRow(index = slot, count = count, seed = "ghost$slot", modifier = Modifier.fillMaxWidth())
					} else {
						ExternalRowView(shown, slot, count, onOpenArtist, onOpenAlbum)
					}
				}
			}
		}
	}

	rows.drop(GHOST_ROWS).forEachIndexed { i, row ->
		val index = i + GHOST_ROWS
		item(span = { GridItemSpan(maxLineSpan) }, key = row.key, contentType = "external") {
			ExternalRowView(
				row, index, count, onOpenArtist, onOpenAlbum,
				modifier = Modifier.animateItem().arrive(gate, row.key, i)
			)
		}
	}
}

@Composable
private fun ExternalRowView(
	row: ExternalRow,
	index: Int,
	count: Int,
	onOpenArtist: (ExternalArtist) -> Unit,
	onOpenAlbum: (LbAlbumCandidate) -> Unit,
	modifier: Modifier = Modifier
) {
	when (row) {
		is ExternalRow.Artist -> ExternalArtistRow(
			artist = row.artist,
			index = index,
			count = count,
			onClick = { onOpenArtist(row.artist) },
			modifier = modifier
		)
		is ExternalRow.Album -> {
			val album = row.album
			// Owned, but lb-bot has not resolved the Navidrome id yet: a real state after a fill it
			// performed itself. Said, rather than offering to fetch it again.
			val owned = when {
				!album.releaseOwned -> null
				album.releaseAlbumId.isNotBlank() -> stringResource(Res.string.label_in_library)
				else -> stringResource(Res.string.lbbot_fill_placed)
			}
			ExternalAlbumRow(
				album = album,
				index = index,
				count = count,
				ownedLabel = owned,
				onClick = { onOpenAlbum(album) },
				modifier = modifier
			)
		}
	}
}
