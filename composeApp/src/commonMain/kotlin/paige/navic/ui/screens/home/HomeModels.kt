package paige.navic.ui.screens.home

import paige.navic.ui.screens.fresh.viewmodels.FreshFilters
import paige.navic.ui.screens.fresh.viewmodels.FreshScope
import paige.navic.ui.screens.fresh.viewmodels.FreshSort
import paige.navic.ui.screens.fresh.viewmodels.FreshType
import paige.navic.ui.screens.fresh.viewmodels.freshOrdered
import androidx.compose.runtime.Immutable
import androidx.navigation3.runtime.NavKey
import paige.navic.data.database.entities.SavedQueueEntity
import paige.navic.domain.manager.LbFreshFeed
import paige.navic.ui.screens.fresh.freshAlbumTarget
import paige.navic.domain.models.Mix
import paige.navic.domain.models.YOUR_MIX_ID

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
 * The Fresh shelf: the first releases the Fresh page shows under its stored scope, type and sort
 * ([filters]; `freshOrdered`), one tile per release group, at most [limit]. It was the artists the
 * library holds in lb-bot's own order, whatever the page was set to, so the two showed different
 * releases (P5 round 3, the owner's note). A row with neither a release group nor a library album
 * has nowhere to go and is left out. A null feed (nothing cached, nothing answered) is an empty shelf.
 */
internal fun freshShelf(
	feed: LbFreshFeed?,
	limit: Int = 12,
	filters: FreshFilters = FreshFilters(FreshScope.YOURS, FreshSort.DATE, FreshType.ALL)
): List<FreshTile> {
	val rows = feed?.releases ?: return emptyList()
	return freshOrdered(rows, filters.scope, filters.sort, filters.type).asSequence()
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
internal fun freshShelfAfterRead(
	current: List<FreshTile>,
	feed: LbFreshFeed?,
	limit: Int = 12,
	filters: FreshFilters = FreshFilters(FreshScope.YOURS, FreshSort.DATE, FreshType.ALL)
): List<FreshTile> =
	if (feed != null || current.isEmpty()) freshShelf(feed, limit, filters) else current

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
enum class HomeSection { ContinueListening, RecentlyAdded, Fresh, RecentlyPlayed, FrequentlyPlayed, YourMixes }

/** How many albums Recently played and Frequently played each read (P6). */
const val HOME_ALBUMS_PER_SHELF = 12

/** The hub keeps at most this many mixes (its `MIXES_MAX`); Your mixes shows every one of them. */
const val MIXES_MAX = 30

/**
 * Which sections the page draws (P6, the owner's ruling of 2026-10-21: Home is populated). A section
 * with nothing to say is absent and leaves no gap: no pill without saved queues, no shelf without
 * albums, Fresh only where the lb-bot layer is on AND it has tiles, Recently played and Frequently
 * played (Navidrome's own lists) only with albums, Your mixes only with a tile to show.
 */
fun homeSections(
	queueCount: Int,
	recentCount: Int,
	freshAvailable: Boolean,
	freshCount: Int,
	playedCount: Int,
	frequentCount: Int,
	mixTileCount: Int
): List<HomeSection> = buildList {
	if (queueCount > 0) add(HomeSection.ContinueListening)
	if (recentCount > 0) add(HomeSection.RecentlyAdded)
	if (freshAvailable && freshCount > 0) add(HomeSection.Fresh)
	if (playedCount > 0) add(HomeSection.RecentlyPlayed)
	if (frequentCount > 0) add(HomeSection.FrequentlyPlayed)
	if (mixTileCount > 0) add(HomeSection.YourMixes)
}

/**
 * One tile of Your mixes: a hub mix, or Your mix itself ([yourMix], [mix] null), which is not stored
 * and is named by `YOUR_MIX_ID`. A tile OPENS the mix's page (P5 round 2), it never starts it.
 */
@Immutable
data class MixTile(val mix: Mix?, val yourMix: Boolean) {
	val key: String get() = if (yourMix) YOUR_MIX_ID else mix?.id.orEmpty()
}

/**
 * Your mixes: Your mix first while the station engine is there, then every mix the hub holds (at most
 * [MIXES_MAX]) by when it was last played, a mix never played by when it last changed, newest first. A
 * tie keeps the hub's order. Every kind is a tile, one this build cannot play included: its page says so.
 */
fun mixTiles(mixes: List<Mix>, yourMixAvailable: Boolean): List<MixTile> = buildList {
	if (yourMixAvailable) add(MixTile(null, yourMix = true))
	mixes.asSequence()
		.filter { it.id.isNotBlank() && it.id != YOUR_MIX_ID }
		.sortedByDescending { if (it.lastPlayedAt > 0L) it.lastPlayedAt else it.updatedAt }
		.take(MIXES_MAX)
		.forEach { add(MixTile(it, yourMix = false)) }
}

/**
 * Whether the Continue listening pill starts open. With nothing playing the saved queues are what
 * there is to do, so their cards show (board 1f); with something playing it is a pill. With no queues
 * there is no pill at all.
 */
fun continueStartsExpanded(nothingPlaying: Boolean, queueCount: Int): Boolean =
	nothingPlaying && queueCount > 0
