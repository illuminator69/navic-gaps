package paige.navic.domain.search

/*
 * P3: typo-tolerant matching for the library half of Search, pure.
 *
 * Navidrome's search3 matches whole words and prefixes and Room's fallback is a LIKE, so a typo found
 * nothing on either path. Room holds the whole library, so this scores every name locally instead.
 * Each query token must be matched by some candidate token: exactly, as a prefix, or within an edit
 * allowance that grows with the token's length (Damerau: a transposition is one edit). Tokens of one
 * to three letters get no allowance, which is the documented limit ("daft pnk" does not find Daft
 * Punk): with one edit there, "boy" finds Bob Dylan. Non-Latin tokens (CJK, Cyrillic…) match as
 * substrings and never fuzzily, since an edit there changes the word.
 */

/** Lower case, accents folded, "&" read as "and", apostrophes and dots dropped, every other symbol a space. */
fun normalizeForSearch(s: String): String {
	val out = StringBuilder(s.length)
	var pendingSpace = false
	fun put(text: String) {
		if (pendingSpace && out.isNotEmpty()) out.append(' ')
		pendingSpace = false
		out.append(text)
	}
	for (raw in s) {
		val c = raw.lowercaseChar()
		when {
			c == '\'' || c == '’' || c == '‘' || c == '.' || c == 'ʼ' -> Unit
			c == '&' -> {
				pendingSpace = true
				put("and")
				pendingSpace = true
			}
			c in 'a'..'z' || c in '0'..'9' -> put(c.toString())
			c.code < 0x80 -> pendingSpace = true
			else -> {
				val folded = foldChar(c)
				when {
					folded != null -> put(folded)
					c.isLetterOrDigit() -> put(c.toString())
					else -> pendingSpace = true
				}
			}
		}
	}
	return out.toString()
}

/** 0 when [candidate] does not match [query]; higher is better. An exact match is 1000, a prefix 900. */
fun fuzzyScore(query: String, candidate: String): Int {
	val q = PreparedQuery.of(query) ?: return 0
	return FuzzyScorer().score(q, PreparedText.of(candidate))
}

/**
 * The library's names, normalised once, searched per query. Built by `LibraryFuzzyIndex` over about
 * 16,000 rows; [search] allocates nothing per candidate (the distance rows are the scorer's own).
 */
class FuzzyIndex<T>(entries: List<Entry<T>>) {
	data class Entry<T>(val value: T, val text: String)

	private val values: List<T> = entries.map { it.value }
	private val texts: List<PreparedText> = entries.map { PreparedText.of(it.text) }

	val size: Int get() = values.size

	/** Best first; ties keep input order. Empty for a blank query. */
	fun search(query: String, limit: Int): List<T> {
		val q = PreparedQuery.of(query) ?: return emptyList()
		if (limit <= 0) return emptyList()
		val scorer = FuzzyScorer()
		val hits = ArrayList<Pair<Int, Int>>()
		for (i in texts.indices) {
			val s = scorer.score(q, texts[i])
			if (s > 0) hits += s to i
		}
		hits.sortWith(compareByDescending<Pair<Int, Int>> { it.first }.thenBy { it.second })
		return hits.asSequence().take(limit).map { values[it.second] }.toList()
	}
}

internal class PreparedText(val normalized: String, val tokens: List<String>, val joined: String) {
	companion object {
		fun of(text: String): PreparedText {
			val n = normalizeForSearch(text)
			val tokens = if (n.isEmpty()) emptyList() else n.split(' ')
			return PreparedText(n, tokens, tokens.joinToString(""))
		}
	}
}

internal class PreparedQuery(
	val normalized: String,
	val tokens: List<String>,
	val joined: String,
	val latin: BooleanArray
) {
	companion object {
		fun of(query: String): PreparedQuery? {
			val text = PreparedText.of(query)
			if (text.tokens.isEmpty()) return null
			return PreparedQuery(
				text.normalized,
				text.tokens,
				text.joined,
				BooleanArray(text.tokens.size) { isLatinToken(text.tokens[it]) }
			)
		}
	}
}

private const val TIER_EXACT = 3
private const val TIER_PREFIX = 2
private const val TIER_FUZZY = 1
private const val TIER_NONE = 0

private const val MAX_TOKEN = 48

internal class FuzzyScorer {
	private var row0 = IntArray(MAX_TOKEN + 1)
	private var row1 = IntArray(MAX_TOKEN + 1)
	private var row2 = IntArray(MAX_TOKEN + 1)

	fun score(q: PreparedQuery, c: PreparedText): Int {
		if (c.tokens.isEmpty()) return 0
		if (q.normalized == c.normalized) return 1000
		if (c.normalized.startsWith(q.normalized)) return 900
		// One letter is too little to search inside a name: only a start counts.
		if (q.normalized.length == 1) return 0

		var worst = TIER_EXACT
		var firstAtStart = false
		val last = q.tokens.lastIndex
		for (qi in q.tokens.indices) {
			val qt = q.tokens[qi]
			var best = TIER_NONE
			var bestAt = -1
			for (ci in c.tokens.indices) {
				val t = tokenTier(qt, q.latin[qi], c.tokens[ci], qi == last)
				if (t > best) {
					best = t
					bestAt = ci
					if (t == TIER_EXACT) break
				}
			}
			if (best == TIER_NONE) return joinedScore(q, c)
			if (qi == 0) firstAtStart = bestAt == 0
			if (best < worst) worst = best
		}
		val base = when (worst) {
			TIER_EXACT -> 800
			TIER_PREFIX -> 700
			else -> 600
		}
		val extra = (c.tokens.size - q.tokens.size).coerceAtLeast(0)
		return base + (if (firstAtStart) 50 else 0) + (40 - 5 * extra).coerceAtLeast(0)
	}

	/**
	 * "boy genius" against "boygenius": the words run together on one side only. Exact or a prefix,
	 * never fuzzy: joined, "daft pnk" is one edit from "daftpunk", and the short-word rule says no.
	 */
	private fun joinedScore(q: PreparedQuery, c: PreparedText): Int {
		if (q.tokens.size == 1 && c.tokens.size == 1) return 0
		return when {
			q.joined == c.joined -> 800
			c.joined.startsWith(q.joined) -> 700
			else -> 0
		}
	}

	private fun tokenTier(qt: String, latin: Boolean, ct: String, isLast: Boolean): Int {
		if (qt == ct) return TIER_EXACT
		if (ct.startsWith(qt)) return TIER_PREFIX
		if (!latin) return if (ct.contains(qt)) TIER_PREFIX else TIER_NONE
		val k = allowance(qt)
		if (k == 0) return TIER_NONE
		if (qt.length > MAX_TOKEN || ct.length > MAX_TOKEN) return TIER_NONE
		val lengthGap = ct.length - qt.length
		if (lengthGap <= k && lengthGap >= -k && distance(qt, ct, k, prefix = false) <= k) return TIER_FUZZY
		if (isLast && ct.length >= qt.length - k && distance(qt, ct, k, prefix = true) <= k) return TIER_FUZZY
		return TIER_NONE
	}

	/**
	 * Optimal string alignment distance, bounded: anything over [k] comes back as k + 1. With [prefix]
	 * it is the distance to the closest PREFIX of [b] (the last query word may be unfinished).
	 */
	private fun distance(a: String, b: String, k: Int, prefix: Boolean): Int {
		val m = a.length
		val n = if (prefix) minOf(b.length, m + k) else b.length
		var prev2 = row0
		var prev = row1
		var cur = row2
		for (j in 0..n) prev[j] = j
		for (i in 1..m) {
			cur[0] = i
			var rowMin = cur[0]
			val ac = a[i - 1]
			for (j in 1..n) {
				val bc = b[j - 1]
				val cost = if (ac == bc) 0 else 1
				var v = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
				if (i > 1 && j > 1 && ac == b[j - 2] && a[i - 2] == bc) {
					v = minOf(v, prev2[j - 2] + 1)
				}
				cur[j] = v
				if (v < rowMin) rowMin = v
			}
			if (rowMin > k) return k + 1
			val t = prev2
			prev2 = prev
			prev = cur
			cur = t
		}
		if (!prefix) return prev[n]
		var best = k + 1
		val from = (m - k).coerceAtLeast(0)
		for (j in from..n) if (prev[j] < best) best = prev[j]
		return best
	}
}

/** One to three letters: none. Four to six: one edit. Seven or more: two. A token with a digit: none. */
private fun allowance(token: String): Int {
	if (token.any { it in '0'..'9' }) return 0
	return when {
		token.length <= 3 -> 0
		token.length <= 6 -> 1
		else -> 2
	}
}

private fun isLatinToken(token: String): Boolean = token.all { it in 'a'..'z' || it in '0'..'9' }

/** Latin letters with marks, and the few ligatures and letters that are their own spelling. */
private fun foldChar(c: Char): String? = when (c) {
	'à', 'á', 'â', 'ã', 'ä', 'å', 'ā', 'ă', 'ą', 'ǎ', 'ạ', 'ả', 'ấ', 'ầ', 'ẩ', 'ẫ', 'ậ', 'ắ', 'ằ', 'ẳ', 'ẵ', 'ặ' -> "a"
	'æ' -> "ae"
	'ç', 'ć', 'ĉ', 'ċ', 'č' -> "c"
	'ď', 'đ', 'ð' -> "d"
	'è', 'é', 'ê', 'ë', 'ē', 'ĕ', 'ė', 'ę', 'ě', 'ẹ', 'ẻ', 'ẽ', 'ế', 'ề', 'ể', 'ễ', 'ệ' -> "e"
	'ĝ', 'ğ', 'ġ', 'ģ' -> "g"
	'ĥ', 'ħ' -> "h"
	'ì', 'í', 'î', 'ï', 'ĩ', 'ī', 'ĭ', 'į', 'ı', 'ǐ', 'ỉ', 'ị' -> "i"
	'ĵ' -> "j"
	'ķ' -> "k"
	'ĺ', 'ļ', 'ľ', 'ŀ', 'ł' -> "l"
	'ñ', 'ń', 'ņ', 'ň' -> "n"
	'ò', 'ó', 'ô', 'õ', 'ö', 'ø', 'ō', 'ŏ', 'ő', 'ǒ', 'ọ', 'ỏ', 'ố', 'ồ', 'ổ', 'ỗ', 'ộ', 'ơ', 'ớ', 'ờ', 'ở', 'ỡ', 'ợ' -> "o"
	'œ' -> "oe"
	'ŕ', 'ŗ', 'ř' -> "r"
	'ś', 'ŝ', 'ş', 'š', 'ș' -> "s"
	'ß' -> "ss"
	'ţ', 'ť', 'ŧ', 'ț' -> "t"
	'þ' -> "th"
	'ù', 'ú', 'û', 'ü', 'ũ', 'ū', 'ŭ', 'ů', 'ű', 'ų', 'ǔ', 'ư', 'ụ', 'ủ', 'ứ', 'ừ', 'ử', 'ữ', 'ự' -> "u"
	'ŵ' -> "w"
	'ý', 'ÿ', 'ŷ', 'ỳ', 'ỵ', 'ỷ', 'ỹ' -> "y"
	'ź', 'ż', 'ž' -> "z"
	else -> null
}
