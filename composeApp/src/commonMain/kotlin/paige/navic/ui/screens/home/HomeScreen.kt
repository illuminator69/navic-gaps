package paige.navic.ui.screens.home

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import navic.composeapp.generated.resources.Res
import navic.composeapp.generated.resources.title_home
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import paige.navic.di.LocalBottomBarScrollManager
import paige.navic.ui.components.layouts.RootBottomBar
import paige.navic.ui.components.layouts.RootTopBar
import paige.navic.ui.util.withoutTop
import paige.navic.ui.viewmodel.RootViewModel

/**
 * Home (P1): where the app opens. A root page shaped like `AlbumListScreen` (a washed `Scaffold`,
 * the root bars, one lazy list), App.kt wraps it in `Washed` and `WashedBrowsingScreens` names it.
 * It is empty until the sections land (Task 9).
 */
@Composable
fun HomeScreen() {
	val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
	val listState = rememberLazyListState()

	val rootViewModel = koinViewModel<RootViewModel>()
	LaunchedEffect(Unit) {
		rootViewModel.events.collect { event ->
			if (event is RootViewModel.Event.ScrollToTop) {
				listState.animateScrollToItem(0)
			}
		}
	}

	Scaffold(
		topBar = {
			RootTopBar({ Text(stringResource(Res.string.title_home)) }, scrollBehavior)
		},
		bottomBar = {
			val scrollManager = LocalBottomBarScrollManager.current
			RootBottomBar(scrolled = scrollManager.isTriggered)
		}
	) { innerPadding ->
		LazyColumn(
			modifier = Modifier
				.padding(top = innerPadding.calculateTopPadding())
				.nestedScroll(scrollBehavior.nestedScrollConnection),
			state = listState,
			contentPadding = innerPadding.withoutTop()
		) {
		}
	}
}
