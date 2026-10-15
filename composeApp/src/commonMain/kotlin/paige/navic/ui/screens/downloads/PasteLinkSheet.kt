package paige.navic.ui.screens.downloads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey
import kotlinx.coroutines.launch
import navic.composeapp.generated.resources.Res
import navic.composeapp.generated.resources.action_open_link
import navic.composeapp.generated.resources.info_link_unresolved
import navic.composeapp.generated.resources.info_paste_link
import navic.composeapp.generated.resources.option_paste_link
import org.jetbrains.compose.resources.stringResource
import paige.navic.domain.manager.LbLinkResolution
import paige.navic.ui.components.sheets.ModalBottomSheet
import paige.navic.ui.components.sheets.SheetHeader
import paige.navic.ui.navigation.Screen

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
 * Android's share sheet is the other, better entry point (`MainActivity`'s
 * `ACTION_SEND` filter). This one is the Downloads page's top-bar action, so the
 * feature is discoverable without already knowing it is there.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PasteLinkSheet(
	resolve: suspend (String) -> LbLinkResolution?,
	onDismissRequest: () -> Unit,
	onResolved: (NavKey) -> Unit
) {
	val scope = rememberCoroutineScope()
	val urlState = rememberTextFieldState()
	val focus = remember { FocusRequester() }
	val genericFailure = stringResource(Res.string.info_link_unresolved)
	var busy by remember { mutableStateOf(false) }
	var unresolved by remember { mutableStateOf<String?>(null) }

	LaunchedEffect(Unit) { focus.requestFocus() }

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
				stringResource(Res.string.info_paste_link),
				style = MaterialTheme.typography.bodyMedium,
				color = MaterialTheme.colorScheme.onSurfaceVariant
			)
			OutlinedTextField(
				state = urlState,
				lineLimits = TextFieldLineLimits.SingleLine,
				isError = unresolved != null,
				supportingText = unresolved?.let { why -> { Text(why) } },
				modifier = Modifier
					.fillMaxWidth()
					.focusRequester(focus)
			)
			Button(
				enabled = !busy && urlState.text.isNotBlank(),
				onClick = {
					busy = true
					unresolved = null
					scope.launch {
						val answer = resolve(urlState.text.toString().trim())
						busy = false
						if (answer == null || !answer.resolved) {
							// lb-bot names the provider and what it could not read when it
							// can; the generic line covers a null answer and an lb-bot
							// predating the field.
							unresolved = answer?.reason?.ifBlank { null } ?: genericFailure
							return@launch
						}
						// A track resolves to the release it is on, because a track is not a
						// thing this stack can acquire on its own: the whole acquisition
						// pipeline is release-shaped.
						onResolved(
							if (answer.rgid.isNotBlank()) {
								Screen.ExternalAlbum(
									rgid = answer.rgid,
									artistName = answer.artist,
									title = answer.title
								)
							} else {
								Screen.ExternalArtist(answer.mbid, answer.artist)
							}
						)
					}
				},
				modifier = Modifier.fillMaxWidth()
			) {
				if (busy) {
					CircularProgressIndicator(
						modifier = Modifier.size(18.dp),
						strokeWidth = 2.dp,
						color = MaterialTheme.colorScheme.onPrimary
					)
				} else {
					Text(stringResource(Res.string.action_open_link))
				}
			}
		}
	}
}
