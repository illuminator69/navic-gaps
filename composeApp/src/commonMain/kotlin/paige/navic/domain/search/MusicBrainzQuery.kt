package paige.navic.domain.search

/*
 * P3: what Search sends lb-bot's MusicBrainz lookups, pure. `q` reaches MusicBrainz verbatim as a
 * Lucene query (navi-connect CLAUDE.md §7), so typo tolerance there costs no request: a word of four
 * or more Latin letters or digits gets "~1", one edit. Not "~" (distance 2): on short words that
 * ranked strangers first ("boy~ genuis~" put Bob Dylan above boygenius, probed 2026-10-20). A word
 * with a symbol in it is escaped and left exact; text that already reads as Lucene is sent as typed.
 */

private const val LUCENE_SPECIALS = "+-&|!(){}[]^\"~*?:\\/"

fun mbFuzzyQuery(raw: String): String {
	if (raw.isBlank()) return ""
	if (raw.any { it == '"' || it == ':' || it == '~' }) return raw
	return raw.trim().split(Regex("\\s+")).joinToString(" ") { word ->
		when {
			// A bare operator would be read as one: "Simon AND Garfunkel".
			word == "AND" || word == "OR" || word == "NOT" -> word.lowercase()
			word.any { it in LUCENE_SPECIALS } -> buildString {
				for (c in word) {
					if (c in LUCENE_SPECIALS) append('\\')
					append(c)
				}
			}
			word.length >= 4 && word.all(::isLatinLetterOrDigit) -> "$word~1"
			else -> word
		}
	}
}

/** a-z, digits, and the Latin letters with marks (Latin-1 Supplement and Latin Extended-A/B). */
private fun isLatinLetterOrDigit(c: Char): Boolean =
	c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || (c.code in 0xC0..0x24F && c.isLetter())
