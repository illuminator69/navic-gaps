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
import navic.composeapp.generated.resources.info_server_signin_route_unreachable
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

	/**
	 * R36 (re-walk NM3): the server answers at this address, but its sign-in route does not (a proxy
	 * that does not pass `/hub/`). The address is right, so this never says "check the address".
	 */
	data object SignInRouteUnreachable : ServerStatus(Res.string.info_server_signin_route_unreachable, isProblem = true)

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
			SignedIn("a", admin = false), SignedIn("a", admin = true), CantReach, SignInRouteUnreachable, Refused, NavidromeRefused,
			NavidromeUnreachable, TooManyAttempts, SignedOut, OldServer, LocalNetworkBlocked
		)
	}
}

/** The status a sign-in that did not grant says, or null for one that did. */
fun statusOfSignIn(result: SignInResult): ServerStatus? = when (result) {
	is SignInResult.SignedIn -> null
	SignInResult.Refused -> ServerStatus.NavidromeRefused
	is SignInResult.Unreachable -> when {
		result.navidrome -> ServerStatus.NavidromeUnreachable
		result.signInRouteHidden -> ServerStatus.SignInRouteUnreachable
		else -> ServerStatus.CantReach
	}
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
enum class WorbusServerRow { Address, FindHome, SignIn, SignOut, DeviceName }

/**
 * Sign in as `<user>` while no device secret is held (and there is an account to sign in with);
 * Sign out of this server while one is. The token is not a row here: it is under Advanced, and only
 * after the server answered as an older one ([advancedServerRows]).
 */
fun worbusServerRows(signedIn: Boolean, accountName: String?, findHome: Boolean = false): List<WorbusServerRow> = buildList {
	add(WorbusServerRow.Address)
	// Worbus P3 (design §6.4): a set server with no home address offers to look for one.
	if (findHome) add(WorbusServerRow.FindHome)
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

// ---- Worbus P3, lane N5: the Server screen's address rules (design §6.6) --------------------- //

/** Which route the live socket takes, said after the signed-in sentence. */
sealed interface ServerRoute {
	/** A private address: "home network". */
	data object Home : ServerRoute

	/** A Tailscale address: "Tailscale". */
	data object Tailscale : ServerRoute

	/** Any other: "via <host>". */
	data class Via(val host: String) : ServerRoute
}

/** The route the status line names, and whether it adds "not encrypted" (a typed public `ws://`). */
data class RouteNote(val route: ServerRoute, val notEncrypted: Boolean)

/** [active] is the live socket's address; null while there is none (nothing is named). */
fun routeNote(active: String?): RouteNote? {
	val url = active?.let { normaliseHubAddress(it) } ?: return null
	val route = when (classifyHubAddress(url)) {
		AddressClass.Private -> ServerRoute.Home
		AddressClass.Tailscale -> ServerRoute.Tailscale
		AddressClass.Public -> ServerRoute.Via(hubAddressHost(url).orEmpty())
	}
	// Only a typed address can be a public ws:// (found and learned ones never are).
	val plain = route is ServerRoute.Via && url.startsWith("ws://")
	return RouteNote(route, plain)
}

/** An address as a row shows it: the host and its port, the path when there is one; no scheme. */
fun shownAddress(url: String): String = (normaliseHubAddress(url) ?: url).substringAfter("://")

/** The Server screen's rows while no server is set (design §6.4, §6.6), in order. */
sealed interface NotSetRow {
	/** "Worbus Server · 192.168.1.20", Sign in as the account. */
	data class Server(val found: Found.Server) : NotSetRow

	/** "An older Worbus Server may be at 192.168.1.20.", Use this address. */
	data class Older(val found: Found.OlderAt) : NotSetRow

	/** "Found a Worbus Server at …, but not encrypted." Not tappable; Type an address is below. */
	data class NotEncrypted(val address: String) : NotSetRow

	/** The state's sentence ([NotSetSentence]). */
	data class Sentence(val sentence: NotSetSentence) : NotSetRow

	/** Open the app's settings, for the local network permission. */
	data object OpenSettings : NotSetRow

	data object LookOnNetwork : NotSetRow
	data object TypeAddress : NotSetRow
}

enum class NotSetSentence { Looking, NothingBeside, NothingOnNetwork, NeedsPermission, NotOnWifi }

/**
 * What the Worbus Server group shows in place of the empty Address row, for each [FinderState]:
 * found rows first, then the state's sentence, then Look on this network and Type an address.
 */
fun notSetRows(state: FinderState): List<NotSetRow> = buildList {
	when (state) {
		FinderState.Idle -> Unit
		FinderState.Looking -> add(NotSetRow.Sentence(NotSetSentence.Looking))
		FinderState.NothingBeside -> add(NotSetRow.Sentence(NotSetSentence.NothingBeside))
		FinderState.NothingOnNetwork -> add(NotSetRow.Sentence(NotSetSentence.NothingOnNetwork))
		FinderState.NotOnWifi -> add(NotSetRow.Sentence(NotSetSentence.NotOnWifi))
		FinderState.NeedsPermission -> {
			add(NotSetRow.Sentence(NotSetSentence.NeedsPermission))
			add(NotSetRow.OpenSettings)
		}
		is FinderState.Results -> state.found.forEach { found ->
			when (found) {
				is Found.Server -> add(NotSetRow.Server(found))
				is Found.OlderAt -> add(NotSetRow.Older(found))
				is Found.NotEncrypted -> add(NotSetRow.NotEncrypted(found.address))
			}
		}
	}
	add(NotSetRow.LookOnNetwork)
	add(NotSetRow.TypeAddress)
}

/**
 * Tapping Sign in on a found server (design §6.4): its addresses are saved as found (the record starts
 * over as this server's, keeping what was answered Not now), `hubUrl` is the tapped address, the
 * Connect switch goes on, and [signIn] runs project 2's sign-in. An older server ("Use this address")
 * is the same with its one address. One tap. Starting over is a server change (C1): the secret held
 * for whatever came before is forgotten first. An address the record would not keep (a plain-text one
 * on a public host, review I1) changes nothing: it is never laundered into `hubUrl` as typed.
 */
fun signInToFound(
	preferenceManager: PreferenceManager,
	store: HubServerRecordStore,
	found: Found,
	tapped: String,
	signIn: () -> Unit
) {
	val addresses = when (found) {
		is Found.Server -> listOf(tapped) + found.addresses
		is Found.OlderAt -> listOf(found.address)
		is Found.NotEncrypted -> return
	}
	val url = normaliseHubAddress(tapped) ?: return
	val kept = store.read().promptDismissed
	val name = (found as? Found.Server)?.name?.ifBlank { null }
	val record = addresses.fold(HubServerRecord(name = name, promptDismissed = kept)) { r, a -> r.withAddress(a, HubAddress.Source.Found) }
	if (record.find(url) == null) return
	store.startOver(record, hubUrl = url)
	preferenceManager.hubEnabled = true
	signIn()
}

/**
 * Remove a typed or found address from the list. When it was the one `hubUrl` holds, `hubUrl` moves to
 * the next typed or found address, or to "" (no server set) when none is left. True when `hubUrl` moved.
 * With no typed or found address left there is no server: the record forgets its id and its learned
 * address (keeping what was answered Not now, review I2), which is a server change (C1: the secret
 * goes, so the next server, typed or found, gets a sign-in and never this one's secret).
 */
fun removeServerAddress(preferenceManager: PreferenceManager, store: HubServerRecordStore, url: String): Boolean {
	val removed = normaliseHubAddress(url)
	var moved = false
	store.update { record ->
		val next = record.without(url)
		val left = next.addresses.filter { it.source != HubAddress.Source.Learned }
		if (left.isEmpty() || normaliseHubAddress(preferenceManager.hubUrl) == removed) {
			val to = left.firstOrNull()?.url.orEmpty()
			moved = to != preferenceManager.hubUrl
			preferenceManager.hubUrl = to
		}
		if (left.isEmpty()) HubServerRecord(promptDismissed = next.promptDismissed) else next
	}
	return moved
}

/** "Use away from home": the learned address may be dialled (true) or not ([HubServerRecord.learnedOff]). */
fun setUseAwayFromHome(store: HubServerRecordStore, on: Boolean) {
	store.update { it.copy(learnedOff = !on) }
}

/**
 * Add a typed address to the list. With no server set it is a new server (review M6: the list's Add
 * after its last address went is "Type an address", [typeNewServer]), so an old id cannot make it read
 * as another server for good. Null when it does not normalise.
 */
fun addTypedAddress(preferenceManager: PreferenceManager, store: HubServerRecordStore, raw: String): String? {
	if (preferenceManager.hubUrl.isBlank()) return typeNewServer(preferenceManager, store, raw)
	val url = normaliseHubAddress(raw) ?: return null
	store.update { it.withAddress(url, HubAddress.Source.Typed) }
	return url
}

/** The Address row's second line: every other address, the learned one as "Away from home". */
fun otherAddresses(record: HubServerRecord, active: String?): List<HubAddress> {
	val shown = active?.let { normaliseHubAddress(it) }
	return record.addresses.filter { it.url != shown && !(it.source == HubAddress.Source.Learned && record.learnedOff) }
}

/**
 * "Type an address" while no server is set: a new server, so the record starts over with this one
 * typed address (what was answered Not now is kept), and `hubUrl` is it. A server change (C1): the
 * secret held for whatever came before is forgotten, and a sign-in is owed. Null when it does not
 * normalise.
 */
fun typeNewServer(preferenceManager: PreferenceManager, store: HubServerRecordStore, raw: String): String? {
	val url = normaliseHubAddress(raw) ?: return null
	store.startOver(HubServerRecord(promptDismissed = store.read().promptDismissed).withAddress(url, HubAddress.Source.Typed), hubUrl = url)
	return url
}
