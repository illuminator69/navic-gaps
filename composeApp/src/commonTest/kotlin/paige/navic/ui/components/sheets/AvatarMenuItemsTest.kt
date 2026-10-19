package paige.navic.ui.components.sheets

import paige.navic.ui.navigation.Screen
import paige.navic.ui.screens.downloads.DownloadsSection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/*
 * P1: the avatar menu holds what the four-button bar does not, in the board's order: lb-bot's two
 * entries first (they are about something that needs a person), then the groups, then Settings, the
 * server's Shares where the user may make them, and Log out last. What is in it, in what order,
 * where each row goes and which groups fold are decided here as data, so the drawer only draws them.
 *
 * Nothing is drawn for a screen that does not exist yet (Artist matching, Overrides, Duplicates and
 * trash, Source rules, Logs: P6), and nothing that needs lb-bot is drawn without it, with no gap.
 */
class AvatarMenuItemsTest {

	private fun menu(lb: Boolean = true, share: Boolean = true, attention: Int = 0) =
		avatarMenuItems(lbConfigured = lb, canShare = share, attentionCount = attention)

	private fun List<AvatarMenuEntry>.flat(): List<AvatarMenuEntry> =
		flatMap { if (it is AvatarMenuEntry.Group) listOf(it) + it.entries.flat() else listOf(it) }

	private fun List<AvatarMenuEntry>.ids() = map { it.id }

	private fun List<AvatarMenuEntry>.group(id: String) =
		filterIsInstance<AvatarMenuEntry.Group>().first { it.id == id }

	private fun List<AvatarMenuEntry>.link(id: String) =
		flat().filterIsInstance<AvatarMenuEntry.Link>().first { it.id == id }

	@Test
	fun withoutLbBotThereIsNoAttentionActivityOrLibraryTools() {
		val all = menu(lb = false, attention = 4).flat().ids()
		listOf(
			AvatarMenuIds.ATTENTION, AvatarMenuIds.ACTIVITY,
			AvatarMenuIds.LIBRARY_TOOLS, AvatarMenuIds.LIBRARY_MATCHES
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
	fun withLbBotTheTwoEntriesAndLibraryToolsAreThere() {
		val items = menu(lb = true)
		assertTrue(AvatarMenuIds.ATTENTION in items.ids())
		assertTrue(AvatarMenuIds.ACTIVITY in items.ids())
		assertEquals(listOf(AvatarMenuIds.LIBRARY_MATCHES), items.group(AvatarMenuIds.LIBRARY_TOOLS).entries.ids())
	}

	@Test
	fun needsAttentionShowsItsCount() {
		assertEquals(3, menu(attention = 3).link(AvatarMenuIds.ATTENTION).badge)
		assertEquals(1, menu(attention = 1).link(AvatarMenuIds.ATTENTION).badge)
	}

	@Test
	fun nothingNeedingAttentionShowsNoBadgeRatherThanAZero() {
		assertNull(menu(attention = 0).link(AvatarMenuIds.ATTENTION).badge)
		assertNull(menu(attention = 0).link(AvatarMenuIds.ACTIVITY).badge)
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
				AvatarMenuIds.ATTENTION, AvatarMenuIds.ACTIVITY, AvatarMenuIds.LISTENING,
				AvatarMenuIds.LIBRARY_TOOLS, AvatarMenuIds.APP, AvatarMenuIds.SETTINGS,
				AvatarMenuIds.SHARES, AvatarMenuIds.LOG_OUT
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
		// Needs attention and Activity both open the fills list until P6 builds their screens.
		assertEquals(Screen.Downloads(DownloadsSection.Fills), items.link(AvatarMenuIds.ATTENTION).destination)
		assertEquals(Screen.Downloads(DownloadsSection.Fills), items.link(AvatarMenuIds.ACTIVITY).destination)
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
		val all = menu().flat().ids()
		assertEquals(all.size, all.toSet().size)
	}
}
