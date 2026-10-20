package paige.navic.ui.screens.external

import paige.navic.domain.manager.LbRelease
import paige.navic.ui.screens.artist.viewmodels.DiscographyEntry
import paige.navic.ui.screens.artist.viewmodels.DiscographySection
import paige.navic.ui.screens.artist.viewmodels.DiscographyUi
import kotlin.test.Test
import kotlin.test.assertEquals

/*
 * P4: the unowned artist page says what it is doing. A failed scan, a running scan and an artist
 * with no releases are three states; and since the page scans a never-walked artist on arrival, a
 * never-indexed page with nothing running and no error is Scanning (the page is starting it), never
 * "no releases".
 */
class ArtistPageStateTest {
	private val sections = listOf(
		DiscographySection(
			"album",
			listOf(DiscographyEntry(key = "rg-1", title = "After", year = "2020", release = LbRelease(rgid = "rg-1")))
		)
	)

	@Test
	fun loadingIsLoading() {
		assertEquals(ArtistPageState.Loading, artistPageState(DiscographyUi(available = true, indexed = true, sections = sections), loading = true))
	}

	@Test
	fun unavailableIsUnreachable() {
		assertEquals(ArtistPageState.Unreachable, artistPageState(DiscographyUi(available = false), loading = false))
	}

	@Test
	fun indexingIsScanningEvenWithStaleSections() {
		assertEquals(ArtistPageState.Scanning, artistPageState(DiscographyUi(available = true, indexing = true), loading = false))
		assertEquals(
			ArtistPageState.Scanning,
			artistPageState(DiscographyUi(available = true, indexed = true, indexing = true, sections = sections), loading = false)
		)
	}

	@Test
	fun aFailedScanWithNothingIndexedSaysSo() {
		val ui = DiscographyUi(available = true, scanError = "MusicBrainz did not answer (HTTP 503)")
		assertEquals(ArtistPageState.ScanFailed("MusicBrainz did not answer (HTTP 503)"), artistPageState(ui, loading = false))
	}

	@Test
	fun indexedWithNoSectionsHasNoReleases() {
		assertEquals(ArtistPageState.NoReleases, artistPageState(DiscographyUi(available = true, indexed = true), loading = false))
	}

	@Test
	fun indexedWithSectionsHasReleases() {
		assertEquals(ArtistPageState.Releases, artistPageState(DiscographyUi(available = true, indexed = true, sections = sections), loading = false))
	}

	@Test
	fun aSharedDiscographyWithSectionsHasReleases() {
		val ui = DiscographyUi(available = true, indexed = true, scanShared = true, sections = sections)
		assertEquals(ArtistPageState.Releases, artistPageState(ui, loading = false))
	}

	@Test
	fun aRescanThatFailedKeepsTheReleasesItHad() {
		val ui = DiscographyUi(available = true, indexed = true, scanError = "MusicBrainz did not answer", sections = sections)
		assertEquals(ArtistPageState.Releases, artistPageState(ui, loading = false))
	}

	@Test
	fun aNeverIndexedPageIsScanningNotEmpty() {
		assertEquals(ArtistPageState.Scanning, artistPageState(DiscographyUi(available = true), loading = false))
	}
}
