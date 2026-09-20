package paige.navic.util.core

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import paige.navic.data.database.dao.ArtistDao
import paige.navic.domain.models.DomainSong

/** One artist named in a song's credit string, with its library id when we could resolve one. */
@Immutable
data class CreditedArtist(val name: String, val id: String?)

/**
 * How a credit string joins two artists.
 *
 * Word-boundary anchored so a separator inside a name can't split it, and ordered longest-first
 * where one is a prefix of another (`featuring` before `feat` would be wrong the other way round).
 *
 * The abbreviating period sits **outside** the word boundary (`\bfeat\b\.?`), not inside the
 * alternative. Written as `feat\.?\b`, the trailing `\b` could never hold after the period — the
 * next character is a space, and two non-word characters are not a boundary — so the regex
 * backtracked to matching a bare `feat` and left the period glued to the following name. Every
 * credit line then had one dead entry, always the same one: `". B"` resolves to no artist, so the
 * FIRST artist after "feat." was plain text while later ones (split on "&" or ",") linked fine.
 */
private val CREDIT_SEPARATOR = Regex(
	"""\s*(?:\b(?:featuring|feat|ft|with|vs|and|x)\b\.?|[&,;/×])\s*""",
	RegexOption.IGNORE_CASE
)

/** Wrapping punctuation around a split-out name — "(feat. B)" leaves a trailing paren behind. */
private const val CREDIT_TRIM = "()[]{}\"' \t"

/**
 * The artists credited on a song, in the order they are named.
 *
 * Navidrome sends a track's artists as **one string** and the bundled `dev.zt64.subsonic` client
 * exposes no OpenSubsonic `artists[]` array to go with it, so the featured names are recovered by
 * splitting that string and resolving each piece against the local artist table. That is why this
 * is deliberately conservative — it would rather return one credit than a wrong one:
 *
 * - If the **whole** string is itself an artist ("Simon & Garfunkel", "Tyler, The Creator"), it is
 *   one credit and never split. This check comes first precisely because those names contain what
 *   would otherwise be separators.
 * - A split is only accepted when at least two pieces resolve to real artists. One resolving piece
 *   means the split was probably wrong, or the guest simply isn't in this library — either way the
 *   original single credit is the honest thing to show.
 *
 * `contributors` is consulted as a second id source: when Navidrome does populate it, it carries
 * real ids for names that may not have their own artist row.
 *
 * Never throws — a database failure degrades to the single credit the UI showed before.
 */
suspend fun creditedArtists(song: DomainSong, artistDao: ArtistDao): List<CreditedArtist> {
	val whole = song.artistName.trim()
	if (whole.isEmpty()) return emptyList()

	val primaryId = song.artistId.takeIf { it.isNotBlank() && it != "unknown artist" }
	val single = listOf(CreditedArtist(whole, primaryId))

	val pieces = whole.split(CREDIT_SEPARATOR)
		.map { it.trim(*CREDIT_TRIM.toCharArray()) }
		.filter { it.isNotEmpty() }
	if (pieces.size < 2) return single

	val rows = runCatching { artistDao.getArtistsByNames(listOf(whole) + pieces) }
		.getOrElse { return single }
	val byName = rows.associateBy { it.name.lowercase() }

	// One artist whose name merely looks like a list.
	byName[whole.lowercase()]?.let { return listOf(CreditedArtist(whole, it.artistId)) }

	val byContributor = song.contributors
		.filter { it.artistId.isNotBlank() }
		.associate { it.artistName.lowercase() to it.artistId }

	val resolved = pieces.mapIndexed { index, name ->
		val key = name.lowercase()
		CreditedArtist(
			name = name,
			// The track's own artistId belongs to the FIRST credit; the rest have to be looked up.
			id = byName[key]?.artistId ?: byContributor[key] ?: primaryId.takeIf { index == 0 }
		)
	}
	return if (resolved.count { it.id != null } >= 2) resolved else single
}

/**
 * The credit string with each resolvable artist turned into a link, joining text kept verbatim.
 *
 * Rendered off the ORIGINAL string rather than by re-joining the names, so "feat.", "&" and
 * whatever else the tag used survive exactly as written — the alternative reads as a rewrite of
 * the credit. An artist with no id stays plain text: there is no page to open.
 */
fun artistCreditsText(
	display: String,
	credits: List<CreditedArtist>,
	linkStyles: TextLinkStyles? = null,
	onClick: (String) -> Unit
): AnnotatedString = buildAnnotatedString {
	var cursor = 0
	for (credit in credits) {
		val start = display.indexOf(credit.name, cursor)
		if (start < 0) continue
		if (start > cursor) append(display.substring(cursor, start))
		if (credit.id != null) {
			withLink(
				LinkAnnotation.Clickable(
					tag = "artist:${credit.id}",
					styles = linkStyles,
					linkInteractionListener = { onClick(credit.id) }
				)
			) { append(credit.name) }
		} else {
			append(credit.name)
		}
		cursor = start + credit.name.length
	}
	if (cursor < display.length) append(display.substring(cursor))
}

/** No decoration by default: the credit line is already styled, and underlining every name is loud. */
val PlainArtistLinkStyles = TextLinkStyles(style = SpanStyle())
