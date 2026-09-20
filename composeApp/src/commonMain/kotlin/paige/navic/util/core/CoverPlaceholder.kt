package paige.navic.util.core

/**
 * Navidrome's generic artist avatar, recognised without ever looking at a pixel.
 *
 * The server hands EVERY artist a `coverArt` id and an `artistImageUrl` whether or not a picture
 * exists behind them, and serves a flat grey person-glyph PNG for the ones that don't. So a client
 * cannot tell from the library metadata which tiles will come back as artwork and which as that
 * placeholder — and the placeholder is a served image, so no theming reaches it: on a cover-washed
 * page it is a pale lavender slab in the middle of the colour.
 *
 * But Navidrome's cover id carries a hash OF THE IMAGE IT WILL SERVE (`ar-<artist>_<imageHash>`),
 * so every artist falling back to the placeholder shares one hash. That makes it findable by
 * frequency alone: measured on a 2471-artist library, the placeholder's hash was shared by 26
 * artists while the runner-up — a genuinely shared photo — had 3.
 *
 * Learning the hash rather than hardcoding it means nothing here is pinned to one Navidrome
 * version's asset, and a library whose artists all have artwork simply never sets it.
 *
 * [MIN_SHARED] and the runner-up rule are what keep it honest: a handful of artists really can
 * share one image (a duo credited three ways), so the top hash has to be both common in absolute
 * terms and a clear outlier before it is believed. A small library where only two artists lack
 * art stays undetected, which is the right way to be wrong: two grey tiles beats blanking a real
 * photo that two artists happen to share.
 *
 * Written from the artist sync / list read, read from composition — hence `@Volatile` rather than
 * a lock, as the reader only ever needs the latest value and a miss costs one frame.
 */
object CoverPlaceholder {
	private const val MIN_SHARED = 5

	@Volatile
	private var imageHash: String? = null

	/** True when [coverArtId] will resolve to the generic avatar rather than real artwork. */
	fun isPlaceholder(coverArtId: String?): Boolean {
		val hash = imageHash ?: return false
		return coverArtId != null && coverArtId.endsWith("_$hash")
	}

	/**
	 * Takes the two most-shared image hashes in the library. A `null` [topHash], too small a
	 * [topCount], or a runner-up close behind it all leave the previous verdict in place being
	 * replaced by "no placeholder known", which renders exactly as today.
	 */
	fun learn(topHash: String?, topCount: Int, runnerUpCount: Int) {
		imageHash = topHash?.takeIf { topCount >= MIN_SHARED && topCount >= runnerUpCount * 2 }
	}
}
