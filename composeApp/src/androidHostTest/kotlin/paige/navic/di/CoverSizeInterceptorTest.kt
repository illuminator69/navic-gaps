package paige.navic.di

import paige.navic.exoplayer.NOTIFICATION_ARTWORK_MAX_PX
import paige.navic.exoplayer.notificationArtworkKeys
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
	fun theNotificationReadsTheBareIdThenTheSizedFilesAndIsCapped() {
		assertEquals(512, NOTIFICATION_ARTWORK_MAX_PX)
		assertEquals(listOf("c", "c#size=512", "c#size=1024", "c#size=200", "c#size=100", "c#size=50"), notificationArtworkKeys("c"))
		val loader = File("src/androidMain/kotlin/paige/navic/exoplayer/ExoPlayerCoilBitmapLoader.kt").readText()
		assertTrue(".setMaximumOutputDimension(NOTIFICATION_ARTWORK_MAX_PX)" in loader)
	}

	@Test
	fun bothLoadersCarryTheInterceptor() {
		val init = File("src/commonMain/kotlin/paige/navic/di/SingletonImageLoaderInit.kt").readText()
		assertEquals(2, Regex("""add\(CoverSizeInterceptor \{ getDiskCache\(\) \}\)""").findAll(init).count())
	}
}
