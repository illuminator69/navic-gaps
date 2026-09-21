package paige.navic.util

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import paige.navic.domain.models.DomainSongArtist

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
