package paige.navic.ui.screens.discover.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import paige.navic.data.database.dao.AlbumDao
import paige.navic.data.database.dao.ArtistDao
import paige.navic.data.database.dao.PlaylistDao
import paige.navic.data.database.dao.SongDao
import paige.navic.data.database.entities.PlaylistEntity
import paige.navic.domain.manager.AudioMuseManager
import paige.navic.domain.manager.ConnectivityManager
import paige.navic.domain.manager.HubManager
import paige.navic.domain.manager.LbBotManager
import paige.navic.domain.manager.LbFreshRelease
import paige.navic.domain.manager.LbAlbumCandidate
import paige.navic.domain.manager.LbBrowseAlbum
import paige.navic.domain.manager.LbDeezerGenre
import paige.navic.domain.manager.PreferenceManager
import paige.navic.domain.manager.LbSimilarArtist
import paige.navic.domain.manager.RediscoveryPlaylists
import paige.navic.domain.models.Mix
import paige.navic.ui.screens.discover.LISTENBRAINZ_PLAYLIST_PREFIX
import paige.navic.ui.screens.discover.DISCOVER_ROWS
import paige.navic.ui.screens.discover.DiscoverCapability
import paige.navic.ui.screens.discover.DiscoverRowId

/** How many of the most-played artists are eligible to seed the "Fans also like" row. */
private const val SEED_POOL = 12

/** Row content is a glance, not a list screen. */
private const val ROW_LIMIT = 20

/** Deezer's own id for "every genre" — the chart both rows show by default. */
const val DEEZER_GENRE_ALL = "0"

/** Asked for by name because it extends an existing row rather than being one. */
private const val ROUTE_RELATED = "GET /lb/artist/related"

/**
 * A similar artist, with whatever artwork we can actually show.
 *
 * lb-bot names artists; it does not carry their pictures. For an artist the
 * library holds, Navidrome already has one and it is a plain Room lookup — not
 * fetching it was the difference between this row and every other row in the app
 * looking like the same product. For one it does not hold there is no cheap
 * source (lb-bot's `/lb/meta/artist` has a Wikipedia image, but that is a
 * per-artist round trip behind MusicBrainz), so [coverArtId] is null and the
 * tile falls back to the app's ordinary no-artwork placeholder — the same one an
 * untagged library artist gets.
 */
data class DiscoverArtist(
	val mbid: String,
	val name: String,
	val owned: Boolean,
	val indexed: Boolean,
	val artistId: String,
	val coverArtId: String?
)

/**
 * Where a browse tile should open.
 *
 * A Deezer row is not "an album this app knows about": it may be a record the
 * library already holds, a release-group only MusicBrainz knows, or something
 * nothing here can identify. Those are three different destinations, and
 * returning a bare rgid could express only one of them — which is how a tile for
 * an owned album came to open a download page.
 */
sealed interface BrowseTarget {
	/** The library's own album. */
	data class Library(val albumId: String) : BrowseTarget

	/** A release-group the library does not hold. */
	data class External(val rgid: String) : BrowseTarget
}

data class DiscoverUi(
	val loading: Boolean = true,
	/** Which rows have a source that can answer at all. */
	val supported: Set<DiscoverRowId> = emptySet(),
	val fresh: List<LbFreshRelease> = emptyList(),
	val similarArtists: List<DiscoverArtist> = emptyList(),
	/** The artist the similar-artists row is seeded from — what its reason line names. */
	val similarSeed: String = "",
	val rediscovery: List<PlaylistEntity> = emptyList(),
	/** ListenBrainz's Daily/Weekly playlists, written by the Navidrome plugin. */
	val listenBrainz: List<PlaylistEntity> = emptyList(),
	/** The user's own stored recipes, straight off the hub. */
	val mixes: List<Mix> = emptyList(),
	val charts: List<LbBrowseAlbum> = emptyList(),
	val editorial: List<LbBrowseAlbum> = emptyList(),
	/**
	 * The genres both Deezer rows can be narrowed to, and the one in force.
	 *
	 * Empty when the hub is older than the route, and the chips then do not render
	 * at all — the two rows behave exactly as they did before, on Deezer's global
	 * chart. There is no country equivalent and there cannot be: Deezer's open API
	 * has no country parameter and the chart is geolocated by lb-bot's own egress
	 * address.
	 */
	val genres: List<LbDeezerGenre> = emptyList(),
	val genre: String = DEEZER_GENRE_ALL
)

/**
 * One viewmodel for the whole Discover screen, deliberately.
 *
 * `LibraryScreen` learned this the expensive way: it hoists six viewmodels into a
 * shared store owner, and its own comment records that using full
 * `AlbumListViewModel`s for the secondary rows read the entire library three
 * times in parallel. A screen of N rows is exactly that shape, so every row here
 * is a narrow top-N read and they all live in one place.
 *
 * Capability answering is also here rather than per row. Three availability
 * idioms already coexist in this tree — `RadioManager`'s login-scoped StateFlow,
 * `AudioMuseManager.clapAvailability()`'s reasoned enum and
 * `LbBotManager.ensureAvailability()`'s TTL-cached boolean — which is how every
 * surface ended up deciding for itself what "absent" looks like. Neither probe
 * is new; what is new is that one place asks both.
 */
class DiscoverViewModel(
	private val lbBotManager: LbBotManager,
	private val audioMuseManager: AudioMuseManager,
	private val hubManager: HubManager,
	private val songDao: SongDao,
	private val artistDao: ArtistDao,
	private val playlistDao: PlaylistDao,
	private val albumDao: AlbumDao,
	private val preferenceManager: PreferenceManager,
	connectivityManager: ConnectivityManager
) : ViewModel() {
	private val isOnline = connectivityManager.isOnline
	private val _state = MutableStateFlow(DiscoverUi())
	val state = _state.asStateFlow()

	init {
		load()
		// The one row that is not a snapshot. Every other row here is loaded once per
		// screen open and re-read on pull-to-refresh, but the mix list is live hub
		// state that any client can change — and a recipe created on the desktop must
		// appear here without the user knowing to pull.
		viewModelScope.launch {
			// `update`, not `value = value.copy(...)`: `load()` rebuilds the whole
			// state object from a read of `_state.value`, so a plain read-modify-write
			// here can be clobbered by a rebuild that started between the two.
			hubManager.mixes.collect { mixes ->
				_state.update { it.copy(mixes = mixes) }
			}
		}
	}

	fun load() {
		viewModelScope.launch {
			_state.value = _state.value.copy(loading = true)

			// Both playlist rows are Navidrome-only and work offline off Room, so
			// they are read before anything is gated on connectivity — and off ONE
			// DAO call, because two reads of the whole playlist table to answer two
			// filters is the mistake LibraryScreen already paid for.
			val playlists = runCatching {
				playlistDao.getAllPlaylistsByName().map { it.playlist }
			}.getOrDefault(emptyList())
			val rediscovery = playlists
				.filter { (it.name ?: "").startsWith(RediscoveryPlaylists.PREFIX) }
			val listenBrainz = playlists
				.filter { (it.name ?: "").startsWith(LISTENBRAINZ_PLAYLIST_PREFIX) }

			// The two capability probes answer different services and neither needs
			// the other, so they overlap. Everything on this screen used to be
			// strictly sequential — eight round trips end to end, several of them
			// seconds each, which is where a ~25s first open came from.
			val lbBotProbe = async { isOnline.value && lbBotManager.ensureAvailability() }
			val clapProbe = async {
				if (isOnline.value) {
					runCatching { audioMuseManager.clapAvailability().usable }.getOrDefault(false)
				} else false
			}
			val lbBotUp = lbBotProbe.await()
			val clapUsable = clapProbe.await()

			val supported = DISCOVER_ROWS.filter { row ->
				when (val capability = row.capability) {
					is DiscoverCapability.Clap -> clapUsable
					is DiscoverCapability.LbBot ->
						lbBotUp && lbBotManager.advertisesRoute(capability.route)
					DiscoverCapability.Library -> true
				}
			}.mapTo(mutableSetOf()) { it.id }

			// Three at a time, never more. The hub's proxy shares FOUR in-flight
			// slots across every lb-bot route, so fanning all of them out at once
			// would queue behind itself and starve anything else the app asks for
			// while the screen loads.
			val freshJob = async { loadFresh(supported) }
			val similarJob = async { loadSimilarArtists(supported) }
			val genresJob = async { loadGenres(supported) }

			val fresh = freshJob.await()
			val (seedName, similar) = similarJob.await()
			val genres = genresJob.await()

			// A stored genre this hub's Deezer no longer lists would silently show
			// the global chart, so fall back explicitly rather than asking for it.
			val genre = preferenceManager.deezerGenre
				.takeIf { id -> genres.any { it.id == id } }
				?: DEEZER_GENRE_ALL

			// The two Deezer rows are independent of each other and both are scoped
			// by the genre above, so they are the second (and last) parallel pair.
			val chartsJob = async {
				if (DiscoverRowId.CHARTS in supported) {
					lbBotManager.deezerChart(ROW_LIMIT, genre)?.albums.orEmpty().take(ROW_LIMIT)
				} else emptyList()
			}
			val editorialJob = async {
				if (DiscoverRowId.EDITORIAL in supported) {
					lbBotManager.deezerEditorial(ROW_LIMIT, genre)?.albums.orEmpty().take(ROW_LIMIT)
				} else emptyList()
			}
			val charts = chartsJob.await()
			val editorial = editorialJob.await()

			_state.value = DiscoverUi(
				loading = false,
				supported = supported,
				fresh = fresh,
				similarArtists = similar,
				similarSeed = seedName,
				rediscovery = rediscovery,
				listenBrainz = listenBrainz,
				// Not re-read here: the collector in `init` owns this field, and
				// rebuilding the whole state object would drop whatever it last wrote.
				mixes = _state.value.mixes,
				charts = charts,
				editorial = editorial,
				genres = genres,
				genre = genre
			)
		}
	}

	/**
	 * New releases, scoped to artists already in the library — the half with a real
	 * reason line. The site-wide half is a chart, and a chart belongs behind
	 * "See all" rather than at the top of Discover. lb-bot keeps the two ownership
	 * scopes strictly apart and so does this.
	 */
	private suspend fun loadFresh(supported: Set<DiscoverRowId>): List<LbFreshRelease> {
		if (DiscoverRowId.FRESH !in supported) return emptyList()
		return lbBotManager.freshReleases(30)?.releases
			.orEmpty()
			.filter { it.artistOwned }
			.take(ROW_LIMIT)
	}

	/**
	 * The similar-artists row, and the name its reason line has to quote.
	 *
	 * Returns both because the seed is chosen in here and the caller cannot know it
	 * otherwise — which is also why this is a function rather than two `async`s: its
	 * two lb-bot calls stay **sequential** with respect to each other. They spend the
	 * same bounded pool of in-flight proxy slots, and the second only happens at all
	 * when the hub advertises the route.
	 */
	private suspend fun loadSimilarArtists(
		supported: Set<DiscoverRowId>
	): Pair<String, List<DiscoverArtist>> {
		if (DiscoverRowId.SIMILAR_ARTISTS !in supported) return "" to emptyList()
		val seed = pickSeedArtist() ?: return "" to emptyList()

		// Two sources for one row, unioned: ListenBrainz knows what is listened to
		// together and Deezer knows what a catalogue files together, and an artist
		// absent from one is routinely in the other. ListenBrainz's ordering is kept
		// — it is the stronger signal for "fans also like" — and Deezer only extends
		// the tail.
		val fromListenBrainz = lbBotManager
			.similarArtists(seed.second, seed.first, ROW_LIMIT)
			?.artists.orEmpty()
		val fromDeezer = if (lbBotManager.advertisesRoute(ROUTE_RELATED)) {
			lbBotManager.relatedArtists(seed.second, seed.first, ROW_LIMIT)
				?.artists.orEmpty()
		} else emptyList()

		// By MBID where there is one, by name otherwise — Deezer resolves by name
		// upstream, so an unresolved row has no MBID to key on and keying everything
		// on a blank string would collapse them into one.
		val rows = (fromListenBrainz + fromDeezer)
			.distinctBy { it.mbid.ifBlank { it.name.lowercase() } }
			.take(ROW_LIMIT)
		return seed.first to withArtwork(rows)
	}

	/** The genres both Deezer rows can be narrowed to, or none on an older hub. */
	private suspend fun loadGenres(supported: Set<DiscoverRowId>): List<LbDeezerGenre> {
		val wanted = DiscoverRowId.CHARTS in supported || DiscoverRowId.EDITORIAL in supported
		if (!wanted || !lbBotManager.supportsDeezerGenres) return emptyList()
		return lbBotManager.deezerGenres()?.genres.orEmpty()
	}

	/**
	 * Narrow both Deezer rows to one genre, or back to the global chart.
	 *
	 * Reloads only those two rows rather than calling [load]: nothing else on the
	 * screen is scoped by genre, and a full reload would re-spend the lb-bot budget
	 * on Fresh and the similar-artists merge for a chip tap.
	 */
	fun setDeezerGenre(genreId: String) {
		if (_state.value.genre == genreId) return
		preferenceManager.deezerGenre = genreId
		_state.update { it.copy(genre = genreId) }
		viewModelScope.launch {
			val supported = _state.value.supported
			val charts = if (DiscoverRowId.CHARTS in supported) {
				lbBotManager.deezerChart(ROW_LIMIT, genreId)?.albums.orEmpty().take(ROW_LIMIT)
			} else emptyList()
			val editorial = if (DiscoverRowId.EDITORIAL in supported) {
				lbBotManager.deezerEditorial(ROW_LIMIT, genreId)?.albums.orEmpty().take(ROW_LIMIT)
			} else emptyList()
			// `update`, for the same reason the mixes collector uses it: a `load()`
			// may have started while these two calls were in flight.
			_state.update { it.copy(charts = charts, editorial = editorial) }
		}
	}

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
	suspend fun resolveBrowseAlbum(album: LbBrowseAlbum): BrowseTarget? {
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

	/**
	 * Attach Navidrome artwork to the artists the library holds.
	 *
	 * One `getArtistsByIds` for the whole row rather than a lookup per tile —
	 * same reasoning as `_index_indexed_artist_mbids` upstream. An artist lb-bot
	 * named but the library does not hold keeps a null cover and renders the
	 * ordinary placeholder.
	 */
	private suspend fun withArtwork(rows: List<LbSimilarArtist>): List<DiscoverArtist> {
		val ownedIds = rows.mapNotNull { it.artistId.ifBlank { null } }
		val coverById = if (ownedIds.isEmpty()) emptyMap() else runCatching {
			artistDao.getArtistsByIds(ownedIds).associate { it.artistId to it.coverArtId }
		}.getOrDefault(emptyMap())
		return rows.map { r ->
			DiscoverArtist(
				mbid = r.mbid,
				name = r.name,
				owned = r.owned,
				indexed = r.indexed,
				artistId = r.artistId,
				// Navidrome's picture first for an artist the library holds, then the
				// source's own — the Deezer-backed route supplies one, the
				// ListenBrainz merge does not. `CoverArt` takes an absolute URL as an
				// id (see `SessionManager.getCoverArtUrl`), so both go in the same
				// field and the tile needs no special case. The fallback is what
				// stops half a merged row being bare placeholders.
				coverArtId = coverById[r.artistId] ?: r.imageUrl.ifBlank { null }
			)
		}
	}

	/**
	 * One artist to seed "Fans also like" with — not several.
	 *
	 * The obvious build fans out a call per top artist and merges. Two things say
	 * don't. A merged list can only be captioned generically, and a generic
	 * caption is precisely the unattributed shelf the attribution rule exists to
	 * prevent; one seed earns an honest "Because you listen to X". And the hub's
	 * proxy cache is bounded by entries across every lb-bot route combined, so
	 * five calls per screen open churn a cache the whole surface shares.
	 *
	 * Rotates by day so the row is not the same artist forever, without changing
	 * under the reader mid-session.
	 */
	private suspend fun pickSeedArtist(): Pair<String, String?>? {
		val topIds = runCatching { songDao.getTopArtistIds(SEED_POOL).first() }
			.getOrDefault(emptyList())
		if (topIds.isEmpty()) return null
		val artists = runCatching { artistDao.getArtistsByIds(topIds) }
			.getOrDefault(emptyList())
			.filter { it.name.isNotBlank() }
		if (artists.isEmpty()) return null
		val day = (kotlin.time.Clock.System.now().toEpochMilliseconds() / 86_400_000L).toInt()
		val picked = artists[(day % artists.size + artists.size) % artists.size]
		return picked.name to picked.musicBrainzId
	}
}
