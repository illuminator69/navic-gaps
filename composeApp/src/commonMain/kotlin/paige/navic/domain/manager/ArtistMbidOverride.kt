package paige.navic.domain.manager

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * Artist MBID overrides (I-019): the rules, kept free of the network and Room so they can be run
 * in a test (see `ArtistMbidOverrideTest`). [LbBotManager] holds the map and makes the calls.
 *
 * lb-bot keys an artist's discography by a MusicBrainz artist id, and until this existed the only
 * id it was ever given was the one Navidrome read out of the file tags: wrong when the tag is
 * wrong, and nothing at all for an untagged artist, who then could not be indexed. An override is
 * the user naming the right MusicBrainz artist for a Navidrome artist id. lb-bot stores it — it is
 * shared by every client — and this file is the client's side of following it.
 */

/**
 * `GET /lb/overrides`: every artist whose MusicBrainz match the user has set by hand.
 *
 * [artists] is nullable on purpose. An empty list is an answer — nobody has overridden anything —
 * and replaces the device's copy; a body with no list at all is not one, and a default of
 * `emptyList()` would make the two indistinguishable and wipe every override on a stray `{}`.
 */
@Serializable
data class LbOverrides(
	val artists: List<LbArtistOverride>? = null,
	/**
	 * "Matches to check" (I-021), nullable for the same reason and one more: an lb-bot that
	 * predates the list sends no such field, and that is "no list", not "nothing to check".
	 */
	val toCheck: List<LbMatchToCheck>? = null
)

/**
 * One override. Field names mirror the wire verbatim.
 *
 * [tagMbid] is what Navidrome said when the override was set ("" when it said nothing), kept so
 * the pick can be shown beside what it replaced. [setAt] is epoch seconds.
 */
@Serializable
data class LbArtistOverride(
	val ndArtistId: String = "",
	val mbid: String = "",
	val name: String = "",
	val tagMbid: String = "",
	val setAt: Double = 0.0
)

/**
 * What `POST /lb/artist/override` and its `/remove` twin answer. [taskId] is the discography scan
 * lb-bot started for the artist's new identity, "" when it started none (see
 * [overrideScanToFollow]). [mbid] and [name] are only on a set, and [mbid] is the one to keep: it
 * may differ from the id sent, because MusicBrainz merges artists.
 */
@Serializable
data class LbOverrideWrite(
	val ok: Boolean = false,
	val mbid: String = "",
	val name: String = "",
	@SerialName("task_id") val taskId: String = ""
)

/**
 * The scan to wait on after an override write: lb-bot's task id, or null when it started none —
 * **and then the client starts none either.**
 *
 * lb-bot starts the scan itself. A blank id is deliberate: a set onto an MBID whose discography
 * another library artist already holds (a scan from this artist would rewrite that artist's row
 * against the wrong albums), or a remove after which nothing resolves. A missing field reads the
 * same as a blank one.
 */
internal fun overrideScanToFollow(written: LbOverrideWrite): String? =
	written.taskId.takeIf { it.isNotBlank() }

/**
 * What `POST /lb/artist/discography` — the artist page's Rescan — answers. [taskId] is the scan
 * lb-bot started, "" when it started none; [covered] says why it started none: the artist's
 * effective MBID is one whose discography another library artist holds, the same check an
 * override write applies. An lb-bot that predates the field sends no `covered` and always a task.
 */
@Serializable
internal data class LbRescanAnswer(
	val ok: Boolean = false,
	@SerialName("task_id") val taskId: String = "",
	val covered: Boolean = false
)

/** What a rescan request came to. */
sealed interface LbRescanStart {
	/** lb-bot started a scan (or joined the one running): wait on it. */
	data class Started(val taskId: String) : LbRescanStart

	/**
	 * lb-bot started nothing on purpose: another library artist holds this discography. There
	 * is nothing to wait on — no spinner, and one plain line saying so.
	 */
	data object Shared : LbRescanStart

	/** The request failed, was refused, or was answered with no task and no reason. */
	data object NotStarted : LbRescanStart
}

/**
 * [answer] is null when the request did not come back as a 200.
 *
 * **A blank task id is never followed**, the same rule as [overrideScanToFollow]: waiting on ""
 * matched no scan record and spun until the scan timeout. It is "shared" only when lb-bot says
 * `covered` — the sentence that goes with it is a claim about another artist, and a blank id
 * alone does not make it.
 */
internal fun rescanStart(answer: LbRescanAnswer?): LbRescanStart = when {
	answer == null || !answer.ok -> LbRescanStart.NotStarted
	answer.taskId.isNotBlank() -> LbRescanStart.Started(answer.taskId)
	answer.covered -> LbRescanStart.Shared
	else -> LbRescanStart.NotStarted
}

/** What an unconfirmed write is said to have failed with, where lb-bot sent no text of its own. */
const val WRITE_NOT_CONFIRMED = "lb-bot did not confirm it"

/**
 * A write lb-bot answered 200 for is done only when the answer says `ok`. One that says
 * `ok: false` — or carries no `ok` at all, which decodes to false — stored or started nothing,
 * and reading it as success would follow a scan that does not exist or report a sync nobody
 * asked for. A failure is left as it is.
 */
internal fun <T> confirmedWrite(
	result: LbBotManager.LbResult<T>,
	ok: (T) -> Boolean
): LbBotManager.LbResult<T> =
	if (result is LbBotManager.LbResult.Ok && !ok(result.value)) {
		LbBotManager.LbResult.Failed(LbBotManager.LbError.Rejected(200, WRITE_NOT_CONFIRMED))
	} else {
		result
	}

/**
 * The overrides keyed by Navidrome artist id, which is how every reader asks. A row naming no
 * artist or no MBID overrides nothing and is dropped rather than kept under a blank key. Null
 * when the body carried no list: not an answer, and the caller keeps the map it has.
 */
internal fun overrideMap(body: LbOverrides): Map<String, LbArtistOverride>? =
	body.artists
		?.filter { it.ndArtistId.isNotBlank() && it.mbid.isNotBlank() }
		?.associateBy { it.ndArtistId }

/**
 * The MusicBrainz artist id lb-bot is asked about for a library artist: the user's override when
 * there is one for that Navidrome artist id, else Navidrome's own tag. Blank when there is neither.
 *
 * Every artist MBID that reaches an lb-bot call or a MusicBrainz link goes through this. An
 * override equal to the tag is still an override; it just resolves to the same id.
 */
internal fun effectiveArtistMbid(
	overrides: Map<String, LbArtistOverride>,
	ndId: String,
	tagMbid: String?
): String {
	val picked = if (ndId.isBlank()) null else overrides[ndId]?.mbid?.takeIf { it.isNotBlank() }
	return picked ?: tagMbid.orEmpty()
}

private const val MBID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
private val MBID = Regex(MBID_PATTERN)
private val ARTIST_URL =
	Regex("(?:https?://)?(?:[a-z0-9-]+\\.)?musicbrainz\\.org/artist/($MBID_PATTERN)(?:[/?#].*)?")

/**
 * What the picker's paste field accepts: a bare MBID, or a `musicbrainz.org/artist/<uuid>` URL.
 * Returns the id in lowercase, or null for anything else.
 *
 * The path is checked, not just the UUID: a release or release-group URL carries a well-formed id
 * that names no artist, and lb-bot would only find that out by asking MusicBrainz.
 */
internal fun parseArtistMbid(input: String): String? {
	val text = input.trim().lowercase()
	if (MBID.matches(text)) return text
	return ARTIST_URL.matchEntire(text)?.groupValues?.get(1)
}

/**
 * Why an override write was refused, from its status and lb-bot's `code` / `error` / `retryable`.
 *
 * The general mapping (`LbBotManager.failureFor`) reads every 404 as "the hub has no such route"
 * and every 502 as "busy", and both are wrong here:
 * - lb-bot answers **404 with a code** for a real refusal (`unknown_mbid`: MusicBrainz has no such
 *   artist; `no_override`: nothing to remove). Only a **bare** 404 is a route nobody answers — the
 *   hub's, or an lb-bot older than the hub. So is a **405**: lb-bot's SPA fallback is a GET-only
 *   catch-all, and a POST to a path it has never heard of matches that rule.
 * - lb-bot answers **502 with `retryable`** when MusicBrainz — or Navidrome's artist list — is
 *   unavailable and nothing was stored, on a set and on a remove alike; its own `error` text
 *   says which. The hub's own 502 / 504 (lb-bot unreachable, or timed out) carry no such flag.
 */
internal fun overrideWriteError(
	status: Int,
	code: String,
	message: String,
	retryable: Boolean
): LbBotManager.LbError = when {
	status == 405 || (status == 404 && code.isBlank()) -> LbBotManager.LbError.RouteUnknown
	(status == 502 || status == 504) && !retryable -> LbBotManager.LbError.Busy
	else -> LbBotManager.LbError.Rejected(status, message)
}
