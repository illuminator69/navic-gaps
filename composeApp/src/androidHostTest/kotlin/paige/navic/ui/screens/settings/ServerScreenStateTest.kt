package paige.navic.ui.screens.settings

import paige.navic.domain.manager.ServerStatus
import paige.navic.domain.manager.serverStatus
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Worbus settings N3: the Server page says where the link stands in one sentence per state, and
 * nothing on it names an internal (navi-connect, Tier 2, HUB_TOKEN, WebSocket) or says lb-bot.
 */
class ServerScreenStateTest {

	private fun status(
		enabled: Boolean = true,
		address: Boolean = true,
		token: Boolean = true,
		connected: Boolean = false,
		refused: Boolean = false,
		blocked: Boolean = false,
		unreachable: Boolean = false
	) = serverStatus(enabled, address, token, connected, refused, blocked, unreachable)

	@Test
	fun aSwitchedOffServerIsOffWhateverElseIsStored() {
		assertEquals(ServerStatus.Off, status(enabled = false))
		assertEquals(ServerStatus.Off, status(enabled = false, address = false, token = false))
	}

	@Test
	fun noAddressOrNoTokenIsNotConfiguredAndNeverSpins() {
		assertEquals(ServerStatus.NotConfigured, status(address = false))
		assertEquals(ServerStatus.NotConfigured, status(token = false))
		assertEquals(ServerStatus.NotConfigured, status(address = false, token = false, unreachable = true))
	}

	@Test
	fun dialledAndNothingFailedYetIsConnecting() {
		assertEquals(ServerStatus.Connecting, status())
	}

	@Test
	fun aLinkThatIsUpIsConnectedWhateverAnOldFailureSaid() {
		assertEquals(ServerStatus.Connected, status(connected = true))
		assertEquals(ServerStatus.Connected, status(connected = true, unreachable = true, refused = true))
	}

	@Test
	fun aLinkThatIsUpIsConnectedEvenWithNoTokenOrAddressStored() {
		assertEquals(ServerStatus.Connected, status(token = false, connected = true))
		assertEquals(ServerStatus.Connected, status(address = false, connected = true))
		assertEquals(ServerStatus.Connected, status(address = false, token = false, connected = true))
		// Switched off still wins over a link that has not been torn down yet.
		assertEquals(ServerStatus.Off, status(enabled = false, token = false, connected = true))
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
		assertEquals(ServerStatus.entries.size, ServerStatus.entries.map { it.line }.toSet().size)
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
