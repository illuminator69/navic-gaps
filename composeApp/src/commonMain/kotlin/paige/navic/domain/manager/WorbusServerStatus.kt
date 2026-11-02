package paige.navic.domain.manager

import navic.composeapp.generated.resources.Res
import navic.composeapp.generated.resources.info_server_add_address
import navic.composeapp.generated.resources.info_server_cant_reach
import navic.composeapp.generated.resources.info_server_connected
import navic.composeapp.generated.resources.info_server_connecting
import navic.composeapp.generated.resources.info_server_navidrome_refused
import navic.composeapp.generated.resources.info_server_navidrome_unreachable
import navic.composeapp.generated.resources.info_server_not_signed_in
import navic.composeapp.generated.resources.info_server_off
import navic.composeapp.generated.resources.info_server_old_server
import navic.composeapp.generated.resources.info_server_refused
import navic.composeapp.generated.resources.info_server_signed_in
import navic.composeapp.generated.resources.info_server_signed_in_admin
import navic.composeapp.generated.resources.info_server_signed_out
import navic.composeapp.generated.resources.info_server_signing_in
import navic.composeapp.generated.resources.info_server_too_many_attempts
import navic.composeapp.generated.resources.notice_local_network_blocked
import org.jetbrains.compose.resources.StringResource

/**
 * Where the link to the Worbus Server stands, as the Server page says it: one sentence per state
 * (design §6.2's table for every refusal, verbatim in strings.xml).
 */
sealed class ServerStatus(val line: StringResource, val isProblem: Boolean = false) {
	/** The sentence's arguments ([SignedIn]'s name). */
	open val args: List<String> get() = emptyList()

	/** Connect is switched off. */
	data object Off : ServerStatus(Res.string.info_server_off)

	/** Connect is on, but there is no address. */
	data object NotConfigured : ServerStatus(Res.string.info_server_add_address)

	/** An address, and no credential: never signed in here, or signed out of this server. */
	data object NotSignedIn : ServerStatus(Res.string.info_server_not_signed_in)

	/** A sign-in is on its way. */
	data object SigningIn : ServerStatus(Res.string.info_server_signing_in)

	/** Dialling, and nothing has failed yet. */
	data object Connecting : ServerStatus(Res.string.info_server_connecting)

	/** In the session with no user named: an older server (the token), as before Worbus P2. */
	data object Connected : ServerStatus(Res.string.info_server_connected)

	/** In the session, signed in as [name] (`welcome.user`, the `user` frame). */
	data class SignedIn(val name: String, val admin: Boolean) :
		ServerStatus(if (admin) Res.string.info_server_signed_in_admin else Res.string.info_server_signed_in) {
		override val args: List<String> get() = listOf(name)
	}

	/** Dialling fails: a wrong address, a server that is down, no network. */
	data object CantReach : ServerStatus(Res.string.info_server_cant_reach, isProblem = true)

	/** The server turned the legacy token down. Retrying the same token is pointless. */
	data object Refused : ServerStatus(Res.string.info_server_refused, isProblem = true)

	/** 401 `navidrome_refused`. */
	data object NavidromeRefused : ServerStatus(Res.string.info_server_navidrome_refused, isProblem = true)

	/** 503 `navidrome_unreachable`. */
	data object NavidromeUnreachable : ServerStatus(Res.string.info_server_navidrome_unreachable, isProblem = true)

	/** 429 `too_many_attempts`. */
	data object TooManyAttempts : ServerStatus(Res.string.info_server_too_many_attempts, isProblem = true)

	/** 4005 / `signed_out`, and the one silent sign-in did not bring it back. */
	data object SignedOut : ServerStatus(Res.string.info_server_signed_out, isProblem = true)

	/** The server has no sign-in route (404, 405, not its JSON; R6): it needs its token. */
	data object OldServer : ServerStatus(Res.string.info_server_old_server)

	/** Android's local network permission is the likely reason the dial fails (B-108). */
	data object LocalNetworkBlocked : ServerStatus(Res.string.notice_local_network_blocked, isProblem = true)

	companion object {
		/** Every state, for the tests that hold one sentence to each. */
		val all: List<ServerStatus> get() = listOf(
			Off, NotConfigured, NotSignedIn, SigningIn, Connecting, Connected,
			SignedIn("a", admin = false), SignedIn("a", admin = true), CantReach, Refused, NavidromeRefused,
			NavidromeUnreachable, TooManyAttempts, SignedOut, OldServer, LocalNetworkBlocked
		)
	}
}

/** The status a sign-in that did not grant says, or null for one that did. */
fun statusOfSignIn(result: SignInResult): ServerStatus? = when (result) {
	is SignInResult.SignedIn -> null
	SignInResult.Refused -> ServerStatus.NavidromeRefused
	is SignInResult.Unreachable -> if (result.navidrome) ServerStatus.NavidromeUnreachable else ServerStatus.CantReach
	SignInResult.TooManyAttempts -> ServerStatus.TooManyAttempts
	SignInResult.OldServer -> ServerStatus.OldServer
	// Nothing was asked (review M-3); [HubAuth] never stores it as an answer.
	SignInResult.NoAccount -> ServerStatus.NotSignedIn
}

/**
 * The one answer the Server page's status line reads. Order matters:
 * - a sign-in on its way says so, whatever the socket is doing;
 * - a link that is up says who it is signed in as (or "Connected" with no user: an older server), except
 *   that a sign-in the user just tapped and was refused is said over it — the token may still be
 *   connecting this device as before, but the answer to the tap must not vanish;
 * - then what is missing (address, credential), what the last sign-in or the hub said, the dial.
 *
 * [linkedByToken]: the socket said hello with the legacy token (review M-4). The hub names the primary
 * user for it too (R2), so it reads "Connected", never "Signed in as" over a "Sign in as" row.
 */
fun serverStatus(
	enabled: Boolean,
	hasAddress: Boolean,
	hasCredential: Boolean,
	connected: Boolean,
	user: HubUser?,
	auth: HubAuthState,
	authRefused: Boolean,
	localNetworkBlocked: Boolean,
	unreachable: Boolean,
	linkedByToken: Boolean = false
): ServerStatus {
	if (!enabled) return ServerStatus.Off
	if (auth == HubAuthState.SigningIn) return ServerStatus.SigningIn
	val signInFailure = (auth as? HubAuthState.Answered)?.let { statusOfSignIn(it.result) }
	if (connected) {
		// A refused tap is said over a link the token holds; an older server answering 405 is not a
		// refusal, and a link that is up there is simply Connected. Nor is "can't reach" (an upgrade
		// attempt that failed to answer, R23): the server is plainly reached.
		if (signInFailure != null && signInFailure != ServerStatus.OldServer && signInFailure != ServerStatus.CantReach) {
			return signInFailure
		}
		if (linkedByToken) return ServerStatus.Connected
		return user?.let { ServerStatus.SignedIn(it.name, it.isAdmin) } ?: ServerStatus.Connected
	}
	if (!hasAddress) return ServerStatus.NotConfigured
	if (auth == HubAuthState.SignedOut) return ServerStatus.SignedOut
	if (signInFailure != null && (signInFailure != ServerStatus.OldServer || !hasCredential)) return signInFailure
	if (!hasCredential) return ServerStatus.NotSignedIn
	if (authRefused) return ServerStatus.Refused
	if (localNetworkBlocked) return ServerStatus.LocalNetworkBlocked
	if (unreachable) return ServerStatus.CantReach
	return ServerStatus.Connecting
}

/** The Worbus Server group's rows, in order (design §6.5). */
enum class WorbusServerRow { Address, SignIn, SignOut, DeviceName }

/**
 * Sign in as `<user>` while no device secret is held (and there is an account to sign in with);
 * Sign out of this server while one is. The token is not a row here: it is under Advanced, and only
 * after the server answered as an older one ([advancedServerRows]).
 */
fun worbusServerRows(signedIn: Boolean, accountName: String?): List<WorbusServerRow> = buildList {
	add(WorbusServerRow.Address)
	when {
		signedIn -> add(WorbusServerRow.SignOut)
		!accountName.isNullOrBlank() -> add(WorbusServerRow.SignIn)
	}
	add(WorbusServerRow.DeviceName)
}

/** Advanced's rows: AudioMuse as before, and the token for an older server once one answered as such. */
enum class AdvancedServerRow { AudioMuseAddress, AudioMuseToken, ServerToken }

fun advancedServerRows(oldServerSeen: Boolean): List<AdvancedServerRow> = buildList {
	add(AdvancedServerRow.AudioMuseAddress)
	add(AdvancedServerRow.AudioMuseToken)
	if (oldServerSeen) add(AdvancedServerRow.ServerToken)
}
