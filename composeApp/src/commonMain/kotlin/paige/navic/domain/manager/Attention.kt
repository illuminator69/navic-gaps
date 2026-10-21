package paige.navic.domain.manager

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/*
 * navi-connect P6: Needs attention, as rules. lb-bot's own "to fix" (PROTOCOL §15.6), read through the
 * hub's `GET /lb/attention`, joined with this device's request ledger.
 *
 * The count is lb-bot's web UI's number, `failedGaps + failedRequests + folders`: the same on every
 * client, for the avatar's dot, the menu's badge and the page's title line. Albums waiting on a pick
 * and artists to check are listed and never counted. Where the hub cannot answer (older, unreachable,
 * nothing read yet) the P1 count of this device's own ledger ([needsAttentionCount]) stands, and a
 * read that did not happen is never drawn as zero.
 */

/** lb-bot's numbers, the keys of `/lb/attention`'s `counts`. */
@Serializable
data class AttentionCounts(
	val failedGaps: Int = 0,
	val failedRequests: Int = 0,
	val folders: Int = 0,
	val foldersUnidentified: Int = 0,
	val picks: Int = 0
)

/** One album lb-bot holds as failed or waiting on a pick: a review group (`gap`) or a whole-album request. */
@Serializable
data class AttentionAlbum(
	val kind: String = "",
	val key: String = "",
	val groupId: String = "",
	val releaseMbid: String = "",
	val rgid: String = "",
	val albumId: String = "",
	val artist: String = "",
	val album: String = "",
	val coverUrl: String = "",
	/** `failed` or `picking`. */
	val state: String = "",
	val missingCount: Int = 0,
	val total: Int = 0,
	/** Epoch seconds, lb-bot's clock. */
	val updatedAt: Double = 0.0
) {
	val picking: Boolean get() = state == "picking"
}

/**
 * A downloaded folder waiting to be filed. Never its name or its path (host paths, PROTOCOL §15.6):
 * what it was identified as, or nothing, and how many files it holds.
 */
@Serializable
data class AttentionFolder(
	val id: String = "",
	val fileCount: Int = 0,
	/** lb-bot's own summary of the folder's formats, e.g. "flac". */
	val formats: String = "",
	/** "Artist — Album", or "" for a folder lb-bot could not identify. */
	val matchLabel: String = "",
	/** lb-bot's word for how sure the match is: `likely`, `possible`, `unknown`, … */
	val confidence: String = "",
	val canConfirm: Boolean = false,
	val groupId: String = "",
	val releaseMbid: String = "",
	val identified: Boolean = false
)

@Serializable
data class AttentionTruncated(val albums: Int = 0, val folders: Int = 0)

/** `GET /lb/attention`'s answer. */
@Serializable
data class LbAttention(
	val version: Int = 0,
	val serverTime: Double = 0.0,
	val counts: AttentionCounts? = null,
	val albums: List<AttentionAlbum> = emptyList(),
	val folders: List<AttentionFolder> = emptyList(),
	val truncated: AttentionTruncated = AttentionTruncated()
)

/** What one attention read came to. */
sealed interface AttentionRead {
	data class Ok(val attention: LbAttention) : AttentionRead

	/** The hub or lb-bot is older than the route: a bare 404, or a 405. */
	data object Older : AttentionRead

	/** Anything else: a 5xx, a transport failure, a 200 that is not an attention answer. */
	data object Unavailable : AttentionRead
}

/**
 * One answer read as [AttentionRead]. A 404 with no `code` (or a 405) is "older": a 404 that carries
 * one is lb-bot's own answer about something else. `{}`, `[]` and any 200 without `version` and
 * `counts` is not an answer at all.
 */
fun attentionRead(status: Int, body: String): AttentionRead = when (status) {
	in 200..299 -> {
		val parsed = try {
			lbWireJson.decodeFromString(LbAttention.serializer(), body)
		} catch (e: Exception) {
			null
		}
		if (parsed != null && parsed.version >= 1 && parsed.counts != null) AttentionRead.Ok(parsed)
		else AttentionRead.Unavailable
	}
	404 -> if (bodyCode(body).isBlank()) AttentionRead.Older else AttentionRead.Unavailable
	405 -> AttentionRead.Older
	else -> AttentionRead.Unavailable
}

/** The `code` of an error body, or "" when there is none or the body is not an object. */
internal fun bodyCode(body: String): String = try {
	(lbWireJson.parseToJsonElement(body).jsonObject["code"] as? JsonPrimitive)?.contentOrNull.orEmpty()
} catch (e: Exception) {
	""
}

/** lb-bot's to-fix number: failed gaps, failed requests and folders waiting to be filed. */
fun toFixCount(c: AttentionCounts): Int = c.failedGaps + c.failedRequests + c.folders

/** The dot's and the badge's number: lb-bot's when one is known, else this device's own (P1). */
fun attentionBadge(read: LbAttention?, ledgerCount: Int): Int =
	read?.counts?.let(::toFixCount) ?: ledgerCount

/**
 * A folder in one line: "Artist — Album · 11 files", or "Unidentified folder · 11 files". Built from
 * what lb-bot identified and the count alone; a folder's name never reaches this side.
 */
fun folderLine(f: AttentionFolder, unidentified: String, filesWord: (Int) -> String): String {
	val what = f.matchLabel.takeIf { f.identified && it.isNotBlank() } ?: unidentified
	return "$what · ${filesWord(f.fileCount)}"
}

/** Whether a server row and a device ledger row are the same fill: by release MBID, else by group id. */
internal fun sameFill(releaseMbid: String, groupId: String, entry: LbFillEntry): Boolean = when {
	releaseMbid.isNotBlank() && entry.releaseMbid.isNotBlank() -> releaseMbid == entry.releaseMbid
	groupId.isNotBlank() -> groupId == entry.groupId || (entry.isGap && groupId == entry.key)
	else -> false
}

/** A ledger row the attention page lists: settled, and a failure or a pick waiting. */
internal fun LbFillEntry.wantsAttention(): Boolean =
	settled && (outcome == LbBotManager.OUTCOME_FAILED || outcome == LbBotManager.OUTCOME_NEEDS_PICK)

/** One album row on the page. [entry] is this device's ledger row for it, when there is one. */
data class AttentionRow(
	val id: String,
	val album: AttentionAlbum,
	val entry: LbFillEntry?
) {
	/** Only a row the ledger holds can be retried or dismissed here; lb-bot's own rows open the album. */
	val actionable: Boolean get() = entry != null
}

/** A one-line note under the page's title. */
sealed interface AttentionNotice {
	/** The hub or lb-bot is older: only this device's requests can be listed. */
	data object Older : AttentionNotice

	/** lb-bot did not answer; what is shown was read at [since] (epoch ms), or never when null. */
	data class Unreachable(val since: Long?) : AttentionNotice

	/** The caps cut rows: say how many more lb-bot holds. */
	data class Truncated(val albums: Int, val folders: Int) : AttentionNotice
}

sealed interface AttentionPage {
	/** Nothing read yet and nothing to show from before: not "nothing to fix". */
	data object Loading : AttentionPage

	data class Rows(
		val albums: List<AttentionRow>,
		val folders: List<AttentionFolder>,
		/** Library Matches' "to check" list, or null when it is not known. Listed, never counted. */
		val artistsToCheck: Int?,
		/** lb-bot's number, or null when no answer is known (then the title says no number at all). */
		val toFix: Int?,
		val notices: List<AttentionNotice>
	) : AttentionPage
}

/**
 * The page, from the last read, the last good answer ([last], read at [lastAt]) and the ledger.
 *
 * - A good answer lists lb-bot's rows; a ledger row the server also lists appears once, lb-bot's
 *   state with the device's [AttentionRow.entry]. The device's own failed or waiting rows lb-bot no
 *   longer lists follow (lb-bot forgets a failed request after a day; the ledger keeps a week).
 * - A failed read keeps the last good answer and says since when; with none, the device's rows.
 * - An older hub or lb-bot lists the device's rows and says so. No read at all is [AttentionPage.Loading].
 */
fun attentionPage(
	read: AttentionRead?,
	last: LbAttention?,
	lastAt: Long?,
	ledger: List<LbFillEntry>,
	artistsToCheck: Int?
): AttentionPage {
	val answer = when (read) {
		is AttentionRead.Ok -> read.attention
		AttentionRead.Unavailable -> last
		AttentionRead.Older -> null
		null -> last
	}
	if (read == null && answer == null) return AttentionPage.Loading

	val device = ledger.filter { it.wantsAttention() }
	val notices = buildList {
		if (read == AttentionRead.Older) add(AttentionNotice.Older)
		if (read == AttentionRead.Unavailable) add(AttentionNotice.Unreachable(lastAt.takeIf { last != null }))
		val cut = answer?.truncated
		if (cut != null && (cut.albums > 0 || cut.folders > 0)) add(AttentionNotice.Truncated(cut.albums, cut.folders))
	}
	if (answer == null) {
		return AttentionPage.Rows(
			albums = device.sortedForAttention().map { it.asAttentionRow() },
			folders = emptyList(),
			artistsToCheck = artistsToCheck,
			toFix = null,
			notices = notices
		)
	}

	val claimed = mutableSetOf<String>()
	val serverRows = answer.albums.mapNotNull { album ->
		val entry = ledger.firstOrNull { it.key !in claimed && sameFill(album.releaseMbid, album.groupId, it) }
		if (entry != null) claimed += entry.key
		// The ledger is newer than any read for the rows it names (a fill frame or a poll moves it
		// at once): a fill this device has started again is running, and no longer waits on anyone.
		if (entry != null && !entry.settled) return@mapNotNull null
		AttentionRow(id = "${album.kind}:${album.key.ifBlank { album.groupId.ifBlank { album.releaseMbid } }}", album = album, entry = entry)
	}
	val deviceOnly = device.filter { it.key !in claimed }.sortedForAttention().map { it.asAttentionRow() }
	return AttentionPage.Rows(
		albums = serverRows + deviceOnly,
		folders = answer.folders,
		artistsToCheck = artistsToCheck,
		toFix = answer.counts?.let(::toFixCount),
		notices = notices
	)
}

/** Failed first, then waiting on a pick; newest first inside each. */
private fun List<LbFillEntry>.sortedForAttention(): List<LbFillEntry> =
	sortedWith(compareBy<LbFillEntry> { if (it.outcome == LbBotManager.OUTCOME_FAILED) 0 else 1 }
		.thenByDescending { it.finishedAt })

/** A ledger row as the page's album row, named and keyed as lb-bot would. */
internal fun LbFillEntry.asAttentionRow(): AttentionRow = AttentionRow(
	id = "device:$key",
	album = AttentionAlbum(
		kind = if (isGap) "gap" else "request",
		key = key,
		groupId = groupId,
		releaseMbid = releaseMbid,
		rgid = rgid,
		artist = artist,
		album = album,
		coverUrl = coverUrl,
		state = if (outcome == LbBotManager.OUTCOME_NEEDS_PICK) "picking" else "failed",
		total = total,
		updatedAt = finishedAt / 1000.0
	),
	entry = this
)

/**
 * Whether to read `/lb/attention` now: never while a read is out, always when forced, otherwise at
 * most once in [ATTENTION_MIN_INTERVAL_MS]. [readAt] 0 is "never read".
 */
fun shouldRefreshAttention(now: Long, readAt: Long, force: Boolean, inFlight: Boolean): Boolean = when {
	inFlight -> false
	force -> true
	readAt <= 0L -> true
	else -> now - readAt >= ATTENTION_MIN_INTERVAL_MS
}

const val ATTENTION_MIN_INTERVAL_MS = 30_000L

/**
 * Whether a `fill` frame forces a read: one that names a failed state (the count may just have
 * grown) or a pick waiting. Its `state` for an album fill, its `status` for a gap summary.
 */
fun fillFrameForcesAttention(frame: JsonObject): Boolean {
	fun str(key: String) = (frame[key] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
	return str("state") in ATTENTION_FORCING_STATES || str("status") in ATTENTION_FORCING_STATES
}

private val ATTENTION_FORCING_STATES = setOf("failed", "needs_match", "picking")

/**
 * What [LbBotManager.attention] holds: the last good answer and when it was read ([lastAt]), when
 * anything was last read ([readAt], for the 30 s rule), and how the last read went.
 */
data class AttentionState(
	val last: LbAttention? = null,
	val lastAt: Long = 0L,
	val readAt: Long = 0L,
	val status: AttentionRead? = null
)

/**
 * The next [AttentionState] after a read. A good answer replaces the last; "older" forgets it (the
 * hub or lb-bot was replaced by one without the route, and an answer from before is no longer the
 * truth); a failure keeps it.
 */
fun nextAttentionState(prev: AttentionState, read: AttentionRead, now: Long): AttentionState = when (read) {
	is AttentionRead.Ok -> AttentionState(last = read.attention, lastAt = now, readAt = now, status = read)
	AttentionRead.Older -> AttentionState(last = null, lastAt = 0L, readAt = now, status = read)
	AttentionRead.Unavailable -> prev.copy(status = read, readAt = now)
}
