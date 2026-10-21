package paige.navic.domain.manager

import kotlinx.serialization.Serializable

/*
 * navi-connect P6: Source history, as rules. What lb-bot's fill ledger still holds (`GET /lb/activity`,
 * PROTOCOL §15.6) joined with this device's own ledger, which outlives lb-bot's (it keeps a verified
 * row 6 h and a failed one 24 h; the device keeps a week).
 *
 * lb-bot's row wins the state; the device's keeps what lb-bot has forgotten and is the only one with
 * buttons: a row only lb-bot knows offers Open album and nothing else (no Retry, no Cancel, and no
 * Dismiss, since it expires there by itself).
 */

/** One request in lb-bot's ledger. `state` is the §15.2 server state. */
@Serializable
data class ActivityRequest(
	val releaseMbid: String = "",
	val rgid: String = "",
	val groupId: String = "",
	val albumId: String = "",
	val artist: String = "",
	val album: String = "",
	val state: String = "",
	val percent: Int = 0,
	val done: Int = 0,
	val total: Int = 0,
	val failureKind: String = "",
	val reason: String = "",
	val retryable: Boolean = false,
	val gapFill: Boolean = false,
	/** Epoch seconds, lb-bot's clock. */
	val updatedAt: Double = 0.0
)

/** One file-level transfer. Never a peer's filename or a path. */
@Serializable
data class ActivityTransfer(
	val id: String = "",
	val kind: String = "",
	val album: String = "",
	val artist: String = "",
	val groupId: String = "",
	val state: String = "",
	val pct: Int = 0,
	val peer: String = ""
)

/** `GET /lb/activity`'s answer. */
@Serializable
data class LbActivity(
	val version: Int = 0,
	val serverTime: Double = 0.0,
	val requests: List<ActivityRequest> = emptyList(),
	val transfers: List<ActivityTransfer> = emptyList()
)

sealed interface ActivityRead {
	data class Ok(val requests: List<ActivityRequest>, val transfers: List<ActivityTransfer>) : ActivityRead
	data object Older : ActivityRead
	data object Unavailable : ActivityRead
}

/** One answer read as [ActivityRead], by [attentionRead]'s rules. */
fun activityRead(status: Int, body: String): ActivityRead = when (status) {
	in 200..299 -> {
		val parsed = try {
			lbWireJson.decodeFromString(LbActivity.serializer(), body)
		} catch (e: Exception) {
			null
		}
		if (parsed != null && parsed.version >= 1) ActivityRead.Ok(parsed.requests, parsed.transfers)
		else ActivityRead.Unavailable
	}
	404 -> if (bodyCode(body).isBlank()) ActivityRead.Older else ActivityRead.Unavailable
	405 -> ActivityRead.Older
	else -> ActivityRead.Unavailable
}

/** Source history's chips, in order. */
@Serializable
enum class SourceHistorySection { Requests, Transfers, Wishlist }

/**
 * One row of Requests. [state] is the ledger's own state string (lb-bot's when it lists the row);
 * [entry] is this device's row, the one with buttons; [server] is lb-bot's.
 */
data class HistoryRow(
	val key: String,
	val artist: String,
	val album: String,
	val rgid: String,
	val state: String,
	val percent: Int?,
	/** Epoch ms. */
	val updatedAt: Long,
	val fromDevice: Boolean,
	val entry: LbFillEntry?,
	val server: ActivityRequest? = null
) {
	/** Searching, queued, downloading or placing: the Running group. */
	val running: Boolean get() = if (server == null && entry != null) !entry.settled else state in RUNNING_STATES
}

private val RUNNING_STATES = setOf("searching", "queued", "downloading", "placing")

/**
 * lb-bot's list joined with the device's, each request once. A server row and a device row are the
 * same request by release MBID, else by group id ([sameFill]); lb-bot's state wins, the device's row
 * comes along. A device row lb-bot no longer lists stays, however old.
 */
fun mergeHistory(server: List<ActivityRequest>, device: List<LbFillEntry>): List<HistoryRow> {
	val claimed = mutableSetOf<String>()
	val fromServer = server.map { s ->
		val entry = device.firstOrNull { it.key !in claimed && sameFill(s.releaseMbid, s.groupId, it) }
		if (entry != null) claimed += entry.key
		HistoryRow(
			key = entry?.key ?: s.releaseMbid.ifBlank { s.groupId.ifBlank { s.rgid } },
			artist = s.artist.ifBlank { entry?.artist.orEmpty() },
			album = s.album.ifBlank { entry?.album.orEmpty() },
			rgid = s.rgid.ifBlank { entry?.rgid.orEmpty() },
			state = s.state,
			percent = s.percent.takeIf { s.state == "downloading" },
			updatedAt = (s.updatedAt * 1000).toLong(),
			fromDevice = entry != null,
			entry = entry,
			server = s
		)
	}
	val deviceOnly = device.filter { it.key !in claimed }.map { e ->
		HistoryRow(
			key = e.key,
			artist = e.artist,
			album = e.album,
			rgid = e.rgid,
			state = e.state,
			percent = e.percent.takeIf { !e.settled && e.state == "downloading" },
			updatedAt = deviceClock(e),
			fromDevice = true,
			entry = e
		)
	}
	return fromServer + deviceOnly
}

/** When a device row last moved: its settle, else the later of its start and its last answer. */
internal fun deviceClock(e: LbFillEntry): Long =
	if (e.settled && e.finishedAt > 0L) e.finishedAt else maxOf(e.startedAt, e.lastCheckedAt)

data class HistoryGroups(val running: List<HistoryRow>, val earlier: List<HistoryRow>)

/** Running over Earlier, newest first inside each; a tie keeps the merged order. */
fun historyGroups(rows: List<HistoryRow>): HistoryGroups {
	val (running, earlier) = rows.partition { it.running }
	return HistoryGroups(
		running = running.sortedByDescending { it.updatedAt },
		earlier = earlier.sortedByDescending { it.updatedAt }
	)
}

/** The Transfers chip is drawn only when lb-bot lists any. */
fun transfersShown(transfers: List<ActivityTransfer>): Boolean = transfers.isNotEmpty()

private val SETTLED_SERVER_STATES = setOf("verified", "failed", "cancelled", "needs_match")

/**
 * A row only lb-bot knows as a ledger row, so the fill vocabulary words it ([paige.navic.ui.components.common.describeFill]).
 * Only its words are used: such a row offers Open album and nothing else, whatever its buttons say.
 */
fun ActivityRequest.asLedgerRow(): LbFillEntry {
	val settled = state in SETTLED_SERVER_STATES
	return LbFillEntry(
		key = releaseMbid.ifBlank { groupId.ifBlank { rgid } },
		isGap = gapFill,
		rgid = rgid,
		groupId = groupId,
		releaseMbid = releaseMbid,
		artist = artist,
		album = album,
		quality = "",
		state = state,
		outcome = when (state) {
			"verified" -> LbBotManager.OUTCOME_DONE
			"failed", "needs_match" -> LbBotManager.OUTCOME_FAILED
			"cancelled" -> LbBotManager.OUTCOME_CANCELLED
			else -> LbBotManager.OUTCOME_RUNNING
		},
		reason = reason,
		percent = percent,
		done = done,
		total = total,
		mp3WouldHelp = false,
		failureKind = failureKind,
		retryable = retryable,
		attempts = 0,
		settled = settled,
		startedAt = 0L,
		finishedAt = (updatedAt * 1000).toLong(),
		cancellable = false
	)
}

/** An attention album lb-bot holds and this device does not, worded the same way. */
fun AttentionAlbum.asLedgerRow(): LbFillEntry = LbFillEntry(
	key = key.ifBlank { groupId.ifBlank { releaseMbid } },
	isGap = kind == "gap",
	rgid = rgid,
	groupId = groupId,
	releaseMbid = releaseMbid,
	artist = artist,
	album = album,
	quality = "",
	state = state,
	outcome = if (picking) LbBotManager.OUTCOME_NEEDS_PICK else LbBotManager.OUTCOME_FAILED,
	reason = "",
	percent = 0,
	done = 0,
	total = total,
	mp3WouldHelp = false,
	failureKind = "",
	retryable = false,
	attempts = 0,
	settled = true,
	startedAt = 0L,
	finishedAt = (updatedAt * 1000).toLong(),
	cancellable = false
)
