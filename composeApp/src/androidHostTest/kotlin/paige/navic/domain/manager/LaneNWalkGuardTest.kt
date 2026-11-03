package paige.navic.domain.manager

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/*
 * Worbus P2, lane N's walk on the AVD (NB1 to NB7, R31 to R35): the wiring no host test can run,
 * held at the source. The rules themselves are tested in HubAuthTest and LaneNWalkTest.
 */
class LaneNWalkGuardTest {
	private val sources: List<File> = listOf("src/commonMain", "src/androidMain")
		.map(::File)
		.flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }

	private fun text(name: String) = sources.single { it.name == name }.readText()

	private fun member(file: String, signature: String): String {
		val all = text(file)
		val start = all.indexOf(signature)
		assertTrue(start >= 0, "$file: $signature")
		return all.substring(start, all.indexOf("\n\t}\n", start))
	}

	/**
	 * NB2: EVERY surface that prints a sentence saying lb-bot is missing, unreachable or not set up
	 * also says R26's too-old sentence when that is the cause. Enumerated from strings.xml, not listed
	 * by hand: the walk found the Wishlist page outside the five surfaces the review named.
	 */
	@Test
	fun every_lb_unavailability_line_knows_too_old() {
		val strings = File("src/commonMain/composeResources/values/strings.xml").readText()
		val unavailable = Regex("""<string name="([^"]+)">([^<]*)</string>""").findAll(strings)
			.filter { m ->
				Regex("""(?i)(can't|couldn't|could not) reach lb-bot|needs lb-bot|lb-bot isn't set up|lb-bot is busy or unreachable""")
					.containsMatchIn(m.groupValues[2])
			}
			.map { it.groupValues[1] }
			.toList()
		assertTrue(unavailable.size >= 10, "the regex still finds the lines: $unavailable")
		// Primary-only surfaces: the hub never answers the primary user lbbot_too_old (`refuse_caller`).
		val exempt = mapOf("ListeningStats.kt" to "the listening history is the primary user's alone")
		val markers = listOf("lbUnavailableRes(", "LbError.AccessRefused", "tooOld")
		val offenders = mutableListOf<String>()
		for (name in unavailable) {
			val use = Regex("""\bRes\.string\.$name\b""")
			for (f in sources.filter { use.containsMatchIn(it.readText()) }) {
				if (f.name in exempt) continue
				if (markers.none { it in f.readText() }) offenders += "${f.name}: $name"
			}
		}
		assertEquals(emptyList(), offenders, "a surface says lb-bot is unreachable where the hub says too old")
		val wishlistVm = text("WishlistViewModel.kt")
		assertTrue("tooOld = lbBotManager.lbAccess.value == LbAccess.TOO_OLD" in wishlistVm)
		assertTrue("wishlistUnsupportedLine(state.tooOld)" in text("WishlistSection.kt"))
	}

	/** NB7 (R31) and R35: the loop retries a sign-in with no verdict; a P2 welcome over the token signs in. */
	@Test
	fun the_loop_follows_r31_and_r35() {
		val loop = member("HubManager.kt", "private suspend fun runLoop(")
		assertTrue("hubAuth.upgradeRetryWanted" in loop, "no verdict: wait and try the sign-in again")
		assertTrue("hubAuth.noteWelcomeUser(" in text("HubManager.kt"), "R35 at welcome")
		// R36: the retry wait is the loop's own: one loop job (restart joins the last), so a retry never
		// stacks, and stop() (logout, sign-out) cancels it with the loop.
		assertTrue("scope.launch" !in loop && "GlobalScope" !in loop, "no timer of its own")
		assertTrue("waitBeforeNextAttempt()" in loop)
		val hub = text("HubManager.kt")
		val waits = Regex("""waitBeforeNextAttempt\(\)""")
		assertEquals(waits.findAll(hub).count(), waits.findAll(loop).count(), "the wait lives only in the loop")
		assertTrue("connectJob?.cancel()" in member("HubManager.kt", "fun stop("))
		assertTrue("stop()" in member("HubManager.kt", "fun signOutOfServer(") && "stopSocket = { stop() }" in member("HubManager.kt", "fun leaveForLogout("))
	}

	/** NB1 (R32): the hello, the welcome and the cast bridges read the per-account id. */
	@Test
	fun device_ids_are_per_account() {
		val hub = text("HubManager.kt")
		assertTrue("hubAuth.helloDeviceId()" in hub)
		assertTrue("hubAuth.noteWelcomeDeviceId(id, viaSecret = helloSentSecret)" in hub)
		assertTrue("preferenceManager.hubDeviceId" !in hub, "HubManager names no single id any more")
		assertTrue("hubAuth.helloDeviceId()" in text("CastBridgeManager.kt"))
		assertTrue("preferenceManager.hubDeviceId" !in text("CastBridgeManager.kt"))
	}

	/** NB3: the attention read asks the admin question. */
	@Test
	fun attention_asks_admin() {
		assertTrue("admin = _roles.value.admin" in member("LbBotManager.kt", "suspend fun refreshAttention("))
		assertTrue("take(200)}" !in text("LbBotManager.kt"), "every logged body goes through lbLogBody")
	}

	/** NB5 (R34): the Server screen's rows and status line, and the menu's gated rows, move. */
	@Test
	fun server_rows_and_menu_rows_move() {
		val screen = text("ServerScreen.kt")
		assertTrue(Regex("""WorbusServerRow\.entries(\.filter \{[^}]*\})?\.forEach""").containsMatchIn(screen) && "Fold(visible = shownAt >= 0)" in screen, "the rows fold")
		assertTrue("AdvancedServerRow.entries.forEach" in screen, "the token row folds")
		assertTrue(Regex("""AnimatedContent\(\s*targetState = status""").containsMatchIn(screen), "the status line changes with motion")
		assertTrue("Fold(visible = lbAccess == LbAccess.TOO_OLD)" in screen && "Fold(visible = lbAccess == LbAccess.REFUSED)" in screen)
		// Re-walk NM1: Sign in and Sign out are ONE row whose words cross-fade, both ways; no chevron.
		assertTrue(
			"WorbusServerRow.SignOut -> SegmentedListItem(" !in screen && "WorbusServerRow.SignOut -> SettingsNavItem(" !in screen,
			"no second row swapped in for Sign out"
		)
		// A top-level function: up to its own closing brace at column 0.
		val action = screen.substring(screen.indexOf("private fun ServerActionRow("))
			.let { it.substring(0, it.indexOf("\n}\n")) }
		assertTrue("SegmentedListItem(" in action && "SettingsNavItem" !in action, "an action row, no chevron")
		assertTrue(Regex("""AnimatedContent\(\s*targetState = action""").containsMatchIn(action), "its words change with motion")
		assertTrue("ServerActionRow(" in screen.substringBefore("private fun ServerActionRow("))
		assertTrue("Fold(visible = slot.shown)" in text("AvatarMenu.kt"), "the menu's gated rows fold")
	}

	/** Re-walk NM3: R36's status names the sign-in route, not the address, in the Worbus P2 block. */
	@Test
	fun the_hidden_route_says_so() {
		val strings = File("src/commonMain/composeResources/values/strings.xml").readText()
		val block = strings.substring(strings.indexOf("<!-- Worbus P2 -->"))
		assertTrue(
			"\n\t<string name=\"info_server_signin_route_unreachable\">This Worbus Server's sign-in isn't reachable at this address. It may sit behind a proxy that doesn't pass /hub/.</string>" in block
		)
		assertTrue("Res.string.info_server_signin_route_unreachable" in text("WorbusServerStatus.kt"))
	}

	/** NB6: a planned cancel is not logged at error level. */
	@Test
	fun a_planned_cancel_is_not_an_error() {
		val loop = member("HubManager.kt", "private suspend fun runLoop(")
		assertTrue("if (hubDialFailureIsError(e)) Logger.e(" in loop)
	}
}
