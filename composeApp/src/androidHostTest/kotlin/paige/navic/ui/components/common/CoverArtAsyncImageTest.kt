package paige.navic.ui.components.common

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Row 12 (adapted from upstream f9f69ef0): CoverArt and RemoteCoverArt draw with AsyncImage, no
 * subcomposition per tile, and CoverArt keeps what the error slot carried: B-038's album-cover
 * fallback, FALLBACK_PENDING (nothing drawn while the album's cover is looked up), the tinted
 * placeholder box and the colour prefetch.
 */
class CoverArtAsyncImageTest {
	private val dir = File("src/commonMain/kotlin/paige/navic/ui/components/common")
	private val cover = File(dir, "CoverArt.kt").readText()
	private val remote = File(dir, "RemoteCoverArt.kt").readText()

	@Test
	fun noSubcomposition() {
		assertTrue("SubcomposeAsyncImage(" !in cover && "SubcomposeAsyncImage(" !in remote && "import coil3.compose.SubcomposeAsyncImage" !in cover + remote)
		assertTrue("AsyncImage(" in cover && "AsyncImage(" in remote)
	}

	@Test
	fun theErrorPathKeepsTheAlbumFallback() {
		val error = cover.substringAfter("onError = { failure = it.result.throwable }")
		assertTrue("albumDao.getAlbumCoverArtId(fallbackAlbumId)" in error, error)
		assertTrue("if (fallback == FALLBACK_PENDING) return@Box" in error, error)
		assertTrue("CoverArt(\n\t\t\t\t\tcoverArtId = albumCover," in error, error)
		assertTrue("Box(commonModifier, contentAlignment = Alignment.Center, propagateMinConstraints = true)" in error, error)
	}

	@Test
	fun theTintAndThePrefetchStay() {
		assertTrue("Modifier.background(tint)" in cover && "Modifier.background(tint)" in remote)
		assertTrue("LaunchedEffect(coverArtId) { prefetcher.request(coverArtId) }" in cover)
	}
}
