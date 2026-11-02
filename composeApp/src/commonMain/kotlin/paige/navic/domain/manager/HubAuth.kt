package paige.navic.domain.manager

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okio.ByteString.Companion.encodeUtf8
import paige.navic.util.Logger
import kotlin.random.Random

/*
 * Worbus P2 (design §6.2, §6.5): this device signs in to the Worbus Server with the Navidrome
 * account it already holds, and keeps the per-device secret the server hands back. Every call to
 * the Worbus Server (the socket's hello, the cast bridges' hellos, every HTTP route) reads its
 * credential from [HubAuth.bearer] / [HubAuth.helloCredential] and nothing else
 * (`HubBearerGuardTest`).
 *
 * The password never leaves the device: the server is sent a Subsonic salt and token,
 * `md5(password + salt)`, which it checks against its own Navidrome once and then drops.
 */

/** Who this device acts for on the Worbus Server: `welcome.user`, the `user` frame, sign-in's `user`. */
data class HubUser(val name: String, val isAdmin: Boolean, val isPrimary: Boolean)

/** `{name, admin, primary}`, or null for anything that is not one (an older hub sends none). */
fun hubUserOf(obj: JsonObject?): HubUser? {
	if (obj == null) return null
	val name = (obj["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
	val admin = (obj["admin"] as? JsonPrimitive)?.booleanOrNull ?: return null
	val primary = (obj["primary"] as? JsonPrimitive)?.booleanOrNull ?: return null
	return HubUser(name, admin, primary)
}

/** What one sign-in came to (design §6.2's table, R6). */
sealed interface SignInResult {
	data class SignedIn(val user: HubUser) : SignInResult

	/** 401 `navidrome_refused`: the server's Navidrome does not know this account. */
	data object Refused : SignInResult

	/**
	 * No sign-in happened for want of an answer. [navidrome] true: the Worbus Server answered
	 * 503 `navidrome_unreachable` (its Navidrome did not). False: the Worbus Server itself did not
	 * answer (refused, timed out, dropped), which is never [OldServer] (R6).
	 */
	data class Unreachable(val navidrome: Boolean) : SignInResult

	/** 429 `too_many_attempts`. */
	data object TooManyAttempts : SignInResult

	/** 404, 405, or a 2xx/4xx reply that is not this route's JSON: a server from before sign-in (R6). */
	data object OldServer : SignInResult

	/**
	 * Nothing was asked: no address, or no Navidrome account on this device to sign in with (a
	 * password that could not be read, B-279). Not the server's refusal (review M-3), so it is never
	 * said as "doesn't know this account" and retires no token.
	 */
	data object NoAccount : SignInResult
}

/** One POST to the Worbus Server, as [HubAuthTransport] saw it. */
sealed interface HubAuthReply {
	data class Answered(val status: Int, val body: String) : HubAuthReply

	/** No HTTP answer at all: refused, timed out, dropped, a name that did not resolve. */
	data object NoAnswer : HubAuthReply
}

/** How [HubAuth] reaches `/hub/signin` and `/hub/signout`. The app's is [KtorHubAuthTransport]. */
fun interface HubAuthTransport {
	suspend fun post(url: String, jsonBody: String?, bearer: String?): HubAuthReply
}

/** What a sign-in that worked hands back. Never logged, never kept but in [SecretStore]. */
class SignInGrant(val deviceId: String, val deviceSecret: String, val user: HubUser) {
	override fun toString() = "SignInGrant(deviceId=$deviceId, secret length ${deviceSecret.length}, user=$user)"
}

/** A sign-in's reply, read: either the grant, or the result that says why there is none. */
sealed interface SignInOutcome {
	class Granted(val grant: SignInGrant) : SignInOutcome
	data class NotGranted(val result: SignInResult) : SignInOutcome
}

private val authJson = Json { ignoreUnknownKeys = true; isLenient = true }

/** The `code` of a refusal body, or null when the body is not JSON or carries none. */
private fun refusalCode(body: String): String? = try {
	(authJson.parseToJsonElement(body).jsonObject["code"] as? JsonPrimitive)?.takeIf { it.isString }?.content
} catch (e: Exception) {
	null
}

/**
 * `POST /hub/signin`'s reply read as the hub writes it (`hub.py` `Hub.signin`, verbatim to the
 * global constraints): 200 with `deviceId`, `deviceSecret` and `user`; 401 `navidrome_refused`,
 * 503 `navidrome_unreachable`, 429 `too_many_attempts`, 400 `bad_request`. Anything else that came
 * back is a server from before this route (R6): 404, 405 (the P1 hub's answer to a POST outside its
 * proxies), or a 2xx/4xx body that is not this route's JSON. Any other 5xx is the server or a gateway
 * failing (a reverse proxy's 502 HTML while the hub is down): `Unreachable`, never `OldServer` (the
 * controller's R6 clarification).
 */
fun signInOutcomeOf(reply: HubAuthReply): SignInOutcome {
	if (reply !is HubAuthReply.Answered) return SignInOutcome.NotGranted(SignInResult.Unreachable(navidrome = false))
	val old = SignInOutcome.NotGranted(SignInResult.OldServer)
	if (reply.status == 404 || reply.status == 405) return old
	if (reply.status == 200) {
		val obj = try {
			authJson.parseToJsonElement(reply.body).jsonObject
		} catch (e: Exception) {
			return old
		}
		val deviceId = (obj["deviceId"] as? JsonPrimitive)?.takeIf { it.isString }?.content
		val secret = (obj["deviceSecret"] as? JsonPrimitive)?.takeIf { it.isString }?.content
		val user = hubUserOf(obj["user"] as? JsonObject)
		if (deviceId.isNullOrBlank() || secret.isNullOrBlank() || user == null) return old
		return SignInOutcome.Granted(SignInGrant(deviceId, secret, user))
	}
	return when (reply.status to refusalCode(reply.body)) {
		401 to "navidrome_refused" -> SignInOutcome.NotGranted(SignInResult.Refused)
		503 to "navidrome_unreachable" -> SignInOutcome.NotGranted(SignInResult.Unreachable(navidrome = true))
		429 to "too_many_attempts" -> SignInOutcome.NotGranted(SignInResult.TooManyAttempts)
		// Our own request was malformed. The fields are clamped before sending ([signInBody]), so
		// this is not expected; it is the server refusing this sign-in, not a network fault.
		400 to "bad_request" -> SignInOutcome.NotGranted(SignInResult.Refused)
		else -> if (reply.status >= 500) SignInOutcome.NotGranted(SignInResult.Unreachable(navidrome = false)) else old
	}
}

/** A Subsonic salt and token for one request: `token = md5(password + salt)`, lower-case hex. */
class SubsonicPair(val salt: String, val token: String) {
	override fun toString() = "SubsonicPair(salt length ${salt.length}, token length ${token.length})"
}

/** 12 random bytes as hex: 24 characters, above the hub's 6 (`SIGNIN_SALT_MIN`). */
fun newSalt(random: Random = Random.Default): String =
	random.nextBytes(12).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

fun subsonicPair(password: String, salt: String): SubsonicPair =
	SubsonicPair(salt, "$password$salt".encodeUtf8().md5().hex())

/** The hub's limit on each string field (`SIGNIN_STR_MAX`). */
private const val SIGNIN_STR_MAX = 128

/** The body of `POST /hub/signin` (global constraints): never the password. */
fun signInBody(
	username: String,
	pair: SubsonicPair,
	deviceId: String?,
	name: String,
	platform: String,
	kind: String?
): String = buildJsonObject {
	put("username", username)
	put("salt", pair.salt)
	put("token", pair.token)
	deviceId?.takeIf { it.isNotBlank() && it.length <= SIGNIN_STR_MAX }?.let { put("deviceId", it) }
	put("name", name.take(SIGNIN_STR_MAX))
	put("platform", platform.take(SIGNIN_STR_MAX))
	kind?.let { put("kind", it) }
}.toString()

/** What a hello authenticates with. */
sealed interface HelloCredential {
	/** `auth: {deviceSecret}` (Worbus P2). */
	class DeviceSecret(val secret: String) : HelloCredential {
		override fun equals(other: Any?) = other is DeviceSecret && other.secret == secret
		override fun hashCode() = secret.hashCode()
		override fun toString() = "DeviceSecret(length ${secret.length})"
	}

	/** The legacy shared `token` (an older server, or one not signed in to yet). */
	class LegacyToken(val token: String) : HelloCredential {
		override fun equals(other: Any?) = other is LegacyToken && other.token == token
		override fun hashCode() = token.hashCode()
		override fun toString() = "LegacyToken(length ${token.length})"
	}
}

/** Write [credential] into a hello frame, as the global constraints shape it. */
fun JsonObjectBuilder.putHelloCredential(credential: HelloCredential) {
	when (credential) {
		is HelloCredential.DeviceSecret -> putJsonObject("auth") { put("deviceSecret", credential.secret) }
		is HelloCredential.LegacyToken -> put("token", credential.token)
	}
}

/**
 * The secret if one is held, else the legacy token, else nothing: the ONE rule every caller of the
 * Worbus Server authenticates by.
 */
fun hubCredentialOf(deviceSecret: String, legacyToken: String): HelloCredential? = when {
	deviceSecret.isNotBlank() -> HelloCredential.DeviceSecret(deviceSecret)
	legacyToken.isNotBlank() -> HelloCredential.LegacyToken(legacyToken)
	else -> null
}

/**
 * R23 (controller ruling, both clients): the legacy token is an upgrade bridge, not a fallback. After
 * the last sign-in's [result] (null: none yet this process), may a held token still be sent? Yes while
 * the upgrade has not been answered, for an older server (`OldServer`), and while the server or its
 * Navidrome cannot be asked (`Unreachable`, `TooManyAttempts`: an upgrade must not cost a connection).
 * Not after `navidrome_refused`: that account is not the server's, and the token would make it the
 * primary user. A sign-in that worked has cleared the token already.
 */
fun legacyTokenUsableAfter(result: SignInResult?): Boolean = when (result) {
	null, SignInResult.OldServer, is SignInResult.Unreachable, SignInResult.TooManyAttempts, SignInResult.NoAccount -> true
	SignInResult.Refused, is SignInResult.SignedIn -> false
}

/** R23: whether the next connect tries again to turn the token into a secret. */
fun tokenUpgradeDueAfter(result: SignInResult?): Boolean = when (result) {
	null, is SignInResult.Unreachable, SignInResult.TooManyAttempts, SignInResult.NoAccount -> true
	SignInResult.OldServer, SignInResult.Refused, is SignInResult.SignedIn -> false
}

/**
 * R24: the server closed 4001 (or said `auth`) to a hello that carried `auth.deviceSecret`: a server
 * from before sign-in, which reads only `token` (a new one answers a bad secret with 4005).
 */
fun secretHelloRefusedByOldServer(helloSentSecret: Boolean, closeCode: Short?, authErrorSeen: Boolean): Boolean =
	helloSentSecret && (authErrorSeen || closeCode == CLOSE_AUTH_REFUSED)

/** The hub's close code for a refused shared token (and every hello a P1 hub cannot read). */
const val CLOSE_AUTH_REFUSED: Short = 4001

/** Where a sign-in stands, for the Server page (N2). */
sealed interface HubAuthState {
	/** Nothing attempted since launch, or the last attempt's answer was cleared. */
	data object Idle : HubAuthState

	data object SigningIn : HubAuthState

	/** The last attempt's answer. A [SignInResult.SignedIn] here is this launch's sign-in. */
	data class Answered(val result: SignInResult) : HubAuthState

	/** The server said this device is signed out and one silent sign-in did not bring it back. */
	data object SignedOut : HubAuthState
}

/** The Navidrome account this app is signed in with: its name and the password it holds. */
class NavidromeAccount(val username: String, val password: String) {
	override fun toString() = "NavidromeAccount($username)"
}

/**
 * The device's credential on the Worbus Server, and signing in and out (Worbus P2, lane N).
 *
 * - [bearer] is the device secret, else the legacy token: every Worbus Server call reads it.
 * - [signIn] mints a salt and token from the stored Navidrome password and posts `/hub/signin`.
 *   It adopts and persists the `deviceId` the server returns (R4) and keeps the secret sealed in
 *   [SecretStore]. A server that has a sign-in route makes the legacy token obsolete, so a
 *   successful sign-in forgets it: from then on nothing can fall back to it and act as the
 *   server's primary user (a signed-out device, a logout followed by another account's login).
 * - [afterSignedOut] is the ONE silent sign-in a 4005 / `signed_out` earns, until the next welcome.
 */
class HubAuth(
	private val preferenceManager: PreferenceManager,
	private val secrets: SecretStore,
	private val account: () -> NavidromeAccount?,
	private val kind: () -> String?,
	private val transport: HubAuthTransport = KtorHubAuthTransport(),
	private val platform: String = "android",
	/** Where its lines go (lengths and outcomes only, never a credential). Tests pass a no-op. */
	private val log: (String) -> Unit = { Logger.i("HubAuth", it) }
) {
	/** The app's: the account from [SessionManager], the device kind from [HubLocalDevice]. */
	constructor(
		preferenceManager: PreferenceManager,
		secrets: SecretStore,
		sessionManager: SessionManager,
		localDevice: HubLocalDevice
	) : this(preferenceManager, secrets, { sessionManager.navidromeAccount() }, { localDevice.kind })

	private val lock = Mutex()

	private val _state = MutableStateFlow<HubAuthState>(HubAuthState.Idle)
	val state: StateFlow<HubAuthState> = _state.asStateFlow()

	/**
	 * Set by the silent sign-in, cleared by [noteWelcome]: one per signed-out episode. Written under
	 * the lock, read and cleared from the socket's thread (review M-6).
	 */
	@kotlin.concurrent.Volatile
	private var silentSignInSpent = false

	private val _credentialGeneration = MutableStateFlow(0)

	/**
	 * Moves on every change of the stored credential (a sign-in, a sign-out, a 4005, R24, a typed or
	 * retired token). The cast bridges rebuild on it at once (review M-5): their sockets are bound
	 * to the space of the credential they said hello with.
	 */
	val credentialGeneration: StateFlow<Int> = _credentialGeneration.asStateFlow()

	/** The ONE writer of the stored credentials: null leaves that one as it is. */
	private fun writeCredentials(secret: String? = null, token: String? = null) {
		var changed = false
		if (secret != null && secret != secrets.hubDeviceSecret) {
			secrets.hubDeviceSecret = secret
			changed = true
		}
		if (token != null && token != secrets.hubToken) {
			secrets.hubToken = token
			changed = true
		}
		if (changed) _credentialGeneration.value++
	}

	/**
	 * Review M-2: called by HTTP readers on the hub's own 401 (no `code`) while a credential is held.
	 * Set by [HubManager] to its `kickReconnect`: the reconnect's hello meets 4005 and the one silent
	 * sign-in runs there. HTTP never signs in or forgets anything itself.
	 */
	@kotlin.concurrent.Volatile
	var onUnauthorized: (() -> Unit)? = null

	fun noteHttpUnauthorized() {
		if (hasCredential) onUnauthorized?.invoke()
	}

	/** R23: the last sign-in's answer this process, which decides whether a held token may be sent. */
	@kotlin.concurrent.Volatile
	private var lastSignIn: SignInResult? = null

	/** R23: a connect should first try to turn a held token into a secret. Set again by [noteNewConnect]. */
	@kotlin.concurrent.Volatile
	private var tokenUpgradeDue = true

	/**
	 * Bumped by every sign-out: a sign-in whose answer comes back after one (a logout during a silent
	 * sign-in) keeps nothing it was given.
	 */
	@kotlin.concurrent.Volatile
	private var signOutGeneration = 0

	/**
	 * Whether this device has a credential for the Worbus Server at all: the ONE "is the hub set up"
	 * question (with the address and the switch, [httpBase]). Never "a token is typed": a signed-in
	 * device holds no token (Feishin's review C1: such checks cost a signed-in device its moods, its
	 * lb-bot index mirror and AudioMuse; `HubBearerGuardTest` keeps them out).
	 */
	val hasCredential: Boolean get() = helloCredential() != null

	/** The credential every Worbus Server call sends; null when there is none. */
	fun bearer(): String? = when (val c = helloCredential()) {
		is HelloCredential.DeviceSecret -> c.secret
		is HelloCredential.LegacyToken -> c.token
		null -> null
	}

	/**
	 * The same credential, typed, for a hello. R31 (it replaces R23(a)'s keep-the-token clause): the
	 * legacy token is sent only to an address that answered as a server from before sign-in
	 * ([oldServerSeen]: 404, 405, a 2xx/4xx that is not the sign-in JSON, or R24). Anywhere else a
	 * device holding a token and no secret connects only once a sign-in has worked: on the AVD a
	 * non-primary account whose upgrade got 429 connected on the token as the primary user and read
	 * the owner's listening.
	 *
	 * R35: an OldServer verdict is evidence about one moment. The token is used only once THIS launch's
	 * sign-in at this address answered as an older server (or R24 said so), never on a verdict a
	 * previous launch stored: a hub upgraded in place in between gets its sign-in first.
	 */
	fun helloCredential(): HelloCredential? = hubCredentialOf(
		secrets.hubDeviceSecret,
		secrets.hubToken.takeIf { oldServerSeen && lastSignIn == SignInResult.OldServer }.orEmpty()
	)

	/**
	 * R31: a token is held, no secret, this address is not an older server, and the upgrade sign-in
	 * got no verdict (the server or its Navidrome did not answer, or 429). The device stays
	 * unconnected, the status says why, and the next connect tries the sign-in again.
	 */
	val upgradeRetryWanted: Boolean
		get() = secrets.hubDeviceSecret.isBlank() && secrets.hubToken.isNotBlank() &&
			(lastSignIn is SignInResult.Unreachable || lastSignIn == SignInResult.TooManyAttempts)

	/**
	 * R35: a `welcome` answered a hello. One that carries `user` while the hello sent the legacy
	 * token is a hub with sign-in (an in-place P1→P2 upgrade): this address's OldServer verdict is
	 * dropped and the upgrade sign-in is due at once. True when the caller should reconnect for it;
	 * the token is not sent again until a sign-in has worked (R31).
	 */
	fun noteWelcomeUser(viaToken: Boolean, userPresent: Boolean): Boolean {
		// R36: within this launch, an address whose welcome named a user is a hub with sign-in.
		if (userPresent) p2WelcomeUrl = preferenceManager.hubUrl.trim()
		if (!viaToken || !userPresent || !oldServerSeen) return false
		preferenceManager.hubOldServerUrl = ""
		lastSignIn = null
		verdictUrl = null
		tokenUpgradeDue = true
		_state.value = HubAuthState.Idle
		_credentialGeneration.value++
		log("a hub with sign-in answered the token hello: signing in (R35)")
		return true
	}

	/** True while a device secret is held: the device is signed in to this server. */
	val signedIn: Boolean get() = secrets.hubDeviceSecret.isNotBlank()

	/**
	 * Whether this device belongs to a Worbus Server's users (review M-12): it holds a secret, or the
	 * server signed it out and the silent sign-in did not bring it back, or the server's Navidrome
	 * refused its account. Such a device never reads the fingerprint straight from AudioMuse: that
	 * is the primary user's listening, which the hub keeps to them. A legacy token alone is today's
	 * primary user, and a device that never met a server is as before.
	 */
	val boundToWorbusServer: Boolean
		get() = signedIn || _state.value == HubAuthState.SignedOut || lastSignIn == SignInResult.Refused

	/** True while a legacy token is stored (only an older server needs one). */
	val hasLegacyToken: Boolean get() = secrets.hubToken.isNotBlank()

	/** The Server page's token field (shown only after [SignInResult.OldServer]). "" removes it. */
	fun setLegacyToken(token: String) {
		writeCredentials(token = token.trim())
		// A token typed for an older server (the field shows only after one answered so) is used.
		if (lastSignIn == SignInResult.Refused) lastSignIn = null
	}

	/** The token field's current text, for its dialog. */
	fun legacyTokenForEditing(): String = secrets.hubToken

	/** Whether the server at the current address answered as one from before sign-in (R6). */
	val oldServerSeen: Boolean
		get() {
			val url = preferenceManager.hubUrl.trim()
			return url.isNotEmpty() && preferenceManager.hubOldServerUrl == url
		}

	/** The account a one-tap sign-in would use, for "Sign in as <name>". */
	fun accountName(): String? = account()?.username?.takeIf { it.isNotBlank() }

	/** The HTTP base of the Worbus Server, or null: off, no address, or no credential. */
	fun httpBase(): String? {
		if (!preferenceManager.hubEnabled || !hasCredential) return null
		return hubHttpBaseOf(preferenceManager.hubUrl)
	}

	/**
	 * Sign in with the app's Navidrome account. The password is turned into a salt and token here
	 * and never sent; what comes back is kept in [SecretStore]. The caller reconnects.
	 */
	suspend fun signIn(): SignInResult = lock.withLock { signInLocked() }

	private suspend fun signInLocked(): SignInResult {
		val base = hubHttpBaseOf(preferenceManager.hubUrl)
		val acct = account()
		if (base == null || acct == null || acct.username.isBlank() || acct.password.isEmpty()) {
			// Nothing to sign in with: no address, or no Navidrome account on this device.
			// Review M-3: not the server's refusal. The state is left alone (an `afterSignedOut` then
			// says SignedOut), and no token is retired: nobody refused it.
			log("sign-in skipped: ${if (base == null) "no address" else "no Navidrome account"}")
			return SignInResult.NoAccount
		}
		dropVerdictIfMoved()
		_state.value = HubAuthState.SigningIn
		val generation = signOutGeneration
		val pair = subsonicPair(acct.password, newSalt())
		val body = signInBody(
			username = acct.username,
			pair = pair,
			deviceId = signInDeviceId(acct.username),
			name = preferenceManager.hubDeviceName.ifBlank { "Navic" },
			platform = platform,
			kind = kind()
		)
		val reply = try {
			transport.post("$base$SIGNIN_PATH", body, bearer = null)
		} catch (e: CancellationException) {
			_state.value = HubAuthState.Idle
			throw e
		}
		if (generation != signOutGeneration) {
			log("sign-in answered after a sign-out; nothing kept")
			_state.value = HubAuthState.Idle
			return SignInResult.Unreachable(navidrome = false)
		}
		val result = when (val outcome = signInOutcomeOf(reply)) {
			is SignInOutcome.Granted -> {
				val grant = outcome.grant
				// The server's sign-in route exists: its token is of no further use here (above).
				writeCredentials(secret = grant.deviceSecret, token = "")
				// R4, R32: the id the server chose is this account's id at this address from now on. The
				// pre-P2 single id is claimed only when the server hands that same id back.
				deviceIdKey(acct.username)?.let { saveBoundId(it, grant.deviceId) }
				if (grant.deviceId == preferenceManager.hubDeviceId) preferenceManager.hubLegacyDeviceIdClaimed = true
				preferenceManager.hubOldServerUrl = ""
				log("signed in as ${grant.user.name} (admin=${grant.user.isAdmin}, primary=${grant.user.isPrimary}); secret length ${grant.deviceSecret.length}")
				SignInResult.SignedIn(grant.user)
			}
			is SignInOutcome.NotGranted -> {
				if (outcome.result == SignInResult.OldServer && p2WelcomeUrl == preferenceManager.hubUrl.trim()) {
					log("sign-in answered as an older server at an address that welcomed a user: read as unreachable (R36)")
					lastSignIn = SignInResult.Unreachable(navidrome = false)
					tokenUpgradeDue = true
					verdictUrl = preferenceManager.hubUrl.trim()
					_state.value = HubAuthState.Answered(SignInResult.Unreachable(navidrome = false))
					return SignInResult.Unreachable(navidrome = false)
				}
				if (outcome.result == SignInResult.OldServer) {
					preferenceManager.hubOldServerUrl = preferenceManager.hubUrl.trim()
				}
				if (outcome.result == SignInResult.Refused && secrets.hubDeviceSecret.isBlank() && secrets.hubToken.isNotBlank()) {
					// R23 and its clarification (review I-3): this server's Navidrome does not know the
					// account, so the token would only ever put the device in the primary user's space.
					// Retired, not merely unsent: a later 429 or a restart must not bring it back.
					// R23's cost line: an owner retypes it under Advanced if an older server asks.
					writeCredentials(token = "")
					log("legacy token retired: the account was refused")
				}
				log("sign-in not granted: ${outcome.result} (${(reply as? HubAuthReply.Answered)?.status ?: "no answer"})")
				outcome.result
			}
		}
		lastSignIn = result
		tokenUpgradeDue = tokenUpgradeDueAfter(result)
		verdictUrl = preferenceManager.hubUrl.trim()
		_state.value = HubAuthState.Answered(result)
		return result
	}

	/** A connect begins (start, a settings change): a held token gets its upgrade attempt again. */
	fun noteNewConnect() {
		dropVerdictIfMoved()
		tokenUpgradeDue = true
	}

	/**
	 * R36: the address whose `welcome` named a user this launch (a P2 hub). Its sign-in route answering
	 * as an older server (a path proxy that does not forward the `/hub/` routes) is read as Unreachable: the
	 * token is not taken back, so no P2 welcome → drop the token → OldServer → token → reconnect loop.
	 */
	@kotlin.concurrent.Volatile
	private var p2WelcomeUrl: String? = null

	/** The address the last verdict ([state], [lastSignIn]) answered for; null for none (R33). */
	@kotlin.concurrent.Volatile
	private var verdictUrl: String? = null

	/**
	 * R33 (as Feishin's R29): a sign-in verdict belongs to the address that gave it. Once the address
	 * changes it is dropped: the status no longer says another server's "Too many attempts", and the
	 * new address gets its own sign-in.
	 */
	private fun dropVerdictIfMoved() {
		val at = verdictUrl ?: return
		if (at == preferenceManager.hubUrl.trim()) return
		verdictUrl = null
		lastSignIn = null
		tokenUpgradeDue = true
		silentSignInSpent = false
		_state.value = HubAuthState.Idle
	}

	// ---- R32: the device id is per (hub address, account) ------------------------------------ //

	private val idsJson = Json { ignoreUnknownKeys = true }

	private fun deviceIdKey(username: String?): String? {
		val name = username?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
		val url = preferenceManager.hubUrl.trim().takeIf { it.isNotEmpty() } ?: return null
		return "$url|$name"
	}

	private fun boundIds(): Map<String, String> = try {
		val raw = preferenceManager.hubDeviceIds
		if (raw.isBlank()) emptyMap()
		else idsJson.parseToJsonElement(raw).jsonObject.mapNotNull { (k, v) ->
			(v as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { k to it }
		}.toMap()
	} catch (e: Exception) {
		emptyMap()
	}

	private fun saveBoundId(key: String, id: String) {
		val next = boundIds() + (key to id)
		preferenceManager.hubDeviceIds = buildJsonObject { next.forEach { (k, v) -> put(k, v) } }.toString()
	}

	/**
	 * The id offered to `/hub/signin` for [username] at this address: the one this account was given
	 * here before, else the pre-P2 single id while it is unclaimed (R32): it was the primary user's on
	 * a P1 hub, and only the hub can say whose it is, by handing it back.
	 */
	private fun signInDeviceId(username: String): String? =
		deviceIdKey(username)?.let { boundIds()[it] }
			?: preferenceManager.hubDeviceId.takeIf { it.isNotBlank() && !preferenceManager.hubLegacyDeviceIdClaimed }

	/**
	 * The id this device says hello with, and stamps as a cast bridge's `bridgedBy`: with a secret,
	 * the id it is bound to for the current account and address (R4, R32); otherwise the pre-P2
	 * single id, as before.
	 */
	fun helloDeviceId(): String? {
		val legacy = preferenceManager.hubDeviceId.takeIf { it.isNotBlank() }
		if (!signedIn) return legacy
		return deviceIdKey(account()?.username)?.let { boundIds()[it] } ?: legacy
	}

	/** A `welcome`'s `deviceId`: the bound id of this account with a secret, the single id without. */
	fun noteWelcomeDeviceId(id: String, viaSecret: Boolean) {
		val key = deviceIdKey(account()?.username)
		if (viaSecret && key != null) saveBoundId(key, id)
		else if (!viaSecret) preferenceManager.hubDeviceId = id
	}

	/**
	 * R23 (a): a device holding a legacy token and no secret makes one silent sign-in when it
	 * connects. Success stores the secret and clears the token; the other answers decide whether the
	 * token is still sent ([legacyTokenUsableAfter]) and whether the next connect tries again
	 * ([tokenUpgradeDueAfter]). Nothing to do with a secret held, no token, or no attempt due.
	 */
	suspend fun upgradeLegacyTokenIfDue(): SignInResult? {
		if (!tokenUpgradeDue || secrets.hubDeviceSecret.isNotBlank() || secrets.hubToken.isBlank()) return null
		tokenUpgradeDue = false
		return signIn()
	}

	/**
	 * R24: the server refused a hello that carried the secret with 4001 / `auth`: it is older than
	 * sign-in. The secret means nothing to it, so it is forgotten (a sign-in at a newer server issues
	 * another), and the token field is offered for this address.
	 */
	fun noteOldServerRefusedSecret() {
		writeCredentials(secret = "")
		preferenceManager.hubOldServerUrl = preferenceManager.hubUrl.trim()
		lastSignIn = SignInResult.OldServer
		tokenUpgradeDue = false
		_state.value = HubAuthState.Answered(SignInResult.OldServer)
	}

	/**
	 * The server closed with 4005 or said `signed_out`: the secret it bound is gone. Forget it and
	 * make ONE silent sign-in with the stored account. True when that brought the device back (the
	 * caller reconnects); false when it did not, or when this episode's one try is already spent,
	 * and the state is then [HubAuthState.SignedOut] (the caller stops dialling).
	 */
	suspend fun afterSignedOut(): Boolean = lock.withLock {
		writeCredentials(secret = "")
		if (silentSignInSpent) {
			verdictUrl = preferenceManager.hubUrl.trim()
			_state.value = HubAuthState.SignedOut
			return@withLock false
		}
		silentSignInSpent = true
		val ok = signInLocked() is SignInResult.SignedIn
		if (!ok) {
			verdictUrl = preferenceManager.hubUrl.trim()
			_state.value = HubAuthState.SignedOut
		}
		ok
	}

	/** The server let this device in: a later `signed_out` earns its silent sign-in again. */
	fun noteWelcome() {
		silentSignInSpent = false
		if (_state.value == HubAuthState.SignedOut) _state.value = HubAuthState.Idle
	}

	/**
	 * Sign this device out of the Worbus Server (design §6.5, §6.6 #1): [beginSignOut] then
	 * [finishSignOut]. The caller stops the socket first.
	 */
	suspend fun signOut() = finishSignOut(beginSignOut())

	/**
	 * The synchronous half of a sign-out: forget the secret and the legacy token now, so nothing can
	 * dial or fall back to either from this moment (a logout followed by another account's login
	 * must not act as anyone). Returns what [finishSignOut] tells the server.
	 */
	fun beginSignOut(): PendingSignOut {
		val pending = PendingSignOut(secrets.hubDeviceSecret, hubHttpBaseOf(preferenceManager.hubUrl))
		signOutGeneration++
		writeCredentials(secret = "", token = "")
		lastSignIn = null
		tokenUpgradeDue = true
		silentSignInSpent = false
		_state.value = HubAuthState.Idle
		return pending
	}

	/**
	 * Tell the server (`POST /hub/signout` with the forgotten secret), best effort: a server that is
	 * not there forgets the credential at its 7-day sweep, and nothing here depends on the answer.
	 */
	suspend fun finishSignOut(pending: PendingSignOut) {
		if (pending.secret.isBlank() || pending.base == null) return
		val reply = try {
			transport.post("${pending.base}$SIGNOUT_PATH", null, bearer = pending.secret)
		} catch (e: CancellationException) {
			throw e
		} catch (e: Exception) {
			HubAuthReply.NoAnswer
		}
		log("signed out: ${(reply as? HubAuthReply.Answered)?.status ?: "no answer"}")
	}

	companion object {
		const val SIGNIN_PATH = "/hub/signin"
		const val SIGNOUT_PATH = "/hub/signout"
	}
}

/** The app's [HubAuthTransport]: one Ktor client, bounded so a tap never spins for long. */
class KtorHubAuthTransport(
	private val client: HttpClient = HttpClient {
		install(UserAgent) { agent = "Navic" }
		// The hub gives Navidrome 8 s (`SIGNIN_ND_TIMEOUT`) and answers 503 after it.
		install(HttpTimeout) {
			connectTimeoutMillis = 8_000
			requestTimeoutMillis = 15_000
			socketTimeoutMillis = 15_000
		}
		expectSuccess = false
	},
	private val log: (String) -> Unit = { Logger.w("HubAuth", it) }
) : HubAuthTransport {
	override suspend fun post(url: String, jsonBody: String?, bearer: String?): HubAuthReply = try {
		val response = client.post(url) {
			if (bearer != null) header("Authorization", "Bearer $bearer")
			contentType(ContentType.Application.Json)
			setBody(jsonBody ?: "")
		}
		HubAuthReply.Answered(response.status.value, response.bodyAsText())
	} catch (e: CancellationException) {
		throw e
	} catch (e: Exception) {
		// The URL carries nothing secret; the body and header are not logged.
		log("POST ${url.substringAfterLast('/')} had no answer: ${e::class.simpleName}")
		HubAuthReply.NoAnswer
	}
}

/** A sign-out begun ([HubAuth.beginSignOut]) and not yet told to the server. Prints no secret. */
class PendingSignOut internal constructor(internal val secret: String, internal val base: String?) {
	override fun toString() = "PendingSignOut(secret length ${secret.length})"
}

/**
 * Leaving the Worbus Server on a Navidrome logout (design §6.5, the owner's 2026-11-02 ruling), first
 * half, synchronous (review M-1): stop the socket (so the 4005 the server sends it earns no silent
 * sign-in) and forget the credentials, before the caller launches anything. A process that dies right
 * after a logout then dials as nobody at its next start.
 */
fun beginLeavingWorbusServer(stopSocket: () -> Unit, auth: HubAuth): PendingSignOut {
	stopSocket()
	return auth.beginSignOut()
}

/**
 * The second half: wait for the socket to be gone, tell the server, then drop the saved-queue cache
 * (whose rows the next account's first connect would otherwise push into its own space) and the fill
 * watches (review M-7: the next account would inherit the previous admin's fill ledger, its attention
 * count and the background polling of admin routes).
 */
suspend fun finishLeavingWorbusServer(
	pending: PendingSignOut,
	auth: HubAuth,
	awaitSocketGone: suspend () -> Unit,
	forgetSavedQueues: suspend () -> Unit,
	forgetFillWatches: suspend () -> Unit
) {
	awaitSocketGone()
	auth.finishSignOut(pending)
	forgetSavedQueues()
	forgetFillWatches()
}

/**
 * NB6: whether a dial that ended with [e] is worth an error line. A cancellation is the loop being
 * stopped on purpose (a logout, a restart, a sign-out), said at info level with no stack.
 */
fun hubDialFailureIsError(e: Throwable): Boolean = e !is CancellationException
