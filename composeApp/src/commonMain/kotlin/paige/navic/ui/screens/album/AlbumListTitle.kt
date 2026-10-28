package paige.navic.ui.screens.album

import navic.composeapp.generated.resources.Res
import navic.composeapp.generated.resources.home_frequently_played
import navic.composeapp.generated.resources.home_recently_added
import navic.composeapp.generated.resources.home_recently_played
import navic.composeapp.generated.resources.title_albums
import org.jetbrains.compose.resources.StringResource
import paige.navic.domain.models.DomainAlbumListType

/** What the album list's bar is called: Home's shelf name for the lists a shelf opens, else Albums. */
enum class AlbumListTitle(val label: StringResource) {
	RecentlyPlayed(Res.string.home_recently_played),
	FrequentlyPlayed(Res.string.home_frequently_played),
	RecentlyAdded(Res.string.home_recently_added),
	Albums(Res.string.title_albums)
}

/**
 * B9 (audit L4b-12): See all under a Home shelf opened a page called "Albums". The bar follows the
 * list type, so it changes with the sort control too.
 */
fun albumListTitle(type: DomainAlbumListType): AlbumListTitle = when (type) {
	DomainAlbumListType.Recent -> AlbumListTitle.RecentlyPlayed
	DomainAlbumListType.Frequent -> AlbumListTitle.FrequentlyPlayed
	DomainAlbumListType.Newest -> AlbumListTitle.RecentlyAdded
	else -> AlbumListTitle.Albums
}
