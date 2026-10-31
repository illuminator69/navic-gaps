package paige.navic.ui.screens.artist.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.time.Clock
import navic.composeapp.generated.resources.Res
import navic.composeapp.generated.resources.action_choose_musicbrainz_artist
import navic.composeapp.generated.resources.action_match_manually
import navic.composeapp.generated.resources.action_retry
import navic.composeapp.generated.resources.artist_scan_count
import navic.composeapp.generated.resources.artist_scan_updated
import navic.composeapp.generated.resources.artist_unresolved
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
import paige.navic.ui.screens.artist.viewmodels.DiscographyUi
import paige.navic.ui.screens.artist.ArtistScanAction
import paige.navic.ui.screens.artist.artistScanAction
import paige.navic.ui.screens.radio.custom.formatCount
import paige.navic.ui.util.Fold
import paige.navic.ui.util.rememberFoldMotion

/**
 * The scan behind an artist's discography: "Scan discography" for an artist lb-bot has never walked,
 * a spinner while it walks, and why the last walk failed. The rows themselves are elsewhere now
 * (round 2): the artist page shows a few as Popular releases and the discography page lists them all
 * behind chips (`DiscographyBrowser`), where the rescan is a chip.
 *
 * Owned, partly-owned and absent releases sit side by side in that list, each with a pill saying
 * where it stands. Splitting them into a "yours" list and a "missing" list was the obvious layout and
 * the wrong one: the question a discography answers is "what did this artist release", and the
 * library's coverage of it is an annotation, not a category. Nothing fetches with one tap: a row
 * opens its album, and an album the library lacks is requested from its page, after a look at the
 * sources.
 *
 * Renders nothing at all when lb-bot isn't reachable, which is the state most users are in, and
 * nothing once there is an index with releases in it and no scan running or failed.
 */
/**
 * Worbus P1: which state the scan is in is [artistScanAction]'s; this draws it. The slot folds in and
 * out with the page's fold motion and cross-fades between states: Scan now, then "Searching
 * MusicBrainz… 3 of 10" over a bar, then, once the releases land above it, "Updated just now" at the
 * slot's last height. Folding it away in that frame shortened the page under a reader at its end, and
 * the scroll offset clamped: the page jumped by the slot's height (about 228 px on the AVD).
 *
 * [mbid] is the effective MBID (override, else tag); [scanByNdId] lb-bot can scan without one
 * ([scanByNdIdOffered]); [onChooseMbid] opens the MusicBrainz picker, null where there is none (the
 * hub cannot store an override): then no Match manually, and an older lb-bot's untagged artist gets
 * the disabled button that says why.
 */
@Composable
fun DiscographyScanAction(
	ui: DiscographyUi,
	mbid: String,
	scanByNdId: Boolean,
	onIndex: () -> Unit,
	onChooseMbid: (() -> Unit)? = null
) {
	val action = artistScanAction(ui, mbid, canOverride = onChooseMbid != null, scanByNdId = scanByNdId)
	// The last state drawn stays while the slot folds away.
	var shown by remember { mutableStateOf<ArtistScanAction>(ArtistScanAction.None) }
	if (action != ArtistScanAction.None && action != shown) shown = action
	val motion = rememberFoldMotion()
	// The slot's height before it said "Updated": the line holds it, so nothing below moves up.
	val density = LocalDensity.current
	var held by remember { mutableStateOf(0.dp) }
	Fold(visible = action != ArtistScanAction.None) {
		AnimatedContent(
			targetState = shown,
			// A count moving is the same state and must not cross-fade.
			contentKey = { it::class },
			transitionSpec = {
				fadeIn(motion.effects()) togetherWith fadeOut(motion.effects()) using
					SizeTransform(clip = false) { _, _ -> snap() }
			},
			modifier = Modifier
				.animateContentSize(motion.spatial<IntSize>())
				.onSizeChanged { if (shown != ArtistScanAction.Updated) held = with(density) { it.height.toDp() } },
			label = "artistScanAction"
		) { current ->
			ScanActionContent(current, ui, onIndex, onChooseMbid, minHeight = if (current == ArtistScanAction.Updated) held else 0.dp)
		}
	}
}

@Composable
private fun ScanActionContent(
	action: ArtistScanAction,
	ui: DiscographyUi,
	onIndex: () -> Unit,
	onChooseMbid: (() -> Unit)?,
	minHeight: Dp = 0.dp
) {
	Column(
		modifier = Modifier.fillMaxWidth().heightIn(min = minHeight).padding(horizontal = 16.dp, vertical = 8.dp),
		horizontalAlignment = Alignment.CenterHorizontally,
		verticalArrangement = Arrangement.Center
	) {
		when (action) {
			ArtistScanAction.None -> Unit
			// The quiet line a finished scan leaves, at the height the scan had.
			ArtistScanAction.Updated -> Text(
				stringResource(Res.string.artist_scan_updated),
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant
			)
			is ArtistScanAction.Scanning -> {
				Row(verticalAlignment = Alignment.CenterVertically) {
					CircularProgressIndicator(Modifier.size(18.dp))
					Text(
						stringResource(Res.string.info_indexing_artist),
						style = MaterialTheme.typography.bodyMedium,
						modifier = Modifier.padding(start = 12.dp)
					)
				}
				// lb-bot's own count once it has the artist's list (0 of 0 until then: no bar yet).
				if (action.total > 0) {
					Text(
						stringResource(Res.string.artist_scan_count, formatCount(action.done), formatCount(action.total)),
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
						modifier = Modifier.padding(top = 6.dp)
					)
					val motion = rememberFoldMotion()
					val fraction by animateFloatAsState(
						action.done.coerceIn(0, action.total) / action.total.toFloat(),
						motion.spatial(),
						label = "artistScanBar"
					)
					LinearProgressIndicator(
						progress = { fraction },
						modifier = Modifier.fillMaxWidth(0.6f).padding(top = 8.dp)
					)
				}
			}
			is ArtistScanAction.Unresolved -> {
				// lb-bot looked, and MusicBrainz has nobody it could match: not an error, and not
				// "no releases" either.
				Text(
					stringResource(Res.string.artist_unresolved),
					style = MaterialTheme.typography.bodyMedium,
					textAlign = TextAlign.Center
				)
				Row(
					modifier = Modifier.padding(top = 8.dp),
					horizontalArrangement = Arrangement.spacedBy(8.dp)
				) {
					OutlinedButton(onClick = onIndex) { Text(stringResource(Res.string.action_retry)) }
					if (action.matchOffered && onChooseMbid != null) {
						Button(onClick = onChooseMbid) { Text(stringResource(Res.string.action_match_manually)) }
					}
				}
			}
			ArtistScanAction.ChooseMbid -> {
				// No MusicBrainz id to scan under, an lb-bot that cannot find one itself, and a hub that
				// can store one: offer to choose it (I-019) in place of a button that could only ever be
				// disabled.
				Button(onClick = { onChooseMbid?.invoke() }) {
					Text(stringResource(Res.string.action_choose_musicbrainz_artist))
				}
				// Why there is a picker and no scan (today's line under it).
				Text(
					stringResource(Res.string.info_index_needs_mbid),
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					textAlign = TextAlign.Center,
					modifier = Modifier.padding(top = 6.dp)
				)
			}
			is ArtistScanAction.NeedsMbid -> {
				Button(onClick = onIndex, enabled = false) {
					Text(
						stringResource(
							if (action.rescan) Res.string.action_rescan_discography else Res.string.action_index_artist
						)
					)
				}
				// A disabled button explains nothing on its own, and this one has a real reason.
				Text(
					stringResource(Res.string.info_index_needs_mbid),
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					textAlign = TextAlign.Center,
					modifier = Modifier.padding(top = 6.dp)
				)
			}
			is ArtistScanAction.ScanNow -> {
				Button(onClick = onIndex) {
					Text(
						stringResource(
							if (action.rescan) Res.string.action_rescan_discography else Res.string.action_index_artist
						)
					)
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
		// The scan failed and lb-bot kept what it had. Its sentence, verbatim: it names the cause
		// ("MusicBrainz did not answer (HTTP 503)"), which a paraphrase loses. A MusicBrainz outage
		// is this, never "no match".
		if (ui.scanError.isNotBlank() && action !is ArtistScanAction.Scanning) {
			Text(
				text = ui.scanError,
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.error,
				textAlign = TextAlign.Center,
				modifier = Modifier.padding(top = 6.dp)
			)
		}
		// The rescan was answered "nothing to scan from here": a fact about whose discography this
		// is, so a plain line — not the error colour, and no spinner was ever owed.
		if (ui.scanShared && action !is ArtistScanAction.Scanning) {
			Text(
				stringResource(Res.string.info_discography_shared),
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
				textAlign = TextAlign.Center,
				modifier = Modifier.padding(top = 6.dp)
			)
		}
	}
}

/** "Last scanned N days ago", or null when lb-bot has never said. */
@Composable
internal fun indexFreshnessLabel(ui: DiscographyUi): String? {
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
internal fun sectionTitle(type: String): String = when (type) {
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
