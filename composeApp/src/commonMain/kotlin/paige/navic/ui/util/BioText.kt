package paige.navic.ui.util

/**
 * Text handling for artist/album biography prose.
 *
 * Navidrome's Last.fm agent returns **HTML**, and it always ends with its own
 * `<a href="https://www.last.fm/...">Read more on Last.fm</a>`. The artist header
 * used to render that string through a naive `take(200)`, which meant:
 *
 *  - a bio shorter than 200 characters showed the literal `<a href="https://...">`
 *    markup on screen, because nothing stripped it;
 *  - a longer one was cut mid-word, and often mid-tag, which is worse;
 *  - the Last.fm anchor was the only route to the rest of the text.
 *
 * lb-bot's editorial metadata replaces all of that with plain text and a proper
 * Wikipedia attribution, but the Last.fm bio remains the fallback when lb-bot is
 * absent — so the fallback has to be rendered honestly too.
 */

/**
 * Strip HTML tags and decode the handful of entities Last.fm actually emits.
 *
 * Deliberately not a parser: the input is one paragraph of agent-generated markup
 * with anchors and the occasional `<br>`, not a document, and pulling in an HTML
 * parser for it would be the wrong trade in `commonMain` (this compiles for iOS
 * too). Anything it does not recognise is dropped rather than shown.
 */
fun stripHtml(html: String): String =
	html
		.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), " ")
		.replace(Regex("<[^>]*>"), "")
		.replace("&nbsp;", " ")
		.replace("&amp;", "&")
		.replace("&lt;", "<")
		.replace("&gt;", ">")
		.replace("&quot;", "\"")
		.replace("&#39;", "'")
		.replace(Regex("\\s+"), " ")
		.trim()

/**
 * A teaser of at most [limit] characters, cut at a word boundary.
 *
 * Returns the text unchanged when it already fits — so a short bio gets no
 * ellipsis, and the caller can tell "there is more" from the ellipsis alone by
 * comparing lengths.
 */
fun teaserText(text: String, limit: Int): String {
	val clean = stripHtml(text)
	if (clean.length <= limit) return clean
	val cut = clean.take(limit)
	// Back up to the last space so the teaser never ends mid-word. If there is no
	// space at all in `limit` characters, the hard cut is the only option.
	val boundary = cut.lastIndexOf(' ')
	val kept = if (boundary > limit / 2) cut.take(boundary) else cut
	return kept.trimEnd { it == ' ' || it == ',' || it == ';' } + "…"
}
