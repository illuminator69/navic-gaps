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
import paige.navic.ui.screens.discover.DISCOVER_ROWS
import paige.navic.ui.screens.discover.DiscoverCapability
import paige.navic.ui.screens.discover.DiscoverRowId

/** How many of the most-played artists are eligible to seed the "Fans also like" row. */
private const val SEED_POOL = 12

/** Row content is a glance, not a list screen. */
private const val ROW_LIMIT = 20

data class DiscoverUi(
	val loading: Boolean = true,
	/** Which rows have a source that can answer at all. */
	val supported: Set<DiscoverRowId> = emptySet(),
	val fresh: List<LbFreshRelease> = emptyList(),
	val similarArtists: List<LbSimilarArtist> = emptyList(),
	/** The artist the similar-artists row is seeded from — what its reason line names. */
	val similarSeed: String = "",
	val rediscovery: List<PlaylistEntity> = emptyList()
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

			// Rediscovery is Navidrome-only and works offline off Room, so it is
			// read before anything is gated on connectivity.
			val rediscovery = runCatching {
				playlistDao.getAllPlaylistsByName()
					.map { it.playlist }
					.filter { (it.name ?: "").startsWith(RediscoveryPlaylists.PREFIX) }
			}.getOrDefault(emptyList())

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
				if (seed == null) emptyList()
				else lbBotManager.similarArtists(seed.second, seed.first, ROW_LIMIT)
					?.artists.orEmpty()
			} else emptyList()

			_state.value = DiscoverUi(
				loading = false,
				supported = supported,
				fresh = fresh,
				similarArtists = similar,
				similarSeed = seedName,
				rediscovery = rediscovery
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
