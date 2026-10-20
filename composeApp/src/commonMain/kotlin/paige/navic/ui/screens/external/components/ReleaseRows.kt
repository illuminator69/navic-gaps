package paige.navic.ui.screens.external.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import navic.composeapp.generated.resources.Res
import navic.composeapp.generated.resources.count_tracks
import navic.composeapp.generated.resources.info_tracks_missing
import navic.composeapp.generated.resources.release_tap_hint
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import paige.navic.domain.manager.LbBotManager
import paige.navic.domain.manager.LbFillEntry
import paige.navic.domain.manager.PreferenceManager
import paige.navic.icons.Icons
import paige.navic.icons.outlined.ChevronForward
import paige.navic.ui.components.common.CoverArt
import paige.navic.ui.components.common.MarqueeText
import paige.navic.ui.components.common.RemoteCoverArt
import paige.navic.ui.components.common.SegmentedListItem
import paige.navic.ui.components.common.SegmentedListItemDefaults.segmentedShapes
import paige.navic.ui.components.common.StatePill
import paige.navic.ui.screens.artist.releaseState
import paige.navic.ui.screens.artist.viewmodels.DiscographyEntry
import paige.navic.ui.screens.artist.viewmodels.DiscographySection
import paige.navic.ui.util.COVER_FADE_MS
import paige.navic.ui.util.arrive
import paige.navic.ui.util.rememberArrivalGate

private val ArtSize = 50.dp

/**
 * The discography as rows (P4): one heading per release type, then a row per release with its
 * cover, "year · N tracks", and a pill saying where it stands (`releaseState`: owned, partly owned,
 * added and syncing, missing, or what the fill it has is doing). A partly-owned row also draws how
 * much of it the library holds. Rows replace the carousel's tiles because the review is the point:
 * a tile's corner button fetched an album with nothing read.
 *
 * The ledger is read once for the shelf. A row's fill is the ledger row keyed by its release-group,
 * or, for a partly-owned row, by its gap's group id (a gap fill's key).
 */
@Composable
fun ReleaseRows(
	sections: List<DiscographySection>,
	sectionTitle: @Composable (String) -> String,
	/** The unowned artist page: each heading says what a tap does. */
	tapHint: Boolean,
	onOpenEntry: (DiscographyEntry) -> Unit,
	/** The release's own sheet; null where there is none (nothing owned on the page). */
	onLongClick: ((DiscographyEntry) -> Unit)?
) {
	val lbBot = koinInject<LbBotManager>()
	val ledger by lbBot.ledger.collectAsState()
	val gate = rememberArrivalGate()
	Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
		sections.forEach { section ->
			Column {
				Row(
					Modifier.padding(horizontal = 20.dp).padding(bottom = 6.dp),
					verticalAlignment = Alignment.Bottom
				) {
					Text(
						sectionTitle(section.type),
						style = MaterialTheme.typography.titleMediumEmphasized,
						fontWeight = FontWeight(600),
						modifier = Modifier.weight(1f, fill = false)
					)
					if (tapHint) {
						Text(
							stringResource(Res.string.release_tap_hint),
							style = MaterialTheme.typography.bodySmall,
							color = MaterialTheme.colorScheme.onSurfaceVariant,
							modifier = Modifier.padding(start = 10.dp)
						)
					}
				}
				Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
					section.entries.forEachIndexed { index, entry ->
						ReleaseRow(
							entry = entry,
							fill = fillFor(entry, ledger),
							index = index,
							count = section.entries.size,
							onClick = { onOpenEntry(entry) },
							onLongClick = onLongClick?.takeIf { entry.album != null }?.let { { it(entry) } },
							modifier = Modifier.arrive(gate, entry.key, index)
						)
					}
				}
			}
		}
	}
}

/** The ledger row for [entry]: by release-group, or by gap group for a partly-owned album. */
private fun fillFor(entry: DiscographyEntry, ledger: List<LbFillEntry>): LbFillEntry? {
	val gap = entry.gapGroupId
	if (gap != null) ledger.firstOrNull { it.key == gap }?.let { return it }
	val rgid = entry.release?.rgid.orEmpty()
	return if (rgid.isBlank()) null else ledger.firstOrNull { it.key == rgid }
}

@Composable
private fun ReleaseRow(
	entry: DiscographyEntry,
	fill: LbFillEntry?,
	index: Int,
	count: Int,
	onClick: () -> Unit,
	onLongClick: (() -> Unit)?,
	modifier: Modifier = Modifier
) {
	val preferenceManager = koinInject<PreferenceManager>()
	val shape = preferenceManager.coverArtShape.decreasedShape
	// The release's own length where lb-bot knows it (a partly-owned album: "12 tracks", "11
	// missing"), else the library album's; Room's count of a part-owned album is what it holds.
	val tracks = entry.totalTracks?.takeIf { it > 0 } ?: entry.album?.songCount?.takeIf { it > 0 }
	SegmentedListItem(
		modifier = modifier.padding(horizontal = 16.dp, vertical = 1.5.dp),
		onClick = onClick,
		onLongClick = onLongClick,
		shapes = segmentedShapes(index, count),
		leadingContent = {
			val album = entry.album
			if (album != null) {
				CoverArt(
					coverArtId = album.coverArtId,
					modifier = Modifier.size(ArtSize),
					shape = shape,
					crossfadeMs = COVER_FADE_MS,
					placeholderName = entry.title
				)
			} else {
				// From the Cover Art Archive, fetched with no Navidrome headers attached.
				RemoteCoverArt(
					url = LbBotManager.caaCoverUrl(entry.release?.rgid.orEmpty()).ifBlank { null },
					modifier = Modifier.size(ArtSize),
					shape = shape,
					crossfadeMs = COVER_FADE_MS,
					placeholderName = entry.title
				)
			}
		},
		supportingContent = {
			Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
				val line = listOfNotNull(
					entry.year.takeIf { it.isNotBlank() },
					tracks?.let { pluralStringResource(Res.plurals.count_tracks, it, it) }
				).joinToString(" · ")
				if (line.isNotBlank()) Text(line, maxLines = 1, overflow = TextOverflow.Ellipsis)
				// How much of a partly-owned album the library holds: lb-bot's counts, a thin bar and
				// the number that is the reason to tap.
				val present = entry.presentTracks
				val total = entry.totalTracks
				if (entry.gapGroupId != null && present != null && total != null && total > 0) {
					Row(verticalAlignment = Alignment.CenterVertically) {
						LinearProgressIndicator(
							progress = { present.toFloat() / total },
							modifier = Modifier.width(72.dp),
							drawStopIndicator = {}
						)
						Text(
							stringResource(Res.string.info_tracks_missing, total - present),
							style = MaterialTheme.typography.bodySmall,
							modifier = Modifier.padding(start = 8.dp)
						)
					}
				}
				StatePill(releaseState(entry, fill))
			}
		},
		trailingContent = {
			Icon(Icons.Outlined.ChevronForward, contentDescription = null, modifier = Modifier.size(20.dp))
		}
	) {
		MarqueeText(entry.title)
	}
}
