package paige.navic.ui.screens.fresh

import paige.navic.domain.manager.LbFreshRelease
import paige.navic.ui.screens.fresh.viewmodels.FreshScope
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
}
