package paige.navic.ui.components.common

import paige.navic.di.STATIC_MEMORY_CACHE_PERCENT
import paige.navic.di.coverSizeBucket
import paige.navic.di.pickCoverKey
import paige.navic.di.sizedCoverKey
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Row 6 (adapted from upstream 6947d2f9): the wash asks for a 256 px cover through ONE process-wide
 * loader with its own small memory cache, and blurs a quarter-size layer with a quarter of the
 * radius. Its keys at run time are the CoverSizeInterceptor's (row 9), not the ones the request
 * sets: what is asserted here is that key. The look is checked on the AVD, not here.
 */
class BlendBackgroundCostTest {
	private val blend = File("src/commonMain/kotlin/paige/navic/ui/components/common/BlendBackground.kt").readText()
	private val init = File("src/commonMain/kotlin/paige/navic/di/SingletonImageLoaderInit.kt").readText()

	@Test
	fun theCoverIsAskedForAtTheBlendSizeAndKeyedByTheInterceptor() {
		assertEquals(256, BLEND_ART_SIZE_PX)
		assertTrue(".size(BLEND_ART_SIZE_PX, BLEND_ART_SIZE_PX)" in blend)
		// A memory key is set, so the interceptor rewrites it with the disk key it picks.
		assertTrue(".memoryCacheKey(coverArtId)" in blend && ".diskCacheKey(coverArtId)" in blend)
		// At the default quality (4096) a 256 px wash is the 512 bucket: `id#size=512`, in memory
		// (the static loader's cache) and on disk alike, unless a larger file or the bare id is there.
		val bucket = coverSizeBucket(BLEND_ART_SIZE_PX, 4096)
		assertEquals(512, bucket)
		assertEquals("al-1#size=512", pickCoverKey(sizedCoverKey("al-1", bucket!!), listOf("al-1#size=1024"), "al-1") { false })
		// A quality at or under 512: passed through, the keys stay the bare id.
		assertNull(coverSizeBucket(BLEND_ART_SIZE_PX, 512))
	}

	@Test
	fun theStaticLoadersMemoryCacheIsItsOwnAndSmall() {
		val fn = init.substringAfter("fun getStaticImageLoader(")
		assertTrue("MemoryCache.Builder().maxSizePercent(context, STATIC_MEMORY_CACHE_PERCENT)" in fn, fn)
		assertTrue(".memoryCache { memoryCache }" in fn, fn)
		assertTrue(STATIC_MEMORY_CACHE_PERCENT <= 0.05)
	}

	@Test
	fun theBlurIsScaledWithTheLayer() {
		assertEquals(0.25f, BLEND_RENDER_SCALE)
		assertTrue(".renderAtScale(BLEND_RENDER_SCALE)\n\t\t\t\t.blur(BLEND_BLUR_RADIUS * BLEND_RENDER_SCALE)" in blend)
		assertTrue(".blur(80.dp)" !in blend)
	}

	@Test
	fun theStaticLoaderIsBuiltOnce() {
		val fn = init.substringAfter("fun getStaticImageLoader(")
		assertTrue("return sharedStaticImageLoader ?:" in fn && ".also { sharedStaticImageLoader = it }" in fn, fn)
	}
}
