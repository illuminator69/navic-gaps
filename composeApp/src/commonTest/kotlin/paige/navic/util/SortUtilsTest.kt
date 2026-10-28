package paige.navic.util

import paige.navic.domain.models.DomainAlbumListType
import kotlin.test.Test
import kotlin.test.assertTrue

/** The album lists' SQL ([toSqlQuery]). */
class SortUtilsTest {

	@Test
	fun recentlyPlayedListsOnlyPlayedAlbumsNewestPlayFirst() {
		// B9 (W-11): See all on Home's Recently played listed the whole library, never-played last.
		val sql = DomainAlbumListType.Recent.toSqlQuery().sql
		assertTrue("lastPlayedAt IS NOT NULL" in sql, sql)
		assertTrue("ORDER BY lastPlayedAt DESC" in sql, sql)
	}

	@Test
	fun frequentlyPlayedStillListsOnlyPlayedAlbums() {
		assertTrue("playCount != 0" in DomainAlbumListType.Frequent.toSqlQuery().sql)
	}
}
