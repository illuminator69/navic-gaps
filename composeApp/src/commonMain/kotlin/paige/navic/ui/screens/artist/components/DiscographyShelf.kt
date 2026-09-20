package paige.navic.ui.screens.artist.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.carousel.CarouselItemScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.time.Clock
import kotlinx.collections.immutable.toImmutableList
import navic.composeapp.generated.resources.Res
import navic.composeapp.generated.resources.action_index_artist
import navic.composeapp.generated.resources.action_rescan_discography
import navic.composeapp.generated.resources.info_index_needs_mbid
import navic.composeapp.generated.resources.info_index_scanned_days
import navic.composeapp.generated.resources.info_index_scanned_today
import navic.composeapp.generated.resources.info_index_stale
import navic.composeapp.generated.resources.info_indexing_artist
import navic.composeapp.generated.resources.info_not_in_library
import navic.composeapp.generated.resources.info_pending_sync
import navic.composeapp.generated.resources.info_tracks_missing
import navic.composeapp.generated.resources.title_type_album
import navic.composeapp.generated.resources.title_type_compilation
import navic.composeapp.generated.resources.title_type_demo
import navic.composeapp.generated.resources.title_type_ep
import navic.composeapp.generated.resources.title_type_live
import navic.composeapp.generated.resources.title_type_remix
import navic.composeapp.generated.resources.title_type_single
import navic.composeapp.generated.resources.title_type_soundtrack
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import paige.navic.domain.manager.LbBotManager
import paige.navic.ui.components.common.CoverArt
import paige.navic.ui.components.common.RemoteCoverArt
import paige.navic.ui.components.layouts.ArtCarousel
import paige.navic.ui.screens.artist.viewmodels.DiscographyEntry
import paige.navic.ui.screens.artist.viewmodels.DiscographyUi

/**
 * The artist's discography, as one shelf per release type.
 *
 * Owned, partly-owned and absent releases sit side by side in the same row — an
 * absent one is faded, a partly-owned one carries its track count, and an album
 * the library has in full looks like any other album tile. Splitting them into a
 * "yours" row and a "missing" row was the obvious layout and the wrong one: the
 * question a discography answers is "what did this artist release", and the
 * library's coverage of it is an annotation, not a category.
 *
 * Renders nothing at all when lb-bot isn't reachable, which is the state most
 * users are in. The page above must look exactly as it did before this existed.
 */
@Composable
fun DiscographyShelf(
	ui: DiscographyUi,
	canIndex: Boolean,
	onIndex: () -> Unit,
	onOpenEntry: (DiscographyEntry) -> Unit,
	onSelectEntry: (DiscographyEntry) -> Unit
) {
	if (!ui.available) return

	if (!ui.indexed) {
		// Never scanned. Offer it rather than doing it: the walk is one MusicBrainz
		// request a second, 10-60s for a real discography.
		//
		// Rendered even without an MBID, disabled and saying so. lb-bot's index POST
		// needs `mbid` *and* `name`, so this artist genuinely cannot be scanned —
		// which is a real limitation to state, not a reason to make the control
		// vanish and leave the user wondering where the feature went.
		IndexAction(ui, onIndex, Res.string.action_index_artist, enabled = canIndex)
		return
	}

	ui.sections.forEach { section ->
		ArtCarousel(sectionTitle(section.type), section.entries.toImmutableList()) { entry ->
			DiscographyTile(
				entry = entry,
				onClick = { onOpenEntry(entry) },
				onLongClick = { onSelectEntry(entry) }
			)
		}
	}

	// Always offered once there is an MBID, never only when the index is missing.
	//
	// A scan that matched the wrong MusicBrainz artist — or that MusicBrainz answered thinly —
	// writes a perfectly fresh index holding nothing, and `indexed` is then true forever. Gating
	// the action on `!indexed` meant the one page that could fix that offered no way to, so the
	// artist simply had an empty discography for good. A rescan is idempotent; the only cost of an
	// unnecessary one is a minute of MusicBrainz's patience.
	IndexAction(
		ui = ui,
		onIndex = onIndex,
		label = if (ui.sections.isEmpty()) Res.string.action_index_artist
		else Res.string.action_rescan_discography,
		enabled = canIndex
	)
}

@Composable
private fun IndexAction(
	ui: DiscographyUi,
	onIndex: () -> Unit,
	label: StringResource,
	enabled: Boolean
) {
	Column(
		modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
		horizontalAlignment = Alignment.CenterHorizontally
	) {
		if (ui.indexing) {
			Row(verticalAlignment = Alignment.CenterVertically) {
				CircularProgressIndicator(Modifier.size(18.dp))
				Text(
					stringResource(Res.string.info_indexing_artist),
					style = MaterialTheme.typography.bodyMedium,
					modifier = Modifier.padding(start = 12.dp)
				)
			}
			return@Column
		}
		Button(onClick = onIndex, enabled = enabled) { Text(stringResource(label)) }
		// The scan failed and lb-bot kept what it had. Its sentence, verbatim: it names
		// the cause ("MusicBrainz did not answer (HTTP 503)"), which a paraphrase loses.
		if (ui.scanError.isNotBlank()) {
			Text(
				text = ui.scanError,
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.error,
				textAlign = TextAlign.Center,
				modifier = Modifier.padding(top = 6.dp)
			)
		}
		// A disabled button explains nothing on its own, and this one has a real
		// reason: no MusicBrainz tag on the artist, so lb-bot has nothing to scan.
		if (!enabled) {
			Text(
				stringResource(Res.string.info_index_needs_mbid),
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
				textAlign = TextAlign.Center,
				modifier = Modifier.padding(top = 6.dp)
			)
			return@Column
		}
		// Freshness, so an always-visible Rescan is informed rather than blind.
		indexFreshnessLabel(ui)?.let {
			Text(
				text = it,
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
				modifier = Modifier.padding(top = 6.dp)
			)
		}
	}
}

/** "Last scanned N days ago", or null when lb-bot has never said. */
@Composable
private fun indexFreshnessLabel(ui: DiscographyUi): String? {
	if (!ui.indexed || ui.scannedAt <= 0L) return null
	val days = ((Clock.System.now().toEpochMilliseconds() - ui.scannedAt) / 86_400_000L).toInt()
	val fresh = when {
		days <= 0 -> stringResource(Res.string.info_index_scanned_today)
		else -> pluralStringResource(Res.plurals.info_index_scanned_days, days, days)
	}
	// lb-bot's own staleness verdict, not a threshold reinvented here: it also
	// covers an index built by an older scan version, which no age can express.
	return if (ui.stale) stringResource(Res.string.info_index_stale, fresh) else fresh
}

/**
 * lb-bot's release-type vocabulary as a heading.
 *
 * A closed set (`album`/`ep`/`single` from the browse, plus the type-defining
 * secondary types), so it gets real localizable strings instead of capitalising
 * the wire value — which is where "Ep" came from, and no general-purpose rule
 * would ever get that one right.
 */
@Composable
private fun sectionTitle(type: String): String = when (type) {
	"album" -> stringResource(Res.string.title_type_album)
	"ep" -> stringResource(Res.string.title_type_ep)
	"single" -> stringResource(Res.string.title_type_single)
	"compilation" -> stringResource(Res.string.title_type_compilation)
	"soundtrack" -> stringResource(Res.string.title_type_soundtrack)
	"live" -> stringResource(Res.string.title_type_live)
	"remix" -> stringResource(Res.string.title_type_remix)
	"demo" -> stringResource(Res.string.title_type_demo)
	// Something new upstream: show it rather than hiding the section.
	else -> type.replaceFirstChar { it.uppercase() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CarouselItemScope.DiscographyTile(
	entry: DiscographyEntry,
	onClick: () -> Unit,
	onLongClick: () -> Unit
) {
	val present = entry.presentTracks
	val total = entry.totalTracks

	Column(Modifier.fillMaxWidth()) {
		Box(Modifier.fillMaxWidth()) {
			val artModifier = Modifier
				.fillMaxWidth()
				.maskClip(MaterialTheme.shapes.large)
			if (entry.owned) {
				CoverArt(
					coverArtId = entry.album?.coverArtId,
					contentDescription = null,
					modifier = artModifier,
					shape = RectangleShape,
					onClick = onClick,
					onLongClick = onLongClick
				)
			} else if (entry.pendingSync) {
				// In the library but not in Room yet — a fill that just landed. Shown
				// at full strength: it is not missing, and fading it would say the
				// opposite of what just happened.
				// The click handlers are not optional here. RemoteCoverArt defaults
				// both to null, so this branch — alone of the three — rendered a
				// tile nothing could tap, which made `openPendingSync` unreachable
				// on OWNED artist pages too, not just external ones.
				RemoteCoverArt(
					url = LbBotManager.caaCoverUrl(entry.release?.rgid.orEmpty()),
					contentDescription = null,
					modifier = artModifier,
					shape = RectangleShape,
					onClick = onClick,
					onLongClick = onLongClick
				)
			} else {
				// Cover Art Archive, fetched with NO Navidrome headers attached —
				// see RemoteCoverArt for why that distinction is not cosmetic.
				RemoteCoverArt(
					url = LbBotManager.caaCoverUrl(entry.release?.rgid.orEmpty()),
					contentDescription = null,
					modifier = artModifier.alpha(ABSENT_ALPHA),
					shape = RectangleShape,
					onClick = onClick
				)
			}
			if (entry.gapGroupId != null && present != null && total != null && total > 0) {
				Text(
					// The number the badge exists to answer, rather than the one it can be
					// derived from: "3 missing" is the reason to tap, "9/12" is arithmetic.
					stringResource(Res.string.info_tracks_missing, total - present),
					style = MaterialTheme.typography.labelSmall,
					fontWeight = FontWeight.Medium,
					color = MaterialTheme.colorScheme.onSecondaryContainer,
					modifier = Modifier
						.align(Alignment.BottomEnd)
						.padding(6.dp)
						.background(
							MaterialTheme.colorScheme.secondaryContainer,
							RoundedCornerShape(50)
						)
						.padding(horizontal = 8.dp, vertical = 2.dp)
				)
			}
		}

		// Only a genuinely absent release is faded. An owned one and a just-landed
		// one both read as "yours".
		val dimmed = !entry.owned && !entry.pendingSync

		Text(
			text = entry.title,
			style = MaterialTheme.typography.bodyMedium,
			fontWeight = FontWeight.Medium,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
			modifier = Modifier
				.padding(top = 8.dp, start = 4.dp, end = 4.dp)
				.alpha(if (dimmed) ABSENT_ALPHA else 1f)
		)
		Text(
			text = when {
				entry.pendingSync -> stringResource(Res.string.info_pending_sync)
				entry.owned -> entry.year
				else -> stringResource(Res.string.info_not_in_library)
			},
			style = MaterialTheme.typography.bodySmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
			modifier = Modifier
				.fillMaxWidth()
				.padding(start = 4.dp, end = 4.dp)
				.alpha(if (dimmed) ABSENT_ALPHA else 1f)
		)
	}
}

private const val ABSENT_ALPHA = 0.45f
