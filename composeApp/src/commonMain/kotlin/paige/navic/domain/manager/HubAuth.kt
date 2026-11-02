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

	/** 404, 405, or a reply that is not this route's JSON: a server from before sign-in (R6). */
	data object OldServer : SignInResult
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
 * proxies), or a body that is not this route's JSON.
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
		else -> old
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

	/** Set by the silent sign-in, cleared by [noteWelcome]: one per signed-out episode. */
	private var silentSignInSpent = false

	/** The credential every Worbus Server call sends; null when there is none. */
	fun bearer(): String? = when (val c = helloCredential()) {
		is HelloCredential.DeviceSecret -> c.secret
		is HelloCredential.LegacyToken -> c.token
		null -> null
	}

	/** The same credential, typed, for a hello. */
	fun helloCredential(): HelloCredential? = hubCredentialOf(secrets.hubDeviceSecret, secrets.hubToken)

	/** True while a device secret is held: the device is signed in to this server. */
	val signedIn: Boolean get() = secrets.hubDeviceSecret.isNotBlank()

	/** True while a legacy token is stored (only an older server needs one). */
	val hasLegacyToken: Boolean get() = secrets.hubToken.isNotBlank()

	/** The Server page's token field (shown only after [SignInResult.OldServer]). "" removes it. */
	fun setLegacyToken(token: String) {
		secrets.hubToken = token.trim()
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
		if (!preferenceManager.hubEnabled || bearer() == null) return null
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
			log("sign-in skipped: ${if (base == null) "no address" else "no Navidrome account"}")
			return SignInResult.Refused.also { _state.value = HubAuthState.Answered(it) }
		}
		_state.value = HubAuthState.SigningIn
		val pair = subsonicPair(acct.password, newSalt())
		val body = signInBody(
			username = acct.username,
			pair = pair,
			deviceId = preferenceManager.hubDeviceId,
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
		val result = when (val outcome = signInOutcomeOf(reply)) {
			is SignInOutcome.Granted -> {
				val grant = outcome.grant
				secrets.hubDeviceSecret = grant.deviceSecret
				// The server's sign-in route exists: its token is of no further use here (above).
				secrets.hubToken = ""
				// R4: the id the server chose is this device's id from now on.
				preferenceManager.hubDeviceId = grant.deviceId
				preferenceManager.hubOldServerUrl = ""
				log("signed in as ${grant.user.name} (admin=${grant.user.isAdmin}, primary=${grant.user.isPrimary}); secret length ${grant.deviceSecret.length}")
				SignInResult.SignedIn(grant.user)
			}
			is SignInOutcome.NotGranted -> {
				if (outcome.result == SignInResult.OldServer) {
					preferenceManager.hubOldServerUrl = preferenceManager.hubUrl.trim()
				}
				log("sign-in not granted: ${outcome.result} (${(reply as? HubAuthReply.Answered)?.status ?: "no answer"})")
				outcome.result
			}
		}
		_state.value = HubAuthState.Answered(result)
		return result
	}

	/**
	 * The server closed with 4005 or said `signed_out`: the secret it bound is gone. Forget it and
	 * make ONE silent sign-in with the stored account. True when that brought the device back (the
	 * caller reconnects); false when it did not, or when this episode's one try is already spent,
	 * and the state is then [HubAuthState.SignedOut] (the caller stops dialling).
	 */
	suspend fun afterSignedOut(): Boolean = lock.withLock {
		secrets.hubDeviceSecret = ""
		if (silentSignInSpent) {
			_state.value = HubAuthState.SignedOut
			return@withLock false
		}
		silentSignInSpent = true
		val ok = signInLocked() is SignInResult.SignedIn
		if (!ok) _state.value = HubAuthState.SignedOut
		ok
	}

	/** The server let this device in: a later `signed_out` earns its silent sign-in again. */
	fun noteWelcome() {
		silentSignInSpent = false
		if (_state.value == HubAuthState.SignedOut) _state.value = HubAuthState.Idle
	}

	/**
	 * Sign this device out of the Worbus Server (design §6.5, §6.6 #1): post `/hub/signout` with
	 * the secret (best effort: a server that is not there forgets it at the 7-day sweep), then
	 * forget the secret and the legacy token whatever the answer. The caller stops the socket.
	 */
	suspend fun signOut() = lock.withLock {
		val secret = secrets.hubDeviceSecret
		val base = hubHttpBaseOf(preferenceManager.hubUrl)
		secrets.hubDeviceSecret = ""
		secrets.hubToken = ""
		silentSignInSpent = false
		_state.value = HubAuthState.Idle
		if (secret.isNotBlank() && base != null) {
			val reply = try {
				transport.post("$base$SIGNOUT_PATH", null, bearer = secret)
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				HubAuthReply.NoAnswer
			}
			log("signed out: ${(reply as? HubAuthReply.Answered)?.status ?: "no answer"}")
		}
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
