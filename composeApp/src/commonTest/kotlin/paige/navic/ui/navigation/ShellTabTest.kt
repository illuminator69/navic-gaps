package paige.navic.ui.navigation

import androidx.navigation3.runtime.NavKey
import paige.navic.domain.models.DomainAlbumListType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/*
 * P1: the bar is four fixed buttons, Home, Search, Library and New, the same on the phone's bar and the
 * tablet's rail. What is selected and what a tap does are one rule, so the two cannot disagree.
 *
 * New is not a page: it opens a sheet over whatever is showing, never selects, and never touches the
 * back stack (the stack and the selected tab are a page's business).
 */
class ShellTabTest {

	private val pages: List<NavKey> = listOf(
		Screen.Home, Screen.Search(), Screen.Library(), Screen.AlbumList(nested = true),
		Screen.CollectionDetail("al-1", ""), Screen.Settings.Root, Screen.NowPlaying
	)

	@Test
	fun theBarIsFourButtonsInTheBoardsOrder() {
		assertEquals(
			listOf(ShellTab.Home, ShellTab.Search, ShellTab.Library, ShellTab.New),
			ShellTab.entries.toList()
		)
	}

	@Test
	fun onlyNewIsNotAPage() {
		assertEquals(Screen.Home, ShellTab.Home.destination)
		assertEquals(Screen.Search(), ShellTab.Search.destination)
		assertEquals(Screen.Library(), ShellTab.Library.destination)
		assertNull(ShellTab.New.destination)
	}

	@Test
	fun homeIsSelectedOnlyOnHome() {
		assertTrue(ShellTab.Home.isSelected(Screen.Home))
		assertFalse(ShellTab.Search.isSelected(Screen.Home))
		assertFalse(ShellTab.Library.isSelected(Screen.Home))
		assertFalse(ShellTab.Home.isSelected(Screen.Library()))
		assertFalse(ShellTab.Home.isSelected(null))
	}

	@Test
	fun aPushedPageSelectsNoTab() {
		// The same as the old bar: a page pushed over a tab is not the tab.
		val top = Screen.AlbumList(nested = true, listType = DomainAlbumListType.Newest)
		ShellTab.entries.forEach { assertFalse(it.isSelected(top), "$it on $top") }
	}

	@Test
	fun newIsNeverSelected() {
		pages.forEach { assertFalse(ShellTab.New.isSelected(it), "New on $it") }
		assertFalse(ShellTab.New.isSelected(null))
	}

	@Test
	fun tappingTheSelectedTabScrollsToTop() {
		assertEquals(ShellTap.ScrollToTop, shellTap(ShellTab.Home, Screen.Home))
		assertEquals(ShellTap.ScrollToTop, shellTap(ShellTab.Library, Screen.Library()))
		assertEquals(ShellTap.ScrollToTop, shellTap(ShellTab.Search, Screen.Search()))
	}

	@Test
	fun tappingAnotherTabNavigatesToIt() {
		assertEquals(ShellTap.Navigate(Screen.Library()), shellTap(ShellTab.Library, Screen.Home))
		assertEquals(ShellTap.Navigate(Screen.Home), shellTap(ShellTab.Home, Screen.Library()))
		assertEquals(ShellTap.Navigate(Screen.Search()), shellTap(ShellTab.Search, Screen.Home))
	}

	@Test
	fun tappingATabFromAPushedPageGoesToItRatherThanScrolling() {
		// Library's own stack holds Library then Albums: the Library button is not "selected" there,
		// so it clears the stack back to the Library page, as it did for the old bar.
		val top = Screen.AlbumList(nested = true)
		assertEquals(ShellTap.Navigate(Screen.Library()), shellTap(ShellTab.Library, top))
		assertEquals(ShellTap.Navigate(Screen.Home), shellTap(ShellTab.Home, top))
	}

	@Test
	fun newOpensTheSheetFromAnywhere() {
		pages.forEach { assertEquals(ShellTap.OpenNew, shellTap(ShellTab.New, it), "on $it") }
		assertEquals(ShellTap.OpenNew, shellTap(ShellTab.New, null))
	}
}
