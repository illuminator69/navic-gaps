package paige.navic.ui.components.layouts

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.dropUnlessResumed
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import paige.navic.di.LocalNavStack
import paige.navic.ui.navigation.rememberVisibleNavigationTabs
import paige.navic.ui.navigation.Screen
import paige.navic.ui.viewmodel.RootViewModel

@Composable
fun BottomBar(
	modifier: Modifier = Modifier,
	containerColor: Color = NavigationBarDefaults.containerColor,
	windowInsets: WindowInsets,
	enabled: Boolean = true
) {
	val rootViewModel = koinViewModel<RootViewModel>()
	val backStack = LocalNavStack.current
	val tabs = rememberVisibleNavigationTabs()

	val onTabSelected = { destination: Screen ->
		if (backStack.lastOrNull() == destination) {
			rootViewModel.requestScrollToTop()
		} else {
			backStack.apply {
				clear()
				add(destination)
			}
		}
	}

	NavigationBar(
		modifier = modifier,
		containerColor = containerColor,
		windowInsets = windowInsets
	) {
		tabs.forEach { tab ->
			val selected = backStack.lastOrNull() == tab.destination
			NavigationBarItem(
				selected = selected,
				enabled = enabled,
				onClick = dropUnlessResumed {
					onTabSelected(tab.destination)
				},
				icon = {
					if (selected) {
						Icon(tab.iconFilled, null)
					} else {
						Icon(tab.iconOutlined, null)
					}
				},
				label = { Text(stringResource(tab.label)) }
			)
		}
	}
}
