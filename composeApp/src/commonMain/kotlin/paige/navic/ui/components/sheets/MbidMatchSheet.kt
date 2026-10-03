package paige.navic.ui.components.sheets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest
import navic.composeapp.generated.resources.Res
import navic.composeapp.generated.resources.action_fix_musicbrainz_match
import navic.composeapp.generated.resources.action_mbid_match_remove
import navic.composeapp.generated.resources.action_mbid_match_use
import navic.composeapp.generated.resources.info_mbid_match_bad_id
import navic.composeapp.generated.resources.info_mbid_match_no_results
import navic.composeapp.generated.resources.info_mbid_match_none
import navic.composeapp.generated.resources.info_mbid_match_override
import navic.composeapp.generated.resources.info_mbid_match_override_same
import navic.composeapp.generated.resources.info_mbid_match_override_untagged
import navic.composeapp.generated.resources.info_mbid_match_remove_tagged
import navic.composeapp.generated.resources.info_mbid_match_remove_untagged
import navic.composeapp.generated.resources.info_mbid_match_saving
import navic.composeapp.generated.resources.info_mbid_match_tag
import navic.composeapp.generated.resources.label_mbid_match_current
import navic.composeapp.generated.resources.option_mbid_match_paste
import navic.composeapp.generated.resources.option_mbid_match_search
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import paige.navic.domain.manager.LbArtistCandidate
import paige.navic.domain.manager.LbBotManager
import paige.navic.domain.manager.parseArtistMbid
import paige.navic.ui.util.rememberCoverAmbient

/**
 * How long the search box must be still before lb-bot is asked. Its artist lookup is a live
 * MusicBrainz search behind lb-bot's one-request-a-second lock, so it is never per keystroke.
 */
private const val LOOKUP_DEBOUNCE_MS = 500L

/**
 * Choose which MusicBrainz artist a library artist is (I-019).
 *
 * lb-bot keys an artist's discography by MusicBrainz id, and Navidrome only knows the one in the
 * file tags — missing for an untagged artist, wrong for a mis-tagged one, and either way not
 * something this app can edit. The pick made here is stored by lb-bot and followed by every
 * client; Navidrome and the files are not touched, and "Remove override" undoes it.
 *
 * The sheet only searches. The write is the caller's ([onPick], [onRemoveOverride]): it
 * validates the id against MusicBrainz upstream, so it can take seconds, and it must outlive a
 * sheet that is swiped away. [working] and [error] are that write's state.
 *
 * @param tagMbid Navidrome's own tag, blank when the artist has none.
 * @param currentMbid the id in force — the override when [overridden], else the tag.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MbidMatchSheet(
	artistName: String,
	coverArtId: String?,
	tagMbid: String,
	currentMbid: String,
	overridden: Boolean,
	working: Boolean,
	error: LbBotManager.LbError?,
	onPick: (mbid: String) -> Unit,
	onRemoveOverride: () -> Unit,
	onDismissRequest: () -> Unit
) {
	val lbBot = koinInject<LbBotManager>()
	val title = stringResource(Res.string.action_fix_musicbrainz_match)

	val searchState = rememberTextFieldState(artistName)
	val pasteState = rememberTextFieldState()
	var candidates by remember { mutableStateOf<List<LbArtistCandidate>>(emptyList()) }
	var searching by remember { mutableStateOf(false) }
	// A lookup has answered for the term in the box, so an empty list means "nobody found".
	var answered by remember { mutableStateOf(false) }
	var lookupError by remember { mutableStateOf<LbBotManager.LbError?>(null) }

	LaunchedEffect(Unit) {
		// The artist's own name is searched at once; whatever is typed after waits for the box
		// to go still. `collectLatest` is the debounce: a new term cancels the wait.
		var typed = false
		snapshotFlow { searchState.text.toString().trim() }.collectLatest { term ->
			answered = false
			if (term.isBlank()) {
				candidates = emptyList()
				lookupError = null
				searching = false
				typed = true
				return@collectLatest
			}
			if (typed) delay(LOOKUP_DEBOUNCE_MS)
			typed = true
			searching = true
			val result = lbBot.artistLookupResult(term)
			// The manager answers a cancelled request as a failure; a superseded lookup must
			// not paint that.
			currentCoroutineContext().ensureActive()
			when (result) {
				is LbBotManager.LbResult.Ok -> {
					candidates = result.value
					lookupError = null
				}
				// What was found for the last term stays; the line below says why this one failed.
				is LbBotManager.LbResult.Failed -> lookupError = result.error
			}
			answered = true
			searching = false
		}
	}

	ModalBottomSheet(
		onDismissRequest = onDismissRequest,
		sheetTitle = title,
		ambient = rememberCoverAmbient(coverArtId)
	) {
		// ONE scrolling column, as GapFillSheet learned (B-043): a capped inner list inside a
		// column that does not scroll leaves everything under it unreachable on a short screen.
		Column(
			modifier = Modifier
				.verticalScroll(rememberScrollState())
				.padding(horizontal = 20.dp)
				.padding(bottom = 32.dp),
			verticalArrangement = Arrangement.spacedBy(8.dp)
		) {
			Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
			Text(
				artistName,
				style = MaterialTheme.typography.bodyMedium,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis
			)
			// Where the id in force came from: nothing, Navidrome's tag, or the user's own pick
			// (which may differ from the tag, equal it, or stand in for a tag that is missing).
			SheetCaption(
				stringResource(
					when {
						!overridden && currentMbid.isBlank() -> Res.string.info_mbid_match_none
						!overridden -> Res.string.info_mbid_match_tag
						tagMbid.isBlank() -> Res.string.info_mbid_match_override_untagged
						tagMbid == currentMbid -> Res.string.info_mbid_match_override_same
						else -> Res.string.info_mbid_match_override
					}
				)
			)
			if (currentMbid.isNotBlank()) {
				Text(
					currentMbid,
					style = MaterialTheme.typography.labelSmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant
				)
			}

			TextField(
				state = searchState,
				label = { Text(stringResource(Res.string.option_mbid_match_search)) },
				lineLimits = TextFieldLineLimits.SingleLine,
				modifier = Modifier.fillMaxWidth()
			)
			if (searching && candidates.isEmpty()) {
				CircularProgressIndicator(
					Modifier.size(20.dp).align(Alignment.CenterHorizontally),
					strokeWidth = 2.dp
				)
			}
			LbErrorLine(lookupError)
			if (answered && !searching && candidates.isEmpty() && lookupError == null) {
				Text(
					stringResource(Res.string.info_mbid_match_no_results),
					style = MaterialTheme.typography.bodyMedium,
					color = MaterialTheme.colorScheme.onSurfaceVariant
				)
			}
			candidates.forEach { candidate ->
				val current = candidate.mbid == currentMbid
				// MusicBrainz's own "(UK band)" note first — the only thing that tells two
				// identically named artists apart — then whatever else it knows.
				val detail = listOf(
					candidate.disambiguation,
					candidate.type,
					candidate.country.ifBlank { candidate.area },
					if (candidate.score > 0) "${candidate.score}%" else ""
				).filter { it.isNotBlank() }.joinToString(" • ")
				ListItem(
					colors = ListItemDefaults.colors(containerColor = Color.Transparent),
					enabled = !working && !current && candidate.mbid.isNotBlank(),
					onClick = { onPick(candidate.mbid) },
					content = {
						Text(candidate.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
					},
					supportingContent = if (detail.isBlank()) null else {
						{ Text(detail) }
					},
					trailingContent = if (!current) null else {
						{
							Text(
								stringResource(Res.string.label_mbid_match_current),
								style = MaterialTheme.typography.labelMedium,
								color = MaterialTheme.colorScheme.primary
							)
						}
					}
				)
			}

			// For the artist the search ranks badly or cannot find: the id itself, or the
			// address of their MusicBrainz page.
			val pasted = pasteState.text.toString()
			val parsed = parseArtistMbid(pasted)
			TextField(
				state = pasteState,
				label = { Text(stringResource(Res.string.option_mbid_match_paste)) },
				lineLimits = TextFieldLineLimits.SingleLine,
				modifier = Modifier.fillMaxWidth()
			)
			if (pasted.isNotBlank() && parsed == null) {
				Text(
					stringResource(Res.string.info_mbid_match_bad_id),
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.error
				)
			}
			Row(
				modifier = Modifier.fillMaxWidth(),
				horizontalArrangement = Arrangement.spacedBy(12.dp),
				verticalAlignment = Alignment.CenterVertically
			) {
				Button(
					enabled = !working && parsed != null && parsed != currentMbid,
					onClick = { parsed?.let(onPick) }
				) { Text(stringResource(Res.string.action_mbid_match_use)) }
				if (working) {
					CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
					Text(
						stringResource(Res.string.info_mbid_match_saving),
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant
					)
				}
			}
			// The write's refusal, in lb-bot's own words where it wrote any: no such artist on
			// MusicBrainz, a download for this artist in flight, MusicBrainz unavailable.
			LbErrorLine(error)

			if (overridden) {
				SheetCaption(
					stringResource(
						if (tagMbid.isBlank()) Res.string.info_mbid_match_remove_untagged
						else Res.string.info_mbid_match_remove_tagged
					)
				)
				TextButton(enabled = !working, onClick = onRemoveOverride) {
					Text(stringResource(Res.string.action_mbid_match_remove))
				}
			}
		}
	}
}
