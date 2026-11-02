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

/** What one refused lb-bot answer does to this device. */
data class LbRefusalEffect(
	val access: LbAccess,
	/** 403 `admin_only`: the user is not an admin (whatever was last said); the action goes, silently. */
	val demoteAdmin: Boolean,
	/** The caller says nothing about it: no toast, no error line (the gate vanishes instead). */
	val silent: Boolean
)

/** [current] after an answer of [status] with lb-bot's or the hub's [code]. */
fun lbRefusalEffect(current: LbAccess, status: Int, code: String): LbRefusalEffect = when {
	status == 401 && code == "signin_required" -> LbRefusalEffect(LbAccess.REFUSED, demoteAdmin = false, silent = true)
	status == 503 && code == "lbbot_too_old" -> LbRefusalEffect(LbAccess.TOO_OLD, demoteAdmin = false, silent = true)
	status == 403 && code == "admin_only" -> LbRefusalEffect(current, demoteAdmin = true, silent = true)
	else -> LbRefusalEffect(current, demoteAdmin = false, silent = false)
}

/** The lb-bot layer is drawn at all: configured, and lb-bot did not refuse the server. */
fun lbLayerOpen(configured: Boolean, access: LbAccess): Boolean = configured && access != LbAccess.REFUSED

/** lb-bot reads as available: the advert or probe said so, and the hub did not call it too old for us. */
fun lbAvailableFor(available: Boolean, access: LbAccess): Boolean = available && access != LbAccess.TOO_OLD
