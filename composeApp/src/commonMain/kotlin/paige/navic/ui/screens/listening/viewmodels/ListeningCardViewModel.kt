package paige.navic.ui.screens.listening.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.datetime.TimeZone
import paige.navic.data.database.dao.AlbumDao
import paige.navic.data.database.dao.ArtistDao
import paige.navic.domain.manager.LISTENING_CARD_PERIOD
import paige.navic.domain.manager.LbArt
import paige.navic.domain.manager.LbBotManager
import paige.navic.domain.manager.LbListeningSummary
import paige.navic.domain.manager.ListeningPeriod
import paige.navic.domain.manager.albumCardArt
import paige.navic.domain.manager.libraryArtistArt
import paige.navic.domain.manager.listeningHistoryHeld

/** One page of the home's Stats card from the history: what was played most, its picture, its plays. */
data class ListeningCardItem(
	val name: String,
	val art: LbArt,
	val plays: Int
)

/** The home's Stats card from lb-bot's history (Q-103): the period's top artist, album and track. */
data class ListeningCardUi(
	val period: ListeningPeriod,
	val totalPlays: Int,
	val artist: ListeningCardItem?,
	val album: ListeningCardItem?,
	val track: ListeningCardItem?
)

/**
 * What the library home's Stats card shows when lb-bot holds a listening history
 * (`listeningHistoryHeld`); null leaves the card upstream's, on Navidrome's play counts.
 *
 * One read: the summary Stats opens on ([LISTENING_CARD_PERIOD]), through the same
 * stale-while-revalidate cache, so the card paints from the last body (offline too) and asks
 * lb-bot at most once per cache period. No status read: that one is never cached, and the home
 * is visited constantly. Pictures come from what Room holds, as on the page (`albumCardArt`,
 * `libraryArtistArt`); a track is drawn with its album's.
 */
class ListeningCardViewModel(
	private val lbBotManager: LbBotManager,
	private val albumDao: AlbumDao,
	private val artistDao: ArtistDao
) : ViewModel() {
	@OptIn(ExperimentalCoroutinesApi::class)
	val card: StateFlow<ListeningCardUi?> = lbBotManager.listeningSupported
		.flatMapLatest { supported ->
			if (!supported || !lbBotManager.isConfigured) {
				flowOf(null)
			} else {
				// A read that failed is not "no history": the last body stays on the card.
				lbBotManager.listeningSummary(LISTENING_CARD_PERIOD, TimeZone.currentSystemDefault().id)
					.filterNotNull()
			}
		}
		.map { summary -> summary?.takeIf { listeningHistoryHeld(it) }?.let { card(it) } }
		.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

	private suspend fun card(summary: LbListeningSummary): ListeningCardUi {
		val artist = summary.topArtists.firstOrNull()?.let { top ->
			val held = top.ndArtistId.takeIf { it.isNotBlank() }
				?.let { id -> runCatching { artistDao.getArtistById(id) }.getOrNull() }
				?.let { mapOf(it.artistId to it.coverArtId) }
				.orEmpty()
			ListeningCardItem(top.name, libraryArtistArt(top.ndArtistId, held), top.plays)
		}
		val album = summary.topAlbums.firstOrNull()?.let { top ->
			ListeningCardItem(top.title, albumCardArt(top.ndAlbumId, top.rgid, albumsHeld(top.ndAlbumId)), top.plays)
		}
		val track = summary.topTracks.firstOrNull()?.let { top ->
			ListeningCardItem(top.title, albumCardArt(top.ndAlbumId, top.rgid, albumsHeld(top.ndAlbumId)), top.plays)
		}
		return ListeningCardUi(
			period = LISTENING_CARD_PERIOD,
			totalPlays = summary.totals.plays,
			artist = artist,
			album = album,
			track = track
		)
	}

	/** The album as Room holds it, as the one-entry map the art helpers read. */
	private suspend fun albumsHeld(ndAlbumId: String): Map<String, String?> =
		ndAlbumId.takeIf { it.isNotBlank() }
			?.let { id -> runCatching { albumDao.getAlbumById(id) }.getOrNull() }
			?.let { mapOf(it.album.albumId to it.album.coverArtId) }
			.orEmpty()
}
