package paige.navic.ui.components.sheets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import kotlinx.coroutines.launch
import navic.composeapp.generated.resources.Res
import navic.composeapp.generated.resources.action_not_now
import navic.composeapp.generated.resources.finder_default_name
import navic.composeapp.generated.resources.finder_older_at
import navic.composeapp.generated.resources.option_server_sign_in_as
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import paige.navic.domain.manager.Found
import paige.navic.domain.manager.HubAuth
import paige.navic.domain.manager.HubFinder
import paige.navic.domain.manager.HubManager
import paige.navic.domain.manager.PreferenceManager
import paige.navic.domain.manager.hubAddressHost
import paige.navic.domain.manager.shownAddress
import paige.navic.domain.manager.signInToFound
import paige.navic.ui.components.common.SegmentedListButton
import paige.navic.ui.components.common.SegmentedListButtonDefaults
import paige.navic.ui.util.rememberNowPlayingCoverAmbient

/*
 * Worbus P3 (design §6.4): after a Navidrome sign-in that found exactly one Worbus Server, one sheet
 * offers it: its name and address, Sign in as <account>, and Not now (asked once per server, or per
 * address for an older one). Composed once in App.kt beside NewSheetHost, so it opens over Home after a
 * login. A fork line: drop it and the offer never shows (the Server screen still lists what was found).
 */
@Composable
fun FoundServerSheetHost() {
	val finder = koinInject<HubFinder>()
	val found by finder.prompt.collectAsState()
	found?.let { FoundServerSheet(it, onAnswered = { dismissed -> finder.answerPrompt(dismissed) }) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FoundServerSheet(found: Found, onAnswered: (dismissed: Boolean) -> Unit) {
	val hubManager = koinInject<HubManager>()
	val hubAuth = koinInject<HubAuth>()
	val preferenceManager = koinInject<PreferenceManager>()
	val account = hubAuth.accountName().orEmpty()
	val sheetState = rememberBottomSheetState(
		initialValue = SheetValue.Hidden,
		enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)
	)
	val scope = rememberCoroutineScope()
	val address = when (found) {
		is Found.Server -> found.addresses.first()
		is Found.OlderAt -> found.address
		is Found.NotEncrypted -> return
	}
	// The sheet slides away first, then the answer is acted on.
	val answer = { signIn: Boolean ->
		scope.launch { sheetState.hide() }.invokeOnCompletion {
			if (signIn) signInToFound(preferenceManager, hubManager.serverRecordStore, found, address) { hubManager.signInAndConnect() }
			onAnswered(!signIn)
		}
		Unit
	}
	val title = when (found) {
		is Found.Server -> found.name.ifBlank { stringResource(Res.string.finder_default_name) }
		else -> stringResource(Res.string.finder_default_name)
	}
	val line = when (found) {
		is Found.OlderAt -> stringResource(Res.string.finder_older_at, hubAddressHost(address).orEmpty())
		else -> shownAddress(address)
	}
	ModalBottomSheet(
		onDismissRequest = { onAnswered(true) },
		sheetState = sheetState,
		ambient = rememberNowPlayingCoverAmbient(),
		sheetTitle = title
	) {
		SheetHeader(title, line)
		Column(
			modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
			verticalArrangement = Arrangement.spacedBy(SegmentedListButtonDefaults.SegmentedGap)
		) {
			SegmentedListButton(
				modifier = Modifier.fillMaxWidth(),
				onClick = { answer(true) },
				enabled = account.isNotBlank(),
				colors = SegmentedListButtonDefaults.primaryColors(),
				shapes = SegmentedListButtonDefaults.shapes(index = 0, count = 2)
			) {
				Text(stringResource(Res.string.option_server_sign_in_as, account))
			}
			SegmentedListButton(
				modifier = Modifier.fillMaxWidth(),
				onClick = { answer(false) },
				shapes = SegmentedListButtonDefaults.shapes(index = 1, count = 2)
			) {
				Text(stringResource(Res.string.action_not_now))
			}
		}
		Spacer(Modifier.height(20.dp))
	}
}
