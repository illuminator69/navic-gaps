package paige.navic.domain.manager

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import paige.navic.data.database.entities.SyncActionType

/*
 * Data management (I-020): what the "lb-bot data" panel, "Refresh About" and the pending-actions
 * browser SAY, kept free of the network, Room and Compose so every sentence can be checked in a
 * test (`LbBotDataTest`). The managers record facts; these functions word them.
 *
 * The rule the whole file exists for: a sentence is a claim about lb-bot, and it is only made from
 * something lb-bot (or the hub, or Room) actually said. "Up to date" is never inferred from a
 * timeout, "nothing" is never inferred from a failure, and "loading" is only said while a request
 * is in flight.
 *
 * The panel's sentences are plain English strings, like the navi-connect settings screen they
 * sit in, which is deliberately not localised.
 */

// ----- the index mirror's last pull -------------------------------------------------------------

/** Why a pull did nothing at all. */
enum class LbIndexSkip {
	/** No hub is configured, or it is switched off. */
	NOT_SET_UP,

	/** The hub, or the lb-bot behind it, has no change feed. */
	NO_FEED,

	/** lb-bot is not reachable right now. */
	LB_BOT_DOWN,

	/** lb-bot answered the request with a refusal that is not worth retrying. */
	REFUSED
}

/**
 * What the index mirror's last pull did, as `LbIndexSync` records it — for the one screen that
 * lets the user ask for a pull and then has to say what came of it. Read-only: nothing decides
 * anything from this. [serial] counts passes within the process, so a reader that asked for a
 * pull can tell the pass it caused from the one before it.
 */
sealed interface LbIndexRun {
	val serial: Long

	/** No pass has run since the app started. */
	data object Idle : LbIndexRun {
		override val serial: Long = 0L
	}

	data class Running(override val serial: Long) : LbIndexRun

	/** Finished at [cursor], having changed [applied] artists (0: it was already current). */
	data class Done(override val serial: Long, val cursor: Long, val applied: Int) : LbIndexRun

	data class Skipped(override val serial: Long, val reason: LbIndexSkip) : LbIndexRun

	/** Ended in a failure the sync backs off from and retries; [detail] names it. */
	data class Failed(override val serial: Long, val detail: String) : LbIndexRun
}

/** A failed pull's cause, as a clause that completes "The pull failed: …". */
internal fun indexFailureDetail(error: LbBotManager.LbError): String = when (error) {
	LbBotManager.LbError.NotConfigured -> "no hub is set up"
	LbBotManager.LbError.RouteUnknown -> "this hub or lb-bot has no index feed"
	LbBotManager.LbError.Busy -> "lb-bot was busy or unreachable"
	is LbBotManager.LbError.Unreachable -> "the hub could not be reached"
	// 503 is the hub's single sync slot being held by another client's pull.
	is LbBotManager.LbError.Rejected ->
		if (error.status == 503) "another device's pull was in the way"
		else "lb-bot answered an error (${error.status})"
}

/**
 * The status line under the mirror's numbers. "Up to date" only ever comes from a pull that
 * finished and changed nothing — never from one that failed, was skipped, or is still running.
 */
internal fun indexRunLine(run: LbIndexRun): String = when (run) {
	LbIndexRun.Idle -> "Not pulled since the app started."
	is LbIndexRun.Running -> "Pulling lb-bot's index…"
	is LbIndexRun.Done ->
		if (run.applied == 0) "Up to date: the last pull changed nothing (change ${run.cursor})."
		else "Pulled: ${run.applied} ${if (run.applied == 1) "artist" else "artists"} changed " +
			"(now at change ${run.cursor})."
	is LbIndexRun.Skipped -> when (run.reason) {
		LbIndexSkip.NOT_SET_UP -> "Not pulled: no hub is set up."
		LbIndexSkip.NO_FEED -> "Not pulled: this hub or lb-bot has no index feed."
		LbIndexSkip.LB_BOT_DOWN -> "Not pulled: lb-bot is not reachable. The mirror is unchanged."
		LbIndexSkip.REFUSED -> "Not pulled: lb-bot refused the request. The mirror is unchanged."
	}
	is LbIndexRun.Failed ->
		"The pull failed: ${run.detail}. It resumes where it stopped and is tried again on its own."
}

/**
 * The mirror's own numbers. [supported] false is a hub that advertises no change feed: there is
 * no mirror to describe, and the artist page asks lb-bot directly instead.
 */
internal fun mirrorLine(supported: Boolean, info: LbMirrorInfo): String = when {
	!supported -> "This hub or lb-bot has no index feed, so nothing is mirrored here."
	info.artists == 0L && info.cursor == 0L && info.epoch.isBlank() -> "Nothing mirrored yet."
	else -> "${info.artists} ${if (info.artists == 1L) "artist" else "artists"} mirrored, " +
		"at change ${info.cursor}" +
		(if (info.epoch.isBlank()) "." else " (epoch ${info.epoch.take(8)}).")
}

// ----- shared wording -----------------------------------------------------------------------------

/** An error as a clause, for sentences this panel builds ("Not saved: …"). */
internal fun lbErrorClause(error: LbBotManager.LbError): String = when (error) {
	LbBotManager.LbError.NotConfigured -> "no hub is set up"
	LbBotManager.LbError.RouteUnknown -> "this hub or lb-bot is older and has no such route"
	LbBotManager.LbError.Busy -> "lb-bot is busy or unreachable"
	is LbBotManager.LbError.Unreachable -> "the hub could not be reached"
	// lb-bot's own sentence wherever it wrote one (its full stop is the caller's to add).
	is LbBotManager.LbError.Rejected ->
		error.message.trim().trimEnd('.').ifBlank { "the request failed (${error.status})" }
}

/**
 * Why the panel has nothing to show, or null when the lb-bot layer is there and its lists speak.
 * Two different things switch the layer off, and each is said as what it is: no hub (not set,
 * switched off, or without a token — [hubSet] false), or a hub that is set up and has itself
 * said it proxies no lb-bot ([hubSaysNoLbBot], `LbBotManager.hubSaysNoLbBot`). Telling the
 * second user to "set the hub up" would send them to fix the thing that works.
 */
internal fun lbLayerLine(hubSet: Boolean, hubSaysNoLbBot: Boolean): String? = when {
	!hubSet -> "lb-bot is reached through the hub. Set the hub up above to see its data here."
	hubSaysNoLbBot -> "This hub says it has no lb-bot set up, so there is nothing to show here."
	else -> null
}

/**
 * Which of five things a list fed by lb-bot is, so that no section ever says "Loading…" for
 * something that is not loading, or shows a blank where lb-bot simply could not be asked.
 */
enum class LbPanelData {
	/** The hub or lb-bot does not provide this. */
	UNSUPPORTED,

	/** A request is in flight and nothing has been loaded yet. */
	READING,

	/** lb-bot could not be read and this device holds nothing from before. */
	UNREACHABLE,

	/** lb-bot could not be read; what is shown is the copy this device loaded last. */
	STALE,

	/** What is shown is lb-bot's answer. */
	CURRENT
}

/**
 * [loaded]: a body has been applied at least once, from the cache or the network. [readFailed]:
 * the last network read failed. [reading]: one is in flight. [hubHeard]: the hub has said
 * anything about its lb-bot proxy in this process (`LbBotManager.hubHeard`).
 *
 * **"Older" is a verdict on a hub that answered.** [supported] is false until one has, so on a
 * cold start with the hub out of reach "not supported" is silence, not a refusal: the list is
 * then being asked for, out of reach, or the copy this device loaded last — never unsupported.
 */
internal fun panelData(
	hubHeard: Boolean,
	supported: Boolean,
	reading: Boolean,
	loaded: Boolean,
	readFailed: Boolean
): LbPanelData = when {
	hubHeard && !supported -> LbPanelData.UNSUPPORTED
	loaded && readFailed -> LbPanelData.STALE
	// The hub has not been heard from and nothing is asking it: the cached copy is all there
	// is. While the question is still out, the copy is shown without a verdict on it.
	loaded && !hubHeard && !reading -> LbPanelData.STALE
	loaded -> LbPanelData.CURRENT
	reading -> LbPanelData.READING
	// Nothing loaded and nothing in flight: lb-bot was down when this device last tried, or has
	// not been reachable since the app started.
	else -> LbPanelData.UNREACHABLE
}

const val LB_UNREACHABLE_LINE = "lb-bot is needed for this and is not reachable right now."
const val LB_STALE_LINE = "Can't reach lb-bot. This is the copy this device loaded last."
const val LB_READING_LINE = "Asking lb-bot…"

/** The line under the overrides heading, or null when the list speaks for itself. */
internal fun overridesNote(data: LbPanelData, count: Int): String? = when (data) {
	LbPanelData.UNSUPPORTED ->
		"This hub or lb-bot is older and does not store MusicBrainz overrides yet."
	LbPanelData.READING -> LB_READING_LINE
	LbPanelData.UNREACHABLE -> LB_UNREACHABLE_LINE
	LbPanelData.STALE -> LB_STALE_LINE
	LbPanelData.CURRENT ->
		if (count == 0) "None. An override is set from an artist's page: Fix MusicBrainz match."
		else null
}

/** "Navidrome said: …" — the tag that was in force when the override was set. */
internal fun overrideTagLine(row: LbArtistOverride): String =
	"Navidrome said: ${row.tagMbid.ifBlank { "none" }}"

/**
 * What a set or a remove made from the panel did. It follows lb-bot's scan decision exactly as
 * the artist page does ([overrideScanToFollow]): the panel never starts a scan, and only says one
 * is running when lb-bot answered with its task.
 */
internal fun overrideWriteLine(result: LbBotManager.LbResult<LbOverrideWrite>): String =
	when (result) {
		is LbBotManager.LbResult.Ok ->
			if (overrideScanToFollow(result.value) != null) "Saved. lb-bot is rescanning this artist."
			else "Saved."
		is LbBotManager.LbResult.Failed ->
			if (result.error == LbBotManager.LbError.RouteUnknown) {
				"This lb-bot is older and can't store a MusicBrainz match yet. Nothing was changed."
			} else {
				"Not saved: ${lbErrorClause(result.error)}."
			}
	}

// ----- listening history --------------------------------------------------------------------------

/**
 * The listening mirror's state in words. [lastSync] is `lastSyncAt` already formatted in the
 * device's zone, blank when lb-bot has never synced.
 *
 * [advertised] is `LbBotManager.listeningSupported`, which is the LAST advert's verdict until
 * this process hears one ([hubHeard]) — and `false` is also what an install that never heard an
 * advert holds. So "this hub does not offer it" is only said of a hub that has spoken; before
 * that, not advertised is a question still out ([ListeningPhase.Loading]) or lb-bot out of reach.
 */
internal fun listeningStatusLine(
	hubHeard: Boolean,
	advertised: Boolean,
	phase: ListeningPhase,
	count: Int,
	lastSync: String
): String = when {
	!advertised && hubHeard -> "This hub does not offer listening history yet."
	!advertised -> if (phase == ListeningPhase.Loading) LB_READING_LINE else LB_UNREACHABLE_LINE
	else -> when (phase) {
		ListeningPhase.Loading -> LB_READING_LINE
		ListeningPhase.Off -> "Off: lb-bot has no ListenBrainz user set up."
		ListeningPhase.Older -> "This lb-bot has no listening history yet."
		ListeningPhase.Unreachable -> LB_UNREACHABLE_LINE
		is ListeningPhase.Importing ->
			if (phase.total != null) "Importing: ${phase.count} of ${phase.total} plays so far."
			else "Importing: ${phase.count} plays so far."
		ListeningPhase.Ready ->
			"Ready: $count plays stored." + if (lastSync.isBlank()) "" else " Last sync $lastSync."
		is ListeningPhase.SyncFailed -> {
			// lb-bot's own text, closed with one full stop whether or not it brought its own.
			val reason = phase.error.trim().trimEnd('.')
			(if (reason.isBlank()) "The last sync failed." else "The last sync failed: $reason.") +
				" $count plays already stored are kept."
		}
	}
}

/** What "Sync now" came to for the listening half, from what lb-bot said — never from a clock. */
sealed interface ListeningSyncOutcome {
	/** The request is in flight. */
	data object Asking : ListeningSyncOutcome

	/** 404 / 405: this lb-bot predates the listening routes. A fact, not an error. */
	data object Older : ListeningSyncOutcome

	/** The request itself failed; lb-bot was not asked. */
	data class NotAsked(val error: LbBotManager.LbError) : ListeningSyncOutcome

	/** lb-bot accepted the request, and its status shows no finished pull since. */
	data object Waiting : ListeningSyncOutcome

	/** lb-bot accepted the request, and its status could not be read afterwards. */
	data object AskedUnread : ListeningSyncOutcome

	/** `lastSyncAt` moved: a pull finished after the request. */
	data class Finished(val count: Int) : ListeningSyncOutcome

	data class Importing(val count: Int, val total: Int?) : ListeningSyncOutcome

	/** The status reports a failure that was not there before the request. */
	data class Failed(val error: String) : ListeningSyncOutcome

	data object Off : ListeningSyncOutcome
}

/**
 * [before] is the status read ahead of the request (null when it could not be read), [post] the
 * request's own answer, [after] the status read since (null: not read yet).
 *
 * The request answers at once and never waits on ListenBrainz, so its own `ok` says only that the
 * pull was asked for. "Finished" needs evidence: `lastSyncAt` having moved. An `error` state that
 * reads exactly as it did before the request is the OLD failure, with the new pull still to come.
 */
internal fun listeningSyncOutcome(
	before: LbListeningStatus?,
	post: LbBotManager.LbResult<LbListeningSync>,
	after: LbBotManager.LbResult<LbListeningStatus>?
): ListeningSyncOutcome {
	if (post is LbBotManager.LbResult.Failed) {
		return if (post.error == LbBotManager.LbError.RouteUnknown) ListeningSyncOutcome.Older
		else ListeningSyncOutcome.NotAsked(post.error)
	}
	val status = when (after) {
		null -> return ListeningSyncOutcome.Waiting
		is LbBotManager.LbResult.Failed -> return ListeningSyncOutcome.AskedUnread
		is LbBotManager.LbResult.Ok -> after.value
	}
	val moved = status.lastSyncAt != null && status.lastSyncAt != before?.lastSyncAt
	return when (status.state) {
		"off" -> ListeningSyncOutcome.Off
		"importing" -> ListeningSyncOutcome.Importing(status.count, status.total)
		"error" ->
			if (before?.state == "error" && before.error == status.error && !moved) ListeningSyncOutcome.Waiting
			else ListeningSyncOutcome.Failed(status.error)
		"" -> ListeningSyncOutcome.AskedUnread
		else -> if (moved) ListeningSyncOutcome.Finished(status.count) else ListeningSyncOutcome.Waiting
	}
}

internal fun listeningSyncLine(outcome: ListeningSyncOutcome): String = when (outcome) {
	ListeningSyncOutcome.Asking -> "Asking lb-bot to pull from ListenBrainz…"
	ListeningSyncOutcome.Older -> "This lb-bot has no listening history yet."
	is ListeningSyncOutcome.NotAsked -> "Couldn't ask lb-bot to sync: ${lbErrorClause(outcome.error)}."
	ListeningSyncOutcome.Waiting -> "lb-bot was asked to pull. No new sync has finished yet."
	ListeningSyncOutcome.AskedUnread -> "lb-bot was asked to pull. Its status could not be read since."
	is ListeningSyncOutcome.Finished -> "Synced: ${outcome.count} plays stored."
	is ListeningSyncOutcome.Importing ->
		if (outcome.total != null) "lb-bot is importing: ${outcome.count} of ${outcome.total} plays so far."
		else "lb-bot is importing: ${outcome.count} plays so far."
	is ListeningSyncOutcome.Failed ->
		if (outcome.error.isBlank()) "The sync failed." else "The sync failed: ${outcome.error}"
	ListeningSyncOutcome.Off -> "lb-bot has no ListenBrainz user set up, so there is nothing to sync."
}

// ----- Refresh About ------------------------------------------------------------------------------

private val aboutJson = Json {
	ignoreUnknownKeys = true
	isLenient = true
	coerceInputValues = true
}

/**
 * A `/lb/meta/…` body as an About, or null when it is not one.
 *
 * lb-bot always says `found`, true or false. A body without it — `{}`, an array, a page of HTML
 * — is not an About, and must not be mistaken for the valid sparse answer `{"found": false}`,
 * which IS content: lb-bot looked, and nobody has written about this. The two decode to the same
 * object, which is why the key is checked before decoding.
 */
internal fun aboutBodyOrNull(raw: String): LbMeta? = try {
	val body = aboutJson.parseToJsonElement(raw) as? JsonObject
	if ((body?.get("found") as? JsonPrimitive)?.booleanOrNull == null) null
	else aboutJson.decodeFromJsonElement<LbMeta>(body)
} catch (e: Exception) {
	null
}

/** What a "Refresh About" did. Only [Replaced] changes what is shown, or what is cached. */
sealed interface LbMetaRefresh {
	/** lb-bot answered with an About — a sparse one (`found: false`) included. */
	data class Replaced(val meta: LbMeta) : LbMetaRefresh

	/** A 200 that is not an About. */
	data object Nothing : LbMetaRefresh

	/** lb-bot (or the hub in front of it) could not be reached. */
	data object Unreachable : LbMetaRefresh

	/** lb-bot answered an error. */
	data class Refused(val status: Int) : LbMetaRefresh
}

/** The request's result as a [LbMetaRefresh]; `text` is the body of a 2xx. */
internal fun metaRefreshOf(result: LbBotManager.LbResult<String>): LbMetaRefresh = when (result) {
	is LbBotManager.LbResult.Ok ->
		aboutBodyOrNull(result.value)?.let { LbMetaRefresh.Replaced(it) } ?: LbMetaRefresh.Nothing
	is LbBotManager.LbResult.Failed -> when (val error = result.error) {
		LbBotManager.LbError.NotConfigured,
		LbBotManager.LbError.Busy,
		is LbBotManager.LbError.Unreachable -> LbMetaRefresh.Unreachable
		LbBotManager.LbError.RouteUnknown -> LbMetaRefresh.Refused(404)
		is LbBotManager.LbError.Rejected -> LbMetaRefresh.Refused(error.status)
	}
}

/** Which sentence a refresh ends in. The sentences themselves are string resources. */
enum class AboutRefreshNotice {
	/** There is a new About on screen. */
	UPDATED,

	/** lb-bot looked again and found nothing written; the About on screen is gone. */
	UPDATED_EMPTY,

	/** "lb-bot returned nothing. What is shown is unchanged." */
	NOTHING,

	/** "Couldn't reach lb-bot. What is shown is unchanged." */
	UNREACHABLE,

	/** "lb-bot answered an error (N). What is shown is unchanged." */
	ERROR
}

internal fun aboutRefreshNotice(outcome: LbMetaRefresh): AboutRefreshNotice = when (outcome) {
	is LbMetaRefresh.Replaced ->
		if (outcome.meta.found) AboutRefreshNotice.UPDATED else AboutRefreshNotice.UPDATED_EMPTY
	LbMetaRefresh.Nothing -> AboutRefreshNotice.NOTHING
	LbMetaRefresh.Unreachable -> AboutRefreshNotice.UNREACHABLE
	is LbMetaRefresh.Refused -> AboutRefreshNotice.ERROR
}

// ----- pending actions ----------------------------------------------------------------------------

/** A queued action in the terms the browser words it. */
sealed interface PendingActionKind {
	data object Scrobble : PendingActionKind
	data object Star : PendingActionKind
	data object Unstar : PendingActionKind
	data class Rate(val stars: Int) : PendingActionKind
	data object DeletePlaylist : PendingActionKind
}

internal fun pendingActionKind(type: SyncActionType): PendingActionKind = when (type) {
	SyncActionType.SCROBBLE -> PendingActionKind.Scrobble
	SyncActionType.STAR -> PendingActionKind.Star
	SyncActionType.UNSTAR -> PendingActionKind.Unstar
	SyncActionType.DELETE_PLAYLIST -> PendingActionKind.DeletePlaylist
	SyncActionType.STAR_0 -> PendingActionKind.Rate(0)
	SyncActionType.STAR_1 -> PendingActionKind.Rate(1)
	SyncActionType.STAR_2 -> PendingActionKind.Rate(2)
	SyncActionType.STAR_3 -> PendingActionKind.Rate(3)
	SyncActionType.STAR_4 -> PendingActionKind.Rate(4)
	SyncActionType.STAR_5 -> PendingActionKind.Rate(5)
}

/**
 * What an action is about: the item's title out of the local tables when one of them holds the
 * id, else the id itself. [titles] is whatever Room resolved, keyed by id; a blank title counts
 * as unresolved. An action on something Room no longer has (a deleted playlist, most obviously)
 * still says which id it was, rather than nothing.
 */
internal fun pendingActionTitle(itemId: String, titles: Map<String, String>): String =
	titles[itemId]?.takeIf { it.isNotBlank() } ?: itemId
