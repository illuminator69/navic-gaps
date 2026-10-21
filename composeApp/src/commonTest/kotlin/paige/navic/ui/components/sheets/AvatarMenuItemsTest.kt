package paige.navic.ui.components.sheets

import paige.navic.ui.navigation.Screen
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/*
 * P1, then P6: the avatar menu holds what the four-button bar does not, in the board's order: the
 * groups, then Settings, the server's Shares where the user may make them, and Log out last. Since P6
 * lb-bot's operator half is Library tools: Needs attention (with lb-bot's count), Source history,
 * Library Matches, then the rare tools that open lb-bot's web UI, drawn only when the hub says where
 * it is. Nothing that needs lb-bot is drawn without it, with no gap.
 */
class AvatarMenuItemsTest {

	private fun menu(lb: Boolean = true, share: Boolean = true, attention: Int = 0, webUrl: String = "") =
		avatarMenuItems(lbConfigured = lb, canShare = share, attentionCount = attention, webUrl = webUrl)

	private fun List<AvatarMenuEntry>.flat(): List<AvatarMenuEntry> =
		flatMap { if (it is AvatarMenuEntry.Group) listOf(it) + it.entries.flat() else listOf(it) }

	private fun List<AvatarMenuEntry>.ids() = map { it.id }

	private fun List<AvatarMenuEntry>.group(id: String) =
		filterIsInstance<AvatarMenuEntry.Group>().first { it.id == id }

	private fun List<AvatarMenuEntry>.link(id: String) =
		flat().filterIsInstance<AvatarMenuEntry.Link>().first { it.id == id }

	private fun List<AvatarMenuEntry>.external(id: String) =
		flat().filterIsInstance<AvatarMenuEntry.External>().first { it.id == id }

	@Test
	fun withoutLbBotThereIsNoLibraryToolsAtAll() {
		val all = menu(lb = false, attention = 4, webUrl = "http://lb:8899").flat().ids()
		listOf(
			AvatarMenuIds.ATTENTION, AvatarMenuIds.SOURCE_HISTORY, AvatarMenuIds.LIBRARY_TOOLS,
			AvatarMenuIds.LIBRARY_MATCHES, AvatarMenuIds.DUPLICATES, AvatarMenuIds.SOURCE_RULES,
			AvatarMenuIds.LOGS
		).forEach { assertFalse(it in all, "$it should be absent without lb-bot") }
		// ...and what is left has no gap: the groups and rows that need nothing are all still there.
		assertEquals(
			listOf(
				AvatarMenuIds.LISTENING, AvatarMenuIds.APP, AvatarMenuIds.SETTINGS,
				AvatarMenuIds.SHARES, AvatarMenuIds.LOG_OUT
			),
			menu(lb = false).ids()
		)
	}

	@Test
	fun attentionAndSourceHistoryLeaveTheTopForLibraryTools() {
		val top = menu().ids()
		assertFalse(AvatarMenuIds.ATTENTION in top)
		assertFalse(AvatarMenuIds.SOURCE_HISTORY in top)
		assertEquals(
			listOf(AvatarMenuIds.ATTENTION, AvatarMenuIds.SOURCE_HISTORY, AvatarMenuIds.LIBRARY_MATCHES),
			menu().group(AvatarMenuIds.LIBRARY_TOOLS).entries.ids()
		)
	}

	@Test
	fun theRareToolsAreThereOnlyWithAWebUrlInOrder() {
		assertEquals(
			listOf(
				AvatarMenuIds.ATTENTION, AvatarMenuIds.SOURCE_HISTORY, AvatarMenuIds.LIBRARY_MATCHES,
				AvatarMenuIds.DUPLICATES, AvatarMenuIds.SOURCE_RULES, AvatarMenuIds.LOGS
			),
			menu(webUrl = "http://lb:8899").group(AvatarMenuIds.LIBRARY_TOOLS).entries.ids()
		)
		assertEquals(3, menu(webUrl = "  ").group(AvatarMenuIds.LIBRARY_TOOLS).entries.size)
		val items = menu(webUrl = "http://lb:8899")
		assertEquals("library/cleanup", items.external(AvatarMenuIds.DUPLICATES).path)
		assertEquals("system/sources", items.external(AvatarMenuIds.SOURCE_RULES).path)
		assertEquals("system/logs", items.external(AvatarMenuIds.LOGS).path)
	}

	@Test
	fun needsAttentionShowsItsCount() {
		assertEquals(20, menu(attention = 20).link(AvatarMenuIds.ATTENTION).badge)
		assertEquals(1, menu(attention = 1).link(AvatarMenuIds.ATTENTION).badge)
	}

	@Test
	fun nothingNeedingAttentionShowsNoBadgeRatherThanAZero() {
		assertNull(menu(attention = 0).link(AvatarMenuIds.ATTENTION).badge)
		assertNull(menu(attention = 0).link(AvatarMenuIds.SOURCE_HISTORY).badge)
	}

	@Test
	fun sharesOnlyWhenAllowed() {
		assertTrue(AvatarMenuIds.SHARES in menu(share = true).ids())
		assertFalse(AvatarMenuIds.SHARES in menu(share = false).ids())
		assertFalse(AvatarMenuIds.SHARES in menu(lb = false, share = false).flat().ids())
	}

	@Test
	fun theGroupsAreInTheBoardsOrder() {
		assertEquals(
			listOf(
				AvatarMenuIds.LISTENING, AvatarMenuIds.LIBRARY_TOOLS, AvatarMenuIds.APP,
				AvatarMenuIds.SETTINGS, AvatarMenuIds.SHARES, AvatarMenuIds.LOG_OUT
			),
			menu().ids()
		)
		val items = menu()
		assertEquals(
			listOf(AvatarMenuIds.STATISTICS, AvatarMenuIds.SAVED_QUEUES),
			items.group(AvatarMenuIds.LISTENING).entries.ids()
		)
		assertEquals(
			listOf(
				AvatarMenuIds.AUTOPLAY, AvatarMenuIds.DOWNLOADS, AvatarMenuIds.APPEARANCE,
				AvatarMenuIds.ATTENTION_DOT, AvatarMenuIds.ABOUT
			),
			items.group(AvatarMenuIds.APP).entries.ids()
		)
	}

	@Test
	fun logOutIsLast() {
		assertEquals(AvatarMenuIds.LOG_OUT, menu(lb = true, share = true).last().id)
		assertEquals(AvatarMenuIds.LOG_OUT, menu(lb = false, share = false).last().id)
		assertIs<AvatarMenuEntry.Action>(menu().last())
	}

	@Test
	fun everyRowGoesWhereTheBoardSays() {
		val items = menu()
		assertEquals(Screen.Attention, items.link(AvatarMenuIds.ATTENTION).destination)
		assertEquals(Screen.SourceHistory(), items.link(AvatarMenuIds.SOURCE_HISTORY).destination)
		assertEquals(Screen.Statistics(nested = true), items.link(AvatarMenuIds.STATISTICS).destination)
		assertEquals(Screen.SavedQueues, items.link(AvatarMenuIds.SAVED_QUEUES).destination)
		assertEquals(Screen.LibraryMatches, items.link(AvatarMenuIds.LIBRARY_MATCHES).destination)
		assertEquals(Screen.Downloads(), items.link(AvatarMenuIds.DOWNLOADS).destination)
		assertEquals(Screen.Settings.Appearance, items.link(AvatarMenuIds.APPEARANCE).destination)
		assertEquals(Screen.Settings.About, items.link(AvatarMenuIds.ABOUT).destination)
		assertEquals(Screen.Settings.Root, items.link(AvatarMenuIds.SETTINGS).destination)
		assertEquals(Screen.ShareList, items.link(AvatarMenuIds.SHARES).destination)
	}

	@Test
	fun theTwoSwitchesAreToggles() {
		val app = menu().group(AvatarMenuIds.APP).entries
		assertIs<AvatarMenuEntry.Toggle>(app.first { it.id == AvatarMenuIds.AUTOPLAY })
		assertIs<AvatarMenuEntry.Toggle>(app.first { it.id == AvatarMenuIds.ATTENTION_DOT })
	}

	@Test
	fun listeningNeverFoldsLibraryToolsStartOpenAndAppStartsClosed() {
		val items = menu()
		assertEquals(GroupFold.Never, items.group(AvatarMenuIds.LISTENING).fold)
		assertEquals(GroupFold.StartsOpen, items.group(AvatarMenuIds.LIBRARY_TOOLS).fold)
		assertEquals(GroupFold.StartsClosed, items.group(AvatarMenuIds.APP).fold)
	}

	@Test
	fun everyIdIsUnique() {
		val all = menu(webUrl = "http://lb:8899").flat().ids()
		assertEquals(all.size, all.toSet().size)
	}
}
