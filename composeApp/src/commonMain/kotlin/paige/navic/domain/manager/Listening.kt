package paige.navic.domain.manager

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.plus
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
 * Whether "Sync now" is offered. **Absent where a sync cannot be asked for**: lb-bot has no
 * ListenBrainz user (off), predates the routes (older), or has not answered yet. Unreachable
 * keeps it — the press is how the user asks again, and its answer says lb-bot was not reached.
 */
internal fun listeningSyncOffered(phase: ListeningPhase): Boolean = when (phase) {
	ListeningPhase.Off, ListeningPhase.Older, ListeningPhase.Loading -> false
	ListeningPhase.Ready, ListeningPhase.Unreachable,
	is ListeningPhase.Importing, is ListeningPhase.SyncFailed -> true
}

/**
 * Whether the plain status line is drawn. **The status is shown once**: while the history is
 * importing, failed its last sync, or cannot be reached, the page's banner already says so in
 * full; off and older are the page itself. The line is for the ordinary case — how many plays
 * are stored and when lb-bot last synced.
 */
internal fun listeningStatusLineShown(phase: ListeningPhase): Boolean = phase == ListeningPhase.Ready

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
 * The heat map as whole calendar weeks: [weeks] columns of seven, Monday first, oldest first,
 * the last being the current week. A null cell is a square with no day in it — a day of the
 * current week not yet reached — and is drawn as a placeholder, so the grid is always a clean
 * rectangle.
 */
data class ListeningHeatmap(val weeks: List<List<HeatCell?>>, val max: Int)

const val HEATMAP_DAYS = 112

/** [date] (`yyyy-mm-dd`, a calendar day in the user's zone) as a date, or null when it is not one. */
private fun dayOrNull(date: String): LocalDate? = try {
	LocalDate.parse(date)
} catch (_: Exception) {
	null
}

/**
 * lb-bot's `heatmap` — the last 112 days ending today in the user's zone, whatever period was
 * asked for — as whole weeks, Monday at the top.
 *
 * **The grid starts on the first Monday inside the data and ends with the current week.** Up
 * to six leading days are not drawn: a span that happened to begin on a Sunday used to put one
 * lone square in the first column. The current week is drawn whole, its days not yet reached as
 * placeholders (null), so every row ends together. Sorted here rather than trusted, and cut to
 * the newest 112 first. Each day is placed by its date, not by its position in the list, so a
 * row is one weekday throughout even if lb-bot skipped a day.
 *
 * A history with no Monday in it (a few days old) has nothing to start on: it is one week, its
 * days in their own rows and the squares before them placeholders too.
 */
internal fun heatmapGrid(days: List<LbDayCount>): ListeningHeatmap {
	val dated = days.mapNotNull { day -> dayOrNull(day.date)?.let { it to day } }
		.sortedBy { it.first }
		.takeLast(HEATMAP_DAYS)
	if (dated.isEmpty()) return ListeningHeatmap(emptyList(), 0)
	val monday = dated.indexOfFirst { it.first.dayOfWeek == DayOfWeek.MONDAY }
	val drawn = if (monday >= 0) dated.drop(monday) else dated
	// Rows are Monday (0) to Sunday (6): where the first drawn day sits in its week.
	val lead = drawn.first().first.dayOfWeek.isoDayNumber - 1
	val start = drawn.first().first.toEpochDays()
	val span = lead + (drawn.last().first.toEpochDays() - start).toInt() + 1
	val max = drawn.maxOf { it.second.count }
	val cells = arrayOfNulls<HeatCell>((span + 6) / 7 * 7)
	for ((date, day) in drawn) {
		cells[lead + (date.toEpochDays() - start).toInt()] = HeatCell(day.date, day.count, heatLevel(day.count, max))
	}
	return ListeningHeatmap(cells.toList().chunked(7), max)
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

// ----- the page, in tabs (I-026) -------------------------------------------------------------------
// The listening history is four tabs, the same four under the same names as Feishin's page: what
// it shows did not change, only where. Since I-039 they are Stats' tabs. These are the rules the
// layout follows.

enum class ListeningTab { OVERVIEW, ACTIVITY, HISTORY, NOT_IN_LIBRARY }

/**
 * **The period picker is drawn where it changes something.** There is one period for the page;
 * its control is on Overview, on Not in library, and on Activity below the heat map. History has
 * none: recent plays are a list in time, not an aggregate over a period.
 */
internal fun listeningTabShowsPeriod(tab: ListeningTab): Boolean = tab != ListeningTab.HISTORY

/**
 * **Stats is the listening history wherever there is one (I-039).** My Listening was a page of
 * its own that said much of what upstream's Stats page says, so Stats shows lb-bot's figures, in
 * the tabs above, whenever lb-bot holds a ListenBrainz history, and its own Navidrome figures where
 * there is none to show: no ListenBrainz user ([ListeningPhase.Off]) or an lb-bot older than the
 * routes ([ListeningPhase.Older]). Every other phase is the history's: the first read still out,
 * the import, a failed sync, lb-bot out of reach (the summary this device loaded last).
 */
internal fun listeningOnStats(phase: ListeningPhase): Boolean =
	phase != ListeningPhase.Off && phase != ListeningPhase.Older

/** Whether the tabs are drawn under Stats' title: on the history, once the status has answered. */
internal fun listeningTabsShown(phase: ListeningPhase): Boolean =
	listeningOnStats(phase) && phase != ListeningPhase.Loading

/** The period the library home's Stats card is about: the one Stats opens on. */
val LISTENING_CARD_PERIOD = ListeningPeriod.MONTH

/**
 * **The library home's Stats card follows the history when there is one** (you, 2026-10-16,
 * Q-103). Upstream's card reads Navidrome's play counts; Stats itself shows lb-bot's history
 * wherever lb-bot holds one, so the card does too. The card has no status read of its own (the
 * home is visited constantly and the status is never cached), so "a history" is read off the
 * cached summary: an answer with any play in it, the period's or the heat map's sixteen weeks
 * (which every period's summary carries). Without a ListenBrainz user lb-bot has no plays to
 * count, and a quiet month is still a history (the card then says so, as the page does).
 */
internal fun listeningHistoryHeld(summary: LbListeningSummary?): Boolean =
	summary != null && summary.isAnswer &&
		(summary.totals.plays > 0 || summary.heatmap.any { it.count > 0 })

/** The range the heat map draws, as it says of itself: its first and last day, and how many weeks. */
data class HeatmapSpan(val first: String, val last: String, val weeks: Int)

/**
 * **The heat map is its own span at a glance and does not follow the picker**: lb-bot sends the
 * same days — the last sixteen weeks ending today — with every period's summary. So it names
 * the range actually DRAWN: from the first Monday the grid starts on to the newest day, and how
 * many weeks that is. Null when there is no day to draw.
 */
internal fun heatmapSpan(grid: ListeningHeatmap): HeatmapSpan? {
	val days = grid.weeks.flatten().filterNotNull()
	val first = days.firstOrNull() ?: return null
	return HeatmapSpan(first.date, days.last().date, grid.weeks.size)
}

/** `daily` — the period's plays per day — as bars, oldest day first, whatever order it came in. */
internal fun dayBars(daily: List<LbDayCount>): List<Int> = daySeries(daily).map { it.count }

/**
 * The cover on a card or a row that stands for an album (a top album, a top track's album, a
 * recent play): the library's own when Room holds [ndAlbumId]; else the Cover Art Archive's
 * front for the release group — the cover the app already uses for an album it does not hold,
 * as Fresh does; else the placeholder. From ids the row carries: no request to learn a picture.
 */
internal fun albumCardArt(ndAlbumId: String, rgid: String, albumArt: Map<String, String?>): LbArt = when {
	heldInLibrary(ndAlbumId, albumArt) -> LbArt.Library(albumArt[ndAlbumId], isArtist = false)
	rgid.isNotBlank() -> LbArt.Remote(LbBotManager.caaCoverUrl(rgid))
	else -> LbArt.Placeholder(isArtist = false)
}

/**
 * A "Not in library" card. An album: the Archive's front for its release group, else the
 * placeholder. An artist: nothing in the library to draw — the artist placeholder — unless Room
 * holds the id the row names.
 */
internal fun unownedCardArt(isArtist: Boolean, row: LbUnownedRow, artistArt: Map<String, String?>): LbArt =
	if (isArtist) libraryArtistArt(row.artistId, artistArt) else albumCardArt("", row.rgid, emptyMap())

/** lb-bot charts at most this many days of a period, the newest (`LISTENS_DAILY_MAX_DAYS`). */
const val LISTENING_DAILY_MAX_DAYS = 90

/**
 * Whether the plays-per-day chart shows less than the period picked: lb-bot sends at most the
 * last [LISTENING_DAILY_MAX_DAYS] days, so a year or all time is charted over ninety days — and
 * the screen says so rather than letting ninety bars pass for a year.
 */
internal fun listeningDailyCapped(period: ListeningPeriod): Boolean = when (period) {
	ListeningPeriod.YEAR, ListeningPeriod.ALL -> true
	ListeningPeriod.WEEK, ListeningPeriod.MONTH, ListeningPeriod.QUARTER -> false
}

/** A ranking row's bar (a genre's, a top artist's): its plays against the list's most played, 0 to 1. */
internal fun genreBarFraction(plays: Int, top: Int): Float =
	if (top <= 0 || plays <= 0) 0f else (plays.toFloat() / top).coerceIn(0f, 1f)

// ----- a History row's menu -----------------------------------------------------------------------

/** One song of the album a play is filed under, as far as matching it needs. */
data class HistorySong(val id: String, val title: String, val recordingMbid: String)

/** What a History row's menu is opened on. */
sealed interface HistoryMenu {
	/** The song itself: found in the album, and the only one that fits. */
	data class Song(val songId: String) : HistoryMenu

	/** The album the play is filed under: the song could not be told apart. */
	data class Album(val albumId: String) : HistoryMenu

	/** The artist: the library holds no album for the play. */
	data class Artist(val artistId: String) : HistoryMenu

	/** Nothing in the library to open a menu on. */
	data object None : HistoryMenu
}

/**
 * **Never a menu acting on a guessed song.** A recent play carries no Navidrome song id — only
 * the track's title, the recording's MusicBrainz id and the album and artist the library files it
 * under — so the song is looked for when the menu is asked for, among [albumSongs] (the songs of
 * the play's album as Room holds them; null when it holds no such album):
 *
 * - by the recording id, when exactly one song carries it;
 * - else by title, with case, spacing and punctuation aside, when exactly one song has it;
 * - else the album's own menu — two songs fit, or none: which was played is not known.
 *
 * With no album held: the artist's menu when the library holds the artist ([artistHeld]), and
 * otherwise nothing.
 */
internal fun historyMenu(row: LbRecentPlay, albumSongs: List<HistorySong>?, artistHeld: Boolean): HistoryMenu {
	if (albumSongs == null || row.ndAlbumId.isBlank()) {
		return if (artistHeld && row.ndArtistId.isNotBlank()) HistoryMenu.Artist(row.ndArtistId) else HistoryMenu.None
	}
	val byRecording = if (row.recordingMbid.isBlank()) emptyList()
	else albumSongs.filter { it.recordingMbid == row.recordingMbid }
	byRecording.singleOrNull()?.let { return HistoryMenu.Song(it.id) }
	// Two songs carry the recording: the title cannot tell apart what the id could not.
	if (byRecording.size > 1) return HistoryMenu.Album(row.ndAlbumId)
	val wanted = titleKey(row.track)
	val byTitle = if (wanted.isBlank()) emptyList() else albumSongs.filter { titleKey(it.title) == wanted }
	return byTitle.singleOrNull()?.let { HistoryMenu.Song(it.id) } ?: HistoryMenu.Album(row.ndAlbumId)
}

/** A title with case, spacing and punctuation aside: its letters and digits, lower-cased. */
private fun titleKey(title: String): String = title.lowercase().filter { it.isLetterOrDigit() }

// ----- fix round 1 --------------------------------------------------------------------------------

/**
 * The heat map on screen after [summary] was read (or cleared: null). **It does not depend on
 * the period**, so it must not blink out when one is tapped — the summary is cleared then, and
 * the picker sits under the map. The last one is kept until a summary brings another; a summary
 * that carries none (lb-bot left it out) and a read that failed keep it too.
 */
internal fun keptHeatmap(previous: List<LbDayCount>, summary: LbListeningSummary?): List<LbDayCount> =
	summary?.heatmap?.takeIf { it.isNotEmpty() } ?: previous

/**
 * `daily` as calendar days: sorted, one row per day, **every day between the first and the last
 * present** — a day lb-bot left out is a day with no plays, not a bar that is not there — and a
 * row with no readable date dropped.
 */
internal fun daySeries(daily: List<LbDayCount>): List<LbDayCount> {
	val byDay = daily.mapNotNull { day -> dayOrNull(day.date)?.let { it to day.count.coerceAtLeast(0) } }
		.sortedBy { it.first }
	val first = byDay.firstOrNull()?.first ?: return emptyList()
	val counts = IntArray((byDay.last().first.toEpochDays() - first.toEpochDays()).toInt() + 1)
	for ((date, count) in byDay) counts[(date.toEpochDays() - first.toEpochDays()).toInt()] = count
	return counts.mapIndexed { index, count -> LbDayCount(first.plus(index, DateTimeUnit.DAY).toString(), count) }
}

/**
 * The plays-per-day chart's axis: **dates, never hours.** The first day, the last, and as many
 * evenly spaced between as fit ([most] in all), each as (index of its bar, `MM-DD`).
 */
internal fun dayAxisLabels(series: List<LbDayCount>, most: Int = 5): List<Pair<Int, String>> {
	if (series.isEmpty() || most <= 0) return emptyList()
	val count = minOf(most, series.size)
	val indices = if (count == 1) listOf(series.lastIndex)
	else (0 until count).map { (it.toLong() * series.lastIndex / (count - 1)).toInt() }.distinct()
	// `yyyy-mm-dd` without its year: the chart never spans more than ninety days.
	return indices.map { it to series[it].date.substringAfter('-') }
}

/**
 * The side of one heat-map square: what the width allows for [columns] weeks, and **never more
 * than [cap]** — a history of one or two weeks would otherwise draw squares as wide as the
 * screen. The grid is then narrower than the page and sits at its start.
 */
internal fun heatmapCellSize(available: Float, columns: Int, gap: Float, cap: Float): Float =
	if (columns <= 0) 0f else ((available - gap * (columns - 1)) / columns).coerceIn(0f, cap)

/** What a History row answers to. */
enum class HistoryRowGesture { NONE, TAP, TAP_AND_MENU, MENU_ONLY }

/**
 * **A tap does something or shows nothing.** A row with somewhere to go answers a tap (and a
 * long press, when it has a menu); one with a menu and nowhere to go answers the long press
 * alone — no ripple under a tap that would do nothing; one with neither is plain.
 */
internal fun historyRowGesture(hasTarget: Boolean, hasMenu: Boolean): HistoryRowGesture = when {
	hasTarget && hasMenu -> HistoryRowGesture.TAP_AND_MENU
	hasTarget -> HistoryRowGesture.TAP
	hasMenu -> HistoryRowGesture.MENU_ONLY
	else -> HistoryRowGesture.NONE
}
