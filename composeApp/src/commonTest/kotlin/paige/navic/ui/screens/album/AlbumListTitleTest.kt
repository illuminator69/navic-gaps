package paige.navic.ui.screens.album

import paige.navic.domain.models.DomainAlbumListType
import kotlin.test.Test
import kotlin.test.assertEquals

/** B9: the album list's bar names the list Home's shelf opened ([albumListTitle]). */
class AlbumListTitleTest {

	@Test
	fun eachShelfsListIsNamedLikeTheShelf() {
		assertEquals(AlbumListTitle.RecentlyPlayed, albumListTitle(DomainAlbumListType.Recent))
		assertEquals(AlbumListTitle.FrequentlyPlayed, albumListTitle(DomainAlbumListType.Frequent))
		assertEquals(AlbumListTitle.RecentlyAdded, albumListTitle(DomainAlbumListType.Newest))
	}

	@Test
	fun everyOtherListIsAlbums() {
		assertEquals(AlbumListTitle.Albums, albumListTitle(DomainAlbumListType.Random))
		assertEquals(AlbumListTitle.Albums, albumListTitle(DomainAlbumListType.AlphabeticalByName))
		assertEquals(AlbumListTitle.Albums, albumListTitle(DomainAlbumListType.ByGenre("Rock")))
	}
}
