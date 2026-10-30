package paige.navic.ui.components.common

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Row 6 (adapted from upstream 6947d2f9): the wash asks for a 256 px cover, keyed by that size in
 * memory and by the bare id on disk (B-038), through ONE process-wide loader, and blurs a quarter-
 * size layer with a quarter of the radius. The look is checked on the AVD, not here.
 */
class BlendBackgroundCostTest {
	private val blend = File("src/commonMain/kotlin/paige/navic/ui/components/common/BlendBackground.kt").readText()

	@Test
	fun theCoverIsAskedForAtTheBlendSize() {
		assertEquals(256, BLEND_ART_SIZE_PX)
		assertTrue(".size(BLEND_ART_SIZE_PX, BLEND_ART_SIZE_PX)" in blend)
		assertTrue(".memoryCacheKey(coverArtId?.let { \"\${it}_static_\$BLEND_ART_SIZE_PX\" })" in blend)
		assertTrue(".diskCacheKey(coverArtId)" in blend)
	}

	@Test
	fun theBlurIsScaledWithTheLayer() {
		assertEquals(0.25f, BLEND_RENDER_SCALE)
		assertTrue(".renderAtScale(BLEND_RENDER_SCALE)\n\t\t\t\t.blur(BLEND_BLUR_RADIUS * BLEND_RENDER_SCALE)" in blend)
		assertTrue(".blur(80.dp)" !in blend)
	}

	@Test
	fun theStaticLoaderIsBuiltOnce() {
		val init = File("src/commonMain/kotlin/paige/navic/di/SingletonImageLoaderInit.kt").readText()
		val fn = init.substringAfter("fun getStaticImageLoader(")
		assertTrue("return sharedStaticImageLoader ?:" in fn && ".also { sharedStaticImageLoader = it }" in fn, fn)
	}
}
