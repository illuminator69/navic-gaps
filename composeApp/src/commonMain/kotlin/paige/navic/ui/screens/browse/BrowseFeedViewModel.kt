package paige.navic.ui.screens.browse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import paige.navic.domain.manager.BrowseResolver
import paige.navic.domain.manager.LbBotManager
import paige.navic.domain.manager.LbBrowseAlbum
import paige.navic.domain.manager.LbBrowseFeed
import paige.navic.domain.manager.LbCachePolicy
import paige.navic.domain.manager.LbDeezerGenre
import paige.navic.domain.manager.PreferenceManager
import paige.navic.ui.screens.discover.viewmodels.BrowseTarget
import paige.navic.ui.screens.discover.viewmodels.DEEZER_GENRE_ALL
import kotlinx.coroutines.flow.Flow

/** A tile's identity: Deezer's id, else the release group, else its words. A feed may repeat one. */
fun LbBrowseAlbum.feedKey(): String = deezerId.ifBlank { rgid.ifBlank { title + "\u0000" + artist } }

/** The two Deezer pages Search's browse cards open (P3). */
enum class BrowseKind { CHARTS, PICKS }

/** How many albums a page asks for: a page, not Discover's glance of twenty. */
const val BROWSE_FEED_LIMIT = 50

data class BrowseFeedUi(
	/** Empty when the hub offers no genre list; the chips are then not drawn. */
	val genres: List<LbDeezerGenre> = emptyList(),
	val genre: String = DEEZER_GENRE_ALL,
	val albums: List<LbBrowseAlbum> = emptyList(),
	/** A read for [genre] is out. What is shown stays until it lands. */
	val loading: Boolean = true,
	/** Nothing was cached and nothing came back: said, with Retry. */
	val failed: Boolean = false,
	/** lb-bot answered for [genre] with no albums at all. */
	val empty: Boolean = false
)

/**
 * Charts or Editors' picks: Deezer's rows through lb-bot, narrowed by Deezer's genres, cache first
 * then the network (`LbBotManager.cachedGet`), as Discover's rows read them. The genre is the one
 * Discover's chips keep (`PreferenceManager.deezerGenre`), so the two places agree. A chip change
 * keeps the tiles shown until the new genre's answer lands; an answer for a genre no longer chosen is
 * dropped.
 */
class BrowseFeedViewModel(
	private val kind: BrowseKind,
	private val lbBotManager: LbBotManager,
	private val browseResolver: BrowseResolver,
	private val preferenceManager: PreferenceManager
) : ViewModel() {
	private val _state = MutableStateFlow(BrowseFeedUi(genre = preferenceManager.deezerGenre))
	val state: StateFlow<BrowseFeedUi> = _state.asStateFlow()

	private var feedJob: Job? = null

	init {
		viewModelScope.launch {
			if (lbBotManager.supportsDeezerGenres) {
				lbBotManager.deezerGenres().collect { answer ->
					val genres = answer?.genres.orEmpty()
					if (genres.isEmpty()) return@collect
					// A stored genre this hub's Deezer no longer lists would silently show the global
					// chart: fall back to it explicitly, as Discover does.
					val genre = _state.value.genre.takeIf { id -> genres.any { it.id == id } } ?: DEEZER_GENRE_ALL
					val changed = genre != _state.value.genre
					_state.update { it.copy(genres = genres, genre = genre) }
					if (changed) load()
				}
			}
		}
		load()
	}

	fun setGenre(id: String) {
		if (_state.value.genre == id) return
		preferenceManager.deezerGenre = id
		_state.update { it.copy(genre = id) }
		load()
	}

	fun retry() = load(LbCachePolicy.REFRESH)

	private fun load(policy: LbCachePolicy = LbCachePolicy.NORMAL) {
		val genre = _state.value.genre
		feedJob?.cancel()
		_state.update { it.copy(loading = true, failed = false, empty = false) }
		feedJob = viewModelScope.launch {
			var answered = false
			feed(genre, policy).collect { feed ->
				if (_state.value.genre != genre) return@collect
				if (feed == null) return@collect
				answered = true
				val albums = feed.albums.distinctBy { it.feedKey() }.take(BROWSE_FEED_LIMIT)
				_state.update { it.copy(albums = albums, empty = albums.isEmpty()) }
			}
			if (_state.value.genre == genre) {
				_state.update {
					it.copy(
						loading = false,
						failed = !answered,
						// A failed read keeps nothing from another genre on screen as if it were this one's.
						albums = if (answered) it.albums else emptyList()
					)
				}
			}
		}
	}

	private fun feed(genre: String, policy: LbCachePolicy): Flow<LbBrowseFeed?> = when (kind) {
		BrowseKind.CHARTS -> lbBotManager.deezerChart(BROWSE_FEED_LIMIT, genre, policy)
		BrowseKind.PICKS -> lbBotManager.deezerEditorial(BROWSE_FEED_LIMIT, genre, policy)
	}

	suspend fun resolve(album: LbBrowseAlbum): BrowseTarget? = browseResolver.resolve(album)
}
