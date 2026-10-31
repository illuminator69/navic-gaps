package paige.navic.ui.screens.fresh

import paige.navic.domain.manager.LbFreshRelease
import paige.navic.ui.screens.fresh.viewmodels.FreshScope
import paige.navic.ui.screens.fresh.viewmodels.FreshSort
import paige.navic.ui.screens.fresh.viewmodels.FreshType
import paige.navic.ui.screens.fresh.viewmodels.freshGroups
import paige.navic.ui.screens.fresh.viewmodels.freshDistinct
import paige.navic.ui.screens.fresh.viewmodels.freshKey
import paige.navic.ui.screens.fresh.viewmodels.freshScopeCount
import paige.navic.ui.screens.fresh.viewmodels.freshEffectiveScope
import paige.navic.ui.screens.fresh.viewmodels.freshPlayedOffered
import paige.navic.ui.screens.fresh.viewmodels.freshScoped
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/*
 * I-006, the Fresh rider: each row of lb-bot's fresh feed may carry `artistPlays`, the all-time
 * plays of the row's artists in the listens mirror, and a third scope — "Artists you play" — is
 * the rows where it is above zero, most played first. The scope is offered only when some row
 * has plays: an lb-bot that predates the rider sends no field, and a current one sends 0 on
 * every row while its listens mirror is off or importing — neither is "nothing was released".
 */
class FreshScopeTest {
	private fun row(
		name: String,
		plays: Int?,
		owned: Boolean = false,
		date: String = "2026-10-01"
	) = LbFreshRelease(
		releaseName = name,
		artist = "Artist of $name",
		releaseGroupMbid = "rg-$name",
		releaseDate = date,
		artistOwned = owned,
		artistPlays = plays
	)

	@Test
	fun theScopeIsOfferedOnlyWhenSomeRowHasPlays() {
		assertTrue(freshPlayedOffered(listOf(row("a", 12))))
		assertTrue(freshPlayedOffered(listOf(row("a", 0), row("b", null), row("c", 3))))
	}

	@Test
	fun aFeedWhoseRowsAllSayZeroDoesNotOfferIt() {
		// A current lb-bot always sends the field, and sends 0 while its listens mirror is off or
		// still importing. "(0)" under "Artists you play" would say none of them released
		// anything, which the wire cannot tell from a mirror that is not ready.
		assertFalse(freshPlayedOffered(listOf(row("a", 0), row("b", 0))))
		assertFalse(freshPlayedOffered(listOf(row("a", null), row("b", 0))))
	}

	@Test
	fun anOlderLbBotSendsNoFieldAndTheScopeIsNotOffered() {
		assertFalse(freshPlayedOffered(listOf(row("a", null), row("b", null))))
		assertFalse(freshPlayedOffered(emptyList()))
	}

	@Test
	fun aStoredPlayedScopeFallsBackWhenItIsNotOffered() {
		assertEquals(FreshScope.YOURS, freshEffectiveScope(FreshScope.PLAYED, playedOffered = false))
		assertEquals(FreshScope.PLAYED, freshEffectiveScope(FreshScope.PLAYED, playedOffered = true))
		assertEquals(FreshScope.ALL, freshEffectiveScope(FreshScope.ALL, playedOffered = false))
		assertEquals(FreshScope.YOURS, freshEffectiveScope(FreshScope.YOURS, playedOffered = true))
	}

	@Test
	fun artistsYouPlayIsRowsWithPlaysMostPlayedFirst() {
		val rows = listOf(
			row("quiet", 3), row("never", 0), row("unknown", null), row("loud", 250), row("mid", 40)
		)
		assertEquals(
			listOf("loud", "mid", "quiet"),
			freshScoped(rows, FreshScope.PLAYED).map { it.releaseName }
		)
	}

	@Test
	fun equalPlaysFallBackToNewestThenName() {
		val rows = listOf(
			row("b-old", 10, date = "2026-09-01"),
			row("z-new", 10, date = "2026-10-02"),
			row("a-new", 10, date = "2026-10-02")
		)
		assertEquals(
			listOf("a-new", "z-new", "b-old"),
			freshScoped(rows, FreshScope.PLAYED).map { it.releaseName }
		)
	}

	@Test
	fun theOtherTwoScopesAreUnchanged() {
		val rows = listOf(row("mine", 0, owned = true), row("theirs", 99), row("also-mine", null, owned = true))
		assertEquals(listOf("mine", "also-mine"), freshScoped(rows, FreshScope.YOURS).map { it.releaseName })
		assertEquals(rows, freshScoped(rows, FreshScope.ALL))
	}

	@Test
	fun theRiderIsReadOffTheWireAndItsAbsenceIsNotZero() {
		val wire = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
		assertEquals(17, wire.decodeFromString<LbFreshRelease>("""{"releaseName":"x","artistPlays":17}""").artistPlays)
		assertEquals(0, wire.decodeFromString<LbFreshRelease>("""{"releaseName":"x","artistPlays":0}""").artistPlays)
		assertEquals(null, wire.decodeFromString<LbFreshRelease>("""{"releaseName":"x"}""").artistPlays)
	}

	/*
	 * B-160: lb-bot lists ListenBrainz RELEASES, so two editions of one album are two rows with one
	 * release group, and the grid threw "Key … was already used". The page keeps the first row of a
	 * release group, and every item it draws has its own key.
	 */
	@Test
	fun twoEditionsOfOneReleaseGroupAreOneItemAndEveryKeyIsDistinct() {
		val first = LbFreshRelease(
			releaseName = "Same Album", artist = "Band", releaseMbid = "rel-digital",
			releaseGroupMbid = "rg-same", releaseDate = "2026-10-22", artistOwned = true
		)
		val second = first.copy(releaseMbid = "rel-cd", releaseDate = "2026-10-24")
		val other = LbFreshRelease(
			releaseName = "Other Album", artist = "Band", releaseMbid = "rel-other",
			releaseGroupMbid = "rg-other", releaseDate = "2026-10-23", artistOwned = true
		)
		val noGroup = LbFreshRelease(
			releaseName = "No Group", artist = "Solo", releaseMbid = "rel-nogroup",
			releaseGroupMbid = "", releaseDate = "2026-10-23", artistOwned = true
		)
		val feed = listOf(first, second, other, noGroup)

		for (scope in FreshScope.entries) for (sort in FreshSort.entries) for (type in FreshType.entries) {
			val keys = freshGroups(feed, scope, sort, type).flatMap { it.items }.map(::freshKey)
			assertEquals(keys.distinct(), keys, "duplicate key for $scope / $sort / $type")
		}

		val items = freshGroups(feed, FreshScope.ALL, FreshSort.DATE, FreshType.ALL).flatMap { it.items }
		// 2 release groups + 1 row with none.
		assertEquals(3, items.size)
		assertEquals(2, items.count { it.releaseGroupMbid.isNotBlank() })
		assertEquals(1, items.count { it.releaseGroupMbid.isBlank() })
		assertEquals("rel-digital", items.single { it.releaseGroupMbid == "rg-same" }.releaseMbid)
		// The grid's own keys are never a row's.
		assertTrue(keys(items).none { it == "types" || it.startsWith("h") })
	}

	/*
	 * Of two editions, the one that carries the library album's id is kept: it is the only handle
	 * that opens the album (the review's finding 8). It keeps the first edition's place.
	 */
	@Test
	fun theEditionThatOpensTheLibraryAlbumIsTheOneKept() {
		val digital = LbFreshRelease(
			releaseName = "Same Album", artist = "Band", releaseMbid = "rel-digital",
			releaseGroupMbid = "rg-same", releaseDate = "2026-10-22", artistOwned = true, releaseOwned = true
		)
		val cd = digital.copy(releaseMbid = "rel-cd", releaseAlbumId = "nd-album-7")
		val other = LbFreshRelease(
			releaseName = "Other Album", artist = "Band", releaseMbid = "rel-other",
			releaseGroupMbid = "rg-other", releaseDate = "2026-10-22", artistOwned = true
		)
		val rows = freshDistinct(listOf(digital, other, cd))
		assertEquals(listOf("rel-cd", "rel-other"), rows.map { it.releaseMbid })
		assertEquals("nd-album-7", rows.first().releaseAlbumId)
		// Both carrying one, or neither: the first stays.
		assertEquals("rel-digital", freshDistinct(listOf(digital, cd.copy(releaseAlbumId = ""))).single().releaseMbid)
		assertEquals("rel-a", freshDistinct(listOf(cd.copy(releaseMbid = "rel-a"), cd.copy(releaseMbid = "rel-b"))).single().releaseMbid)
	}

	/*
	 * The N9 walk: two rows of one release group (one with the library album's id) and one with
	 * none showed two tiles under "Everyone (3)". Every chip counts the tiles it shows.
	 */
	@Test
	fun everyScopeChipCountsTheTilesNotTheFeedsRows() {
		val digital = LbFreshRelease(
			releaseName = "Same Album", artist = "Band", releaseMbid = "rel-digital",
			releaseGroupMbid = "rg-same", releaseDate = "2026-10-22", artistOwned = true, releaseOwned = true,
			artistPlays = 4
		)
		val cd = digital.copy(releaseMbid = "rel-cd", releaseAlbumId = "nd-album-7")
		val noGroup = LbFreshRelease(
			releaseName = "No Group", artist = "Stranger", releaseMbid = "rel-nogroup",
			releaseGroupMbid = "", releaseDate = "2026-10-23"
		)
		val feed = listOf(digital, cd, noGroup)
		assertEquals(2, freshScopeCount(feed, FreshScope.ALL))
		assertEquals(1, freshScopeCount(feed, FreshScope.YOURS))
		assertEquals(1, freshScopeCount(feed, FreshScope.PLAYED))
		for (scope in FreshScope.entries) {
			val shown = freshGroups(feed, scope, FreshSort.DATE, FreshType.ALL).sumOf { it.items.size }
			assertEquals(shown, freshScopeCount(feed, scope), "$scope")
		}
	}

	private fun keys(rows: List<LbFreshRelease>) = rows.map(::freshKey)
}
