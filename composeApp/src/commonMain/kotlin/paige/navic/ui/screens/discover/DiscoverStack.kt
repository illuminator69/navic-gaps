package paige.navic.ui.screens.discover

import paige.navic.domain.manager.LbBotManager
import paige.navic.domain.manager.LbBrowseAlbum
import paige.navic.domain.manager.LbFreshRelease
import paige.navic.ui.screens.discover.viewmodels.DiscoverUi

/*
 * P3: Discover's stack of music the library does not hold, each row with the reason it is there, and
 * only reasons something in this stack can back (`DiscoverStackTest`). Not built, because nothing
 * here knows them: "Matches your Relax mixes" (no mood data for music the library lacks) and "Because
 * you play <album>" (no album-level recommendation exists).
 */

/** Why a row is in the stack. [Similar] names the one artist the similar-artists merge was seeded by. */
sealed interface DiscoverReason {
	data object Fresh : DiscoverReason
	data class Similar(val seed: String) : DiscoverReason
	data object Chart : DiscoverReason
	data object Picked : DiscoverReason
}

/** Where a row opens. A Deezer album goes through `BrowseResolver`, as the browse pages' tiles do. */
sealed interface DiscoverTarget {
	data class Release(val release: LbFreshRelease) : DiscoverTarget
	data class Artist(val mbid: String, val name: String) : DiscoverTarget
	data class Browse(val album: LbBrowseAlbum) : DiscoverTarget
}

data class DiscoverEntry(
	val key: String,
	val title: String,
	val subtitle: String,
	val reason: DiscoverReason,
	val target: DiscoverTarget,
	/** A cover URL, or an artist picture (a library cover id or a URL); null draws the placeholder. */
	val art: String?,
	val isArtist: Boolean
)

/**
 * The stack: up to 3 fresh releases by artists the library has (the release itself not owned), up to 3
 * similar artists the library does not hold, up to 2 unowned chart albums and up to 2 unowned
 * editorial albums, dealt round-robin in that order, at most [limit]. A source with nothing gives
 * nothing and leaves no hole. Similar artists need their seed: without one there is no honest reason
 * line, and the rows are dropped rather than worded generically.
 */
fun discoverStack(ui: DiscoverUi, limit: Int = 10): List<DiscoverEntry> {
	val fresh = ui.fresh
		.filter { it.artistOwned && !it.releaseOwned && it.releaseGroupMbid.isNotBlank() }
		.distinctBy { it.releaseGroupMbid }
		.take(3)
		.map {
			DiscoverEntry(
				key = "fresh:${it.releaseGroupMbid}",
				title = it.releaseName,
				subtitle = it.artist,
				reason = DiscoverReason.Fresh,
				target = DiscoverTarget.Release(it),
				art = it.coverUrl.ifBlank { LbBotManager.caaCoverUrl(it.releaseGroupMbid) },
				isArtist = false
			)
		}
	val similar = if (ui.similarSeed.isBlank()) emptyList() else ui.similarArtists
		.filter { !it.owned && it.artistId.isBlank() && it.mbid.isNotBlank() }
		.distinctBy { it.mbid }
		.take(3)
		.map {
			DiscoverEntry(
				key = "similar:${it.mbid}",
				title = it.name,
				subtitle = "",
				reason = DiscoverReason.Similar(ui.similarSeed),
				target = DiscoverTarget.Artist(it.mbid, it.name),
				art = it.coverArtId,
				isArtist = true
			)
		}
	fun browse(albums: List<LbBrowseAlbum>, prefix: String, reason: DiscoverReason) = albums
		.filter { !it.releaseOwned && it.title.isNotBlank() }
		.distinctBy { it.deezerId.ifBlank { it.rgid.ifBlank { it.title + it.artist } } }
		.take(2)
		.map {
			DiscoverEntry(
				key = "$prefix:" + it.deezerId.ifBlank { it.rgid.ifBlank { it.title + "\u0000" + it.artist } },
				title = it.title,
				subtitle = it.artist,
				reason = reason,
				target = DiscoverTarget.Browse(it),
				art = it.coverUrl.ifBlank { if (it.rgid.isNotBlank()) LbBotManager.caaCoverUrl(it.rgid) else null },
				isArtist = false
			)
		}
	val sources = listOf(
		fresh,
		similar,
		browse(ui.charts, "chart", DiscoverReason.Chart),
		browse(ui.editorial, "picked", DiscoverReason.Picked)
	)
	val out = ArrayList<DiscoverEntry>()
	var round = 0
	while (out.size < limit && sources.any { round < it.size }) {
		for (source in sources) {
			if (out.size >= limit) break
			source.getOrNull(round)?.let { entry -> if (out.none { it.key == entry.key }) out += entry }
		}
		round++
	}
	return out
}
