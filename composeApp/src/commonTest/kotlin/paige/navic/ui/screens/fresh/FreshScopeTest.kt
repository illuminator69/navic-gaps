package paige.navic.ui.screens.fresh

import paige.navic.domain.manager.LbFreshRelease
import paige.navic.ui.screens.fresh.viewmodels.FreshScope
import paige.navic.ui.screens.fresh.viewmodels.freshEffectiveScope
import paige.navic.ui.screens.fresh.viewmodels.freshPlaysKnown
import paige.navic.ui.screens.fresh.viewmodels.freshScoped
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/*
 * I-006, the Fresh rider: each row of lb-bot's fresh feed may carry `artistPlays`, the all-time
 * plays of the row's artists in the listens mirror, and a third scope — "Artists you play" — is
 * the rows where it is above zero, most played first. An lb-bot that predates the rider sends no
 * such field at all, which is not the same as sending 0: the scope must then not be offered.
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
	fun theRiderIsKnownOnceAnyRowCarriesTheField() {
		assertTrue(freshPlaysKnown(listOf(row("a", null), row("b", 0))))
		assertTrue(freshPlaysKnown(listOf(row("a", 12))))
	}

	@Test
	fun anOlderLbBotSendsNoFieldAndTheRiderIsUnknown() {
		assertFalse(freshPlaysKnown(listOf(row("a", null), row("b", null))))
		assertFalse(freshPlaysKnown(emptyList()))
	}

	@Test
	fun aStoredPlayedScopeFallsBackWhenTheFeedCannotAnswerIt() {
		assertEquals(FreshScope.YOURS, freshEffectiveScope(FreshScope.PLAYED, playsKnown = false))
		assertEquals(FreshScope.PLAYED, freshEffectiveScope(FreshScope.PLAYED, playsKnown = true))
		assertEquals(FreshScope.ALL, freshEffectiveScope(FreshScope.ALL, playsKnown = false))
		assertEquals(FreshScope.YOURS, freshEffectiveScope(FreshScope.YOURS, playsKnown = true))
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
}
