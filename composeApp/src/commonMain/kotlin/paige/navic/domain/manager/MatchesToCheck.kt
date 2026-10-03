package paige.navic.domain.manager

import kotlinx.serialization.Serializable

/*
 * "Matches to check" (I-021): the library artists whose MusicBrainz identity is worth a look.
 *
 * Where Navidrome has a tag for an artist, lb-bot's index agrees with it. Where it has none,
 * lb-bot resolves the artist by a name search — and for most of those the guess matches none of
 * the albums the library holds, or there is no guess at all. Those are the artists an override
 * exists for, and without this list they can only be found by opening artists one by one. lb-bot
 * computes it and sends it as `toCheck` beside the overrides; this file is the client's reading
 * of it, kept pure so it can be tested (`MatchesToCheckTest`).
 *
 * A row asserts "worth a look", never "wrong".
 */

/**
 * One artist to check. [kind] is `unmatched` — lb-bot guessed [mbid], and no release-group of that
 * artist is anything but missing — or `unresolved` — it found no MusicBrainz artist, and [mbid] is
 * blank. [albums] is how many albums Navidrome reports for the artist.
 */
@Serializable
data class LbMatchToCheck(
	val ndArtistId: String = "",
	val name: String = "",
	val kind: String = "",
	val mbid: String = "",
	val albums: Int = 0
)

const val MATCH_KIND_UNMATCHED = "unmatched"

/**
 * The description above the list. True of both kinds of row, and says what was measured rather
 * than that anything is wrong: an untagged artist whose name is not in Latin script is often
 * listed even when lb-bot's guess is right.
 */
const val MATCHES_TO_CHECK_BLURB =
	"Artists with no MusicBrainz tag in Navidrome, where lb-bot's own guess matches none of " +
		"their albums or where it found no artist at all. Worth a look, not necessarily wrong: " +
		"an artist whose name is not in Latin script is often listed even when the guess is right."

/**
 * The rows to show, or null for "no such list".
 *
 * **Absent is not empty.** An lb-bot that predates the list sends no `toCheck`, and the section
 * is then not shown at all; an empty list is lb-bot saying there is nothing to check, and the
 * section says so. An artist the map already holds an override for is dropped here too: a set
 * made a moment ago takes the row out at once, whether or not the re-read has landed.
 */
internal fun matchesToShow(
	toCheck: List<LbMatchToCheck>?,
	overrides: Map<String, LbArtistOverride>
): List<LbMatchToCheck>? =
	toCheck?.filter { it.ndArtistId.isNotBlank() && it.ndArtistId !in overrides }

/** Whether lb-bot has a guess that "Keep this match" could make the user's own. */
internal fun matchCanKeep(row: LbMatchToCheck): Boolean =
	row.kind == MATCH_KIND_UNMATCHED && row.mbid.isNotBlank()

/**
 * What is the matter with a row, in words. A kind this build has never heard of is worded by
 * what it carries: a guess, or none. lb-bot only lists an artist with at least one album, so a
 * count of 0 is a field that did not arrive, and is not said as a number.
 */
internal fun matchReason(row: LbMatchToCheck): String = when {
	row.mbid.isBlank() -> "No MusicBrainz artist found"
	row.albums <= 0 -> "lb-bot guessed a MusicBrainz artist that matches none of your albums"
	row.albums == 1 -> "lb-bot guessed a MusicBrainz artist that does not match your 1 album"
	else -> "lb-bot guessed a MusicBrainz artist that matches none of your ${row.albums} albums"
}

/**
 * The line under a row's name: what was measured, and how many albums the artist has. A guess's
 * sentence carries the count itself; "No MusicBrainz artist found" does not, so the count
 * follows it (the same line the desktop client shows).
 */
internal fun matchDetail(row: LbMatchToCheck): String {
	val reason = matchReason(row)
	return if (row.mbid.isBlank() && row.albums > 0) {
		"$reason · ${row.albums} ${if (row.albums == 1) "album" else "albums"}"
	} else {
		reason
	}
}

/**
 * Whether the section exists. Once a body has been loaded, only one that carried `toCheck` shows
 * it. With lb-bot out of reach and nothing loaded it does exist, to say that lb-bot is needed —
 * whether lb-bot sends the list cannot be known then. While the first read is still in flight it
 * does not: the answer is seconds away, and a heading that appears only to vanish on an lb-bot
 * that predates the list is worse than one that arrives with its rows.
 */
internal fun matchesSectionShown(data: LbPanelData, rows: List<LbMatchToCheck>?): Boolean =
	when (data) {
		LbPanelData.UNSUPPORTED, LbPanelData.READING -> false
		LbPanelData.UNREACHABLE -> true
		LbPanelData.STALE, LbPanelData.CURRENT -> rows != null
	}

/** The line under the heading, or null when the rows speak for themselves. */
internal fun matchesNote(data: LbPanelData, count: Int): String? = when (data) {
	LbPanelData.UNSUPPORTED, LbPanelData.READING -> null
	LbPanelData.UNREACHABLE -> LB_UNREACHABLE_LINE
	LbPanelData.STALE -> LB_STALE_LINE
	LbPanelData.CURRENT -> if (count == 0) "Nothing to check." else null
}
