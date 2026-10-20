package paige.navic.ui.screens.external.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import navic.composeapp.generated.resources.count_releases
import navic.composeapp.generated.resources.count_tracks
import navic.composeapp.generated.resources.info_source_partial
import navic.composeapp.generated.resources.release_tap_hint
import navic.composeapp.generated.resources.release_type_album
import navic.composeapp.generated.resources.release_type_compilation
import navic.composeapp.generated.resources.release_type_demo
import navic.composeapp.generated.resources.release_type_ep
import navic.composeapp.generated.resources.release_type_live
import navic.composeapp.generated.resources.release_type_remix
import navic.composeapp.generated.resources.release_type_single
import navic.composeapp.generated.resources.release_type_soundtrack
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import paige.navic.domain.manager.LbBotManager
import paige.navic.domain.manager.LbFillEntry
import paige.navic.domain.manager.PreferenceManager
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

private val ArtSize = 56.dp

/**
 * The discography as rows (P4): a row per release with its cover, "year · N tracks", and a pill
 * saying where it stands (`releaseState`: owned, partly owned, added and syncing, missing, or what
 * the fill it has is doing). A partly-owned row counts what the library holds ("9 of 12 tracks")
 * and draws it as a bar. Rows replace the carousel's tiles because the review is the point: a
 * tile's corner button fetched an album with nothing read.
 *
 * As the owner's mockups lay it out (round 2): the pill at the row's end, where a column of them
 * reads down the list; a running or failed request's longer sentence goes under the line instead,
 * where it cannot crush the title. No chevron: the whole row is the target.
 *
 * The ledger is read once for the list. A row's fill is the ledger row keyed by its release-group,
 * or, for a partly-owned row, by its gap's group id (a gap fill's key).
 */
@Composable
fun ReleaseRows(
	sections: List<DiscographySection>,
	sectionTitle: @Composable (String) -> String,
	onOpenEntry: (DiscographyEntry) -> Unit,
	/** The release's own sheet; null where there is none (nothing owned on the page). */
	onLongClick: ((DiscographyEntry) -> Unit)?,
	/** The unowned artist page: each heading says what a tap does. */
	tapHint: Boolean = false,
	/** One heading per section; false where the caller has drawn the list's heading itself. */
	headings: Boolean = true,
	/** Each heading counts its releases ("Albums  5 releases"). */
	countInHeading: Boolean = false,
	/** Each row names its type ("Album · 2019 · 10 tracks"): a list that is not split by type. */
	typeOf: ((DiscographyEntry) -> String)? = null,
	/** False where every row would say the same thing (no lb-bot index: all owned). */
	pills: Boolean = true
) {
	val lbBot = koinInject<LbBotManager>()
	val ledger by lbBot.ledger.collectAsState()
	val gate = rememberArrivalGate()
	Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
		sections.forEach { section ->
			Column {
				if (headings) {
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
						val note = when {
							countInHeading -> pluralStringResource(Res.plurals.count_releases, section.entries.size, section.entries.size)
							tapHint -> stringResource(Res.string.release_tap_hint)
							else -> null
						}
						if (note != null) {
							Text(
								note,
								style = MaterialTheme.typography.bodySmall,
								color = MaterialTheme.colorScheme.onSurfaceVariant,
								modifier = Modifier.padding(start = 10.dp)
							)
						}
					}
				}
				Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
					section.entries.forEachIndexed { index, entry ->
						ReleaseRow(
							entry = entry,
							fill = fillFor(entry, ledger),
							type = typeOf?.invoke(entry),
							pills = pills,
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
	type: String?,
	pills: Boolean,
	index: Int,
	count: Int,
	onClick: () -> Unit,
	onLongClick: (() -> Unit)?,
	modifier: Modifier = Modifier
) {
	val preferenceManager = koinInject<PreferenceManager>()
	val shape = preferenceManager.coverArtShape.decreasedShape
	// The release's own length where lb-bot knows it, else the library album's; Room's count of a
	// part-owned album is what it holds, not how long the release is.
	val tracks = entry.totalTracks?.takeIf { it > 0 } ?: entry.album?.songCount?.takeIf { it > 0 }
	val present = entry.presentTracks
	val total = entry.totalTracks
	val partly = entry.gapGroupId != null && present != null && total != null && total > 0
	val pill = releaseState(entry, fill)
	// A base state is one word and sits at the row's end; a request's sentence goes under the line.
	val pillAtEnd = pills && fill == null
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
			Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
				val line = listOfNotNull(
					type?.let { releaseTypeName(it) },
					entry.year.takeIf { it.isNotBlank() },
					when {
						partly -> stringResource(Res.string.info_source_partial, present, total)
						tracks != null -> pluralStringResource(Res.plurals.count_tracks, tracks, tracks)
						else -> null
					}
				).joinToString(" · ")
				if (line.isNotBlank()) Text(line, maxLines = 1, overflow = TextOverflow.Ellipsis)
				// How much of a partly-owned album the library holds, from lb-bot's counts.
				if (partly) {
					LinearProgressIndicator(
						progress = { present.toFloat() / total },
						modifier = Modifier.width(120.dp),
						drawStopIndicator = {}
					)
				}
				if (pills && !pillAtEnd) StatePill(pill)
			}
		},
		trailingContent = if (pillAtEnd) {
			{ StatePill(pill) }
		} else null
	) {
		MarqueeText(entry.title)
	}
}

/** lb-bot's release type, singular, for a row's line. A type it adds later reads as itself. */
@Composable
fun releaseTypeName(type: String): String = when (type) {
	"album" -> stringResource(Res.string.release_type_album)
	"ep" -> stringResource(Res.string.release_type_ep)
	"single" -> stringResource(Res.string.release_type_single)
	"compilation" -> stringResource(Res.string.release_type_compilation)
	"soundtrack" -> stringResource(Res.string.release_type_soundtrack)
	"live" -> stringResource(Res.string.release_type_live)
	"remix" -> stringResource(Res.string.release_type_remix)
	"demo" -> stringResource(Res.string.release_type_demo)
	else -> type.replaceFirstChar { it.uppercase() }
}
