package paige.navic.ui.screens.artist.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.time.Clock
import navic.composeapp.generated.resources.Res
import navic.composeapp.generated.resources.action_choose_musicbrainz_artist
import navic.composeapp.generated.resources.action_index_artist
import navic.composeapp.generated.resources.action_rescan_discography
import navic.composeapp.generated.resources.info_discography_shared
import navic.composeapp.generated.resources.info_index_needs_mbid
import navic.composeapp.generated.resources.info_index_scanned_days
import navic.composeapp.generated.resources.info_index_scanned_today
import navic.composeapp.generated.resources.info_index_stale
import navic.composeapp.generated.resources.info_indexing_artist
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
import paige.navic.ui.screens.artist.viewmodels.DiscographyEntry
import paige.navic.ui.screens.external.components.ReleaseRows
import paige.navic.ui.screens.artist.viewmodels.DiscographyUi

/**
 * The artist's discography, one heading per release type and a row per release ([ReleaseRows], P4).
 *
 * Owned, partly-owned and absent releases sit side by side in the same list, each with a pill
 * saying where it stands. Splitting them into a "yours" list and a "missing" list was the obvious
 * layout and the wrong one: the question a discography answers is "what did this artist release",
 * and the library's coverage of it is an annotation, not a category. Nothing here fetches with one
 * tap: a row opens its album, and an album the library lacks is requested from its page, after a
 * look at the sources.
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
	/** The long press: an owned release's own sheet. Null where nothing on the page is owned. */
	onSelectEntry: ((DiscographyEntry) -> Unit)?,
	/** The unowned artist page: each heading says a row is where its sources are found. */
	tapHint: Boolean = false,
	/** Opens the MusicBrainz match picker. Null where there is none to open: an artist off the
	 *  library, or a hub that cannot store an override. Only offered when [canIndex] is false. */
	onChooseMbid: (() -> Unit)? = null
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
		IndexAction(
			ui = ui,
			onIndex = onIndex,
			label = Res.string.action_index_artist,
			enabled = canIndex,
			onChooseMbid = onChooseMbid
		)
		return
	}

	// Rows with a state pill each (P4), the ledger read once for the shelf inside them.
	ReleaseRows(
		sections = ui.sections,
		sectionTitle = { sectionTitle(it) },
		tapHint = tapHint,
		onOpenEntry = onOpenEntry,
		onLongClick = onSelectEntry
	)

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
		enabled = canIndex,
		onChooseMbid = onChooseMbid
	)
}

@Composable
private fun IndexAction(
	ui: DiscographyUi,
	onIndex: () -> Unit,
	label: StringResource,
	enabled: Boolean,
	onChooseMbid: (() -> Unit)?
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
		if (!enabled && onChooseMbid != null) {
			// No MusicBrainz id to scan under, and the hub can store one: offer to choose it
			// (I-019) in place of a button that could only ever be disabled.
			Button(onClick = onChooseMbid) {
				Text(stringResource(Res.string.action_choose_musicbrainz_artist))
			}
		} else {
			Button(onClick = onIndex, enabled = enabled) { Text(stringResource(label)) }
		}
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
		// The rescan was answered "nothing to scan from here": a fact about whose discography
		// this is, so a plain line — not the error colour, and no spinner was ever owed.
		if (ui.scanShared) {
			Text(
				stringResource(Res.string.info_discography_shared),
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
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
