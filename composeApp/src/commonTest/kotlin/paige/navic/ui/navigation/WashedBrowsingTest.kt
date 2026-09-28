package paige.navic.ui.navigation

import androidx.navigation3.runtime.NavKey
import paige.navic.domain.models.DomainAlbumListType
import paige.navic.util.CoverPlaceholder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/*
 * B-008 option 2: the tablet rail takes the now-playing cover's scheme while the PAGE on top of the
 * back stack is one `BrowsingAmbient` washes, and keeps the base scheme otherwise. These pin which
 * keys count as that page and which sit on top of it without replacing it. That the predicate
 * matches App.kt's `Washed` call sites is WashedBrowsingDriftTest's job (androidHostTest), since
 * it has to read the source tree.
 */
class WashedBrowsingTest {

	// --- isWashedBrowsing --------------------------------------------------------------------

	@Test
	fun everyWashedBrowsingPageIsWashedIncludingTheLibraryWhichWrapsItself() {
		val washed = listOf(
			Screen.Library(), Screen.Starred(), Screen.AlbumList(), Screen.PlaylistList(),
			Screen.ArtistList(), Screen.GenreList(), Screen.SongList(), Screen.RadioList(),
			Screen.Fresh(), Screen.Discover(), Screen.MixList(), Screen.Search(), Screen.Statistics()
		)
		washed.forEach { assertTrue(it.isWashedBrowsing(), "$it should be washed") }
	}

	@Test
	fun theNestedAndScopedVariantsOfATabAreWashedToo() {
		// The predicate is over the destination, not over one value of it: the home's shortcuts
		// push `nested = true` copies, and "see all tracks" pushes an artist-scoped song list.
		assertTrue(Screen.AlbumList(nested = true, listType = DomainAlbumListType.Newest).isWashedBrowsing())
		assertTrue(Screen.SongList(nested = true, artistId = "ar-1", artistName = "A").isWashedBrowsing())
		assertTrue(Screen.Library(nested = true).isWashedBrowsing())
	}

	@Test
	fun detailArtistSettingsAndOtherPagesAreNotWashed() {
		val base = listOf(
			Screen.CollectionDetail("al-1", "albums"), Screen.ArtistDetail("ar-1"),
			Screen.SongDetailScreen("s-1"), Screen.GenreDetail("Rock"),
			Screen.ExternalAlbum("rg-1"), Screen.ExternalArtist("mb-1"),
			Screen.ImageView("c-1", "t", "k"), Screen.SavedQueues, Screen.Wishlist, Screen.ShareList,
			Screen.SmartPlaylistEditor(), Screen.Login,
			Screen.Settings.Root, Screen.Settings.Appearance, Screen.Settings.NaviConnect
		)
		base.forEach { assertFalse(it.isWashedBrowsing(), "$it should keep the base scheme") }
	}

	// --- isPageOverlay -----------------------------------------------------------------------

	@Test
	fun theSheetScenesAreOverlaysAndTheImageViewerIsNot() {
		listOf(
			Screen.NowPlaying, Screen.Lyrics, Screen.Queue, Screen.PlaybackSpeed,
			Screen.SongDetailSheet("s-1")
		).forEach { assertTrue(it.isPageOverlay(), "$it is an OverlayScene") }
		// ImageView has only transition metadata, so it is a single-pane scene that REPLACES the
		// page rather than sitting on top of it.
		assertFalse(Screen.ImageView("c-1", "t", "k").isPageOverlay())
		assertFalse(Screen.AlbumList().isPageOverlay())
		assertFalse(Screen.CollectionDetail("al-1", "albums").isPageOverlay())
	}

	// --- topPage / railFollowsCover ----------------------------------------------------------

	@Test
	fun aWashedTabOnTopThemesTheRail() {
		assertTrue(listOf<NavKey>(Screen.AlbumList()).railFollowsCover())
		assertTrue(listOf<NavKey>(Screen.Library(), Screen.AlbumList(nested = true)).railFollowsCover())
	}

	@Test
	fun sheetsOverAWashedPageLeaveTheRailFollowingThePageUnderThem() {
		val stack = listOf<NavKey>(Screen.Library(), Screen.NowPlaying, Screen.Queue)
		assertEquals(Screen.Library(), stack.topPage())
		assertTrue(stack.railFollowsCover())
		assertTrue(listOf<NavKey>(Screen.Search(), Screen.SongDetailSheet("s-1")).railFollowsCover())
	}

	@Test
	fun aTwoPaneDetailOnTopKeepsTheBaseRail() {
		// `detailPane("root")` beside the washed list pane: the page on top is the detail.
		assertFalse(listOf<NavKey>(Screen.AlbumList(), Screen.CollectionDetail("al-1", "albums")).railFollowsCover())
		assertFalse(
			listOf<NavKey>(Screen.AlbumList(), Screen.CollectionDetail("al-1", "albums"), Screen.NowPlaying)
				.railFollowsCover()
		)
	}

	@Test
	fun settingsArtistAndImagePagesKeepTheBaseRail() {
		assertFalse(listOf<NavKey>(Screen.Settings.Root).railFollowsCover())
		assertFalse(listOf<NavKey>(Screen.Settings.Root, Screen.Settings.Appearance).railFollowsCover())
		assertFalse(listOf<NavKey>(Screen.Library(), Screen.ArtistDetail("ar-1")).railFollowsCover())
		assertFalse(listOf<NavKey>(Screen.Library(), Screen.ImageView("c-1", "t", "k")).railFollowsCover())
	}

	@Test
	fun aStackOfNothingButOverlaysHasNoPage() {
		assertNull(emptyList<NavKey>().topPage())
		assertNull(listOf<NavKey>(Screen.NowPlaying).topPage())
		assertFalse(emptyList<NavKey>().railFollowsCover())
		assertFalse(listOf<NavKey>(Screen.NowPlaying).railFollowsCover())
	}

	// --- railWearsCover ----------------------------------------------------------------------

	@Test
	fun aWashedPageWithAResolvedCoverWearsIt() {
		assertTrue(railWearsCover(followsCover = true, coverArtId = "al-1", themed = true, resolved = true))
	}

	@Test
	fun nothingPlayingARadioStreamOrACoverlessTrackGoesBaseEvenWithAStalePalette() {
		// `rememberCoverColorScheme` keeps the last palette when the id goes away, so `resolved`
		// can still be true here: clear queue (no current song), a radio stream (coverArtId =
		// null), a track with no art (null or blank).
		assertFalse(railWearsCover(followsCover = true, coverArtId = null, themed = true, resolved = true))
		assertFalse(railWearsCover(followsCover = true, coverArtId = "", themed = true, resolved = true))
		assertFalse(railWearsCover(followsCover = true, coverArtId = "  ", themed = true, resolved = true))
	}

	@Test
	fun navidromesGenericAvatarIsNoCoverEither() {
		// The engine never fetches a placeholder id (its `hasArt`), so it too leaves a stale palette.
		CoverPlaceholder.learn(topHash = "deadbeef", topCount = 26, runnerUpCount = 3)
		try {
			assertFalse(railWearsCover(followsCover = true, coverArtId = "ar-7_deadbeef", themed = true, resolved = true))
			assertTrue(railWearsCover(followsCover = true, coverArtId = "ar-7_cafe", themed = true, resolved = true))
		} finally {
			CoverPlaceholder.learn(topHash = null, topCount = 0, runnerUpCount = 0)
		}
	}

	@Test
	fun theEnginesOwnGatesAndTheTopPageStillApply() {
		assertFalse(railWearsCover(followsCover = false, coverArtId = "al-1", themed = true, resolved = true))
		assertFalse(railWearsCover(followsCover = true, coverArtId = "al-1", themed = false, resolved = true))
		assertFalse(railWearsCover(followsCover = true, coverArtId = "al-1", themed = true, resolved = false))
	}
}
