package paige.navic.ui.screens.home

import androidx.compose.runtime.Immutable
import androidx.navigation3.runtime.NavKey
import paige.navic.data.database.entities.SavedQueueEntity
import paige.navic.domain.manager.LbFreshFeed
import paige.navic.ui.screens.fresh.freshAlbumTarget

/*
 * navi-connect P1: what Home shows, as plain functions (`HomeModelsTest`). The page and its ViewModel
 * only gather the inputs and draw the answers.
 */

/**
 * One tile of Home's Fresh shelf: a new release by an artist the library holds.
 *
 * [owned] is the RELEASE's flag (lb-bot's index says this release group is on disk), the same rule the
 * Fresh page uses, and NOT "in my library": the artist being owned is why the tile is here at all, and
 * says nothing about this album. The tap target follows [albumId] (see [freshTarget]).
 */
@Immutable
data class FreshTile(
	val rgid: String,
	val title: String,
	val artist: String,
	val coverUrl: String?,
	val owned: Boolean,
	val albumId: String?,
	val artistMbid: String?,
	val artistId: String?
) {
	/** What the tile is keyed by (arrival, list identity): its release group, else its library album. */
	val key: String get() = rgid.ifBlank { albumId.orEmpty() }
}

/**
 * The Fresh shelf: lb-bot's feed narrowed to artists the library holds, in lb-bot's own order, one tile
 * per release group, at most [limit]. A row with neither a release group nor a library album has
 * nowhere to go and is left out. A null feed (nothing cached, nothing answered) is an empty shelf.
 */
fun freshShelf(feed: LbFreshFeed?, limit: Int = 12): List<FreshTile> {
	val rows = feed?.releases ?: return emptyList()
	return rows.asSequence()
		.filter { it.artistOwned }
		.filter { it.releaseGroupMbid.isNotBlank() || it.releaseAlbumId.isNotBlank() }
		.distinctBy { it.releaseGroupMbid.ifBlank { it.releaseAlbumId } }
		.take(limit)
		.map {
			FreshTile(
				rgid = it.releaseGroupMbid,
				title = it.releaseName,
				artist = it.artist,
				coverUrl = it.coverUrl.takeIf { url -> url.isNotBlank() },
				owned = it.releaseOwned,
				albumId = it.releaseAlbumId.takeIf { id -> id.isNotBlank() },
				artistMbid = it.artistMbids.firstOrNull()?.takeIf { id -> id.isNotBlank() },
				artistId = it.artistId.takeIf { id -> id.isNotBlank() }
			)
		}
		.toList()
}

/**
 * The shelf after a read. An answer replaces it (even one with nothing for the library in it: lb-bot
 * did answer). `null` is "nothing cached and nothing came back", which may only stand in for an empty
 * shelf: a failed read never takes away the shelf that is on screen.
 */
fun freshShelfAfterRead(current: List<FreshTile>, feed: LbFreshFeed?, limit: Int = 12): List<FreshTile> =
	if (feed != null || current.isEmpty()) freshShelf(feed, limit) else current

/**
 * Where a tapped Fresh tile goes: the Fresh page's own rule (`freshAlbumTarget`), not a copy of it.
 * Null only for a tile [freshShelf] would never have made.
 */
fun freshTarget(tile: FreshTile): NavKey? = freshAlbumTarget(
	albumId = tile.albumId.orEmpty(),
	rgid = tile.rgid,
	artistMbid = tile.artistMbid.orEmpty(),
	artistName = tile.artist,
	title = tile.title,
	artistId = tile.artistId.orEmpty()
)

/**
 * The saved queues Home offers to resume: the one playing now first (this is one shared history on two
 * clients, and the other client's carousel pins it first), then the rest, newest first, at most
 * [limit]. [playingId] is the queue's id as this device or the hub session names it, or null.
 */
fun continueListening(
	queues: List<SavedQueueEntity>,
	playingId: String?,
	limit: Int = 6
): List<SavedQueueEntity> = queues
	.sortedWith(
		compareByDescending<SavedQueueEntity> { playingId != null && it.id == playingId }
			.thenByDescending { it.updatedAt }
	)
	.take(limit)

/** Home's sections, in the order the page draws them. */
enum class HomeSection { ContinueListening, RecentlyAdded, Fresh, YourMix }

/**
 * Which sections the page draws. A section with nothing to say is absent and leaves no gap: no pill
 * without saved queues, no shelf without albums, Fresh only where the lb-bot layer is on AND it has
 * tiles, Your mix only with the station engine.
 */
fun homeSections(
	queueCount: Int,
	recentCount: Int,
	freshAvailable: Boolean,
	freshCount: Int,
	yourMixAvailable: Boolean
): List<HomeSection> = buildList {
	if (queueCount > 0) add(HomeSection.ContinueListening)
	if (recentCount > 0) add(HomeSection.RecentlyAdded)
	if (freshAvailable && freshCount > 0) add(HomeSection.Fresh)
	if (yourMixAvailable) add(HomeSection.YourMix)
}

/**
 * Whether the Continue listening pill starts open. With nothing playing the saved queues are what
 * there is to do, so their cards show (board 1f); with something playing it is a pill. With no queues
 * there is no pill at all.
 */
fun continueStartsExpanded(nothingPlaying: Boolean, queueCount: Int): Boolean =
	nothingPlaying && queueCount > 0
