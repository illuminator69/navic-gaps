package paige.navic.domain.manager

import navic.composeapp.generated.resources.Res
import navic.composeapp.generated.resources.error_lbbot_too_old
import navic.composeapp.generated.resources.info_wishlist_unsupported
import navic.composeapp.generated.resources.search_lookup_failed
import paige.navic.ui.components.common.FillText
import paige.navic.ui.components.common.describeFill
import paige.navic.ui.components.sheets.AvatarMenuEntry
import paige.navic.ui.components.sheets.AvatarMenuIds
import paige.navic.ui.components.sheets.aboutRefreshMessage
import paige.navic.ui.components.sheets.avatarMenuItems
import paige.navic.ui.components.sheets.lbUnavailableRes
import paige.navic.ui.components.sheets.menuGroupSlots
import paige.navic.ui.screens.downloads.wishlistUnsupportedLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/*
 * Worbus P2, lane N's walk on the AVD (NB2, NB3, NB5): the rules behind what the walk saw, pure.
 */
class LaneNWalkTest {

	// ---- NB2: every lb-bot surface says too-old as the hub says it (R26) ------------------------- //

	@Test
	fun the_wishlist_says_too_old_in_the_hubs_words() {
		assertEquals(Res.string.error_lbbot_too_old, wishlistUnsupportedLine(tooOld = true))
		assertEquals(Res.string.info_wishlist_unsupported, wishlistUnsupportedLine(tooOld = false), "absent lb-bot as before")
		assertEquals(Res.string.error_lbbot_too_old, lbUnavailableRes(true, Res.string.search_lookup_failed))
		assertEquals(Res.string.search_lookup_failed, lbUnavailableRes(false, Res.string.search_lookup_failed))
	}

	@Test
	fun about_refresh_says_too_old_not_unreachable() {
		val refused = LbBotManager.LbResult.Failed(LbBotManager.LbError.AccessRefused(LbRefusal.TOO_OLD))
		val notice = aboutRefreshNotice(aboutRefreshOutcome(null, metaRefreshOf(refused)))
		assertEquals(AboutRefreshNotice.TOO_OLD, notice)
		assertEquals(Res.string.error_lbbot_too_old, aboutRefreshMessage(notice))
	}

	@Test
	fun a_failing_fill_poll_says_too_old() {
		val entry = LbFillEntry(
			key = "rg-1", isGap = false, rgid = "rg-1", groupId = "", releaseMbid = "rel-1",
			artist = "After", album = "After EP", quality = "", state = "downloading", outcome = "running",
			reason = "", percent = 40, done = 2, total = 5, mp3WouldHelp = false, failureKind = "",
			retryable = true, attempts = 1, settled = false, startedAt = 0L, finishedAt = 0L,
			bytesDone = 1L, bytesTotal = 2L, lastSource = "bob", leftOut = 0,
			lastCheckedAt = 1_000L, lastErrorTicks = 3
		)
		val old = describeFill(entry, now = 9_000L, wishlistAvailable = false, tooOld = true).sublines
		assertTrue(FillText.Res(Res.string.error_lbbot_too_old) in old, "$old")
		val down = describeFill(entry, now = 9_000L, wishlistAvailable = false, tooOld = false).sublines
		assertFalse(FillText.Res(Res.string.error_lbbot_too_old) in down)
	}

	// ---- Re-walk NM2: no empty-state claim about a list that was not read --------------------- //

	@Test
	fun nothing_to_fix_only_for_a_list_that_was_read() {
		val empty = AttentionPage.Rows(albums = emptyList(), folders = emptyList(), artistsToCheck = null, toFix = 0, notices = emptyList())
		assertTrue(attentionEmptyShown(empty), "lb-bot answered, nothing listed")
		// Too old, unavailable or refused: the read failed, and only the one sentence is said.
		assertFalse(attentionEmptyShown(empty.copy(notices = listOf(AttentionNotice.Unreachable(since = null)))))
		assertFalse(attentionEmptyShown(empty.copy(notices = listOf(AttentionNotice.Unreachable(since = 1_000L)))))
		assertFalse(attentionEmptyShown(empty.copy(toFix = null, notices = listOf(AttentionNotice.Older))), "older: only this phone's rows")
		assertFalse(attentionEmptyShown(empty.copy(artistsToCheck = 2)), "something is listed")
	}

	// ---- NB3: a non-admin never reads /lb/attention, and a refusal logs one line ----------------- //

	@Test
	fun a_non_admin_never_reads_attention() {
		assertFalse(shouldRefreshAttention(now = 100_000L, readAt = 0L, force = true, inFlight = false, admin = false))
		assertFalse(shouldRefreshAttention(now = 100_000L, readAt = 0L, force = false, inFlight = false, admin = false))
		assertTrue(shouldRefreshAttention(now = 100_000L, readAt = 0L, force = false, inFlight = false, admin = true))
	}

	@Test
	fun a_refusal_logs_one_line() {
		// Flask's JSON ends in a newline: logcat drew it as an empty second `W LbBotManager:` line.
		val logged = lbLogBody("""{"code": "admin_only", "error": "Admins only."}""" + "\n")
		assertFalse('\n' in logged)
		assertEquals("""{"code": "admin_only", "error": "Admins only."}""", logged)
	}

	// ---- NB5 (R34): the gated menu rows fold, they do not jump ----------------------------------- //

	private fun tools(admin: Boolean): AvatarMenuEntry.Group =
		avatarMenuItems(lbConfigured = true, canShare = true, attentionCount = 3, webUrl = "http://lb:8899", admin = admin)
			.filterIsInstance<AvatarMenuEntry.Group>().first { it.id == AvatarMenuIds.LIBRARY_TOOLS }

	@Test
	fun gated_menu_rows_keep_their_place_and_fold() {
		val every = tools(admin = true)
		val bob = menuGroupSlots(tools(admin = false), every)
		// Every admin row is composed in its place; only the wishlist is shown, the rest folded away.
		assertEquals(every.entries.map { it.id }, bob.map { it.entry.id })
		assertEquals(listOf(AvatarMenuIds.WISHLIST), bob.filter { it.shown }.map { it.entry.id })
		val owner = menuGroupSlots(every, every)
		assertTrue(owner.all { it.shown })
		// A shown row is the current entry (its badge moves with it).
		val badged = menuGroupSlots(tools(admin = true), every.copy(entries = every.entries.map {
			if (it is AvatarMenuEntry.Link) it.copy(badge = null) else it
		}))
		assertEquals(3, (badged.first { it.entry.id == AvatarMenuIds.ATTENTION }.entry as AvatarMenuEntry.Link).badge)
	}
}
