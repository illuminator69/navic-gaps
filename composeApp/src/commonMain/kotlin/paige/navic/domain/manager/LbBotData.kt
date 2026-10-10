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

const val INDEX_PULL_ASKED_LINE = "A pull has been asked for…"
const val INDEX_PULL_NOT_STARTED_LINE = "No pull has started since you asked."

/**
 * The mirror's line once "Sync now" has been tapped. [serialAtTap] is [LbIndexRun.serial] as it
 * stood at the tap (null: no tap is being answered), and [overdue] says a few seconds have gone
 * by with nothing running.
 *
 * The last run is a verdict on an EARLIER pull. Between the tap and the pass it causes, showing
 * it — "Up to date" most of all — would read as the answer to the tap. So until a pass newer
 * than the one seen at the tap has started, the line says the pull has been asked for, and if
 * none starts it says that. A pass that was already running at the tap is said as running: that
 * is no verdict and is true as it stands, and the pull the tap asked for follows it.
 */
internal fun indexRunLineAfterTap(run: LbIndexRun, serialAtTap: Long?, overdue: Boolean): String =
	when {
		serialAtTap == null || run.serial > serialAtTap -> indexRunLine(run)
		run is LbIndexRun.Running -> indexRunLine(run)
		overdue -> INDEX_PULL_NOT_STARTED_LINE
		else -> INDEX_PULL_ASKED_LINE
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

/**
 * Whether a list fed by lb-bot is being read, as [panelData] means it. [inFlight] counts the
 * reads that are out — the panel opening and "Sync now" can each have one, and the first to
 * come back must not end the state for the other. [readDue] is true until the panel's first
 * read has started: the list is about to be asked for, which is "reading" and not "lb-bot is
 * not reachable" for the frame in between.
 */
internal fun panelReading(inFlight: Int, readDue: Boolean): Boolean = readDue || inFlight > 0

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
 * What a set or a remove ([removing]) made from the panel did, each in its own words: a remove
 * that failed removed nothing, and "Not saved" would be a sentence about something else. It
 * follows lb-bot's scan decision exactly as the artist page does ([overrideScanToFollow]): the
 * panel never starts a scan, and only says one is running when lb-bot answered with its task.
 */
internal fun overrideWriteLine(
	result: LbBotManager.LbResult<LbOverrideWrite>,
	removing: Boolean
): String =
	when (result) {
		is LbBotManager.LbResult.Ok -> {
			val done = if (removing) "Removed." else "Saved."
			if (overrideScanToFollow(result.value) != null) "$done lb-bot is rescanning this artist."
			else done
		}
		is LbBotManager.LbResult.Failed -> when {
			result.error != LbBotManager.LbError.RouteUnknown ->
				"${if (removing) "Not removed" else "Not saved"}: ${lbErrorClause(result.error)}."
			removing ->
				"This lb-bot is older and stores no MusicBrainz matches, so there was nothing to remove."
			else ->
				"This lb-bot is older and can't store a MusicBrainz match yet. Nothing was changed."
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

	/** lb-bot accepted the request, and nothing read since is evidence that the pull finished. */
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
 * [before] is the baseline: the status read **immediately before the request** — never whatever
 * the panel last showed, which may be minutes old and would credit a background sync that
 * finished in between to this tap. Null when that read failed. [post] is the request's own
 * answer, [after] the status read since (null: not read yet).
 *
 * The request answers at once and never waits on ListenBrainz, so its own `ok` says only that the
 * pull was asked for. "Finished" needs evidence: `lastSyncAt` having moved **past the baseline**.
 * An `error` state that reads exactly as it did before the request is the OLD failure, with the
 * new pull still to come.
 *
 * **With no baseline there is no evidence.** A sync time that is there now may have been there
 * all along, and so may an error: neither is called this tap's, and the outcome is [Waiting]
 * (see [listeningSyncBaseline] for how a later read can still supply one). What lb-bot is doing
 * right now — importing, off — is a fact either way.
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
	val moved = before != null && status.lastSyncAt != null && status.lastSyncAt != before.lastSyncAt
	return when (status.state) {
		"off" -> ListeningSyncOutcome.Off
		"importing" -> ListeningSyncOutcome.Importing(status.count, status.total)
		"error" -> when {
			before == null -> ListeningSyncOutcome.Waiting
			before.state == "error" && before.error == status.error && !moved -> ListeningSyncOutcome.Waiting
			else -> ListeningSyncOutcome.Failed(status.error)
		}
		"" -> ListeningSyncOutcome.AskedUnread
		else -> if (moved) ListeningSyncOutcome.Finished(status.count) else ListeningSyncOutcome.Waiting
	}
}

/**
 * The baseline, given the one held so far and a status [read] that has just come back: the one
 * held is never replaced, and with none held the read becomes it. So the read made immediately
 * before the request is the baseline when it succeeds; when it fails, the first status that can
 * be read since stands in. A sync that finishes after THAT read finished after the request too,
 * so it is still evidence — a baseline taken late can only under-claim. A read that failed, or
 * answered no `state`, is no baseline.
 */
internal fun listeningSyncBaseline(
	held: LbListeningStatus?,
	read: LbBotManager.LbResult<LbListeningStatus>?
): LbListeningStatus? =
	held ?: (read as? LbBotManager.LbResult.Ok)?.value?.takeIf { it.state.isNotBlank() }

internal fun listeningSyncLine(outcome: ListeningSyncOutcome): String = when (outcome) {
	ListeningSyncOutcome.Asking -> "Asking lb-bot to pull from ListenBrainz…"
	ListeningSyncOutcome.Older -> "This lb-bot has no listening history yet."
	is ListeningSyncOutcome.NotAsked -> "Couldn't ask lb-bot to sync: ${lbErrorClause(outcome.error)}."
	// "Not seen to finish" is all that is known — with no baseline it may even have finished.
	ListeningSyncOutcome.Waiting -> "lb-bot was asked to pull. It has not been seen to finish yet."
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
 * — is not an About, and must not be mistaken for the valid sparse answer `{"found": false}`:
 * lb-bot answered, and has no text. The two are different facts and are said differently
 * ("returned nothing" / "found nothing"), but they decode to the same object, which is why the
 * key is checked before decoding. Neither replaces an About that has content
 * ([aboutRefreshOutcome]).
 */
internal fun aboutBodyOrNull(raw: String): LbMeta? = try {
	val body = aboutJson.parseToJsonElement(raw) as? JsonObject
	if ((body?.get("found") as? JsonPrimitive)?.booleanOrNull == null) null
	else aboutJson.decodeFromJsonElement<LbMeta>(body)
} catch (e: Exception) {
	null
}

/** What the refresh request itself came to, before it is weighed against what the page shows. */
sealed interface LbMetaRefresh {
	/** lb-bot answered with an About — possibly a sparse one (`found: false`). */
	data class Answered(val meta: LbMeta) : LbMetaRefresh

	/** A 200 that is not an About. */
	data object Nothing : LbMetaRefresh

	/** lb-bot (or the hub in front of it) could not be reached. */
	data object Unreachable : LbMetaRefresh

	/** lb-bot answered an error: its status, and its own sentence when it sent one. */
	data class Refused(val status: Int, val text: String = "") : LbMetaRefresh
}

/**
 * How a non-2xx answer to a refresh reads, from its status and lb-bot's `error` / `retryable`.
 *
 * The general mapping (`LbBotManager.failureFor`) reads every 502 as "busy" and drops its text,
 * which is wrong here: lb-bot answers **502 with `retryable`** when a leg of the refresh
 * (MusicBrainz, Wikipedia) did not answer — it has kept its stored About and says which leg, and
 * that sentence is the one to show. The hub's own 502 / 504 (lb-bot unreachable, or timed out)
 * carry no such flag and are "could not reach lb-bot". Every other status keeps lb-bot's text.
 */
internal fun metaRefreshError(
	status: Int,
	message: String,
	retryable: Boolean
): LbBotManager.LbError = when {
	(status == 502 || status == 504) && !retryable -> LbBotManager.LbError.Busy
	else -> LbBotManager.LbError.Rejected(status, message)
}

/** The request's result as a [LbMetaRefresh]; `text` is the body of a 2xx. */
internal fun metaRefreshOf(result: LbBotManager.LbResult<String>): LbMetaRefresh = when (result) {
	is LbBotManager.LbResult.Ok ->
		aboutBodyOrNull(result.value)?.let { LbMetaRefresh.Answered(it) } ?: LbMetaRefresh.Nothing
	is LbBotManager.LbResult.Failed -> when (val error = result.error) {
		LbBotManager.LbError.NotConfigured,
		LbBotManager.LbError.Busy,
		is LbBotManager.LbError.Unreachable -> LbMetaRefresh.Unreachable
		LbBotManager.LbError.RouteUnknown -> LbMetaRefresh.Refused(404)
		is LbBotManager.LbError.Rejected -> LbMetaRefresh.Refused(error.status, error.message)
	}
}

/**
 * Whether the answer is stored as the page's About for next time: only one with content.
 *
 * A sparse `found: false` is never stored. Under the ordinary cache key it replaced a good About
 * for the cache's whole month — and lb-bot has answered `found: false` for a MusicBrainz or
 * Wikipedia blip as readily as for a true "nothing written".
 */
internal fun aboutRefreshStored(refresh: LbMetaRefresh): Boolean =
	refresh is LbMetaRefresh.Answered && refresh.meta.found

/** What a "Refresh About" came to, weighed against the About the page shows. */
sealed interface AboutRefreshOutcome {
	/** An answer with content that differs from what is shown: it replaces it. */
	data class Updated(val meta: LbMeta) : AboutRefreshOutcome

	/** An answer with content, identical to what is shown. */
	data object Same : AboutRefreshOutcome

	/** `found: false` while an About with content is shown. What is shown stays. */
	data object NothingThisTime : AboutRefreshOutcome

	/** `found: false` with no About content on screen: shown as what it is. Never stored. */
	data class NothingWritten(val meta: LbMeta) : AboutRefreshOutcome

	/** A 200 that is not an About. */
	data object Nothing : AboutRefreshOutcome

	data object Unreachable : AboutRefreshOutcome

	/** lb-bot answered an error; [text] is its own sentence, blank when it sent none. */
	data class Refused(val status: Int, val text: String) : AboutRefreshOutcome
}

/**
 * [shown] is the About on the page as the answer arrives (null: none).
 *
 * **A refresh never destroys a good About.** "Content" is lb-bot's own `found` — a summary or a
 * Wikidata description. Only an answer with content replaces what is shown; `found: false` over
 * an About that has content changes nothing and is said as "nothing this time", because that is
 * all that is known. With no content on screen the sparse answer may stand (it can still carry
 * links or credits) and is said as "nothing written". An answer equal to what is shown is not
 * "refreshed".
 */
internal fun aboutRefreshOutcome(shown: LbMeta?, refresh: LbMetaRefresh): AboutRefreshOutcome =
	when (refresh) {
		is LbMetaRefresh.Answered -> when {
			!refresh.meta.found ->
				if (shown?.found == true) AboutRefreshOutcome.NothingThisTime
				else AboutRefreshOutcome.NothingWritten(refresh.meta)
			refresh.meta == shown -> AboutRefreshOutcome.Same
			else -> AboutRefreshOutcome.Updated(refresh.meta)
		}
		LbMetaRefresh.Nothing -> AboutRefreshOutcome.Nothing
		LbMetaRefresh.Unreachable -> AboutRefreshOutcome.Unreachable
		is LbMetaRefresh.Refused -> AboutRefreshOutcome.Refused(refresh.status, refresh.text)
	}

/** The About the page shows after a refresh: only two outcomes change it. */
internal fun aboutAfterRefresh(shown: LbMeta?, outcome: AboutRefreshOutcome): LbMeta? = when (outcome) {
	is AboutRefreshOutcome.Updated -> outcome.meta
	is AboutRefreshOutcome.NothingWritten -> outcome.meta
	else -> shown
}

/** Which sentence a refresh ends in. The sentences themselves are string resources. */
enum class AboutRefreshNotice {
	/** "About refreshed." — said only of an answer with content that changed what is shown. */
	UPDATED,

	/** "lb-bot's answer is the same as what is shown." */
	SAME,

	/** "lb-bot found nothing this time. What is shown is unchanged." */
	NOTHING_THIS_TIME,

	/** "lb-bot found nothing written about this." */
	NOTHING_WRITTEN,

	/** "lb-bot returned nothing. What is shown is unchanged." */
	NOTHING,

	/** "Couldn't reach lb-bot. What is shown is unchanged." */
	UNREACHABLE,

	/** "lb-bot answered an error (N). What is shown is unchanged." — its status, no text sent. */
	ERROR,

	/** "lb-bot answered an error (N): <lb-bot's text>. What is shown is unchanged." */
	ERROR_SAID
}

internal fun aboutRefreshNotice(outcome: AboutRefreshOutcome): AboutRefreshNotice = when (outcome) {
	is AboutRefreshOutcome.Updated -> AboutRefreshNotice.UPDATED
	AboutRefreshOutcome.Same -> AboutRefreshNotice.SAME
	AboutRefreshOutcome.NothingThisTime -> AboutRefreshNotice.NOTHING_THIS_TIME
	is AboutRefreshOutcome.NothingWritten -> AboutRefreshNotice.NOTHING_WRITTEN
	AboutRefreshOutcome.Nothing -> AboutRefreshNotice.NOTHING
	AboutRefreshOutcome.Unreachable -> AboutRefreshNotice.UNREACHABLE
	is AboutRefreshOutcome.Refused ->
		if (outcome.text.isBlank()) AboutRefreshNotice.ERROR else AboutRefreshNotice.ERROR_SAID
}

/**
 * The arguments of [aboutRefreshNotice]'s sentence: an error's status, then lb-bot's text when it
 * sent one — without its own full stop, which the sentence adds.
 */
internal fun aboutRefreshArgs(outcome: AboutRefreshOutcome): List<Any> = when (outcome) {
	is AboutRefreshOutcome.Refused -> {
		val text = outcome.text.trim().trimEnd('.')
		if (text.isBlank()) listOf(outcome.status) else listOf(outcome.status, text)
	}
	else -> emptyList()
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
 * The ids whose titles still have to be looked up: the queue's, less those already asked for
 * ([seen], found or not). The queue re-emits on every row a drain deletes, and asking Room for
 * every title of a long queue each time is a full set of reads per sent action.
 */
internal fun pendingTitlesToLookUp(ids: Collection<String>, seen: Set<String>): Set<String> =
	ids.toSet() - seen

/**
 * What an action is about: the item's title out of the local tables when one of them holds the
 * id, else the id itself. [titles] is whatever Room resolved, keyed by id; a blank title counts
 * as unresolved. An action on something Room no longer has (a deleted playlist, most obviously)
 * still says which id it was, rather than nothing.
 */
internal fun pendingActionTitle(itemId: String, titles: Map<String, String>): String =
	titles[itemId]?.takeIf { it.isNotBlank() } ?: itemId

// ----- album overrides and name merges (I-022, I-023) ---------------------------------------------
// Both lists ride the overrides body beside the artist overrides, so they are exactly as loaded,
// stale or unreachable as those are. Each may be ABSENT from the body — an lb-bot that predates
// it, or one that could not work it out — and absent is not empty.

/**
 * Whether a list's section exists. **A missing list renders nothing**; an empty one is shown,
 * to say there are none. With nothing loaded at all there is no section either: the overrides
 * list above already says lb-bot is being asked, is out of reach, or is older. And [controls]
 * — the hub offers the route the rows' Undo goes through — because a list whose every button
 * can only fail is not one to show (Feishin gates the same two sections the same way).
 */
internal fun overrideListShown(data: LbPanelData, rows: List<*>?, controls: Boolean = true): Boolean =
	controls && rows != null && (data == LbPanelData.STALE || data == LbPanelData.CURRENT)

/** The line under the album overrides' heading, or null when the rows speak for themselves. */
internal fun albumRowsNote(data: LbPanelData, count: Int): String? = when (data) {
	LbPanelData.STALE -> LB_STALE_LINE
	LbPanelData.CURRENT ->
		if (count == 0) "None. An album's release is set from the album's page: Fix album match." else null
	LbPanelData.UNSUPPORTED, LbPanelData.READING, LbPanelData.UNREACHABLE -> null
}

/** As [albumRowsNote], for the name merges. */
internal fun mergeRowsNote(data: LbPanelData, count: Int): String? = when (data) {
	LbPanelData.STALE -> LB_STALE_LINE
	LbPanelData.CURRENT ->
		if (count == 0) {
			"None. Two spellings of one artist are merged from the artist's page: Fix MusicBrainz match."
		} else {
			null
		}
	LbPanelData.UNSUPPORTED, LbPanelData.READING, LbPanelData.UNREACHABLE -> null
}

private fun filesText(count: Int): String = "$count ${if (count == 1) "file" else "files"}"

/**
 * Problems, not files (ruling X9): a cover that could not be saved is one of lb-bot's
 * `failures` too, and "12 of 12 files; 1 could not be written" was wrong.
 */
private fun problemsText(count: Int): String = "$count ${if (count == 1) "problem" else "problems"}."

/** Said once every file is dealt with and lb-bot still holds the row active (ruling X4). */
private const val INDEX_WAIT = "lb-bot is waiting for Navidrome to index them (up to a minute)."

/**
 * A row lb-bot calls `failed`. That is an apply or a merge that did not finish — **or an undo
 * that did not**: the row does not say which, so the sentence is true of both, and lb-bot's
 * reasons under it ([albumRowProblems]) say the rest.
 */
private fun didNotFinish(done: Int, total: Int, problems: Int, what: String): String = listOfNotNull(
	"Did not finish: $done of ${filesText(total)} written.",
	problemsText(problems).takeIf { problems > 0 },
	"Undo puts the previous $what back."
).joinToString(" ")

/** "Artist — Album", by the names lb-bot stored. Never blank, and never a path: at worst the row's id. */
internal fun albumRowTitle(row: LbAlbumOverrideRow): String =
	listOf(row.artist.trim(), row.album.trim()).filter { it.isNotBlank() }.joinToString(" — ").ifBlank { row.id }

/**
 * What an album override row says of itself: every number is the row's own. A state this build
 * has never heard of is not put into words — no raw token, and no sentence nothing backs.
 */
internal fun albumRowDetail(row: LbAlbumOverrideRow): String = when (row.state) {
	ALBUM_STATE_APPLYING -> when {
		albumWaitingForIndex(row) -> "The tags are written. $INDEX_WAIT"
		row.total > 0 -> "lb-bot is writing the tags: ${row.done} of ${filesText(row.total)}."
		else -> "lb-bot is writing the tags."
	}
	ALBUM_STATE_REMOVING ->
		if (albumWaitingForIndex(row)) "The previous tags are back. $INDEX_WAIT"
		else "lb-bot is putting the previous tags back."
	ALBUM_STATE_FAILED -> didNotFinish(row.done, row.total, row.failures.size, "tags")
	ALBUM_STATE_APPLIED -> {
		val retagged = when {
			row.failures.isNotEmpty() ->
				"Retagged ${row.done} of ${filesText(row.total)}. ${problemsText(row.failures.size)}"
			// The release was kept and nothing rewritten: a cover-only change has no tags to count.
			row.done > 0 || row.releaseMbid != row.prevReleaseMbid -> "Retagged ${filesText(row.done)}."
			else -> ""
		}
		val cover = if (row.coverUrl.isNotBlank()) "Cover changed." else ""
		listOf(retagged, cover).filter { it.isNotBlank() }.joinToString(" ").ifBlank { "No tag was rewritten." }
	}
	else -> ""
}

/** A row at rest — applied, or failed part-way — can be undone; one lb-bot is working on cannot. */
internal fun albumRowCanUndo(row: LbAlbumOverrideRow): Boolean =
	row.id.isNotBlank() && (row.state == ALBUM_STATE_APPLIED || row.state == ALBUM_STATE_FAILED)

/** "Rewritten spelling → kept name", by the names lb-bot stored; at worst the row's id. */
internal fun mergeRowTitle(row: LbMergeRow): String =
	listOf(row.from.name.trim(), row.into.name.trim()).filter { it.isNotBlank() }.joinToString(" → ").ifBlank { row.id }

/** As [albumRowDetail], for a name merge. */
internal fun mergeRowDetail(row: LbMergeRow): String = when (row.state) {
	MERGE_STATE_MERGING -> when {
		mergeWaitingForIndex(row) -> "The files are retagged. $INDEX_WAIT"
		row.total > 0 -> "lb-bot is retagging the files: ${row.done} of ${row.total}."
		else -> "lb-bot is retagging the files."
	}
	MERGE_STATE_UNDOING ->
		if (mergeWaitingForIndex(row)) "The previous names are back. $INDEX_WAIT"
		else "lb-bot is putting the previous names back."
	MERGE_STATE_FAILED -> didNotFinish(row.done, row.total, row.failures.size, "names")
	MERGE_STATE_MERGED ->
		if (row.failures.isNotEmpty()) {
			"Retagged ${row.done} of ${filesText(row.total)}. ${problemsText(row.failures.size)}"
		} else {
			"Retagged ${filesText(row.done)}."
		}
	else -> ""
}

internal fun mergeRowCanUndo(row: LbMergeRow): Boolean =
	row.id.isNotBlank() && (row.state == MERGE_STATE_MERGED || row.state == MERGE_STATE_FAILED)

/**
 * What pressing Undo came to — the request's own answer. lb-bot works in the background, so a
 * started undo is said as started; how it ends is said when it ends, and the row then leaves
 * the list. A refusal is lb-bot's sentence; an lb-bot older than the route is a fact, not an
 * error.
 */
internal fun undoStartLine(result: LbBotManager.LbResult<*>, merge: Boolean): String = when (result) {
	is LbBotManager.LbResult.Ok ->
		if (merge) "lb-bot is putting the previous names back." else "lb-bot is putting the previous tags back."
	is LbBotManager.LbResult.Failed ->
		if (result.error == LbBotManager.LbError.RouteUnknown) {
			"This lb-bot is older and has no such undo. Nothing was changed."
		} else {
			"Not undone: ${lbErrorClause(result.error)}."
		}
}

/**
 * The line under the lists for an Undo pressed in this panel, **for as long as it is true**.
 * [started] is the request's own answer ([undoStartLine]); [phase] is where the job that request
 * started now stands, or null when this device no longer follows one.
 *
 * "lb-bot is putting … back" is a sentence about a job still running, so it is said only while
 * the job is followed. Once the job has ended the line says how; and with no job to speak of
 * it says nothing — the list above is then what is true.
 */
internal fun undoLine(started: LbBotManager.LbResult<*>, merge: Boolean, phase: LbJobPhase?): String {
	if (started is LbBotManager.LbResult.Failed) return undoStartLine(started, merge)
	val what = if (merge) "names" else "tags"
	return when (phase) {
		null -> ""
		LbJobPhase.POLLING -> undoStartLine(started, merge)
		// For an undo the row being gone IS the finish; GONE is not a phase it ends in.
		LbJobPhase.DONE, LbJobPhase.GONE -> "lb-bot put the previous $what back."
		LbJobPhase.FAILED -> "lb-bot could not put all the previous $what back."
		LbJobPhase.TIMED_OUT -> "lb-bot is still working on it. Refresh reads the list again."
	}
}

/** How many of a row's problems the panel spells out; the rest are counted in one closing line. */
const val ROW_PROBLEMS_SHOWN = 10

/**
 * lb-bot's reasons for a row at rest, one line each — what could not be written, or could not
 * be put back. **They are what makes Undo readable**: lb-bot's undo is two presses when a file
 * has gone missing, and only the reason says "undo again to restore it if it is back, or to
 * give up on it". Named by title, never by path. Nothing while lb-bot is still working.
 */
internal fun albumRowProblems(row: LbAlbumOverrideRow): List<String> =
	if (row.state != ALBUM_STATE_APPLIED && row.state != ALBUM_STATE_FAILED) emptyList()
	else problemLines(row.failures.map { listOf(it.title, it.reason) })

/** As [albumRowProblems], for a name merge: album, title, reason. */
internal fun mergeRowProblems(row: LbMergeRow): List<String> =
	if (row.state != MERGE_STATE_MERGED && row.state != MERGE_STATE_FAILED) emptyList()
	else problemLines(row.failures.map { listOf(it.album, it.title, it.reason) })

/** One line per problem from whatever parts it has; past [ROW_PROBLEMS_SHOWN], a count of the rest. */
private fun problemLines(problems: List<List<String>>): List<String> {
	val lines = problems
		.map { parts -> parts.map { it.trim() }.filter { it.isNotBlank() }.joinToString(" — ") }
		.filter { it.isNotBlank() }
	return if (lines.size <= ROW_PROBLEMS_SHOWN) lines
	else lines.take(ROW_PROBLEMS_SHOWN) + "…and ${lines.size - ROW_PROBLEMS_SHOWN} more."
}
