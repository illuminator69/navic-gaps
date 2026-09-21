package paige.navic.util

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import paige.navic.domain.models.DomainSongArtist
import paige.navic.data.database.dao.ArtistDao

/**
 * The credited artists that actually have a page to open.
 *
 * [DomainSong.artists] is the server's full credit list, but this fork's library sync stores
 * only ALBUM artists (see DbRepository.fetchAlbumArtists), so a featured track artist — "Kid
 * Cudi" on a Consequence track — has no Room row and its detail screen correctly refuses to
 * load. Linking it anyway produces a tappable name that lands on "Something went wrong".
 *
 * So the names are still the server's, and so are the ids; only the LINKS are filtered. The
 * rest of the credit line renders as plain text, which is what the old name-splitting
 * implementation did for exactly the same reason.
 */
suspend fun linkableArtists(
	artists: List<DomainSongArtist>,
	artistDao: ArtistDao
): List<DomainSongArtist> {
	if (artists.isEmpty()) return emptyList()
	val known = runCatching { artistDao.getArtistsByIds(artists.map { it.id }) }
		.getOrElse { return emptyList() }
		.mapTo(mutableSetOf()) { it.artistId }
	return artists.filter { it.id in known }
}

/**
 * The credit string with each credited artist turned into a link, joining text kept verbatim.
 *
 * Rendered off the ORIGINAL string rather than by re-joining the names, so "feat.", "&" and
 * whatever else the tag used survive exactly as written — the alternative reads as a rewrite of
 * the credit, which is what upstream's `appendArtists` does (it re-joins with ", ").
 *
 * The artists themselves come from [DomainSong.artists], i.e. the OpenSubsonic `artists[]` array,
 * so their ids are the server's own. This fork used to recover them by splitting `artistName` on
 * "feat."/"&"/"," and resolving each piece against the artist table, because the bundled
 * subsonic client exposed no such array; upstream's switch to its own subsonic-kotlin fork at
 * alpha45 made that heuristic — and the `\bfeat\b\.?` word-boundary bug it carried — obsolete.
 *
 * A name that does not appear in [display] is skipped rather than appended, so the line always
 * reads as the tag wrote it.
 */
fun artistCreditsText(
	display: String,
	credits: List<DomainSongArtist>,
	linkStyles: TextLinkStyles? = null,
	onClick: (String) -> Unit
): AnnotatedString = buildAnnotatedString {
	var cursor = 0
	for (credit in credits) {
		val start = display.indexOf(credit.name, cursor)
		if (start < 0) continue
		if (start > cursor) append(display.substring(cursor, start))
		withLink(
			LinkAnnotation.Clickable(
				tag = "artist:${credit.id}",
				styles = linkStyles,
				linkInteractionListener = { onClick(credit.id) }
			)
		) { append(credit.name) }
		cursor = start + credit.name.length
	}
	if (cursor < display.length) append(display.substring(cursor))
}

/** No decoration by default: the credit line is already styled, and underlining every name is loud. */
val PlainArtistLinkStyles = TextLinkStyles(style = SpanStyle())
