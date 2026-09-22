package paige.navic.ui.screens.discover.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import paige.navic.data.database.dao.ArtistDao
import paige.navic.data.database.dao.PlaylistDao
import paige.navic.data.database.dao.SongDao
import paige.navic.data.database.entities.PlaylistEntity
import paige.navic.domain.manager.AudioMuseManager
import paige.navic.domain.manager.ConnectivityManager
import paige.navic.domain.manager.LbBotManager
import paige.navic.domain.manager.LbFreshRelease
import paige.navic.domain.manager.LbSimilarArtist
import paige.navic.domain.manager.RediscoveryPlaylists
import paige.navic.ui.screens.discover.LISTENBRAINZ_PLAYLIST_PREFIX
import paige.navic.ui.screens.discover.DISCOVER_ROWS
import paige.navic.ui.screens.discover.DiscoverCapability
import paige.navic.ui.screens.discover.DiscoverRowId

/** How many of the most-played artists are eligible to seed the "Fans also like" row. */
private const val SEED_POOL = 12

/** Row content is a glance, not a list screen. */
private const val ROW_LIMIT = 20

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
	val listenBrainz: List<PlaylistEntity> = emptyList()
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
	private val songDao: SongDao,
	private val artistDao: ArtistDao,
	private val playlistDao: PlaylistDao,
	connectivityManager: ConnectivityManager
) : ViewModel() {
	private val isOnline = connectivityManager.isOnline
	private val _state = MutableStateFlow(DiscoverUi())
	val state = _state.asStateFlow()

	init { load() }

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

			val lbBotUp = isOnline.value && lbBotManager.ensureAvailability()
			val clapUsable = if (isOnline.value) {
				runCatching { audioMuseManager.clapAvailability().usable }.getOrDefault(false)
			} else false

			val supported = DISCOVER_ROWS.filter { row ->
				when (val capability = row.capability) {
					is DiscoverCapability.Clap -> clapUsable
					is DiscoverCapability.LbBot ->
						lbBotUp && lbBotManager.advertisesRoute(capability.route)
					DiscoverCapability.Library -> true
				}
			}.mapTo(mutableSetOf()) { it.id }

			val fresh = if (DiscoverRowId.FRESH in supported) {
				// Scoped to artists already in the library — the half with a real
				// reason line. The site-wide half is a chart, and a chart belongs
				// behind "See all" rather than at the top of Discover. lb-bot
				// keeps the two ownership scopes strictly apart and so does this.
				lbBotManager.freshReleases(30)?.releases
					.orEmpty()
					.filter { it.artistOwned }
					.take(ROW_LIMIT)
			} else emptyList()

			var seedName = ""
			val similar = if (DiscoverRowId.SIMILAR_ARTISTS in supported) {
				val seed = pickSeedArtist()
				seedName = seed?.first.orEmpty()
				val rows = if (seed == null) emptyList()
				else lbBotManager.similarArtists(seed.second, seed.first, ROW_LIMIT)
					?.artists.orEmpty()
				withArtwork(rows)
			} else emptyList()

			_state.value = DiscoverUi(
				loading = false,
				supported = supported,
				fresh = fresh,
				similarArtists = similar,
				similarSeed = seedName,
				rediscovery = rediscovery,
				listenBrainz = listenBrainz
			)
		}
	}

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
				coverArtId = coverById[r.artistId]
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
