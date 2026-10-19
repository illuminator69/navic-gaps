package paige.navic.ui.navigation

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import navic.composeapp.generated.resources.Res
import navic.composeapp.generated.resources.title_home
import navic.composeapp.generated.resources.title_library
import navic.composeapp.generated.resources.title_new
import navic.composeapp.generated.resources.title_search
import org.jetbrains.compose.resources.StringResource
import paige.navic.domain.manager.ShellController
import paige.navic.icons.Icons
import paige.navic.icons.filled.Home
import paige.navic.icons.filled.LibraryMusic
import paige.navic.icons.outlined.Add
import paige.navic.icons.outlined.Home
import paige.navic.icons.outlined.LibraryMusic
import paige.navic.icons.outlined.Search
import paige.navic.ui.viewmodel.RootViewModel

/**
 * navi-connect P1: the bar's four fixed buttons, in the order they are drawn, on the phone's bar and
 * the tablet's rail alike. It replaces the user-arranged list of [NavigationTab]s, which the
 * Library home now reaches (Albums, Playlists, Artists) and the avatar menu holds the rare tools of.
 *
 * [New] is not a page: it has no [destination], is never selected and opens a sheet.
 */
enum class ShellTab(
	val destination: Screen?,
	val iconFilled: ImageVector,
	val iconOutlined: ImageVector,
	val label: StringResource
) {
	Home(Screen.Home, Icons.Filled.Home, Icons.Outlined.Home, Res.string.title_home),
	Search(Screen.Search(), Icons.Outlined.Search, Icons.Outlined.Search, Res.string.title_search),
	Library(Screen.Library(), Icons.Filled.LibraryMusic, Icons.Outlined.LibraryMusic, Res.string.title_library),
	New(null, Icons.Outlined.Add, Icons.Outlined.Add, Res.string.title_new)
}

/** Whether [top], the page on top of the back stack, is this tab's own page. A pushed page selects none. */
fun ShellTab.isSelected(top: NavKey?): Boolean = destination != null && destination == top

/** What a tap on a button does. */
sealed interface ShellTap {
	/** The tab is already showing: take its list back to the top. */
	data object ScrollToTop : ShellTap

	/** Go to the tab's page, with nothing under it. */
	data class Navigate(val destination: Screen) : ShellTap

	/** Open the New sheet over whatever is showing; the stack and the selected tab stay as they are. */
	data object OpenNew : ShellTap
}

fun shellTap(tab: ShellTab, top: NavKey?): ShellTap {
	val destination = tab.destination ?: return ShellTap.OpenNew
	return if (tab.isSelected(top)) ShellTap.ScrollToTop else ShellTap.Navigate(destination)
}

/**
 * The one tap handler for the bar and the rail, so they cannot disagree. A tab switch clears the
 * back stack (every root page's ViewModel dies with it unless it opts out: CLAUDE.md §2 rule 14).
 */
fun onShellTabTapped(
	tab: ShellTab,
	backStack: NavBackStack<NavKey>,
	rootViewModel: RootViewModel,
	shellController: ShellController
) {
	when (val tap = shellTap(tab, backStack.lastOrNull())) {
		ShellTap.ScrollToTop -> rootViewModel.requestScrollToTop()
		is ShellTap.Navigate -> backStack.apply {
			clear()
			add(tap.destination)
		}
		ShellTap.OpenNew -> shellController.openNew()
	}
}
