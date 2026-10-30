package paige.navic.di

import paige.navic.exoplayer.NOTIFICATION_ARTWORK_MAX_PX
import paige.navic.exoplayer.notificationArtworkKeys
import paige.navic.exoplayer.notificationArtworkUrl
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Row 9 (adapted from upstream 6dcfa9f4 … 24e531e9): Subsonic covers by size bucket. */
class CoverSizeInterceptorTest {
	private val url = "https://nd.example/rest/getCoverArt?u=me&t=abc&s=salt&v=1.16.1&c=Navic&f=json&id=al-1%2B2&size=4096"

	@Test
	fun theSmallestBucketAtOrAboveTheWidthUnderTheCap() {
		assertEquals(50, coverSizeBucket(1, 4096))
		assertEquals(50, coverSizeBucket(50, 4096))
		assertEquals(200, coverSizeBucket(150, 4096))
		assertEquals(512, coverSizeBucket(450, 4096))
		assertEquals(1024, coverSizeBucket(900, 4096))
		// Wider than every bucket: the URL as it is (the cap), never a smaller file stretched.
		assertNull(coverSizeBucket(1050, 4096))
		// A quality of 500: nothing at or above it is asked for.
		assertEquals(200, coverSizeBucket(150, 500))
		assertNull(coverSizeBucket(450, 500))
		assertNull(coverSizeBucket(512, 512))
		assertEquals(1024, coverSizeBucket(900, null))
	}

	@Test
	fun onlyGetCoverArtUrlsAreRead() {
		assertEquals("al-1+2" to 4096, parseCoverArtUrl(url))
		assertEquals("x" to null, parseCoverArtUrl("https://nd/rest/getCoverArt.view?id=x"))
		assertNull(parseCoverArtUrl("https://coverartarchive.org/release-group/abc/front-500"))
		assertNull(parseCoverArtUrl("https://hub.example/lb/art?id=x&size=500"))
		assertNull(parseCoverArtUrl("https://nd/rest/getCoverArt?size=10"))
	}

	@Test
	fun theSizeIsReplacedOrAddedAndNothingElseMoves() {
		assertEquals(url.replace("size=4096", "size=200"), withCoverSize(url, 200))
		assertEquals("https://nd/rest/getCoverArt?id=x&size=50", withCoverSize("https://nd/rest/getCoverArt?id=x", 50))
	}

	@Test
	fun aSizedFileThenALargerOneThenTheBareIdThenAFetch() {
		val keys = listOf("a#size=200") to listOf("a#size=512", "a#size=1024")
		fun pick(vararg onDisk: String) = pickCoverKey(keys.first[0], keys.second, "a") { it in onDisk }
		assertEquals("a#size=200", pick("a#size=200", "a"))
		assertEquals("a#size=512", pick("a#size=1024", "a#size=512", "a"))
		assertEquals("a", pick("a"))
		assertEquals("a#size=200", pick())
		assertEquals("a#size=200", sizedCoverKey("a", 200))
	}

	@Test
	fun theNotificationNeverTakesATinyEntryAndNeverFetchesTheCap() {
		assertEquals(512, NOTIFICATION_ARTWORK_MAX_PX)
		// 512 and 1024 the tiles keep, then the bare id; never 200, 100 or 50.
		assertEquals(listOf("c#size=512", "c#size=1024", "c"), notificationArtworkKeys("c"))
		// Nothing on disk: fetched at 512, not at the cover-art quality.
		assertEquals(url.replace("size=4096", "size=512"), notificationArtworkUrl(url))
		assertEquals("https://nd/rest/getCoverArt?id=x&size=512", notificationArtworkUrl("https://nd/rest/getCoverArt?id=x"))
		// A quality under 512 is kept; a picture that is not a Subsonic cover is left alone.
		assertEquals("https://nd/rest/getCoverArt?id=x&size=300", notificationArtworkUrl("https://nd/rest/getCoverArt?id=x&size=300"))
		assertEquals("https://hub.example/lb/art?id=x&size=4096", notificationArtworkUrl("https://hub.example/lb/art?id=x&size=4096"))
		val loader = File("src/androidMain/kotlin/paige/navic/exoplayer/ExoPlayerCoilBitmapLoader.kt").readText()
		assertTrue(".setMaximumOutputDimension(NOTIFICATION_ARTWORK_MAX_PX)" in loader)
	}

	@Test
	fun theNotificationReadsTheDiskOffTheCallersThread() {
		// media3 calls loadBitmap on its application thread: the whole body is the IO future.
		val loader = File("src/androidMain/kotlin/paige/navic/exoplayer/ExoPlayerCoilBitmapLoader.kt").readText()
		val body = loader.substringAfter("override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap>").substringBefore("\n}\n")
		assertTrue(body.trimStart().startsWith("= scope.future {"), body)
		assertTrue("CoroutineScope(SupervisorJob() + Dispatchers.IO)" in loader)
		assertTrue("openSnapshot" !in body, body)
	}

	@Test
	fun bothLoadersCarryTheInterceptorWithTheirMemoryCache() {
		val init = File("src/commonMain/kotlin/paige/navic/di/SingletonImageLoaderInit.kt").readText()
		assertEquals(2, Regex("""add\(CoverSizeInterceptor\(\{ getDiskCache\(\) \}, \{ memoryCache \}\)\)""").findAll(init).count())
		assertEquals(2, Regex("""\.memoryCache \{ memoryCache \}""").findAll(init).count())
		// One disk cache, made once even when both loaders ask at once.
		assertTrue("private val sharedDiskCache: DiskCache by lazy {" in init)
	}

	@Test
	fun theTargetIsTheLargerSideKnown() {
		assertEquals(300, coverTargetPx(150, 300))
		assertEquals(150, coverTargetPx(150, null))
		assertEquals(300, coverTargetPx(null, 300))
		assertNull(coverTargetPx(null, null))
		// A 150 x 300 tile is the 512 bucket, not the 200 the width alone gave.
		assertEquals(512, coverSizeBucket(coverTargetPx(150, 300)!!, 4096))
	}

	@Test
	fun aCoverInMemoryOrChosenBeforeNeedsNoDiskProbe() {
		val memo = CoverKeyMemo()
		val candidates = listOf("a#size=200", "a#size=512", "a")
		// Nothing in memory, nothing chosen: only the disk can tell.
		assertNull(fastCoverKey(candidates, "m", memo) { false })
		// In memory under a larger key: that one, no disk.
		assertEquals("a#size=512", fastCoverKey(candidates, "m", memo) { it == "a#size=512" })
		// Memory first, in the candidates' order.
		assertEquals("a#size=200", fastCoverKey(candidates, "m", memo) { true })
		// Chosen before (the disk was probed once): remembered.
		memo["m"] = "a"
		assertEquals("a", fastCoverKey(candidates, "m", memo) { false })
	}

	@Test
	fun theMemoForgetsTheEldestPastItsSize() {
		val memo = CoverKeyMemo(maxEntries = 2)
		memo["a"] = "1"; memo["b"] = "2"; memo["a"] = "1"; memo["c"] = "3"
		assertNull(memo["b"])
		assertEquals("1", memo["a"])
		assertEquals("3", memo["c"])
	}

	@Test
	fun aFailedFetchFallsBackToTheLargestSmallerFile() {
		assertEquals(listOf("a#size=200", "a#size=100", "a#size=50"), smallerCoverKeys("a", 512))
		assertEquals(emptyList(), smallerCoverKeys("a", 50))
		val onDisk = setOf("a#size=100", "a#size=50")
		assertEquals("a#size=100", smallerCoverKeys("a", 512).firstOrNull { it in onDisk })
	}

	@Test
	fun theInterceptorAsksMemoryBeforeItLeavesTheThread() {
		val src = File("src/commonMain/kotlin/paige/navic/di/CoverSizeInterceptor.kt").readText()
		val body = src.substringAfter("override suspend fun intercept(")
		val fast = body.indexOf("fastCoverKey(")
		val hop = body.indexOf("withContext(Dispatchers.IO)")
		assertTrue(fast in 0 until hop, "the memory and memo lookup must come before the IO hop")
		assertTrue("val key = fast ?: withContext(Dispatchers.IO)" in body)
	}
}
