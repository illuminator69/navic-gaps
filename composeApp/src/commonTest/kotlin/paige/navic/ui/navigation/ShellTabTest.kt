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

	// --- P6: Back from Search or Library goes Home ----------------------------------------------

	private val home: NavKey = Screen.Home
	private val search: NavKey = Screen.Search()
	private val library: NavKey = Screen.Library()
	private val album: NavKey = Screen.CollectionDetail("al-1", "")

	@Test
	fun aTabTapLeavesHomeUnderSearchAndLibrary() {
		assertEquals(listOf(home, search), stackAfterTap(listOf(home), ShellTab.Search))
		assertEquals(listOf(home, library), stackAfterTap(listOf(home), ShellTab.Library))
	}

	@Test
	fun searchToLibraryReplacesRatherThanPushes() {
		assertEquals(listOf(home, library), stackAfterTap(listOf(home, search), ShellTab.Library))
		assertEquals(listOf(home, search), stackAfterTap(listOf(home, library), ShellTab.Search))
	}

	@Test
	fun aTapFromAPushedPageIsTheTabsOwnPageAgain() {
		assertEquals(listOf(home, library), stackAfterTap(listOf(home, library, album), ShellTab.Library))
		assertEquals(listOf(home, search), stackAfterTap(listOf(home, library, album), ShellTab.Search))
	}

	@Test
	fun homeIsHomeAloneFromAnywhere() {
		listOf(listOf(home), listOf(home, search), listOf(home, library, album), listOf(search)).forEach {
			assertEquals(listOf(home), stackAfterTap(it, ShellTab.Home), "from $it")
		}
	}

	@Test
	fun theSelectedTabStillScrollsToTopAndNewTouchesNothing() {
		assertEquals(ShellTap.ScrollToTop, shellTap(ShellTab.Search, search))
		assertNull(stackAfterTap(listOf(home, search), ShellTab.New))
	}

	/** A back stack that records its edits: what was removed (by index) and what was added. */
	private class RecordingStack(vararg keys: NavKey) : ArrayList<NavKey>(keys.toList()) {
		val removedAt = mutableListOf<Int>()
		val added = mutableListOf<NavKey>()
		override fun removeAt(index: Int): NavKey = super.removeAt(index).also { removedAt += index }
		override fun add(element: NavKey): Boolean = super.add(element).also { added += element }
		override fun clear() {
			removedAt += indices.reversed()
			super.clear()
		}
	}

	@Test
	fun theSmallestEditsKeepHomesEntry() {
		val stack = RecordingStack(home, library, album)
		stack.becomeStack(listOf(home, search))
		assertEquals(listOf(home, search), stack.toList())
		assertFalse(0 in stack.removedAt, "Home's entry is never removed (its saved state goes with it)")
		assertEquals(listOf(search), stack.added)

		val back = RecordingStack(home, search)
		back.becomeStack(listOf(home))
		assertEquals(listOf(home), back.toList())
		assertEquals(listOf(1), back.removedAt)
		assertTrue(back.added.isEmpty())

		val legacy = RecordingStack(search)
		legacy.becomeStack(listOf(home, library))
		assertEquals(listOf(home, library), legacy.toList())
	}

	@Test
	fun aLegacyStackGetsHomeUnderItOnce() {
		assertEquals(listOf(home, search), normalizedStack(listOf(search)))
		assertEquals(listOf(home, library, album), normalizedStack(listOf(library, album)))
		assertEquals(listOf(home, search), normalizedStack(listOf(home, search)))
		assertEquals(listOf(home), normalizedStack(listOf(home)))
		assertEquals(listOf(home, search), normalizedStack(normalizedStack(listOf(search))), "once")
	}

	/**
	 * B-181 / B-180: a stack that already holds Home somewhere is never given a second one at the
	 * bottom. `[Search, Home]` would have become `[Home, Search, Home]`, the shape B-180 saw.
	 */
	@Test
	fun aStackThatAlreadyHoldsHomeIsNotGivenAnother() {
		assertEquals(listOf(search, home), normalizedStack(listOf(search, home)))
		assertEquals(listOf(library, album, home), normalizedStack(listOf(library, album, home)))
		assertEquals(listOf(home, search, home), normalizedStack(listOf(home, search, home)), "left as it was")
	}

	@Test
	fun aStackThatStartsWithAnotherPageIsUnchanged() {
		assertEquals(listOf<NavKey>(Screen.Login), normalizedStack(listOf(Screen.Login)))
		assertEquals(listOf(album), normalizedStack(listOf(album)))
		assertEquals(listOf<NavKey>(Screen.Search(nested = true)), normalizedStack(listOf(Screen.Search(nested = true))))
		assertEquals(emptyList(), normalizedStack(emptyList()))
	}

	@Test
	fun aRootPageIsATabsOwnPage() {
		assertTrue(isRootPage(home))
		assertTrue(isRootPage(search))
		assertTrue(isRootPage(library))
		assertFalse(isRootPage(album))
		assertFalse(isRootPage(Screen.Search(nested = true)), "a nested Search is not the tab's page")
		assertFalse(isRootPage(Screen.Library(nested = true)))
		assertFalse(isRootPage(null))
	}

	// Audit L2-04 (W-02, ran): the Library's magnifier pushed Search over the Library, so Back from
	// Search landed on the Library. It is the Search tab's tap: Back from Search goes Home.
	@Test
	fun theLibraryMagnifierLeavesHomeAndSearch() {
		assertEquals(listOf(home, search), searchEntryStack(listOf(home, library)))
		assertEquals(listOf(home, search), searchEntryStack(listOf(home)))
		assertEquals(listOf(home, search), searchEntryStack(listOf(home, search)))
	}
}
