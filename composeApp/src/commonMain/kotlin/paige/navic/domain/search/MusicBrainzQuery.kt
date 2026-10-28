package paige.navic.domain.search

/*
 * P3: what Search sends lb-bot's MusicBrainz lookups, pure. `q` reaches MusicBrainz verbatim as a
 * Lucene query (navi-connect CLAUDE.md §7), so typo tolerance there costs no request: a word of four
 * or more Latin letters or digits gets "~1", one edit. Not "~" (distance 2): on short words that
 * ranked strangers first ("boy~ genuis~" put Bob Dylan above boygenius, probed 2026-10-20). A word
 * with a symbol in it is escaped and left exact; text that already reads as Lucene ([readsAsLucene]) is
 * sent as typed.
 */

private const val LUCENE_SPECIALS = "+-&|!(){}[]^\"~*?:\\/"

fun mbFuzzyQuery(raw: String): String = luceneWords(raw, fuzzy = true)

/**
 * The album lookup's form: the words as typed OR their fuzzy form, one request still. Measured live on
 * 2026-10-20 (RESULTS-find): fuzzy terms alone rescued typos ("in rainbos", "pablo honny") but pushed
 * exact titles down ("in rainbows" #1 to #2, "blue weekend" #1 to #5, past the four album rows All
 * shows); with the typed words beside them the exact title stays first and the typos are still found.
 * Artists did not need it: the fuzzy form ranked the right artist first on every probe.
 */
fun mbAlbumQuery(raw: String): String {
	val fuzzy = mbFuzzyQuery(raw)
	if (readsAsLucene(raw)) return fuzzy
	val exact = luceneWords(raw, fuzzy = false)
	return if (exact == fuzzy) fuzzy else "($exact) OR ($fuzzy)"
}

/** MusicBrainz's search fields a person might type as `field:value`. */
private val MB_FIELDS = setOf(
	"artist", "release", "releasegroup", "recording", "work", "label", "tag", "type", "country",
	"arid", "rgid", "reid", "mbid"
)

private val FIELD_PREFIX = Regex("""(?:^|[\s(])([A-Za-z]+):""")
private val FUZZY_TERM = Regex("""\w~\d*(?:\s|$|\))""")

/**
 * Text that reads as Lucene and is sent as typed (the P3 feature): a balanced pair of double quotes,
 * a `~` right after a word character, or a known MusicBrainz field name followed by a colon. Anything
 * else with a `:`, a lone `"` or a stray `~` is escaped like every other special (audit L4b-10: any
 * of the three sent the whole text raw, so "Songs: Ohia" asked MusicBrainz for a field "Songs").
 */
private fun readsAsLucene(raw: String): Boolean {
	val quotes = raw.count { it == '"' }
	if (quotes >= 2 && quotes % 2 == 0) return true
	if (FUZZY_TERM.containsMatchIn(raw)) return true
	return FIELD_PREFIX.findAll(raw).any { it.groupValues[1].lowercase() in MB_FIELDS }
}

private fun luceneWords(raw: String, fuzzy: Boolean): String {
	if (raw.isBlank()) return ""
	if (readsAsLucene(raw)) return raw
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
			fuzzy && word.length >= 4 && word.all(::isLatinLetterOrDigit) -> "$word~1"
			else -> word
		}
	}
}

/** a-z, digits, and the Latin letters with marks (Latin-1 Supplement and Latin Extended-A/B). */
private fun isLatinLetterOrDigit(c: Char): Boolean =
	c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || (c.code in 0xC0..0x24F && c.isLetter())
