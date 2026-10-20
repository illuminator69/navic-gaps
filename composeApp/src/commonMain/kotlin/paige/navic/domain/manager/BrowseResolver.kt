package paige.navic.domain.manager

import paige.navic.data.database.dao.AlbumDao
import paige.navic.ui.screens.discover.viewmodels.BrowseTarget

/**
 * Where a Deezer browse tile opens: the library's own album, a release-group only MusicBrainz knows,
 * or nowhere (P3: moved out of `DiscoverViewModel`, unchanged, so Discover and the Charts and Editors'
 * picks pages answer a tap the same way). Room is asked before the network, and a MusicBrainz answer
 * is validated against what was asked, never taken first (CLAUDE.md §7).
 */
class BrowseResolver(
	private val lbBotManager: LbBotManager,
	private val albumDao: AlbumDao
) {
	/**
	 * The release-group id for a browse row that arrived without one.
	 *
	 * Deezer has no MusicBrainz ids, so lb-bot resolves its rows against the local
	 * discography index by name and leaves `rgid` blank when that index has never
	 * seen the record. It deliberately does **not** fan out a MusicBrainz search per
	 * row — that spends the scanner's global one-request-per-second budget on a shelf
	 * nobody tapped — and designates the tap as where the one search happens. This is
	 * that search, for the one row the user actually chose.
	 *
	 * Returns null when nothing matched, and the caller then does nothing rather than
	 * opening an empty page: `ExternalAlbumScreen` keyed on a blank rgid loads
	 * forever, which is a worse answer than a tap that declines.
	 */
	suspend fun resolve(album: LbBrowseAlbum): BrowseTarget? {
		// 1. lb-bot already knows. Cheapest and most authoritative.
		if (album.releaseOwned && album.releaseAlbumId.isNotBlank()) {
			return BrowseTarget.Library(album.releaseAlbumId)
		}

		// 2. THE LOCAL LIBRARY, before anything on the network.
		//
		// lb-bot marks ownership from its release-group index, which only covers
		// artists whose discography it has actually scanned — so a record the user
		// owns by an unscanned artist comes back `owned: false` with no rgid, and
		// every later step is then answering the wrong question. Room holds the
		// whole library and can answer "do I have this" exactly, for free, offline.
		// This is why a Deezer tile for an album sitting on disk opened a download
		// page captioned "Not in your library".
		localAlbumFor(album.artist, album.title)?.let { return BrowseTarget.Library(it) }

		// 3. lb-bot resolved the row by name against its index.
		if (album.rgid.isNotBlank()) return BrowseTarget.External(album.rgid)

		// 4. One MusicBrainz search, for the one row the user actually tapped.
		val artist = album.artist.trim()
		val title = album.title.trim()
		if (title.isBlank()) return null
		// Both the search and the local match drop the edition suffix. Deezer
		// routinely ships "Discovery (Remastered)" where MusicBrainz and the
		// library both say "Discovery", and searching for the parenthetical
		// matches nothing while `LIKE '%…(Remastered)%'` matches nothing locally.
		val searchTitle = title.substringBefore(" (").substringBefore(" [").trim()
			.ifBlank { title }

		// FIELDED, not free text. `q` is passed to MusicBrainz verbatim, and a bare
		// "Daft Punk Discovery" scores a release-group *titled* "Daft Punk's
		// Discovery but it's in the SM64 Soundfont" above the actual `Discovery`,
		// because one title contains both search words and the other contains one.
		// That is not a hypothetical: it is what this row did.
		val candidates = if (artist.isNotBlank()) {
			lbBotManager.albumLookup(
				"""artist:"${lucene(artist)}" AND releasegroup:"${lucene(searchTitle)}""""
			).ifEmpty {
				// A fielded query that matches nothing is a real answer for a
				// mis-tagged Deezer row, so free text is still worth one attempt —
				// but the validation below is what keeps its ranking honest.
				lbBotManager.albumLookup(listOf(artist, searchTitle).joinToString(" "))
			}
		} else {
			lbBotManager.albumLookup(searchTitle)
		}

		val best = bestBrowseCandidate(candidates, artist, title) ?: return null
		if (best.releaseOwned && best.releaseAlbumId.isNotBlank()) {
			return BrowseTarget.Library(best.releaseAlbumId)
		}
		return best.rgid.ifBlank { null }?.let { BrowseTarget.External(it) }
	}

	/**
	 * The library's own album id for an artist + title, or null.
	 *
	 * Matched on normalised text rather than by id because there is no id to match
	 * on — Deezer carries no MBIDs, which is the whole reason these rows arrive
	 * unresolved. Deliberately strict: an edition suffix is ignored, everything
	 * else must agree, because a wrong "you already own this" sends the user to
	 * some other record of theirs and hides the one they asked for.
	 */
	private suspend fun localAlbumFor(artist: String, title: String): String? {
		val wantTitle = normalizeTitle(title)
		val wantArtist = normalizeName(artist)
		if (wantTitle.isBlank()) return null
		return runCatching {
			albumDao.searchAlbumsList(title.substringBefore(" (").substringBefore(" [").trim())
				.firstOrNull { row ->
					normalizeTitle(row.album.name.orEmpty()) == wantTitle &&
						(wantArtist.isBlank() || artistsAgree(normalizeName(row.album.artistName.orEmpty()), wantArtist))
				}
				?.album?.albumId
		}.getOrNull()
	}

	/**
	 * The candidate that is actually the record asked for, or null.
	 *
	 * **Never `firstOrNull()`.** MusicBrainz's ranking is a text score and lb-bot
	 * passes it through; validating the answer against what was asked is the only
	 * thing that separates `Discovery` from a parody of it. Declining is a correct
	 * outcome — the caller says so — and is much better than opening the wrong
	 * album, which reads as the feature being broken rather than as a near miss.
	 */
	private fun bestBrowseCandidate(
		candidates: List<LbAlbumCandidate>,
		artist: String,
		title: String
	): LbAlbumCandidate? {
		val wantTitle = normalizeTitle(title)
		val wantArtist = normalizeName(artist)
		if (wantTitle.isBlank()) return null
		return candidates
			.filter { normalizeTitle(it.title) == wantTitle }
			.filter { wantArtist.isBlank() || artistsAgree(normalizeName(it.artist), wantArtist) }
			// Ownership first: it is a fact about the library rather than a text
			// score, so it outranks anything MusicBrainz has to say.
			.maxByOrNull { (if (it.releaseOwned) 1_000_000 else 0) + it.score }
	}

	/**
	 * Two credits naming the same act.
	 *
	 * Equality is too strict in one direction only: MusicBrainz writes the full
	 * credit ("Daft Punk feat. Julian Casablancas") where Deezer writes the act.
	 * So a prefix on a **token** boundary counts, and an unrelated artist that
	 * merely starts with the same letters does not.
	 */
	private fun artistsAgree(a: String, b: String): Boolean =
		a == b || a.startsWith("$b ") || b.startsWith("$a ")

	/** Lower-cased, punctuation-free, single-spaced. */
	private fun normalizeName(value: String): String = value
		.lowercase()
		.map { if (it.isLetterOrDigit() || it.isWhitespace()) it else ' ' }
		.joinToString("")
		.split(" ")
		.filter { it.isNotBlank() }
		.joinToString(" ")

	/**
	 * [normalizeName] plus the edition suffix dropped.
	 *
	 * "Discovery (Remastered)" and "Discovery" are the same album to a person and
	 * to this screen; keeping the parenthetical would make an owned record look
	 * unowned and send the tap to a download page.
	 */
	private fun normalizeTitle(value: String): String =
		normalizeName(value.substringBefore(" (").substringBefore(" ["))

	/** Escape what Lucene would otherwise read as syntax. */
	private fun lucene(value: String): String =
		value.replace("\\", "").replace("\"", "").trim()
}
