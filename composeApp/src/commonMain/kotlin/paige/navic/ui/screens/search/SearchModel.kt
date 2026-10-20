package paige.navic.ui.screens.search

import navic.composeapp.generated.resources.Res
import navic.composeapp.generated.resources.title_albums
import navic.composeapp.generated.resources.title_all
import navic.composeapp.generated.resources.title_artists
import navic.composeapp.generated.resources.title_playlists
import navic.composeapp.generated.resources.title_songs
import org.jetbrains.compose.resources.StringResource
import paige.navic.domain.manager.LbArtistCandidate
import paige.navic.domain.models.DomainAlbum
import paige.navic.domain.models.DomainArtist
import paige.navic.domain.models.DomainPlaylist
import paige.navic.domain.models.DomainSong
import paige.navic.domain.search.FuzzyScorer
import paige.navic.domain.search.PreparedQuery
import paige.navic.domain.search.PreparedText
import kotlin.time.Instant

/*
 * P3: what the Search page shows, as plain functions (`SearchModelTest`). The page draws; these decide.
 *
 * The library comes first: Navidrome's own search, then what only the typo-tolerant pass found
 * ([mergeHits]). The unowned half is lb-bot's MusicBrainz lookups, each a [Lookup] with four states, and
 * [searchPhase] never reads one still out, or one that failed, as "nothing": the page says "No results"
 * only once every source has answered with nothing.
 */

enum class SearchCategory(val res: StringResource) {
	ALL(Res.string.title_all),
	SONGS(Res.string.title_songs),
	ALBUMS(Res.string.title_albums),
	ARTISTS(Res.string.title_artists),
	PLAYLISTS(Res.string.title_playlists)
}

data class LibraryHits(
	val artists: List<DomainArtist>,
	val albums: List<DomainAlbum>,
	val songs: List<DomainSong>,
	val playlists: List<DomainPlaylist>
) {
	val isEmpty: Boolean
		get() = artists.isEmpty() && albums.isEmpty() && songs.isEmpty() && playlists.isEmpty()

	companion object {
		val None = LibraryHits(emptyList(), emptyList(), emptyList(), emptyList())
	}
}

/** Exact first, then the fuzzy items not already there (by id), each list's order kept and its duplicates gone. */
fun mergeHits(exact: LibraryHits, fuzzy: LibraryHits): LibraryHits = LibraryHits(
	artists = (exact.artists + fuzzy.artists).distinctBy { it.id },
	albums = (exact.albums + fuzzy.albums).distinctBy { it.id },
	songs = (exact.songs + fuzzy.songs).distinctBy { it.id },
	playlists = (exact.playlists + fuzzy.playlists).distinctBy { it.id }
)

private const val CATEGORY_CAP = 50

/** All shows a few of each (3 artists, 4 albums, 6 songs, 3 playlists); a category shows up to 50 of its own and nothing else. */
fun capped(hits: LibraryHits, category: SearchCategory): LibraryHits = when (category) {
	SearchCategory.ALL -> LibraryHits(
		hits.artists.take(3), hits.albums.take(4), hits.songs.take(6), hits.playlists.take(3)
	)
	SearchCategory.SONGS -> LibraryHits.None.copy(songs = hits.songs.take(CATEGORY_CAP))
	SearchCategory.ALBUMS -> LibraryHits.None.copy(albums = hits.albums.take(CATEGORY_CAP))
	SearchCategory.ARTISTS -> LibraryHits.None.copy(artists = hits.artists.take(CATEGORY_CAP))
	SearchCategory.PLAYLISTS -> LibraryHits.None.copy(playlists = hits.playlists.take(CATEGORY_CAP))
}

/** One of lb-bot's lookups for the settled query. [Failed] is said on the page (a pill that retries), never shown as empty. */
sealed interface Lookup<out T> {
	data object Idle : Lookup<Nothing>
	data object Looking : Lookup<Nothing>
	data class Done<T>(val items: List<T>) : Lookup<T>
	data object Failed : Lookup<Nothing>
}

/** A MusicBrainz artist, and the library artist it is when the library holds that MBID. */
data class ExternalArtist(val candidate: LbArtistCandidate, val ownedNdArtistId: String?)

/**
 * Ownership by MBID only, in MusicBrainz's order. Two artists can share a name, so a name match would
 * put a stranger in the library section (or hide the one the user wanted); an MBID cannot.
 */
fun splitExternalArtists(
	candidates: List<LbArtistCandidate>,
	ownedByMbid: Map<String, String>
): List<ExternalArtist> = candidates.map { c ->
	ExternalArtist(c, c.mbid.takeIf { it.isNotBlank() }?.let { ownedByMbid[it] }?.takeIf { it.isNotBlank() })
}

/**
 * lb-bot's MusicBrainz second is global, so a lookup is spent only on a query that can say something:
 * three letters or digits, or two when one of them is CJK (two characters there are a name).
 */
fun externalLookupWanted(query: String): Boolean {
	var count = 0
	var cjk = false
	for (c in query) {
		if (c.isLetterOrDigit()) {
			count++
			if (isCjk(c)) cjk = true
		}
	}
	return count >= 3 || (cjk && count >= 2)
}

private fun isCjk(c: Char): Boolean {
	val code = c.code
	return code in 0x3040..0x30FF || code in 0x3400..0x4DBF || code in 0x4E00..0x9FFF ||
		code in 0xAC00..0xD7AF || code in 0xF900..0xFAFF
}

sealed interface SearchPhase {
	/** The field is empty: recent searches and the browse cards. */
	data object Browse : SearchPhase
	data object Results : SearchPhase
	data object NoResults : SearchPhase
	/** Offline with downloaded music that matches. */
	data object Offline : SearchPhase
	/** Offline and nothing downloaded matches. */
	data object OfflineNone : SearchPhase
}

/**
 * Which page to draw. "No results" needs every source to have answered with nothing: the library
 * ([librarySettled] for this query) and, where lb-bot is on and the query is long enough to ask, both
 * lookups. One still [Lookup.Looking] is the ghost rows; one [Lookup.Failed] is the pill. Offline only
 * downloaded music is searched, and lookups left over from before the connection went do not count.
 */
fun searchPhase(
	query: String,
	online: Boolean,
	lbConfigured: Boolean,
	hits: LibraryHits,
	artists: Lookup<*>,
	albums: Lookup<*>,
	librarySettled: Boolean = true
): SearchPhase {
	if (query.isBlank()) return SearchPhase.Browse
	if (!online) {
		return if (hits.isEmpty && librarySettled) SearchPhase.OfflineNone else SearchPhase.Offline
	}
	if (!hits.isEmpty || !librarySettled) return SearchPhase.Results
	if (!lbConfigured) return SearchPhase.NoResults
	val lookups = listOf(artists, albums)
	if (lookups.any { it is Lookup.Looking || it is Lookup.Failed }) return SearchPhase.Results
	if (lookups.any { it is Lookup.Done<*> && it.items.isNotEmpty() }) return SearchPhase.Results
	return SearchPhase.NoResults
}

/**
 * Offline, Search finds downloaded music only (the board's promise, made true here rather than by a
 * banner over the whole cached library): the downloaded songs whose title, artist or album matches, the
 * albums they come from, and their artists, each artist carrying how many of its albums are downloaded.
 * Typo-tolerant like the online pass. Albums are built from the downloaded songs (an album row opens
 * the library album page by its id, which loads from Room as ever).
 */
fun offlineHits(downloaded: List<DomainSong>, query: String): LibraryHits {
	val q = PreparedQuery.of(query) ?: return LibraryHits.None
	if (downloaded.isEmpty()) return LibraryHits.None
	val scorer = FuzzyScorer()
	fun score(text: String?): Int = if (text.isNullOrBlank()) 0 else scorer.score(q, PreparedText.of(text))

	val songs = downloaded
		.map { it to maxOf(score(it.title), score(it.artistName), score(it.albumTitle)) }
		.filter { it.second > 0 }
		.sortedByDescending { it.second }
		.map { it.first }

	val byAlbum = downloaded.filter { !it.albumId.isNullOrBlank() }.groupBy { it.albumId!! }
	val albums = byAlbum.mapNotNull { (albumId, albumSongs) ->
		val first = albumSongs.first()
		val artist = first.albumArtists.firstOrNull()
		val s = maxOf(score(first.albumTitle), score(artist?.name ?: first.artistName))
		if (s == 0) return@mapNotNull null
		DomainAlbum(
			id = albumId,
			name = first.albumTitle,
			artistName = artist?.name ?: first.artistName,
			artistId = artist?.id ?: first.artistId,
			year = first.year,
			coverArtId = first.coverArtId,
			genre = first.genre,
			genres = first.genres,
			songCount = albumSongs.size,
			duration = albumSongs.fold(kotlin.time.Duration.ZERO) { acc, it -> acc + it.duration },
			createdAt = Instant.fromEpochSeconds(0),
			starredAt = null,
			lastPlayedAt = null,
			userRating = null,
			version = null,
			musicBrainzId = null,
			songs = albumSongs
		) to s
	}.sortedByDescending { it.second }.map { it.first }

	val byArtist = downloaded.groupBy { (it.albumArtists.firstOrNull()?.id ?: it.artistId) }
		.filterKeys { it.isNotBlank() }
	val artists = byArtist.mapNotNull { (artistId, artistSongs) ->
		val first = artistSongs.first()
		val name = first.albumArtists.firstOrNull()?.name ?: first.artistName ?: return@mapNotNull null
		val s = score(name)
		if (s == 0) return@mapNotNull null
		DomainArtist(
			id = artistId,
			name = name,
			albumCount = artistSongs.mapNotNull { it.albumId?.takeIf { id -> id.isNotBlank() } }.distinct().size
		) to s
	}.sortedByDescending { it.second }.map { it.first }

	return LibraryHits(artists = artists, albums = albums, songs = songs, playlists = emptyList())
}

/** The browse cards under an empty field, in the board's order. */
enum class BrowseTile { GENRES, CHARTS, PICKS, FRESH, DISCOVER }

data class PlacedTile(val tile: BrowseTile, val fullWidth: Boolean)

/**
 * Which browse cards show, and how wide: Genres always; Charts and Editors' picks where the hub
 * proxies Deezer's routes ([chartsRoute], [picksRoute]: advertised AND the lb-bot layer on, since an
 * empty advert reads as capable); Fresh where lb-bot answers; Discover where lb-bot answers or
 * AudioMuse is set up (it carries mood search), always full width. A missing card leaves no hole:
 * an odd last half-width card spans both columns.
 */
fun browseTiles(
	lbBotAvailable: Boolean,
	chartsRoute: Boolean,
	picksRoute: Boolean,
	audioMuseConfigured: Boolean
): List<PlacedTile> {
	val halves = buildList {
		add(BrowseTile.GENRES)
		if (chartsRoute) add(BrowseTile.CHARTS)
		if (picksRoute) add(BrowseTile.PICKS)
		if (lbBotAvailable) add(BrowseTile.FRESH)
	}
	return buildList {
		halves.forEachIndexed { i, tile ->
			add(PlacedTile(tile, fullWidth = halves.size % 2 == 1 && i == halves.lastIndex))
		}
		if (lbBotAvailable || audioMuseConfigured) add(PlacedTile(BrowseTile.DISCOVER, fullWidth = true))
	}
}

/** One row of the "Not in your library" section. [key] is its identity in the list. */
sealed interface ExternalRow {
	val key: String

	data class Artist(val artist: ExternalArtist) : ExternalRow {
		override val key: String get() = "x:ar:" + artist.candidate.mbid.ifBlank { artist.candidate.name }
	}

	data class Album(val album: paige.navic.domain.manager.LbAlbumCandidate) : ExternalRow {
		override val key: String get() = "x:al:" + album.rgid.ifBlank { album.title + "\u0000" + album.artist }
	}
}

/**
 * The unowned rows for a category, artists before albums, each once. An artist the library holds (by
 * MBID) is not here: it is drawn in the library section. All shows a few of each (3 artists, 4 albums);
 * Artists and Albums show all of their own; Songs and Playlists none, since MusicBrainz answered
 * neither.
 */
fun externalRows(
	artists: Lookup<ExternalArtist>,
	albums: Lookup<paige.navic.domain.manager.LbAlbumCandidate>,
	category: SearchCategory
): List<ExternalRow> {
	val a = (artists as? Lookup.Done)?.items.orEmpty().filter { it.ownedNdArtistId == null }.map { ExternalRow.Artist(it) }
	val b = (albums as? Lookup.Done)?.items.orEmpty().map { ExternalRow.Album(it) }
	val rows = when (category) {
		SearchCategory.ALL -> a.take(3) + b.take(4)
		SearchCategory.ARTISTS -> a
		SearchCategory.ALBUMS -> b
		SearchCategory.SONGS, SearchCategory.PLAYLISTS -> emptyList()
	}
	return rows.distinctBy { it.key }
}
