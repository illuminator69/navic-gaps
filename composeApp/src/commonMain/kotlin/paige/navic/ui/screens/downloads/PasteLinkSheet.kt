package paige.navic.ui.screens.downloads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey
import kotlinx.coroutines.launch
import navic.composeapp.generated.resources.Res
import navic.composeapp.generated.resources.action_open_link
import navic.composeapp.generated.resources.action_paste_from_clipboard
import navic.composeapp.generated.resources.info_link_unresolved
import navic.composeapp.generated.resources.info_paste_link
import navic.composeapp.generated.resources.link_looking
import navic.composeapp.generated.resources.link_looking_help
import navic.composeapp.generated.resources.option_paste_link
import org.jetbrains.compose.resources.stringResource
import paige.navic.domain.manager.LbLinkResolution
import paige.navic.icons.Icons
import paige.navic.icons.outlined.Copy
import paige.navic.ui.components.sheets.ModalBottomSheet
import paige.navic.ui.components.sheets.SheetHeader
import paige.navic.ui.navigation.Screen
import paige.navic.ui.screens.search.components.GhostRow
import paige.navic.ui.screens.search.components.SearchPill

/** Where the paste sheet is (P3, the boards' three states in one place). */
private sealed interface LinkStep {
	data object Paste : LinkStep
	data object Looking : LinkStep
	data class Failed(val reason: String) : LinkStep
}

/**
 * Paste a link from somewhere else and get it here.
 *
 * The whole point is that "I found this on Spotify" stops being a manual search.
 * It **resolves before it acts** and opens what it thinks the link is, because
 * resolution quality genuinely varies: a MusicBrainz URL is exact, a Spotify or
 * Deezer id is a provider lookup, and an Apple / YT-Music / Tidal / Qobuz URL is a
 * MusicBrainz search over an artist and title scraped out of the URL. Acting
 * straight on the last of those would start downloading the wrong record often
 * enough to matter.
 *
 * Three states (P3): **paste** (the field, "Paste from clipboard", which reads the clipboard once when
 * tapped, and the one-line help), **looking** (the link kept in the field, a ghost row where the
 * answer will be and a "Looking it up" pill), **failed** (lb-bot's reason, or the generic line, under
 * a field that can be edited and sent again).
 *
 * Android's share sheet is the other, better entry point (`MainActivity`'s
 * `ACTION_SEND` filter). This one is the New sheet's "Add from a link", Search's "Nothing for …"
 * and the Downloads page's top-bar action, so the feature is discoverable without already knowing
 * it is there.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PasteLinkSheet(
	resolve: suspend (String) -> LbLinkResolution?,
	onDismissRequest: () -> Unit,
	onResolved: (NavKey) -> Unit
) {
	val scope = rememberCoroutineScope()
	val clipboard = LocalClipboardManager.current
	val urlState = rememberTextFieldState()
	val focus = remember { FocusRequester() }
	val genericFailure = stringResource(Res.string.info_link_unresolved)
	var step by remember { mutableStateOf<LinkStep>(LinkStep.Paste) }
	val looking = step == LinkStep.Looking

	LaunchedEffect(Unit) { focus.requestFocus() }

	val lookUp: () -> Unit = {
		val link = urlState.text.toString().trim()
		if (link.isNotEmpty() && step != LinkStep.Looking) {
			step = LinkStep.Looking
			scope.launch {
				val answer = resolve(link)
				if (answer == null || !answer.resolved) {
					// lb-bot names the provider and what it could not read when it
					// can; the generic line covers a null answer and an lb-bot
					// predating the field.
					step = LinkStep.Failed(answer?.reason?.ifBlank { null } ?: genericFailure)
					return@launch
				}
				step = LinkStep.Paste
				// A track resolves to the release it is on, because a track is not a
				// thing this stack can acquire on its own: the whole acquisition
				// pipeline is release-shaped.
				onResolved(
					if (answer.rgid.isNotBlank()) {
						Screen.ExternalAlbum(
							rgid = answer.rgid,
							artistName = answer.artist,
							title = answer.title,
							fromLink = true
						)
					} else {
						Screen.ExternalArtist(answer.mbid, answer.artist)
					}
				)
			}
		}
	}

	ModalBottomSheet(onDismissRequest = onDismissRequest) {
		SheetHeader(stringResource(Res.string.option_paste_link))
		Column(
			modifier = Modifier
				.fillMaxWidth()
				.padding(horizontal = 20.dp)
				.padding(top = 12.dp, bottom = 24.dp),
			verticalArrangement = Arrangement.spacedBy(12.dp)
		) {
			Text(
				stringResource(if (looking) Res.string.link_looking_help else Res.string.info_paste_link),
				style = MaterialTheme.typography.bodyMedium,
				color = MaterialTheme.colorScheme.onSurfaceVariant
			)
			OutlinedTextField(
				state = urlState,
				lineLimits = TextFieldLineLimits.SingleLine,
				readOnly = looking,
				isError = step is LinkStep.Failed,
				supportingText = (step as? LinkStep.Failed)?.let { failed -> { Text(failed.reason) } },
				onKeyboardAction = { lookUp() },
				modifier = Modifier
					.fillMaxWidth()
					.focusRequester(focus)
			)
			if (looking) {
				Row(verticalAlignment = Alignment.CenterVertically) {
					SearchPill(stringResource(Res.string.link_looking), busy = true)
				}
				GhostRow(index = 0, count = 1, seed = urlState.text.toString())
			} else {
				Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
					OutlinedButton(
						onClick = {
							// Read once, on the tap: nothing is read from the clipboard unasked.
							val pasted = clipboard.getText()?.text?.trim().orEmpty()
							if (pasted.isNotEmpty()) {
								urlState.setTextAndPlaceCursorAtEnd(pasted)
								lookUp()
							}
						},
						modifier = Modifier.weight(1f)
					) {
						Icon(Icons.Outlined.Copy, contentDescription = null, modifier = Modifier.size(18.dp))
						Text(stringResource(Res.string.action_paste_from_clipboard), modifier = Modifier.padding(start = 8.dp))
					}
					Button(
						enabled = urlState.text.isNotBlank(),
						onClick = lookUp,
						modifier = Modifier.weight(1f)
					) {
						Text(stringResource(Res.string.action_open_link))
					}
				}
			}
		}
	}
}
