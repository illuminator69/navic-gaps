package paige.navic.domain.manager

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Row 7 (the idea of upstream 45e24afa): a download puts its covers on disk once per run of the
 * queue, under the bare id (B-038), without decoding them. Source guards: the behaviour needs
 * Coil and a server, and is watched on the AVD.
 */
class DownloadCoverCacheTest {
	private val text = File("src/commonMain/kotlin/paige/navic/domain/manager/DownloadManager.kt").readText()
	private val cache = text.substringAfter("private suspend fun cacheCoverArt(").substringBefore("private suspend fun forgetCoversOfThisRun")

	@Test
	fun aCoverIsNeverDecodedForADownload() {
		assertTrue("imageLoader.execute" !in cache && ".size(Size.ORIGINAL)" !in text, cache)
	}

	@Test
	fun aCoverIsAskedForOncePerRunAndNotWhenOnDisk() {
		assertTrue("coversThisRun.add(coverId)" in cache, cache)
		assertTrue("diskCache.openSnapshot(coverId)" in cache, cache)
		val drained = text.substringAfter("if (completedSongsDownloaded >= totalSongsToDownload) {").substringBefore("} else {")
		assertTrue("forgetCoversOfThisRun()" in drained, drained)
	}

	@Test
	fun theDiskEntryIsTheBareId() {
		assertTrue("diskCache.openEditor(coverId)" in cache, cache)
	}
}
