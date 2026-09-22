package paige.navic.ui.components.common

import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import navic.composeapp.generated.resources.Res
import navic.composeapp.generated.resources.acquire_get_album
import navic.composeapp.generated.resources.acquire_incomplete
import navic.composeapp.generated.resources.acquire_no_sources
import navic.composeapp.generated.resources.acquire_started
import navic.composeapp.generated.resources.acquire_unavailable
import navic.composeapp.generated.resources.acquire_uncertain
import navic.composeapp.generated.resources.acquire_wrong_format
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import paige.navic.di.LocalSnackBarState
import paige.navic.domain.manager.AcquireOutcome
import paige.navic.domain.manager.AcquireReason
import paige.navic.domain.manager.LbBotManager
import paige.navic.icons.Icons
import paige.navic.icons.outlined.Download

/**
 * One gesture to fetch a release the library doesn't have, and the one place that
 * decides when one gesture is honest.
 *
 * [LbBotManager.acquire] reviews lb-bot's ranked sources first and only fires when
 * nothing is left to decide; everything else arrives here as
 * [AcquireOutcome.NeedsReview] and [onReview] opens the source picker the tap would
 * otherwise have bypassed. The blind version of this gesture existed once and
 * fetched the wrong record for a self-titled album — that is what the picker is for.
 *
 * The spinner is load-bearing, not decoration: `/lb/album/sources` is a live slskd
 * fan-out taking tens of seconds, and until the download is posted there is no watch
 * and so nothing in the ledger for the row to render. Without it the tile looks inert
 * and gets tapped again.
 */
@Composable
fun AcquireButton(
	rgid: String,
	onReview: () -> Unit,
	modifier: Modifier = Modifier,
	artist: String = "",
	album: String = ""
) {
	val lbBot = koinInject<LbBotManager>()
	// The app's one snackbar host, so the outcome is said in the same place every
	// other background result in this app is said — and so no call site has to
	// thread a message callback through a row it does not otherwise own.
	val snackBarState = LocalSnackBarState.current
	val scope = rememberCoroutineScope()
	var busy by remember { mutableStateOf(false) }
	val label = stringResource(Res.string.acquire_get_album)

	IconButton(
		onClick = {
			if (busy) return@IconButton
			busy = true
			scope.launch {
				val outcome = lbBot.acquire(rgid, artist = artist, album = album)
				busy = false
				when (outcome) {
					is AcquireOutcome.NeedsReview -> {
						onReview()
						snackBarState.showSnackbar(getString(reasonRes(outcome.reason)))
					}
					// Name the format. Quality is a *ranking* term upstream rather
					// than a filter, so "what am I actually getting" is a real
					// question, and the source row is normally where it is answered.
					is AcquireOutcome.Started -> snackBarState.showSnackbar(
						getString(
							Res.string.acquire_started,
							album.ifBlank { label },
							outcome.format.ifBlank { "?" },
							outcome.peer
						)
					)
				}
			}
		},
		modifier = modifier
	) {
		if (busy) {
			CircularProgressIndicator(
				modifier = Modifier.size(18.dp),
				strokeWidth = 2.dp,
				color = MaterialTheme.colorScheme.primary
			)
		} else {
			Icon(
				imageVector = Icons.Outlined.Download,
				contentDescription = label,
				tint = MaterialTheme.colorScheme.primary
			)
		}
	}
}

private fun reasonRes(reason: AcquireReason) = when (reason) {
	AcquireReason.NO_SOURCES -> Res.string.acquire_no_sources
	AcquireReason.UNCERTAIN_MATCH -> Res.string.acquire_uncertain
	AcquireReason.INCOMPLETE -> Res.string.acquire_incomplete
	AcquireReason.WRONG_FORMAT -> Res.string.acquire_wrong_format
	AcquireReason.UNAVAILABLE -> Res.string.acquire_unavailable
}
