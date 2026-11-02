package paige.navic.ui.screens.settings

import paige.navic.domain.manager.AdvancedServerRow
import paige.navic.domain.manager.HubAuthState
import paige.navic.domain.manager.HubUser
import paige.navic.domain.manager.ServerStatus
import paige.navic.domain.manager.SignInResult
import paige.navic.domain.manager.WorbusServerRow
import paige.navic.domain.manager.advancedServerRows
import paige.navic.domain.manager.serverStatus
import paige.navic.domain.manager.worbusServerRows
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Worbus settings N3, then Worbus P2 (lane N2): the Server page says where the link stands in one
 * sentence per state, signs in instead of asking for a token, and nothing on it names an internal
 * (navi-connect, Tier 2, HUB_TOKEN, WebSocket) or says lb-bot.
 */
class ServerScreenStateTest {

	private fun status(
		enabled: Boolean = true,
		address: Boolean = true,
		credential: Boolean = true,
		connected: Boolean = false,
		user: HubUser? = null,
		auth: HubAuthState = HubAuthState.Idle,
		refused: Boolean = false,
		blocked: Boolean = false,
		unreachable: Boolean = false,
		byToken: Boolean = false
	) = serverStatus(enabled, address, credential, connected, user, auth, refused, blocked, unreachable, linkedByToken = byToken)

	private fun answered(r: SignInResult) = HubAuthState.Answered(r)

	@Test
	fun a_link_the_legacy_token_holds_says_connected() {
		// Review M-4: the hub names the primary user for a legacy socket too (R2). Over a link the
		// token holds, "Signed in as owner · admin" would contradict the "Sign in as bob" row below.
		val owner = HubUser("owner", isAdmin = true, isPrimary = true)
		assertEquals(ServerStatus.Connected, status(connected = true, user = owner, byToken = true))
		assertEquals(ServerStatus.SignedIn("owner", admin = true), status(connected = true, user = owner, byToken = false))
		// A refused tap is still said over it.
		assertEquals(ServerStatus.NavidromeRefused, status(connected = true, user = owner, byToken = true, auth = answered(SignInResult.Refused)))
	}

	@Test
	fun status_for_each_signin_result() {
		// No credential yet: the tap's answer is the line, each its own (design §6.2's table).
		val none = false
		assertEquals(ServerStatus.SigningIn, status(credential = none, auth = HubAuthState.SigningIn))
		assertEquals(ServerStatus.NavidromeRefused, status(credential = none, auth = answered(SignInResult.Refused)))
		assertEquals(ServerStatus.NavidromeUnreachable, status(credential = none, auth = answered(SignInResult.Unreachable(navidrome = true))))
		assertEquals(ServerStatus.CantReach, status(credential = none, auth = answered(SignInResult.Unreachable(navidrome = false))))
		assertEquals(ServerStatus.TooManyAttempts, status(credential = none, auth = answered(SignInResult.TooManyAttempts)))
		assertEquals(ServerStatus.OldServer, status(credential = none, auth = answered(SignInResult.OldServer)))
		assertEquals(ServerStatus.SignedOut, status(credential = none, auth = HubAuthState.SignedOut))
		assertEquals(ServerStatus.NotSignedIn, status(credential = none))

		// Signed in: who, and whether an admin, once the hub has said so.
		val ok = answered(SignInResult.SignedIn(HubUser("bob", false, false)))
		assertEquals(ServerStatus.Connecting, status(auth = ok))
		assertEquals(ServerStatus.SignedIn("bob", admin = false), status(auth = ok, connected = true, user = HubUser("bob", false, false)))
		assertEquals(ServerStatus.SignedIn("alice", admin = true), status(connected = true, user = HubUser("alice", true, false)))
		// An older hub names nobody: Connected, as before P2.
		assertEquals(ServerStatus.Connected, status(connected = true))

		// A refused tap is said over a link the legacy token still holds; an older server's 405 is not.
		assertEquals(ServerStatus.NavidromeRefused, status(connected = true, auth = answered(SignInResult.Refused)))
		assertEquals(ServerStatus.Connected, status(connected = true, auth = answered(SignInResult.OldServer)))
		// R23: an upgrade attempt that got no answer says nothing over a link that is up.
		assertEquals(ServerStatus.Connected, status(connected = true, auth = answered(SignInResult.Unreachable(navidrome = false))))
		// The token typed for an older server then dials as before.
		assertEquals(ServerStatus.Connecting, status(auth = answered(SignInResult.OldServer)))
		assertEquals(ServerStatus.Refused, status(auth = answered(SignInResult.OldServer), refused = true))
	}

	@Test
	fun the_sentences_are_the_designs() {
		val strings = File("src/commonMain/composeResources/values/strings.xml").readText()
		fun text(name: String) = Regex("<string name=\"$name\">(.*?)</string>").find(strings)!!.groupValues[1]
		assertEquals("This Worbus Server's Navidrome doesn't know this account. It may use a different Navidrome server.", text("info_server_navidrome_refused"))
		assertEquals("The Worbus Server can't reach its Navidrome right now.", text("info_server_navidrome_unreachable"))
		assertEquals("Too many attempts. Try again in a few minutes.", text("info_server_too_many_attempts"))
		assertEquals("Signed out of the Worbus Server", text("info_server_signed_out"))
		assertEquals("Signed in as %1\$s · admin", text("info_server_signed_in_admin"))
		assertEquals("Signed in as %1\$s", text("info_server_signed_in"))
		assertEquals("Server token, for older servers", text("option_server_token_old"))
		assertFalse("Add the address and token to connect" in strings, "the token is not asked for any more")
		// One P2 block.
		assertEquals(1, Regex("<!-- Worbus P2 -->").findAll(strings).count())
	}

	@Test
	fun token_row_only_after_old_server() {
		assertFalse(AdvancedServerRow.ServerToken in advancedServerRows(oldServerSeen = false))
		assertTrue(AdvancedServerRow.ServerToken in advancedServerRows(oldServerSeen = true))
		// And never in the main group, whatever is held.
		for (signedIn in listOf(false, true)) {
			val rows = worbusServerRows(signedIn, "bob")
			assertEquals(WorbusServerRow.Address, rows.first())
			assertEquals(WorbusServerRow.DeviceName, rows.last())
		}
	}

	@Test
	fun sign_in_while_out_sign_out_while_in() {
		assertEquals(listOf(WorbusServerRow.Address, WorbusServerRow.SignIn, WorbusServerRow.DeviceName), worbusServerRows(false, "bob"))
		assertEquals(listOf(WorbusServerRow.Address, WorbusServerRow.SignOut, WorbusServerRow.DeviceName), worbusServerRows(true, "bob"))
		// No Navidrome account to sign in with: no row that would do nothing.
		assertEquals(listOf(WorbusServerRow.Address, WorbusServerRow.DeviceName), worbusServerRows(false, null))
	}

	@Test
	fun aSwitchedOffServerIsOffWhateverElseIsStored() {
		assertEquals(ServerStatus.Off, status(enabled = false))
		assertEquals(ServerStatus.Off, status(enabled = false, address = false, credential = false))
		assertEquals(ServerStatus.Off, status(enabled = false, connected = true, auth = HubAuthState.SigningIn))
	}

	@Test
	fun noAddressIsNotConfiguredAndNeverSpins() {
		assertEquals(ServerStatus.NotConfigured, status(address = false))
		assertEquals(ServerStatus.NotConfigured, status(address = false, credential = false, unreachable = true))
	}

	@Test
	fun dialledAndNothingFailedYetIsConnecting() {
		assertEquals(ServerStatus.Connecting, status())
	}

	@Test
	fun aLinkThatIsUpIsConnectedWhateverAnOldFailureSaid() {
		assertEquals(ServerStatus.Connected, status(connected = true, unreachable = true, refused = true))
		assertEquals(ServerStatus.Connected, status(address = false, credential = false, connected = true))
	}

	@Test
	fun aRefusedTokenIsNamedAndBeatsCantReach() {
		assertEquals(ServerStatus.Refused, status(refused = true))
		assertEquals(ServerStatus.Refused, status(refused = true, unreachable = true))
	}

	@Test
	fun aFailingDialIsCantReachUnlessAndroidBlocksTheLocalNetwork() {
		assertEquals(ServerStatus.CantReach, status(unreachable = true))
		assertEquals(ServerStatus.LocalNetworkBlocked, status(unreachable = true, blocked = true))
	}

	@Test
	fun everyStateHasItsOwnSentence() {
		assertEquals(ServerStatus.all.size, ServerStatus.all.map { it.line }.toSet().size)
	}

	@Test
	fun noStringTheServerPageUsesNamesAnInternal() {
		val screen = File("src/commonMain/kotlin/paige/navic/ui/screens/settings/ServerScreen.kt").readText() +
			File("src/commonMain/kotlin/paige/navic/domain/manager/WorbusServerStatus.kt").readText()
		val strings = File("src/commonMain/composeResources/values/strings.xml").readText()
		val values = Regex("<string name=\"([^\"]+)\"[^>]*>(.*?)</string>", RegexOption.DOT_MATCHES_ALL)
			.findAll(strings).associate { it.groupValues[1] to it.groupValues[2] }
		val used = Regex("Res\\.string\\.([A-Za-z0-9_]+)").findAll(screen).map { it.groupValues[1] }.toSet()
		assertTrue(used.size > 10, "found only ${used.size} strings used by the page")
		val banned = listOf("navi-connect", "navi_connect", "tier 2", "hub_token", "websocket", "lb-bot")
		for (name in used) {
			val text = (values[name] ?: error("$name is not in strings.xml")).lowercase()
			for (word in banned) {
				assertTrue(word !in text, "string $name says \"$word\": $text")
			}
		}
	}
}
