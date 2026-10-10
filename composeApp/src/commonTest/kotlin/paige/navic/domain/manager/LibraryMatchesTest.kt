package paige.navic.domain.manager

import kotlin.test.Test
import kotlin.test.assertEquals

/*
 * I-026 "Library Matches": the page that replaced the "lb-bot data" settings section. What the
 * lists contain and say is unchanged and tested where it was (`LbBotDataTest`,
 * `OverrideListsTest`, `MatchesToCheckTest`); these are the page's own rules — which section it
 * opens on, what a chip says, what a row draws and whether it leads anywhere.
 */
class LibraryMatchesTest {
	private val all = listOf(MatchesSection.TO_CHECK, MatchesSection.ARTISTS, MatchesSection.ALBUMS, MatchesSection.MERGES)

	// --- the sections ------------------------------------------------------------------------

	@Test
	fun thePageOffersFourSectionsInOrder() {
		assertEquals(all, matchesSections(toCheck = true, albums = true, merges = true))
	}

	@Test
	fun aListLbBotDoesNotSendIsNoSectionAndArtistsIsAlwaysOne() {
		// An older lb-bot: the override list alone.
		assertEquals(listOf(MatchesSection.ARTISTS), matchesSections(toCheck = false, albums = false, merges = false))
		assertEquals(
			listOf(MatchesSection.TO_CHECK, MatchesSection.ARTISTS, MatchesSection.MERGES),
			matchesSections(toCheck = true, albums = false, merges = true)
		)
	}

	@Test
	fun thePageOpensOnToCheckWhenItHasRowsElseOnArtists() {
		assertEquals(MatchesSection.TO_CHECK, matchesSectionToShow(null, all, toCheckCount = 106))
		assertEquals(MatchesSection.ARTISTS, matchesSectionToShow(null, all, toCheckCount = 0))
		// Rows counted for a section that is not on offer open nothing.
		assertEquals(MatchesSection.ARTISTS, matchesSectionToShow(null, listOf(MatchesSection.ARTISTS), toCheckCount = 5))
	}

	@Test
	fun theUsersChoiceStandsWhileItIsOnOffer() {
		assertEquals(MatchesSection.MERGES, matchesSectionToShow(MatchesSection.MERGES, all, toCheckCount = 106))
		// An empty section is still the user's to look at.
		assertEquals(MatchesSection.TO_CHECK, matchesSectionToShow(MatchesSection.TO_CHECK, all, toCheckCount = 0))
		// The section went away under the choice (an lb-bot rolled back): back to the landing one.
		val fewer = listOf(MatchesSection.TO_CHECK, MatchesSection.ARTISTS)
		assertEquals(MatchesSection.TO_CHECK, matchesSectionToShow(MatchesSection.ALBUMS, fewer, toCheckCount = 3))
	}

	@Test
	fun aChipNamesItsSectionAndCountsWhatWasLoaded() {
		assertEquals("To check (106)", matchesSectionLabel(MatchesSection.TO_CHECK, 106))
		assertEquals("Artists (0)", matchesSectionLabel(MatchesSection.ARTISTS, 0))
		assertEquals("Albums (3)", matchesSectionLabel(MatchesSection.ALBUMS, 3))
		assertEquals("Merged names (1)", matchesSectionLabel(MatchesSection.MERGES, 1))
		// Nothing loaded yet: a count would be a count of nothing.
		assertEquals("Artists", matchesSectionLabel(MatchesSection.ARTISTS, null))
	}

	// --- what a row draws, and whether it is a link ------------------------------------------

	private val artists = mapOf("ar-1" to "ar-ar-1_0", "ar-2" to null)
	private val albums = mapOf<String, String?>("al-1" to "al-al-1_0")
	private val cover = "https://coverartarchive.org/release/x/1.jpg"

	@Test
	fun anArtistRowShowsTheLibraryArtistsImage() {
		assertEquals(LbArt.Library("ar-ar-1_0", isArtist = true), libraryArtistArt("ar-1", artists))
		// Held, with no picture of its own: the art component's placeholder, still as an artist.
		assertEquals(LbArt.Library(null, isArtist = true), libraryArtistArt("ar-2", artists))
	}

	@Test
	fun anArtistGoneFromTheLibraryShowsThePlaceholder() {
		assertEquals(LbArt.Placeholder(isArtist = true), libraryArtistArt("ar-gone", artists))
		assertEquals(LbArt.Placeholder(isArtist = true), libraryArtistArt("", artists))
	}

	@Test
	fun anAlbumRowShowsTheAlbumsCover() {
		assertEquals(LbArt.Library("al-al-1_0", isArtist = false), matchAlbumArt("al-1", cover, albums))
	}

	@Test
	fun anAlbumWhoseIdIsNotKnownYetShowsTheCoverLbBotWasGiven() {
		assertEquals(LbArt.Remote(cover), matchAlbumArt("", cover, albums))
		assertEquals(LbArt.Placeholder(isArtist = false), matchAlbumArt("", "", albums))
	}

	@Test
	fun anAlbumGoneFromTheLibraryShowsThePlaceholder() {
		// The id is named and the library does not hold it: not the cover of something else.
		assertEquals(LbArt.Placeholder(isArtist = false), matchAlbumArt("al-gone", cover, albums))
	}

	@Test
	fun aRowIsALinkOnlyWhenTheLibraryHoldsItsId() {
		assertEquals(true, heldInLibrary("ar-1", artists))
		assertEquals(true, heldInLibrary("ar-2", artists))
		assertEquals(false, heldInLibrary("ar-gone", artists))
		assertEquals(false, heldInLibrary("", artists))
		assertEquals(false, heldInLibrary("", mapOf("" to null)))
	}
}
