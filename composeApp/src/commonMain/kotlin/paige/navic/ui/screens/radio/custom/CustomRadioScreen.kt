package paige.navic.ui.screens.radio.custom

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import org.koin.compose.viewmodel.koinViewModel
import paige.navic.di.LocalNavStack
import paige.navic.domain.manager.PreferenceManager
import paige.navic.domain.manager.ShellController
import paige.navic.ui.screens.library.LibraryChip
import paige.navic.ui.navigation.Screen
import paige.navic.ui.navigation.becomeStack
import paige.navic.ui.util.Material3Transitions
import paige.navic.ui.util.pageMotionOf
import org.koin.compose.koinInject

/*
 * The custom radio (P5): New › Custom radio opens this page, which is two steps, Pick (artists) and
 * Tune (variety, character, the count, Done). Boards RadioPick, RadioTune, RadioTuneUpdating,
 * RadioEmpty, RadioStarted.
 *
 * Board to code:
 *   RadioPick          PickArtistsStep: the grid, the search, "Most played first", "Next, N picked"
 *   RadioTune          TuneStep: the avatars and "+", the title, Artist variety, Song selection
 *   RadioTuneUpdating  TuneStep's footer while CountState.Counting: the last number dimmed, a spinner
 *   RadioEmpty         the footer for Known(0) and Unknown: the warn line, Done disabled
 *   RadioStarted       the mix's page over the Library's Mixes chip (P5 round 2: opened, not played),
 *                      and the snackbar, said by NewSheetHost through ShellController once this page left
 *   (Filters)          not drawn: the hub advertises none (`filters: []`); the slot is the probe's
 */

@Composable
fun CustomRadioScreen() {
	val viewModel = koinViewModel<CustomRadioViewModel>()
	val backStack = LocalNavStack.current
	val shell = koinInject<ShellController>()
	val preferenceManager = koinInject<PreferenceManager>()
	// 0 = Pick, 1 = Tune. Saved with the entry; the choice itself lives in the view model, so a
	// Tune with nothing picked (the process was killed) goes back to Pick.
	var step by rememberSaveable { mutableIntStateOf(0) }
	val picked by viewModel.picked.collectAsStateWithLifecycle()
	LaunchedEffect(step, picked.isEmpty()) {
		if (step == 1 && picked.isEmpty()) step = 0
	}
	NavigationBackHandler(
		state = rememberNavigationEventState(NavigationEventInfo.None),
		isBackEnabled = step == 1,
		onBackCompleted = { step = 0 }
	)
	val close = {
		val index = backStack.indexOfLast { it == Screen.CustomRadio }
		if (index >= 0) backStack.removeAt(index)
	}
	val density = LocalDensity.current
	AnimatedContent(
		targetState = step,
		// The two steps move as two pages do (I-042: the animation style's timing).
		transitionSpec = {
			val motion = pageMotionOf(preferenceManager.animationStyle)
			if (targetState > initialState) {
				Material3Transitions.enter(motion)(density) togetherWith Material3Transitions.exit(motion)(density)
			} else {
				Material3Transitions.popEnter(motion)(density) togetherWith Material3Transitions.popExit(motion)(density)
			}
		},
		label = "customRadioStep"
	) { shown ->
		when (shown) {
			0 -> PickArtistsStep(viewModel, onNext = { step = 1 }, onClose = { close() })
			else -> TuneStep(
				viewModel,
				onAddArtists = { step = 0 },
				onClose = { close() },
				onDone = { mixId ->
					// The radio opens as its own page (P5 round 2), over the Library's Mixes chip where it
					// is kept: the Library tab as a tab tap opens it, on that chip,
					// and the mix's page on top. It is built there, not played.
					shell.showLibraryChip(LibraryChip.Mixes.name)
					// Home under the Library, as a tab tap leaves it (P6): Back goes Library, then Home.
					backStack.becomeStack(listOf(Screen.Home, Screen.Library(), Screen.MixDetail(mixId)))
				}
			)
		}
	}
}
