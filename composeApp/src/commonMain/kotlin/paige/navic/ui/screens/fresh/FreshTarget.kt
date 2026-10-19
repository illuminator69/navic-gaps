package paige.navic.ui.screens.fresh

import androidx.navigation3.runtime.NavKey
import paige.navic.ui.navigation.Screen

/**
 * Where a tapped Fresh release goes: ONE rule, for the Fresh page's tiles and Home's shelf alike.
 *
 * An album the library holds ([albumId], the Navidrome id lb-bot reports for an owned release) opens
 * the library album. Everything else goes to the external album page keyed on the release group,
 * including a release by an artist the library does have, because owning the artist says nothing
 * about owning this album. [artistId] is the Navidrome artist id when the artist is in the library
 * (blank otherwise), so "View artist" there opens the real page. Null when there is neither an album
 * nor a release group: never a destination that cannot load.
 *
 * A tile badged "in library" used to open a *download* page, because the external page's redirect
 * reads `navidromeAlbumIds` off the index and an album lb-bot filled itself has none; the album id is
 * the only handle that can open it.
 */
internal fun freshAlbumTarget(
	albumId: String,
	rgid: String,
	artistMbid: String,
	artistName: String,
	title: String,
	artistId: String
): NavKey? {
	if (albumId.isNotBlank()) return Screen.CollectionDetail(albumId, "")
	if (rgid.isBlank()) return null
	return Screen.ExternalAlbum(
		rgid = rgid,
		artistMbid = artistMbid,
		artistName = artistName,
		title = title,
		artistId = artistId
	)
}
