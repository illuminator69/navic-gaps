package paige.navic.ui.components.layouts

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.dropUnlessResumed
import navic.composeapp.generated.resources.Res
import navic.composeapp.generated.resources.title_account
import navic.composeapp.generated.resources.title_search
import navic.composeapp.generated.resources.title_settings
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import paige.navic.di.LocalNavStack
import paige.navic.domain.models.settings.NavbarConfig
import paige.navic.domain.models.settings.NavbarTab
import paige.navic.icons.Icons
import paige.navic.icons.filled.Settings
import paige.navic.icons.outlined.AccountCircle
import paige.navic.icons.outlined.Search
import paige.navic.ui.components.common.TooltipBox
import paige.navic.ui.components.sheets.AccountSheet
import paige.navic.ui.core.UiState
import paige.navic.ui.navigation.Screen
import paige.navic.ui.screens.settings.viewmodels.NavtabsViewModel

@Composable
fun RootTopBar(
	title: @Composable () -> Unit,
	scrollBehavior: TopAppBarScrollBehavior,
	actions: @Composable RowScope.() -> Unit = {},
) {
	val navViewModel = koinViewModel<NavtabsViewModel>()
	val navState by navViewModel.state.collectAsState()
	val navConfig = (navState as? UiState.Success)?.data

	MediumFlexibleTopAppBar(
		title = {
			CompositionLocalProvider(
				LocalTextStyle provides when (LocalTextStyle.current) {
					MaterialTheme.typography.headlineMedium -> MaterialTheme.typography.headlineSmall
					else -> MaterialTheme.typography.titleLarge
				}
			) {
				title()
			}
		},
		actions = {
			actions()
			Actions(navConfig = navConfig)
		},
		scrollBehavior = scrollBehavior,
		// TRANSPARENT at rest, so the page's cover gradient runs unbroken up under the status
		// bar. The default `containerColor` is `surface` — the scheme's own neutral — which over
		// a gradient painted from `coverAmbientGradient` is a visibly darker, flatter band across
		// the top of the page, status bar included. The album page's bar has always been
		// transparent-until-scrolled for the same reason (`collection/components/TopBar.kt`), and
		// this is the browsing pages' version of it.
		//
		// Safe with cover theming off too: the bar then shows the Scaffold's `background`, which
		// in a generated scheme is the same tone as the `surface` it used to paint.
		// Fully transparent, both states: the page behind this bar is the cover ambient, which is
		// a gradient composited over blurred artwork — no flat colour can match it, and any
		// container colour draws a band across the top of the page. Nothing is lost by dropping
		// it: the collapsing bar takes up the scroll offset, so content does not end up under the
		// title. Content that paints an opaque page background WOULD break this, which is why the
		// list screens no longer do (see AlbumListScreen).
		colors = TopAppBarDefaults.topAppBarColors(
			containerColor = Color.Transparent,
			scrolledContainerColor = Color.Transparent
		),
	)
}

@Composable
private fun Actions(
	navConfig: NavbarConfig?,
) {
	val backStack = LocalNavStack.current

	val isSearchEnabled = navConfig?.tabs?.any {
		it.id == NavbarTab.Id.SEARCH && it.visible
	} == true

	var accountSheetOpen by rememberSaveable { mutableStateOf(false) }

	if (!isSearchEnabled) {
		TooltipBox(stringResource(Res.string.title_search)) {
			IconButton(
				onClick = dropUnlessResumed {
					backStack.add(Screen.Search(nested = true))
				}
			) {
				Icon(
					imageVector = Icons.Outlined.Search,
					contentDescription = stringResource(Res.string.title_search)
				)
			}
		}
	}

	TooltipBox(stringResource(Res.string.title_settings)) {
		IconButton(onClick = dropUnlessResumed {
			backStack.add(Screen.Settings.Root)
		}) {
			Icon(
				imageVector = Icons.Filled.Settings,
				contentDescription = stringResource(Res.string.title_settings)
			)
		}
	}

	TooltipBox(stringResource(Res.string.title_account)) {
		IconButton(onClick = {
			accountSheetOpen = true
		}) {
			Icon(
				imageVector = Icons.Outlined.AccountCircle,
				contentDescription = stringResource(Res.string.title_account)
			)
		}
	}

	if (accountSheetOpen) {
		AccountSheet(onDismissRequest = { accountSheetOpen = false })
	}
}
