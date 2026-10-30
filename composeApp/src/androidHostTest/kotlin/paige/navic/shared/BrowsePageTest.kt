package paige.navic.shared

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Android Auto's Albums node honours the page it is asked for, with no songs read (row 4a). */
class BrowsePageTest {
	@Test
	fun aPageIsALimitAndAnOffset() {
		assertEquals(50 to 0, browsePage(0, 50))
		assertEquals(50 to 100, browsePage(2, 50))
		assertEquals(Int.MAX_VALUE to 0, browsePage(0, Int.MAX_VALUE))
	}

	@Test
	fun aPageThatHoldsNothingIsNull() {
		assertNull(browsePage(-1, 50))
		assertNull(browsePage(0, 0))
		assertNull(browsePage(2, Int.MAX_VALUE))
	}

	@Test
	fun theAlbumsNodeReadsRowsByPage() {
		val text = File("src/androidMain/kotlin/paige/navic/shared/MediaPlayer.android.kt").readText()
		val node = text.substringAfter("parentId == MEDIA_ALBUMS_ID ->").substringBefore("parentId == MEDIA_ARTISTS_ID")
		assertTrue("getAlbumRowsPage" in node, node)
		assertTrue("getAllAlbumsList" !in node, node)
	}
}
