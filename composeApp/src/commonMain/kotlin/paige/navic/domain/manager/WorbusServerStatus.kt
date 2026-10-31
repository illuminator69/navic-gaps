package paige.navic.domain.manager

import navic.composeapp.generated.resources.Res
import navic.composeapp.generated.resources.info_server_cant_reach
import navic.composeapp.generated.resources.info_server_connected
import navic.composeapp.generated.resources.info_server_connecting
import navic.composeapp.generated.resources.info_server_not_configured
import navic.composeapp.generated.resources.info_server_off
import navic.composeapp.generated.resources.info_server_refused
import navic.composeapp.generated.resources.notice_local_network_blocked
import org.jetbrains.compose.resources.StringResource

/** Where the link to the Worbus Server stands, as the Server page says it. */
enum class ServerStatus(val line: StringResource) {
	/** Connect is switched off. */
	Off(Res.string.info_server_off),

	/** Connect is on, but there is no address or no token to dial with. */
	NotConfigured(Res.string.info_server_not_configured),

	/** Dialling, and nothing has failed yet. */
	Connecting(Res.string.info_server_connecting),

	/** The server answered and this device is in its session. */
	Connected(Res.string.info_server_connected),

	/** Dialling fails: a wrong address, a server that is down, no network. */
	CantReach(Res.string.info_server_cant_reach),

	/** The server answered and turned the token down. Retrying the same token is pointless. */
	Refused(Res.string.info_server_refused),

	/** Android's local network permission is the likely reason the dial fails (B-108). */
	LocalNetworkBlocked(Res.string.notice_local_network_blocked)
}

/**
 * The one answer the Server page's status line and its Open settings row read. Order matters:
 * a link that is up is connected whatever an old failure left behind; a refused token beats
 * "can't reach" because the server was reached.
 */
fun serverStatus(
	enabled: Boolean,
	hasAddress: Boolean,
	hasToken: Boolean,
	connected: Boolean,
	authRefused: Boolean,
	localNetworkBlocked: Boolean,
	unreachable: Boolean
): ServerStatus = when {
	!enabled -> ServerStatus.Off
	!hasAddress || !hasToken -> ServerStatus.NotConfigured
	connected -> ServerStatus.Connected
	authRefused -> ServerStatus.Refused
	localNetworkBlocked -> ServerStatus.LocalNetworkBlocked
	unreachable -> ServerStatus.CantReach
	else -> ServerStatus.Connecting
}
