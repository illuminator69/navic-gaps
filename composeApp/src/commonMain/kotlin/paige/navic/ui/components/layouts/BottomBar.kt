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
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import paige.navic.di.LocalNavStack
import paige.navic.domain.manager.ShellController
import paige.navic.ui.navigation.ShellTab
import paige.navic.ui.navigation.isSelected
import paige.navic.ui.navigation.onShellTabTapped
import paige.navic.ui.viewmodel.RootViewModel

/**
 * The phone's bar: the four fixed [ShellTab]s (P1). What is selected and what a tap does are
 * [ShellTab.isSelected] and [onShellTabTapped], the same ones the tablet's rail uses.
 */
@Composable
fun BottomBar(
	modifier: Modifier = Modifier,
	containerColor: Color = NavigationBarDefaults.containerColor,
	windowInsets: WindowInsets,
	enabled: Boolean = true
) {
	val rootViewModel = koinViewModel<RootViewModel>()
	val shellController = koinInject<ShellController>()
	val backStack = LocalNavStack.current
	val top = backStack.lastOrNull()

	NavigationBar(
		modifier = modifier,
		containerColor = containerColor,
		windowInsets = windowInsets
	) {
		ShellTab.entries.forEach { tab ->
			val selected = tab.isSelected(top)
			NavigationBarItem(
				selected = selected,
				enabled = enabled,
				onClick = dropUnlessResumed {
					onShellTabTapped(tab, backStack, rootViewModel, shellController)
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
