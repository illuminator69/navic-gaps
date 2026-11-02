package paige.navic.domain.manager

import kotlinx.serialization.json.JsonObject
import paige.navic.domain.models.Mix
import paige.navic.domain.models.MixKind

/*
 * Worbus P2, lane N3 (design §6.5): what the signed-in user may see. lb-bot's route table is the only
 * rights table (§6.4); these rules only keep the client from offering what the server would refuse. A
 * gated control is ABSENT, never disabled. Every rule is here, pure, and read by the surfaces through
 * LbBotManager's flags and HubManager's roles.
 */

/** What this device's user may do: admin actions, and the primary user's listening. */
data class HubRoles(val admin: Boolean, val primary: Boolean) {
	companion object {
		/** An older hub, or the legacy shared token (the primary user, R2): today's behaviour. */
		val EVERYTHING = HubRoles(admin = true, primary = true)

		/** Signed in, and the hub has not said who yet: nothing gated is offered until it does. */
		val NOTHING_YET = HubRoles(admin = false, primary = false)
	}
}

/**
 * The roles for [user] (`welcome.user`, the `user` frame). Null with a device secret held is a
 * signed-in device before its welcome (a cold start, a reconnect): fail closed, so a non-admin's
 * phone never shows an admin action for a moment. Null without one is an older hub or the legacy
 * token, which is today's behaviour (everything).
 */
fun hubRolesOf(user: HubUser?, signedIn: Boolean): HubRoles = when {
	user != null -> HubRoles(admin = user.isAdmin, primary = user.isPrimary)
	signedIn -> HubRoles.NOTHING_YET
	else -> HubRoles.EVERYTHING
}

/** A `{"t":"user","user":{…}}` frame's user, or null for one that is not that shape (ignored). */
fun hubUserFrameOf(msg: JsonObject): HubUser? = hubUserOf(msg["user"] as? JsonObject)

/** An admin action is offered where the route is and the user is an admin. */
fun adminGated(offered: Boolean, roles: HubRoles): Boolean = offered && roles.admin

/** The primary user's listening (ListenBrainz mirror, Stats' tabs, the fingerprint) is theirs alone. */
fun primaryGated(offered: Boolean, roles: HubRoles): Boolean = offered && roles.primary

/**
 * lb-bot's downloads mode as this user meets it. Downloading (requests, fills, their retry and cancel)
 * is an admin action in lb-bot's table (P1), so a non-admin gets the "downloads off" surfaces, which are
 * the wishlist instead of a request: exactly what they may do.
 */
fun downloadsFor(roles: HubRoles, mode: LbDownloads): LbDownloads = if (roles.admin) mode else LbDownloads.OFF

/** Your mix (the seedless station, the hub's "Your mix") is read from the primary user's listening. */
fun yourMixOffered(engine: Boolean, roles: HubRoles): Boolean = engine && roles.primary

/**
 * A mix built from the primary user's listening: the Sonic Fingerprint, a seedless station (Your
 * mix), and `adaptive`, which plays as Your mix with the engine (`mixBuiltByAudioMuse`'s three).
 */
fun mixReadsPrimarysListening(mix: Mix): Boolean = when (mix.kind) {
	MixKind.FINGERPRINT, MixKind.ADAPTIVE -> true
	MixKind.STATION -> mix.seedId.isBlank()
	else -> false
}

/** The mixes this user is shown: a non-primary user none of [mixReadsPrimarysListening]. */
fun mixesFor(roles: HubRoles, mixes: List<Mix>): List<Mix> =
	if (roles.primary) mixes else mixes.filterNot(::mixReadsPrimarysListening)

/**
 * Whether the fingerprint may be read by this route. Through the hub always (it answers 403
 * `primary_only` to anyone else); straight from AudioMuse only on a device NOT signed in to a
 * Worbus Server, which would otherwise read the primary user's listening with credentials of its own.
 */
fun fingerprintRouteAllowed(viaHub: Boolean, signedIn: Boolean): Boolean = viaHub || !signedIn

/** Whether lb-bot will answer this user at all (design §6.4, §6.5). */
enum class LbAccess {
	OPEN,

	/** 401 `signin_required`: lb-bot refused the server (Feishin's `lbRefused`). Its surfaces render nothing. */
	REFUSED,

	/** 503 `lbbot_too_old` from the hub: lb-bot is too old for signed-in users. Rendered as unavailable. */
	TOO_OLD
}

/**
 * An lb-bot answer that is about who is asking, not about what was asked (design §6.4, §6.5). Each is
 * its own [LbBotManager.LbError.AccessRefused], never "lb-bot isn't set up." (review I-2).
 */
enum class LbRefusal {
	/** 401 `signin_required`: lb-bot refused the server. The layer renders nothing. */
	SIGNIN_REQUIRED,

	/** 403 `admin_only`: not an admin (whatever was last said). The action goes, silently. */
	ADMIN_ONLY,

	/** 403 `primary_only`: not the primary user. Silent, as admin_only. */
	PRIMARY_ONLY,

	/** The hub's 503 `lbbot_too_old`: lb-bot is too old for signed-in users. Said in one sentence. */
	TOO_OLD,

	/**
	 * The hub's own 401 (no `code`) while this device holds a credential: a secret the hub revoked
	 * while the socket was down (review M-2). Silent; the socket is kicked and finds out.
	 */
	HUB_UNAUTHORIZED
}

/** [status] with lb-bot's or the hub's [code] as a refusal about who asks, or null for any other answer. */
fun lbRefusalOf(status: Int, code: String, credentialHeld: Boolean = false): LbRefusal? = when {
	status == 401 && code == "signin_required" -> LbRefusal.SIGNIN_REQUIRED
	status == 401 && code.isBlank() && credentialHeld -> LbRefusal.HUB_UNAUTHORIZED
	status == 403 && code == "admin_only" -> LbRefusal.ADMIN_ONLY
	status == 403 && code == "primary_only" -> LbRefusal.PRIMARY_ONLY
	status == 503 && code == "lbbot_too_old" -> LbRefusal.TOO_OLD
	else -> null
}

/**
 * The error a refused answer is returned as, from its status and body, or null when it is not a
 * refusal about who asks. Every reader of a non-2xx lb answer asks this first.
 */
fun lbAccessError(status: Int, raw: String, credentialHeld: Boolean): LbBotManager.LbError.AccessRefused? {
	if (status != 401 && status != 403 && status != 503) return null
	val code = (lbFailureOf(status, raw) as? LbBotManager.LbError.Rejected)?.code.orEmpty()
	return lbRefusalOf(status, code, credentialHeld)?.let { LbBotManager.LbError.AccessRefused(it) }
}

/** What one refused lb-bot answer does to this device. */
data class LbRefusalEffect(
	val access: LbAccess,
	/** 403 `admin_only`: the user is not an admin (whatever was last said); the action goes, silently. */
	val demoteAdmin: Boolean,
	/** The caller says nothing about it: no toast, no error line (the gate vanishes instead). */
	val silent: Boolean,
	/** The hub's own 401 with a credential held: kick the socket (review M-2). */
	val kickSocket: Boolean = false
)

/** [current] after an answer of [status] with lb-bot's or the hub's [code]. */
fun lbRefusalEffect(current: LbAccess, status: Int, code: String, credentialHeld: Boolean = false): LbRefusalEffect =
	when (lbRefusalOf(status, code, credentialHeld)) {
		LbRefusal.SIGNIN_REQUIRED -> LbRefusalEffect(LbAccess.REFUSED, demoteAdmin = false, silent = true)
		LbRefusal.TOO_OLD -> LbRefusalEffect(LbAccess.TOO_OLD, demoteAdmin = false, silent = true)
		LbRefusal.ADMIN_ONLY -> LbRefusalEffect(current, demoteAdmin = true, silent = true)
		LbRefusal.PRIMARY_ONLY -> LbRefusalEffect(current, demoteAdmin = false, silent = true)
		LbRefusal.HUB_UNAUTHORIZED -> LbRefusalEffect(current, demoteAdmin = false, silent = true, kickSocket = true)
		null -> LbRefusalEffect(current, demoteAdmin = false, silent = false)
	}

/**
 * [current] after any lb answer of [status] (review I-1). A 2xx clears too-old: the hub serves a
 * non-primary caller only an answer that proved lb-bot's identity (R17), and the primary user is never
 * too old. A refusal is [lbRefusalEffect]'s; anything else (a 502 while lb-bot is down) says nothing.
 */
fun lbAccessAfterAnswer(current: LbAccess, status: Int, code: String): LbAccess = when {
	status in 200..299 -> if (current == LbAccess.TOO_OLD) LbAccess.OPEN else current
	else -> lbRefusalEffect(current, status, code).access
}

/**
 * [current] after a `welcome.lb` or an `lb` frame (review I-1, R17/R20). `identity: true` clears
 * too-old: the hub broadcasts it once lb-bot proves itself again. An advert without it while lb-bot
 * is up is an older lb-bot, which the hub refuses everyone but the primary user (design §6.4): too old
 * at once, with no round trip to find out. lb-bot down says nothing either way. A refused server
 * ([LbAccess.REFUSED]) stays refused: that is lb-bot's word, not its age.
 */
fun lbAccessAfterAdvert(current: LbAccess, identity: Boolean, primary: Boolean, available: Boolean): LbAccess = when {
	current == LbAccess.REFUSED -> current
	identity -> LbAccess.OPEN
	available && !primary -> LbAccess.TOO_OLD
	else -> current
}

/** The lb-bot layer is drawn at all: configured, and lb-bot did not refuse the server. */
fun lbLayerOpen(configured: Boolean, access: LbAccess): Boolean = configured && access != LbAccess.REFUSED

/** lb-bot reads as available: the advert or probe said so, and the hub did not call it too old for us. */
fun lbAvailableFor(available: Boolean, access: LbAccess): Boolean = available && access != LbAccess.TOO_OLD

/**
 * NB3: an answer's body as one log line. lb-bot's JSON ends in a newline (Flask), which logcat drew
 * as an empty second `W LbBotManager:` line under every refusal.
 */
fun lbLogBody(text: String): String = text.take(200).replace(Regex("\\s+"), " ").trim()
