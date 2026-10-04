package paige.navic.domain.manager

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlin.time.Instant

/*
 * Listening analytics (I-006): the wire shapes and the rules, kept free of the network, Room and
 * Compose so they run in a test (see `ListeningTest`). lb-bot mirrors the user's ListenBrainz
 * listens and serves the aggregates; [LbBotManager] makes the calls, `ListeningViewModel` holds
 * the screen's state, and everything either of them has to DECIDE is a function here.
 *
 * Field names mirror the wire verbatim. Epoch fields are Double: lb-bot writes `time.time()`
 * floats elsewhere, and an Int field would throw on a decimal point and take the body with it.
 */

/** The windows `/lb/listening/summary` and `/lb/listening/unowned` take. */
enum class ListeningPeriod(val wire: String) {
	WEEK("7d"),
	MONTH("30d"),
	QUARTER("90d"),
	YEAR("365d"),
	ALL("all");

	companion object {
		/** An unknown value (a newer build's, a corrupt one) falls back rather than throwing. */
		fun fromWire(wire: String): ListeningPeriod = entries.firstOrNull { it.wire == wire } ?: MONTH
	}
}

/** `GET /lb/listening/status`. [total] is ListenBrainz's own count, null when it has not said. */
@Serializable
data class LbListeningStatus(
	val state: String = "",
	val count: Int = 0,
	val total: Int? = null,
	val oldest: Double? = null,
	val newest: Double? = null,
	val lastSyncAt: Double? = null,
	val error: String = ""
)

/**
 * `GET /lb/listening/summary`.
 *
 * [generatedAt] is kept as lb-bot sent it: the contract names the field without a type, and a
 * typed field that guessed wrong would fail the decode of the whole summary. [generatedAtSeconds]
 * reads it. [daily] is parsed and not drawn — the layout has no daily chart.
 */
@Serializable
data class LbListeningSummary(
	val period: String = "",
	val tz: String = "",
	val generatedAt: JsonElement? = null,
	val totals: LbListeningTotals = LbListeningTotals(),
	val streak: LbListeningStreak = LbListeningStreak(),
	val heatmap: List<LbDayCount> = emptyList(),
	val daily: List<LbDayCount> = emptyList(),
	val hours: List<Int> = emptyList(),
	val topArtists: List<LbTopArtist> = emptyList(),
	val topAlbums: List<LbTopAlbum> = emptyList(),
	val topTracks: List<LbTopTrack> = emptyList(),
	val genres: List<LbGenrePlays> = emptyList(),
	val genrePlays: Int = 0
) {
	/** Epoch seconds; 0 when lb-bot sent none, or sent it in a shape this build does not read. */
	val generatedAtSeconds: Double
		get() = (generatedAt as? JsonPrimitive)?.doubleOrNull ?: 0.0

	/**
	 * Whether this is a summary at all. lb-bot echoes the `period` it answered for; a body
	 * without one (a stray `{}`) decodes to all zeros, and zeros read as an empty history.
	 */
	val isAnswer: Boolean
		get() = period.isNotBlank()
}

/** [seconds] sums only the listens that carry a duration; [secondsKnownFor] is how many did. */
@Serializable
data class LbListeningTotals(
	val plays: Int = 0,
	val seconds: Double = 0.0,
	val secondsKnownFor: Int = 0,
	val artists: Int = 0,
	val albums: Int = 0
)

@Serializable
data class LbListeningStreak(
	val current: Int = 0,
	val longest: Int = 0
)

@Serializable
data class LbDayCount(
	val date: String = "",
	val count: Int = 0
)

@Serializable
data class LbTopArtist(
	val name: String = "",
	val mbid: String = "",
	val plays: Int = 0,
	val ndArtistId: String = ""
)

/**
 * [owned] is "the library holds it": lb-bot's index holds the release-group as owned, **or**
 * Navidrome's album list has an album with the listen's release MBID, or one by the same artist
 * with the same title. Wider than Fresh's `releaseOwned` (the index alone) on purpose — nearly
 * everything in a listening history is owned, and an artist lb-bot never scanned would otherwise
 * have their albums listed as "not in your library". [ndAlbumId] can be blank on an owned row
 * (a filled album still syncing): such a row is plain text, never a link to the acquire page.
 */
@Serializable
data class LbTopAlbum(
	val title: String = "",
	val artist: String = "",
	val artistMbid: String = "",
	val rgid: String = "",
	val plays: Int = 0,
	val owned: Boolean = false,
	val ndAlbumId: String = ""
)

@Serializable
data class LbTopTrack(
	val title: String = "",
	val artist: String = "",
	val recordingMbid: String = "",
	val rgid: String = "",
	val plays: Int = 0,
	val ndAlbumId: String = ""
)

@Serializable
data class LbGenrePlays(
	val genre: String = "",
	val plays: Int = 0
)

/** `GET /lb/listening/unowned`: what was played most and the library does not hold. */
@Serializable
data class LbListeningUnowned(
	val period: String = "",
	val kind: String = "",
	val rows: List<LbUnownedRow> = emptyList()
)

/** An `album` row always carries [rgid]; an `artist` row has a blank [title] and [rgid]. */
@Serializable
data class LbUnownedRow(
	val kind: String = "",
	val title: String = "",
	val artist: String = "",
	val artistMbid: String = "",
	val rgid: String = "",
	val plays: Int = 0,
	val lastListenedAt: Double = 0.0,
	val artistOwned: Boolean = false,
	val artistId: String = ""
)

/** `GET /lb/listening/recent`, newest first. [nextBefore] is null on the last page. */
@Serializable
data class LbListeningRecent(
	val rows: List<LbRecentPlay> = emptyList(),
	val nextBefore: Double? = null
)

@Serializable
data class LbRecentPlay(
	val listenedAt: Double = 0.0,
	val track: String = "",
	val artist: String = "",
	val release: String = "",
	val artistMbid: String = "",
	val rgid: String = "",
	val recordingMbid: String = "",
	val ndArtistId: String = "",
	val ndAlbumId: String = "",
	val client: String = ""
)

/** `POST /lb/listening/sync`: the worker was asked to pull; [state] is the mirror's state now. */
@Serializable
data class LbListeningSync(
	val ok: Boolean = false,
	val state: String = ""
)

const val LISTENING_KIND_ALBUM = "album"
const val LISTENING_KIND_ARTIST = "artist"

// ----- what the screen is looking at ------------------------------------------------------------

/**
 * What `/lb/listening/status` said, in the terms the screen renders.
 *
 * [Older] and [Unreachable] are both "no answer" and must not be confused: one is an lb-bot that
 * predates the routes (a quiet sentence, nothing to retry), the other is one that did not answer
 * (the cached summary, or a failure line with a retry).
 */
sealed interface ListeningPhase {
	/** Nothing has answered yet. */
	data object Loading : ListeningPhase

	/** lb-bot has no ListenBrainz user configured: there is no history to show. */
	data object Off : ListeningPhase

	/** The route does not exist: this lb-bot is older than the hub in front of it. */
	data object Older : ListeningPhase

	/** The status could not be read. */
	data object Unreachable : ListeningPhase

	/** The first backfill is running; every number on the page covers only [count] plays. */
	data class Importing(val count: Int, val total: Int?) : ListeningPhase

	data object Ready : ListeningPhase

	/** The last sync failed. Rows already stored are still served; [error] is lb-bot's text. */
	data class SyncFailed(val error: String) : ListeningPhase
}

/**
 * A blank `state` is not an answer (a stray `{}` decodes to one) and reads as [Unreachable]. A
 * state this build has never heard of reads as [Ready]: a newer lb-bot is still serving rows.
 */
internal fun listeningPhase(result: LbBotManager.LbResult<LbListeningStatus>): ListeningPhase =
	when (result) {
		is LbBotManager.LbResult.Failed ->
			if (result.error == LbBotManager.LbError.RouteUnknown) ListeningPhase.Older
			else ListeningPhase.Unreachable
		is LbBotManager.LbResult.Ok -> when (result.value.state) {
			"" -> ListeningPhase.Unreachable
			"off" -> ListeningPhase.Off
			"importing" -> ListeningPhase.Importing(result.value.count, result.value.total)
			"error" -> ListeningPhase.SyncFailed(result.value.error)
			else -> ListeningPhase.Ready
		}
	}

/**
 * The phase after one poll of the status while an import runs. A poll that did not answer is not
 * an answer: the page keeps saying "importing" rather than flipping to "can't reach lb-bot" and
 * back every five seconds.
 */
internal fun phaseAfterPoll(previous: ListeningPhase, polled: ListeningPhase): ListeningPhase =
	if (previous is ListeningPhase.Importing && polled == ListeningPhase.Unreachable) previous
	else polled

/**
 * Whether the import has just ended — the cue to read the summary and the unowned list again.
 * Without it the page keeps the partial totals it read mid-import and shows them, with the
 * "importing" banner gone, as though they were the whole history.
 */
internal fun importFinished(previous: ListeningPhase, next: ListeningPhase): Boolean =
	previous is ListeningPhase.Importing &&
		(next == ListeningPhase.Ready || next is ListeningPhase.SyncFailed || next == ListeningPhase.Off)

/**
 * Whether a summary was generated before the mirror's last sync, i.e. may be missing listens the
 * mirror now holds. This is what catches a summary cached mid-import across a relaunch, where
 * there is no "importing" phase left to see end.
 */
internal fun summaryNeedsRefresh(generatedAt: Double, lastSyncAt: Double?): Boolean =
	lastSyncAt != null && generatedAt < lastSyncAt

// ----- numbers, as the page words them ----------------------------------------------------------

/** How many of the period's plays the listening time is measured over. */
data class ListeningCoverage(val known: Int, val plays: Int)

/**
 * "from N of M plays", or null when the time covers every play and needs no caveat. Not every
 * listen carries a duration, so the total is a floor, and saying it without this would overstate
 * what was measured.
 */
internal fun listeningTimeCoverage(totals: LbListeningTotals): ListeningCoverage? =
	if (totals.secondsKnownFor < totals.plays) ListeningCoverage(totals.secondsKnownFor, totals.plays)
	else null

/** A duration split for display. Whole minutes, rounded down. */
data class ListeningDuration(val days: Int, val hours: Int, val minutes: Int)

internal fun listeningDuration(seconds: Double): ListeningDuration {
	val total = (if (seconds.isNaN() || seconds < 0) 0.0 else seconds).toLong() / 60
	return ListeningDuration(
		days = (total / (24 * 60)).toInt(),
		hours = ((total / 60) % 24).toInt(),
		minutes = (total % 60).toInt()
	)
}

/** `before` for the next page of recent plays: whole epoch seconds, never `1.79E9`. */
internal fun beforeParam(nextBefore: Double): String = nextBefore.toLong().toString()

/** `2026-10-03 21:05` in [zone]; blank for a missing time. */
internal fun formatListenedAt(epochSeconds: Double, zone: TimeZone): String {
	if (epochSeconds <= 0.0 || epochSeconds.isNaN()) return ""
	val at = Instant.fromEpochSeconds(epochSeconds.toLong()).toLocalDateTime(zone)
	fun two(n: Int) = n.toString().padStart(2, '0')
	return "${at.year}-${two(at.month.ordinal + 1)}-${two(at.day)} ${two(at.hour)}:${two(at.minute)}"
}

/**
 * A recent-plays row's key in the lazy list. The index is part of it: the same recording
 * scrobbled twice in one second is two identical rows, and a duplicate key in a lazy list is a
 * crash, not a glitch. Pages only append, so an index never moves.
 */
internal fun recentPlayKey(index: Int, row: LbRecentPlay): String =
	"$index|${row.listenedAt}|${row.recordingMbid}"

// ----- the heatmap ------------------------------------------------------------------------------

/** One day on the heatmap. [level] is 0 (nothing played) to 4 (the busiest days). */
data class HeatCell(val date: String, val count: Int, val level: Int)

/**
 * The 16-week grid: [weeks] columns of seven days, oldest first, the last cell being the newest
 * day lb-bot sent. A null cell is padding, and only ever on the OLD side: lb-bot sent fewer than
 * 112 days, and the grid keeps its shape rather than its start.
 */
data class ListeningHeatmap(val weeks: List<List<HeatCell?>>, val max: Int)

const val HEATMAP_DAYS = 112

/**
 * lb-bot's `heatmap` — the last 112 days ending today in the user's zone — as 16 columns of 7.
 * Every column is seven consecutive days, so a row is one weekday throughout. Sorted here rather
 * than trusted, cut to the newest 112, and padded on the OLD side when there are fewer, so the
 * newest day is always the last cell.
 *
 * The rows are therefore NOT a calendar week: the bottom row is the newest day's weekday and the
 * top row the weekday after it (history ending on a Saturday reads Sunday to Saturday; ending on
 * a Wednesday, Thursday to Wednesday). The screen draws no weekday labels for that reason.
 */
internal fun heatmapGrid(days: List<LbDayCount>): ListeningHeatmap {
	val newest = days.filter { it.date.isNotBlank() }.sortedBy { it.date }.takeLast(HEATMAP_DAYS)
	val max = newest.maxOfOrNull { it.count } ?: 0
	val cells: List<HeatCell?> = List(HEATMAP_DAYS - newest.size) { null } +
		newest.map { HeatCell(it.date, it.count, heatLevel(it.count, max)) }
	return ListeningHeatmap(cells.chunked(7), max)
}

/** Quartiles of the busiest day, rounded up, so one play on a quiet history still shows. */
internal fun heatLevel(count: Int, max: Int): Int =
	if (count <= 0 || max <= 0) 0 else ((count * 4 + max - 1) / max).coerceIn(1, 4)

/** `hours` as exactly 24 non-negative bars, whatever lb-bot sent. */
internal fun hourBars(hours: List<Int>): List<Int> =
	List(24) { hour -> hours.getOrNull(hour)?.coerceAtLeast(0) ?: 0 }

// ----- where a row leads ------------------------------------------------------------------------

/**
 * Where a tapped row goes. Rows link out and never download: an entity the library holds opens
 * its library page, one it does not opens the external page, where the reviewed acquire flow
 * already lives, and one nothing identifies is not a tap target at all.
 */
sealed interface ListeningTarget {
	data class LibraryAlbum(val albumId: String) : ListeningTarget
	data class ExternalAlbum(
		val rgid: String,
		val artistMbid: String,
		val artist: String,
		val title: String,
		/** The Navidrome artist id when the artist is in the library, for the page's artist link. */
		val artistId: String = ""
	) : ListeningTarget
	data class LibraryArtist(val artistId: String) : ListeningTarget
	data class ExternalArtist(val mbid: String, val name: String) : ListeningTarget
	data object None : ListeningTarget
}

/**
 * An album row's destination. [knownAlbumIds] is what Room holds: lb-bot's `ndAlbumId` is
 * authoritative, but an id is not a page that exists — an album Navidrome has and this device
 * has not synced yet opens on the album screen's not-in-the-database error.
 *
 * [owned] is lb-bot's "the library holds it". An owned album with no page here — no id yet (a
 * filled album still syncing), or an id this device has not synced — is **not a tap target**:
 * the external page is where an album is acquired, and a row only links there when the library
 * does not hold it.
 */
internal fun albumTarget(
	ndAlbumId: String,
	rgid: String,
	artistMbid: String,
	artist: String,
	title: String,
	knownAlbumIds: Set<String>,
	artistId: String = "",
	owned: Boolean = false
): ListeningTarget = when {
	ndAlbumId.isNotBlank() && ndAlbumId in knownAlbumIds -> ListeningTarget.LibraryAlbum(ndAlbumId)
	owned -> ListeningTarget.None
	rgid.isNotBlank() -> ListeningTarget.ExternalAlbum(rgid, artistMbid, artist, title, artistId)
	else -> ListeningTarget.None
}

/** Why one of the page's lists could not be read, as the screen words it. */
sealed interface ListeningListFailure {
	/** An lb-bot older than the route (404): a fact, said quietly, with nothing to retry. */
	data object Older : ListeningListFailure

	/** lb-bot's own sentence: a 503 while Navidrome's library lists are unavailable, a 400. */
	data class Said(val text: String) : ListeningListFailure

	/** Nothing was said: out of reach, busy, or an error with no text. */
	data object Generic : ListeningListFailure
}

internal fun listeningListFailure(error: LbBotManager.LbError): ListeningListFailure = when (error) {
	LbBotManager.LbError.RouteUnknown -> ListeningListFailure.Older
	is LbBotManager.LbError.Rejected ->
		error.message.trim().takeIf { it.isNotBlank() }?.let { ListeningListFailure.Said(it) }
			?: ListeningListFailure.Generic
	else -> ListeningListFailure.Generic
}

/** As [albumTarget], for an artist: only album artists have a row in Room, and so a page. */
internal fun artistTarget(
	ndArtistId: String,
	mbid: String,
	name: String,
	knownArtistIds: Set<String>
): ListeningTarget = when {
	ndArtistId.isNotBlank() && ndArtistId in knownArtistIds -> ListeningTarget.LibraryArtist(ndArtistId)
	mbid.isNotBlank() -> ListeningTarget.ExternalArtist(mbid, name)
	else -> ListeningTarget.None
}

/** A recent play: its album when one is identified, else its artist. */
internal fun recentPlayTarget(
	row: LbRecentPlay,
	knownAlbumIds: Set<String>,
	knownArtistIds: Set<String>
): ListeningTarget {
	val album = albumTarget(
		row.ndAlbumId, row.rgid, row.artistMbid, row.artist, row.release, knownAlbumIds,
		artistId = row.ndArtistId.takeIf { it in knownArtistIds }.orEmpty()
	)
	return if (album != ListeningTarget.None) album
	else artistTarget(row.ndArtistId, row.artistMbid, row.artist, knownArtistIds)
}

/** An unowned row: the external album page, or the external artist page for an `artist` row. */
internal fun unownedTarget(row: LbUnownedRow, knownArtistIds: Set<String>): ListeningTarget =
	if (row.rgid.isNotBlank()) {
		ListeningTarget.ExternalAlbum(
			row.rgid, row.artistMbid, row.artist, row.title,
			artistId = row.artistId.takeIf { row.artistOwned && it in knownArtistIds }.orEmpty()
		)
	} else if (row.artistMbid.isNotBlank()) {
		ListeningTarget.ExternalArtist(row.artistMbid, row.artist)
	} else {
		ListeningTarget.None
	}
