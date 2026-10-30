package paige.navic.domain.manager

import paige.navic.data.database.entities.DownloadEntity
import paige.navic.data.database.entities.DownloadStatus
import paige.navic.data.database.entities.bySongId
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Row 8 (adapted from upstream 597be8ab, dca900ab): download status by song id and per collection. */
class DownloadStatusLookupTest {
	private fun row(id: String, status: DownloadStatus) = DownloadEntity(songId = id, status = status)

	@Test
	fun aCollectionReadsFromItsOwnRows() {
		assertEquals(DownloadStatus.NOT_DOWNLOADED, collectionDownloadStatus(emptyList(), 3))
		assertEquals(DownloadStatus.DOWNLOADING, collectionDownloadStatus(listOf(row("a", DownloadStatus.QUEUED)), 3))
		assertEquals(
			DownloadStatus.DOWNLOADING,
			collectionDownloadStatus(listOf(row("a", DownloadStatus.FAILED), row("b", DownloadStatus.DOWNLOADING)), 2)
		)
		assertEquals(DownloadStatus.FAILED, collectionDownloadStatus(listOf(row("a", DownloadStatus.FAILED), row("b", DownloadStatus.DOWNLOADED)), 2))
		assertEquals(DownloadStatus.NOT_DOWNLOADED, collectionDownloadStatus(listOf(row("a", DownloadStatus.DOWNLOADED)), 2))
		assertEquals(DownloadStatus.DOWNLOADED, collectionDownloadStatus(listOf(row("a", DownloadStatus.DOWNLOADED), row("b", DownloadStatus.DOWNLOADED)), 2))
	}

	@Test
	fun aRowFindsItsDownloadById() {
		val map = listOf(row("a", DownloadStatus.DOWNLOADED), row("b", DownloadStatus.QUEUED)).bySongId()
		assertEquals(DownloadStatus.QUEUED, map["b"]?.status)
		assertEquals(null, map["c"])
	}

	@Test
	fun noScreenSearchesTheWholeTablePerRow() {
		val offenders = File("src/commonMain/kotlin").walkTopDown()
			.filter { it.isFile && it.extension == "kt" }
			.filter { "allDownloads.find" in it.readText() || "allDownloads.firstOrNull" in it.readText() }
			.map { it.name }.toList()
		assertEquals(emptyList(), offenders)
	}

	@Test
	fun theCollectionStatusIsADaoFlowNotTheWholeTable() {
		val text = File("src/commonMain/kotlin/paige/navic/domain/manager/DownloadManager.kt").readText()
		val fn = text.substringAfter("fun getCollectionDownloadStatus(").substringBefore("fun clearAllDownloads()")
		assertTrue("downloadDao.getDownloadsForSongs(" in fn && "storedDownloads" !in fn, fn)
	}
}
