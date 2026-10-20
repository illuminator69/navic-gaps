package paige.navic.ui.screens.artist

import paige.navic.domain.manager.LbRelease
import paige.navic.domain.models.DomainAlbum
import paige.navic.ui.screens.artist.viewmodels.DiscographyEntry
import paige.navic.ui.screens.artist.viewmodels.DiscographySection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/*
 * P4 round 2: the discography page's chips. Type chips count each type ("All" counts them all),
 * an ownership filter flattens the list and counts its matches against the whole, and a sort orders
 * within a section. The artist page's few "popular releases" are the most played first.
 */
class DiscographyFilterTest {
	private fun album(id: String, plays: Int) = DomainAlbum(
		id = id, name = id, artistName = "A", artistId = "a", year = null, coverArtId = null,
		genre = null, genres = emptyList(), songCount = 7, duration = null, createdAt = Instant.fromEpochSeconds(0),
		starredAt = null, lastPlayedAt = null, userRating = null, version = null, musicBrainzId = null,
		songs = emptyList(), playCount = plays
	)

	private fun owned(key: String, year: String, plays: Int) =
		DiscographyEntry(key, key, year, album = album("al-$key", plays), release = LbRelease(rgid = key, title = key, status = "present"))

	private fun missing(key: String, year: String) =
		DiscographyEntry(key, key, year, release = LbRelease(rgid = key, title = key, status = "missing"))

	private fun partly(key: String, year: String, plays: Int) = DiscographyEntry(
		key, key, year, album = album("al-$key", plays),
		release = LbRelease(rgid = key, title = key, status = "incomplete", groupId = "g-$key", present = 9, total = 12)
	)

	private fun syncing(key: String, year: String) =
		DiscographyEntry(key, key, year, release = LbRelease(rgid = key, title = key, status = "present"))

	private val sections = listOf(
		DiscographySection(
			"album",
			listOf(
				owned("quiet", "2022", plays = 54),
				partly("soft", "2024", plays = 61),
				syncing("wire", "2025"),
				missing("slow", "2019"),
				owned("static", "2018", plays = 5)
			)
		),
		DiscographySection("ep", listOf(missing("ghost", "2017"), owned("night", "2020", plays = 3))),
		DiscographySection("single", emptyList())
	)

	@Test
	fun typeChipsCountEachTypeAndAllCountsThemAllSkippingEmptyTypes() {
		assertEquals(
			listOf(TypeChip(null, 7), TypeChip("album", 5), TypeChip("ep", 2)),
			typeChips(sections)
		)
	}

	@Test
	fun ownershipReadsPartlyBeforeOwnedAndCountsSyncingAsOwned() {
		val all = sections.flatMap { it.entries }.associate { it.key to ownershipOf(it) }
		assertEquals(Ownership.PARTLY, all["soft"])
		assertEquals(Ownership.OWNED, all["quiet"])
		assertEquals(Ownership.OWNED, all["wire"])
		assertEquals(Ownership.MISSING, all["slow"])
	}

	@Test
	fun noFilterKeepsTheSectionsAndSortsMostPlayedWithinEach() {
		val view = discographyView(sections, DiscographyFilter())
		assertEquals(false, view.flat)
		assertEquals(listOf("album", "ep"), view.sections.map { it.type })
		// Plays first; the unplayed ones (nothing in the library to play) newest first.
		assertEquals(listOf("soft", "quiet", "static", "wire", "slow"), view.sections[0].entries.map { it.key })
		assertEquals(7, view.matched)
		assertEquals(7, view.total)
	}

	@Test
	fun aTypeChipKeepsOnlyThatSection() {
		val view = discographyView(sections, DiscographyFilter(type = "ep"))
		assertEquals(listOf("ep"), view.sections.map { it.type })
		assertEquals(2, view.matched)
		assertEquals(7, view.total)
	}

	@Test
	fun aTypeThatIsGoneShowsEverythingRatherThanNothing() {
		val view = discographyView(sections, DiscographyFilter(type = "live"))
		assertEquals(listOf("album", "ep"), view.sections.map { it.type })
	}

	@Test
	fun anOwnershipFilterFlattensTheListAndCountsMatchesAgainstTheWhole() {
		val view = discographyView(sections, DiscographyFilter(ownership = Ownership.MISSING, sort = DiscographySort.NEWEST))
		assertTrue(view.flat)
		assertEquals(listOf("slow", "ghost"), view.sections.single().entries.map { it.key })
		assertEquals(2, view.matched)
		assertEquals(7, view.total)
	}

	@Test
	fun ownershipAndTypeCombine() {
		val view = discographyView(sections, DiscographyFilter(type = "album", ownership = Ownership.OWNED, sort = DiscographySort.OLDEST))
		assertEquals(listOf("static", "quiet", "wire"), view.sections.single().entries.map { it.key })
	}

	@Test
	fun anOwnershipFilterWithNoMatchesHasNoSections() {
		val only = listOf(DiscographySection("album", listOf(owned("quiet", "2022", 1))))
		val view = discographyView(only, DiscographyFilter(ownership = Ownership.MISSING))
		assertTrue(view.sections.isEmpty())
		assertEquals(0, view.matched)
		assertEquals(1, view.total)
	}

	@Test
	fun titleSortIgnoresCaseAndBlankYearsSortLast() {
		val mixed = listOf(
			DiscographySection("album", listOf(missing("b", ""), missing("C", "2001"), missing("a", "1999")))
		)
		assertEquals(listOf("a", "b", "C"), discographyView(mixed, DiscographyFilter(sort = DiscographySort.TITLE)).sections[0].entries.map { it.key })
		assertEquals(listOf("C", "a", "b"), discographyView(mixed, DiscographyFilter(sort = DiscographySort.NEWEST)).sections[0].entries.map { it.key })
		assertEquals(listOf("a", "C", "b"), discographyView(mixed, DiscographyFilter(sort = DiscographySort.OLDEST)).sections[0].entries.map { it.key })
	}

	@Test
	fun popularReleasesAreTheMostPlayedThenSyncingThenNewestMissingAlbums() {
		assertEquals(listOf("soft", "quiet", "static", "night"), popularReleases(sections).map { it.key })
		assertEquals(
			listOf("soft", "quiet", "static", "night", "wire", "slow"),
			popularReleases(sections, limit = 10).map { it.key }
		)
	}

	@Test
	fun popularReleasesOfAnArtistTheLibraryLacksAreItsNewestAlbums() {
		val none = listOf(DiscographySection("album", listOf(missing("x", "2001"), missing("y", "2010"))), DiscographySection("ep", listOf(missing("z", "2020"))))
		assertEquals(listOf("y", "x"), popularReleases(none).map { it.key })
	}
}
