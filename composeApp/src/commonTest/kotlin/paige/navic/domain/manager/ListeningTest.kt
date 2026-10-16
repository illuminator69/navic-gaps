package paige.navic.domain.manager

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/*
 * I-006: the rules in Listening.kt. The My Listening screen shows numbers lb-bot computed over a
 * mirror of the user's ListenBrainz listens, and every decision the screen makes about them is a
 * function here: which state the mirror is in, when a summary read mid-import has to be read
 * again, what "listening time" is measured over, how 112 days become a grid, and where a row
 * leads. The cases are the ones that go wrong quietly — a body that reads as "nothing", a partial
 * total shown as the whole, a row that opens a page which does not exist.
 */
class ListeningTest {
	private val wire = Json { ignoreUnknownKeys = true; coerceInputValues = true }

	private fun status(state: String, count: Int = 0, total: Int? = null, error: String = "") =
		LbBotManager.LbResult.Ok(LbListeningStatus(state = state, count = count, total = total, error = error))

	private fun failed(error: LbBotManager.LbError) = LbBotManager.LbResult.Failed(error)

	// --- ListeningPeriod -------------------------------------------------------------------

	@Test
	fun thePeriodsAreTheContractsFiveInOrder() {
		assertEquals(listOf("7d", "30d", "90d", "365d", "all"), ListeningPeriod.entries.map { it.wire })
	}

	@Test
	fun anUnknownPeriodFallsBackToThirtyDays() {
		assertEquals(ListeningPeriod.YEAR, ListeningPeriod.fromWire("365d"))
		assertEquals(ListeningPeriod.MONTH, ListeningPeriod.fromWire("fortnight"))
		assertEquals(ListeningPeriod.MONTH, ListeningPeriod.fromWire(""))
	}

	// --- listeningPhase --------------------------------------------------------------------

	@Test
	fun eachStateReadsAsItsPhase() {
		assertEquals(ListeningPhase.Off, listeningPhase(status("off")))
		assertEquals(ListeningPhase.Importing(1200, 9000), listeningPhase(status("importing", 1200, 9000)))
		assertEquals(ListeningPhase.Importing(1200, null), listeningPhase(status("importing", 1200, null)))
		assertEquals(ListeningPhase.Ready, listeningPhase(status("ready", 9000, 9000)))
		assertEquals(
			ListeningPhase.SyncFailed("ListenBrainz did not answer"),
			listeningPhase(status("error", 9000, error = "ListenBrainz did not answer"))
		)
	}

	@Test
	fun aMissingRouteIsAnOlderLbBotNotAFailure() {
		assertEquals(ListeningPhase.Older, listeningPhase(failed(LbBotManager.LbError.RouteUnknown)))
	}

	@Test
	fun anythingElseThatFailedIsUnreachable() {
		assertEquals(ListeningPhase.Unreachable, listeningPhase(failed(LbBotManager.LbError.Busy)))
		assertEquals(ListeningPhase.Unreachable, listeningPhase(failed(LbBotManager.LbError.Unreachable("timeout"))))
		assertEquals(ListeningPhase.Unreachable, listeningPhase(failed(LbBotManager.LbError.Rejected(500, ""))))
		assertEquals(ListeningPhase.Unreachable, listeningPhase(failed(LbBotManager.LbError.NotConfigured)))
	}

	@Test
	fun aBodyWithNoStateIsNotAnAnswer() {
		// A stray `{}` decodes to a blank state; reading it as "ready" would paint zeros as a history.
		assertEquals(ListeningPhase.Unreachable, listeningPhase(status("")))
		assertEquals(
			ListeningPhase.Unreachable,
			listeningPhase(LbBotManager.LbResult.Ok(wire.decodeFromString<LbListeningStatus>("{}")))
		)
	}

	@Test
	fun aStateThisBuildHasNeverHeardOfStillServesRows() {
		assertEquals(ListeningPhase.Ready, listeningPhase(status("paused")))
	}

	// --- the import ending -----------------------------------------------------------------

	@Test
	fun theImportEndingIsTheCueToReadAgain() {
		val importing = ListeningPhase.Importing(100, 900)
		assertTrue(importFinished(importing, ListeningPhase.Ready))
		assertTrue(importFinished(importing, ListeningPhase.SyncFailed("boom")))
		assertTrue(importFinished(importing, ListeningPhase.Off))
	}

	@Test
	fun progressAndSilenceAreNotTheImportEnding() {
		val importing = ListeningPhase.Importing(100, 900)
		assertFalse(importFinished(importing, ListeningPhase.Importing(200, 900)))
		assertFalse(importFinished(importing, ListeningPhase.Unreachable))
		assertFalse(importFinished(importing, ListeningPhase.Older))
		// Nothing was importing: an ordinary ready-to-ready poll reloads nothing.
		assertFalse(importFinished(ListeningPhase.Ready, ListeningPhase.Ready))
		assertFalse(importFinished(ListeningPhase.Loading, ListeningPhase.Ready))
	}

	@Test
	fun aPollThatDidNotAnswerKeepsSayingImporting() {
		val importing = ListeningPhase.Importing(100, 900)
		assertEquals(importing, phaseAfterPoll(importing, ListeningPhase.Unreachable))
		assertEquals(ListeningPhase.Importing(200, 900), phaseAfterPoll(importing, ListeningPhase.Importing(200, 900)))
		assertEquals(ListeningPhase.Ready, phaseAfterPoll(importing, ListeningPhase.Ready))
		// Outside an import a failed read is what it is.
		assertEquals(ListeningPhase.Unreachable, phaseAfterPoll(ListeningPhase.Ready, ListeningPhase.Unreachable))
	}

	@Test
	fun aSummaryOlderThanTheLastSyncIsReadAgain() {
		assertTrue(summaryNeedsRefresh(generatedAt = 1_000.0, lastSyncAt = 1_500.0))
		assertFalse(summaryNeedsRefresh(generatedAt = 1_500.0, lastSyncAt = 1_500.0))
		assertFalse(summaryNeedsRefresh(generatedAt = 2_000.0, lastSyncAt = 1_500.0))
		// Never synced: nothing newer can exist.
		assertFalse(summaryNeedsRefresh(generatedAt = 1_000.0, lastSyncAt = null))
		// A generatedAt this build could not read is 0, and so always older.
		assertTrue(summaryNeedsRefresh(generatedAt = 0.0, lastSyncAt = 1_500.0))
	}

	@Test
	fun generatedAtIsReadWhateverShapeItArrivesIn() {
		assertEquals(1791.5, wire.decodeFromString<LbListeningSummary>("""{"generatedAt":1791.5}""").generatedAtSeconds)
		assertEquals(1791.0, wire.decodeFromString<LbListeningSummary>("""{"generatedAt":1791}""").generatedAtSeconds)
		// Not a number: the summary still decodes, and the time reads as unknown.
		val iso = wire.decodeFromString<LbListeningSummary>(
			"""{"generatedAt":"2026-10-03T19:00:00Z","totals":{"plays":7}}"""
		)
		assertEquals(0.0, iso.generatedAtSeconds)
		assertEquals(7, iso.totals.plays)
		assertEquals(0.0, wire.decodeFromString<LbListeningSummary>("{}").generatedAtSeconds)
	}

	@Test
	fun aBodyWithNoPeriodIsNotASummary() {
		// lb-bot echoes the period it answered for; `{}` decodes to zeros, and zeros read as
		// an empty history.
		assertFalse(wire.decodeFromString<LbListeningSummary>("{}").isAnswer)
		assertTrue(wire.decodeFromString<LbListeningSummary>("""{"period":"30d"}""").isAnswer)
	}

	// --- numbers ---------------------------------------------------------------------------

	@Test
	fun listeningTimeSaysWhatItWasMeasuredOver() {
		assertEquals(
			ListeningCoverage(80, 100),
			listeningTimeCoverage(LbListeningTotals(plays = 100, secondsKnownFor = 80))
		)
		assertEquals(
			ListeningCoverage(0, 5),
			listeningTimeCoverage(LbListeningTotals(plays = 5, secondsKnownFor = 0))
		)
	}

	@Test
	fun listeningTimeNeedsNoCaveatWhenEveryPlayCarriedADuration() {
		assertNull(listeningTimeCoverage(LbListeningTotals(plays = 100, secondsKnownFor = 100)))
		assertNull(listeningTimeCoverage(LbListeningTotals(plays = 0, secondsKnownFor = 0)))
	}

	@Test
	fun aDurationSplitsIntoDaysHoursAndWholeMinutes() {
		assertEquals(ListeningDuration(0, 0, 0), listeningDuration(0.0))
		assertEquals(ListeningDuration(0, 0, 0), listeningDuration(59.9))
		assertEquals(ListeningDuration(0, 0, 12), listeningDuration(12 * 60.0 + 30))
		assertEquals(ListeningDuration(0, 5, 12), listeningDuration(5 * 3600.0 + 12 * 60))
		assertEquals(ListeningDuration(3, 4, 0), listeningDuration(3 * 86_400.0 + 4 * 3600))
		assertEquals(ListeningDuration(0, 0, 0), listeningDuration(-5.0))
	}

	@Test
	fun theNextPageIsAskedInWholeSecondsNeverScientificNotation() {
		assertEquals("1791051523", beforeParam(1_791_051_523.0))
		assertEquals("1791051523", beforeParam(1_791_051_523.9))
	}

	@Test
	fun aListenIsStampedInTheGivenZone() {
		// 2026-10-03T19:05:00Z
		val at = 1_791_054_300.0
		assertEquals("2026-10-03 19:05", formatListenedAt(at, TimeZone.UTC))
		assertEquals("2026-10-03 21:05", formatListenedAt(at, TimeZone.of("Europe/Berlin")))
		assertEquals("", formatListenedAt(0.0, TimeZone.UTC))
	}

	@Test
	fun identicalRecentRowsStillGetDistinctKeys() {
		val row = LbRecentPlay(listenedAt = 1_791_054_300.0, recordingMbid = "rec-1", track = "Intro")
		val keys = listOf(row, row, row.copy(recordingMbid = "")).mapIndexed { i, r -> recentPlayKey(i, r) }
		assertEquals(3, keys.toSet().size)
	}

	// --- heatmap ---------------------------------------------------------------------------
	// Whole calendar weeks, Monday first: the grid starts on the first Monday inside the data
	// (up to six leading days are not drawn) and ends with the current week, whose days not yet
	// reached are placeholders. lb-bot sends the last 112 days; a span that began on a Sunday
	// used to leave one lone square in the first column and a short last one.

	/** [n] real consecutive calendar days ending on [end], oldest first — what lb-bot sends. */
	private fun consecutiveDays(end: LocalDate, n: Int = 112) = List(n) { i ->
		LbDayCount(end.minus(n - 1 - i, DateTimeUnit.DAY).toString(), i)
	}

	private fun weekdaysOfRow(grid: ListeningHeatmap, row: Int): Set<DayOfWeek> =
		grid.weeks.mapNotNull { it[row] }.map { LocalDate.parse(it.date).dayOfWeek }.toSet()

	@Test
	fun theGridStartsOnTheFirstMondayInTheData() {
		// 2026-10-03 is a Saturday; 112 days ending on it begin on Sunday 2026-06-14. That lone
		// Sunday is not drawn: the grid starts on Monday the 15th.
		val grid = heatmapGrid(consecutiveDays(LocalDate(2026, 10, 3)))
		assertEquals("2026-06-15", grid.weeks.first().first()?.date)
		assertEquals(16, grid.weeks.size)
		assertTrue(grid.weeks.all { it.size == 7 })
		// A row is one weekday throughout: Mondays at the top, Sundays at the bottom.
		(0..6).forEach { row -> assertEquals(1, weekdaysOfRow(grid, row).size, "row $row") }
		assertEquals(setOf(DayOfWeek.MONDAY), weekdaysOfRow(grid, 0))
		assertEquals(setOf(DayOfWeek.SUNDAY), weekdaysOfRow(grid, 6))
	}

	@Test
	fun theCurrentWeekIsWholeWithItsDaysNotYetReachedAsPlaceholders() {
		// Ending on a Saturday: the last column is Monday to Saturday, and Sunday is a
		// placeholder — a square that is there, with no day in it yet.
		val saturday = heatmapGrid(consecutiveDays(LocalDate(2026, 10, 3)))
		assertEquals(
			listOf("2026-09-28", "2026-09-29", "2026-09-30", "2026-10-01", "2026-10-02", "2026-10-03", null),
			saturday.weeks.last().map { it?.date }
		)
		// Ending on a Wednesday: three days, four placeholders.
		val wednesday = heatmapGrid(consecutiveDays(LocalDate(2026, 9, 30)))
		assertEquals(
			listOf("2026-09-28", "2026-09-29", "2026-09-30", null, null, null, null),
			wednesday.weeks.last().map { it?.date }
		)
	}

	@Test
	fun theGridIsACleanRectangleWhateverWeekdayTheSpanEndsOn() {
		for (offset in 0..6) {
			val end = LocalDate(2026, 10, 4).minus(offset, DateTimeUnit.DAY)
			val weeks = heatmapGrid(consecutiveDays(end)).weeks
			assertTrue(weeks.all { it.size == 7 }, "ending $end")
			val cells = weeks.flatten()
			// No hole at the start, and the only empty squares are the tail of the last week.
			assertEquals(DayOfWeek.MONDAY, LocalDate.parse(cells.first()!!.date).dayOfWeek, "ending $end")
			val drawn = cells.takeWhile { it != null }
			assertEquals(end.toString(), drawn.last()?.date)
			assertTrue(cells.drop(drawn.size).all { it == null }, "ending $end")
			assertTrue(cells.size - drawn.size < 7, "ending $end")
		}
	}

	@Test
	fun aSpanThatAlreadyStartsOnAMondayLosesNoDay() {
		// 112 days ending Sunday 2026-10-04 begin on Monday 2026-06-15: sixteen full weeks.
		val grid = heatmapGrid(consecutiveDays(LocalDate(2026, 10, 4)))
		assertEquals(16, grid.weeks.size)
		assertTrue(grid.weeks.flatten().all { it != null })
		assertEquals("2026-06-15", grid.weeks.first().first()?.date)
		assertEquals("2026-10-04", grid.weeks.last().last()?.date)
	}

	@Test
	fun aHistoryWithNoMondayInItKeepsItsDaysOnTheirWeekdays() {
		// Wednesday to Friday: there is no Monday to start on, and dropping the days would
		// leave nothing. One week, the three days in their own rows.
		val grid = heatmapGrid(consecutiveDays(LocalDate(2026, 10, 2), n = 3))
		assertEquals(
			listOf(listOf(null, null, "2026-09-30", "2026-10-01", "2026-10-02", null, null)),
			grid.weeks.map { week -> week.map { it?.date } }
		)
	}

	@Test
	fun theGridIsSortedByDateAndKeepsTheNewestSixteenWeeksOfDays() {
		val input = consecutiveDays(LocalDate(2026, 10, 3), n = 140)
		assertEquals(heatmapGrid(input), heatmapGrid(input.reversed()))
		// Cut to the newest 112 before anything else: the same grid as those 112 alone.
		assertEquals(heatmapGrid(consecutiveDays(LocalDate(2026, 10, 3))).weeks.map { w -> w.map { it?.date } },
			heatmapGrid(input).weeks.map { w -> w.map { it?.date } })
	}

	@Test
	fun theScaleIsTheBusiestDayDrawn() {
		// The day left out before the first Monday does not set the scale.
		val days = consecutiveDays(LocalDate(2026, 10, 3)).mapIndexed { i, d -> d.copy(count = if (i == 0) 500 else 3) }
		assertEquals(3, heatmapGrid(days).max)
	}

	@Test
	fun anEmptyHeatmapDrawsNothing() {
		val grid = heatmapGrid(emptyList())
		assertTrue(grid.weeks.isEmpty())
		assertEquals(0, grid.max)
		assertEquals(null, heatmapSpan(grid))
	}

	@Test
	fun levelsAreQuartilesOfTheBusiestDayRoundedUp() {
		assertEquals(0, heatLevel(0, 8))
		assertEquals(1, heatLevel(1, 8))
		assertEquals(1, heatLevel(2, 8))
		assertEquals(2, heatLevel(3, 8))
		assertEquals(3, heatLevel(6, 8))
		assertEquals(4, heatLevel(8, 8))
		// One play on a history whose busiest day is one play is the busiest day.
		assertEquals(4, heatLevel(1, 1))
		assertEquals(0, heatLevel(3, 0))
	}

	@Test
	fun theHourChartIsAlwaysTwentyFourBars() {
		assertEquals(24, hourBars(emptyList()).size)
		assertEquals(List(24) { 0 }, hourBars(emptyList()))
		assertEquals(24, hourBars(List(30) { 5 }).size)
		assertEquals(5, hourBars(List(30) { 5 })[23])
		assertEquals(listOf(3, 0, 0), hourBars(listOf(3, -2)).take(3))
	}

	// --- where a row leads -----------------------------------------------------------------

	@Test
	fun anAlbumTheLibraryHoldsOpensItsLibraryPage() {
		assertEquals(
			ListeningTarget.LibraryAlbum("al-1"),
			albumTarget("al-1", "rg-1", "mb-a", "Artist", "Title", knownAlbumIds = setOf("al-1"))
		)
	}

	@Test
	fun anAlbumIdRoomDoesNotHoldIsNotAPageThatExists() {
		assertEquals(
			ListeningTarget.ExternalAlbum("rg-1", "mb-a", "Artist", "Title"),
			albumTarget("al-9", "rg-1", "mb-a", "Artist", "Title", knownAlbumIds = setOf("al-1"))
		)
		assertEquals(
			ListeningTarget.None,
			albumTarget("al-9", "", "mb-a", "Artist", "Title", knownAlbumIds = setOf("al-1"))
		)
	}

	@Test
	fun anOwnedAlbumWithNoPageHereIsPlainTextNeverTheAcquirePage() {
		// `owned: true` with `ndAlbumId: ""`: a filled album lb-bot does not know the id of yet.
		assertEquals(
			ListeningTarget.None,
			albumTarget("", "rg-1", "mb-a", "Artist", "Title", knownAlbumIds = emptySet(), owned = true)
		)
		// Owned, with an id this device has not synced: still not the acquire page.
		assertEquals(
			ListeningTarget.None,
			albumTarget("al-9", "rg-1", "mb-a", "Artist", "Title", knownAlbumIds = setOf("al-1"), owned = true)
		)
		// Owned and on this device: its library page, as ever.
		assertEquals(
			ListeningTarget.LibraryAlbum("al-1"),
			albumTarget("al-1", "rg-1", "mb-a", "Artist", "Title", knownAlbumIds = setOf("al-1"), owned = true)
		)
	}

	@Test
	fun theUnownedListSaysWhyItCouldNotBeRead() {
		// lb-bot's own sentence: a 503 while Navidrome's library lists are unavailable, a 400.
		assertEquals(
			ListeningListFailure.Said("Navidrome's album list is unavailable"),
			listeningListFailure(LbBotManager.LbError.Rejected(503, "Navidrome's album list is unavailable"))
		)
		assertEquals(
			ListeningListFailure.Said("unknown period"),
			listeningListFailure(LbBotManager.LbError.Rejected(400, "unknown period"))
		)
		// An lb-bot older than the route: a fact, said quietly, not something to retry.
		assertEquals(ListeningListFailure.Older, listeningListFailure(LbBotManager.LbError.RouteUnknown))
		// Nothing was said: the generic line.
		assertEquals(ListeningListFailure.Generic, listeningListFailure(LbBotManager.LbError.Rejected(500, " ")))
		assertEquals(ListeningListFailure.Generic, listeningListFailure(LbBotManager.LbError.Busy))
		assertEquals(
			ListeningListFailure.Generic,
			listeningListFailure(LbBotManager.LbError.Unreachable("timeout"))
		)
	}

	@Test
	fun anAlbumTheLibraryLacksOpensTheExternalPage() {
		assertEquals(
			ListeningTarget.ExternalAlbum("rg-1", "mb-a", "Artist", "Title"),
			albumTarget("", "rg-1", "mb-a", "Artist", "Title", knownAlbumIds = emptySet())
		)
		assertEquals(ListeningTarget.None, albumTarget("", "", "mb-a", "Artist", "Title", emptySet()))
	}

	@Test
	fun anArtistOpensTheirLibraryPageOnlyWhenRoomHasOne() {
		assertEquals(
			ListeningTarget.LibraryArtist("ar-1"),
			artistTarget("ar-1", "mb-a", "Artist", knownArtistIds = setOf("ar-1"))
		)
		assertEquals(
			ListeningTarget.ExternalArtist("mb-a", "Artist"),
			artistTarget("ar-9", "mb-a", "Artist", knownArtistIds = setOf("ar-1"))
		)
		assertEquals(
			ListeningTarget.ExternalArtist("mb-a", "Artist"),
			artistTarget("", "mb-a", "Artist", knownArtistIds = emptySet())
		)
		assertEquals(ListeningTarget.None, artistTarget("", "", "Artist", emptySet()))
	}

	@Test
	fun aRecentPlayOpensItsAlbumElseItsArtist() {
		val row = LbRecentPlay(
			track = "Song", artist = "Artist", release = "Album",
			artistMbid = "mb-a", rgid = "rg-1", ndArtistId = "ar-1", ndAlbumId = "al-1"
		)
		assertEquals(
			ListeningTarget.LibraryAlbum("al-1"),
			recentPlayTarget(row, setOf("al-1"), setOf("ar-1"))
		)
		assertEquals(
			ListeningTarget.ExternalAlbum("rg-1", "mb-a", "Artist", "Album", artistId = "ar-1"),
			recentPlayTarget(row.copy(ndAlbumId = ""), emptySet(), setOf("ar-1"))
		)
		assertEquals(
			ListeningTarget.LibraryArtist("ar-1"),
			recentPlayTarget(row.copy(ndAlbumId = "", rgid = ""), emptySet(), setOf("ar-1"))
		)
		assertEquals(
			ListeningTarget.ExternalArtist("mb-a", "Artist"),
			recentPlayTarget(row.copy(ndAlbumId = "", rgid = "", ndArtistId = ""), emptySet(), emptySet())
		)
		assertEquals(
			ListeningTarget.None,
			recentPlayTarget(LbRecentPlay(track = "Song", artist = "Artist"), emptySet(), emptySet())
		)
	}

	@Test
	fun anUnownedRowOnlyEverOpensAnExternalPage() {
		val album = LbUnownedRow(
			kind = "album", title = "Album", artist = "Artist", artistMbid = "mb-a", rgid = "rg-1",
			artistOwned = true, artistId = "ar-1"
		)
		assertEquals(
			ListeningTarget.ExternalAlbum("rg-1", "mb-a", "Artist", "Album", artistId = "ar-1"),
			unownedTarget(album, setOf("ar-1"))
		)
		// The artist id is only passed on when lb-bot says the artist is owned AND Room has them.
		assertEquals(
			ListeningTarget.ExternalAlbum("rg-1", "mb-a", "Artist", "Album"),
			unownedTarget(album.copy(artistOwned = false), setOf("ar-1"))
		)
		assertEquals(
			ListeningTarget.ExternalAlbum("rg-1", "mb-a", "Artist", "Album"),
			unownedTarget(album, emptySet())
		)
		val artist = LbUnownedRow(kind = "artist", artist = "Artist", artistMbid = "mb-a")
		assertEquals(ListeningTarget.ExternalArtist("mb-a", "Artist"), unownedTarget(artist, emptySet()))
		assertEquals(ListeningTarget.None, unownedTarget(artist.copy(artistMbid = ""), emptySet()))
	}

	// --- the wire --------------------------------------------------------------------------

	@Test
	fun theSummaryIsReadOffTheWireAsTheContractSpellsIt() {
		val summary = wire.decodeFromString<LbListeningSummary>(
			"""
			{"period":"30d","tz":"Europe/Berlin","generatedAt":1791054300,
			 "totals":{"plays":120,"seconds":25200.5,"secondsKnownFor":100,"artists":14,"albums":22},
			 "streak":{"current":3,"longest":41},
			 "heatmap":[{"date":"2026-10-03","count":9}],
			 "daily":[{"date":"2026-10-03","count":9}],
			 "hours":[0,1,2],
			 "topArtists":[{"name":"A","mbid":"mb-a","plays":30,"ndArtistId":"ar-1"}],
			 "topAlbums":[{"title":"T","artist":"A","artistMbid":"mb-a","rgid":"rg-1","plays":12,"owned":true,"ndAlbumId":"al-1"}],
			 "topTracks":[{"title":"S","artist":"A","recordingMbid":"rec-1","rgid":"rg-1","plays":5,"ndAlbumId":"al-1"}],
			 "genres":[{"genre":"Jazz","plays":40}],"genrePlays":90}
			""".trimIndent()
		)
		assertEquals(LbListeningTotals(120, 25200.5, 100, 14, 22), summary.totals)
		assertEquals(LbListeningStreak(3, 41), summary.streak)
		assertEquals(LbDayCount("2026-10-03", 9), summary.heatmap.single())
		assertEquals(LbTopArtist("A", "mb-a", 30, "ar-1"), summary.topArtists.single())
		assertEquals(LbTopAlbum("T", "A", "mb-a", "rg-1", 12, true, "al-1"), summary.topAlbums.single())
		assertEquals(LbTopTrack("S", "A", "rec-1", "rg-1", 5, "al-1"), summary.topTracks.single())
		assertEquals(LbGenrePlays("Jazz", 40), summary.genres.single())
		assertEquals(90, summary.genrePlays)
	}

	@Test
	fun statusUnownedAndRecentAreReadOffTheWire() {
		val status = wire.decodeFromString<LbListeningStatus>(
			"""{"state":"importing","count":1200,"total":null,"oldest":1.5e9,"newest":1.79e9,"lastSyncAt":null,"error":""}"""
		)
		assertEquals(LbListeningStatus("importing", 1200, null, 1.5e9, 1.79e9, null, ""), status)
		val unowned = wire.decodeFromString<LbListeningUnowned>(
			"""{"period":"30d","kind":"album","rows":[{"kind":"album","title":"T","artist":"A","artistMbid":"mb-a","rgid":"rg-1","plays":7,"lastListenedAt":1791054300,"artistOwned":true,"artistId":"ar-1"}]}"""
		)
		assertEquals(
			LbUnownedRow("album", "T", "A", "mb-a", "rg-1", 7, 1_791_054_300.0, true, "ar-1"),
			unowned.rows.single()
		)
		val recent = wire.decodeFromString<LbListeningRecent>(
			"""{"rows":[{"listenedAt":1791054300,"track":"S","artist":"A","release":"T","artistMbid":"mb-a","rgid":"rg-1","recordingMbid":"rec-1","ndArtistId":"ar-1","ndAlbumId":"al-1","client":"Navic"}],"nextBefore":1791054300}"""
		)
		assertEquals(
			LbRecentPlay(1_791_054_300.0, "S", "A", "T", "mb-a", "rg-1", "rec-1", "ar-1", "al-1", "Navic"),
			recent.rows.single()
		)
		assertEquals(1_791_054_300.0, recent.nextBefore)
		assertNull(wire.decodeFromString<LbListeningRecent>("""{"rows":[],"nextBefore":null}""").nextBefore)
	}

	// --- the status line and "Sync now", moved here from the settings (I-026) ------------------

	@Test
	fun syncNowIsOfferedOnlyWhereASyncCanBeAskedFor() {
		assertEquals(true, listeningSyncOffered(ListeningPhase.Ready))
		assertEquals(true, listeningSyncOffered(ListeningPhase.SyncFailed("rate limited")))
		assertEquals(true, listeningSyncOffered(ListeningPhase.Importing(10, 100)))
		// Not reached: pressing is how the user asks again, and the answer says so.
		assertEquals(true, listeningSyncOffered(ListeningPhase.Unreachable))
		// No ListenBrainz user, an lb-bot older than the routes, nothing heard yet: no button.
		assertEquals(false, listeningSyncOffered(ListeningPhase.Off))
		assertEquals(false, listeningSyncOffered(ListeningPhase.Older))
		assertEquals(false, listeningSyncOffered(ListeningPhase.Loading))
	}

	@Test
	fun theStatusIsSaidOnce() {
		// Ready: the plain line. Everything else is the banner's, or the page itself.
		assertEquals(true, listeningStatusLineShown(ListeningPhase.Ready))
		assertEquals(false, listeningStatusLineShown(ListeningPhase.Importing(10, null)))
		assertEquals(false, listeningStatusLineShown(ListeningPhase.SyncFailed("x")))
		assertEquals(false, listeningStatusLineShown(ListeningPhase.Unreachable))
		assertEquals(false, listeningStatusLineShown(ListeningPhase.Off))
		assertEquals(false, listeningStatusLineShown(ListeningPhase.Older))
		assertEquals(false, listeningStatusLineShown(ListeningPhase.Loading))
	}

	// --- the page in tabs (I-026) --------------------------------------------------------------

	@Test
	fun thePeriodPickerIsDrawnWhereItChangesSomething() {
		assertEquals(true, listeningTabShowsPeriod(ListeningTab.OVERVIEW))
		assertEquals(true, listeningTabShowsPeriod(ListeningTab.ACTIVITY))
		assertEquals(true, listeningTabShowsPeriod(ListeningTab.NOT_IN_LIBRARY))
		// Recent plays are a list in time, not an aggregate over a period.
		assertEquals(false, listeningTabShowsPeriod(ListeningTab.HISTORY))
	}

	@Test
	fun statsIsTheHistoryWhereverThereIsOne() {
		// I-039: My Listening is Stats' listening half. No ListenBrainz user, or an lb-bot older
		// than the routes, leaves upstream's own Navidrome figures; every other phase is the
		// history's, its cached summary under "unreachable" included.
		assertFalse(listeningOnStats(ListeningPhase.Off))
		assertFalse(listeningOnStats(ListeningPhase.Older))
		listOf(
			ListeningPhase.Loading,
			ListeningPhase.Ready,
			ListeningPhase.Unreachable,
			ListeningPhase.Importing(10, 100),
			ListeningPhase.SyncFailed("boom")
		).forEach { assertTrue(listeningOnStats(it), "$it") }
		// The tabs wait for the status: there is nothing to divide while it is out.
		assertFalse(listeningTabsShown(ListeningPhase.Loading))
		assertFalse(listeningTabsShown(ListeningPhase.Off))
		assertTrue(listeningTabsShown(ListeningPhase.Ready))
		assertTrue(listeningTabsShown(ListeningPhase.Unreachable))
	}

	@Test
	fun theHomeCardFollowsTheHistoryWhenThereIsOne() {
		// Q-103: the library home's Stats card shows lb-bot's history wherever there is one.
		val month = LbListeningSummary(period = "30d", totals = LbListeningTotals(plays = 12))
		assertTrue(listeningHistoryHeld(month))
		// A quiet month is still a history: the heat map's sixteen weeks have plays.
		val quiet = LbListeningSummary(period = "30d", heatmap = listOf(LbDayCount("2026-07-01", 3)))
		assertTrue(listeningHistoryHeld(quiet))
		// No ListenBrainz user: nothing counted anywhere. Not an answer, or nothing cached: none.
		assertFalse(listeningHistoryHeld(LbListeningSummary(period = "30d", heatmap = listOf(LbDayCount("2026-07-01", 0)))))
		assertFalse(listeningHistoryHeld(LbListeningSummary(totals = LbListeningTotals(plays = 5))))
		assertFalse(listeningHistoryHeld(null))
	}

	@Test
	fun theHeatMapNamesTheRangeItDraws() {
		// Not the range lb-bot sent: the Sunday before the first Monday is not drawn, and is
		// not claimed.
		val grid = heatmapGrid(consecutiveDays(LocalDate(2026, 10, 3)))
		assertEquals(HeatmapSpan("2026-06-15", "2026-10-03", weeks = 16), heatmapSpan(grid))
		val short = heatmapGrid(consecutiveDays(LocalDate(2026, 10, 2), n = 3))
		assertEquals(HeatmapSpan("2026-09-30", "2026-10-02", weeks = 1), heatmapSpan(short))
	}

	@Test
	fun aYearOrAllTimeIsChartedOverNinetyDaysAndSaysSo() {
		// lb-bot sends at most the newest ninety days of a period's daily series.
		assertEquals(90, LISTENING_DAILY_MAX_DAYS)
		assertEquals(true, listeningDailyCapped(ListeningPeriod.YEAR))
		assertEquals(true, listeningDailyCapped(ListeningPeriod.ALL))
		assertEquals(false, listeningDailyCapped(ListeningPeriod.WEEK))
		assertEquals(false, listeningDailyCapped(ListeningPeriod.MONTH))
		assertEquals(false, listeningDailyCapped(ListeningPeriod.QUARTER))
	}

	@Test
	fun aGenresBarIsItsPlaysAgainstTheMostPlayedGenre() {
		assertEquals(1f, genreBarFraction(40, 40))
		assertEquals(0.25f, genreBarFraction(10, 40))
		assertEquals(0f, genreBarFraction(0, 40))
		// Nothing to scale against, and nonsense, draw no bar rather than a full one.
		assertEquals(0f, genreBarFraction(5, 0))
		assertEquals(0f, genreBarFraction(-3, 40))
		assertEquals(1f, genreBarFraction(50, 40))
	}

	@Test
	fun thePeriodsDaysAreBarsOldestFirst() {
		val daily = listOf(LbDayCount("2026-10-03", 4), LbDayCount("2026-10-01", 2), LbDayCount("2026-10-02", -1), LbDayCount("", 9))
		// Sorted by day, a nonsense count is no plays, and a row with no day is not a bar.
		assertEquals(listOf(2, 0, 4), dayBars(daily))
		assertEquals(emptyList(), dayBars(emptyList()))
	}

	// --- what a card draws ---------------------------------------------------------------------

	private val heldAlbums = mapOf<String, String?>("al-1" to "al-al-1_0", "al-2" to null)
	private val heldArtists = mapOf<String, String?>("ar-1" to "ar-ar-1_0")
	private val rg = "33333333-3333-4333-8333-333333333333"

	@Test
	fun anAlbumCardShowsTheLibrarysCoverThenTheArchivesThenThePlaceholder() {
		assertEquals(LbArt.Library("al-al-1_0", isArtist = false), albumCardArt("al-1", rg, heldAlbums))
		// Held, with no cover of its own: still the library's (its placeholder), not a guess.
		assertEquals(LbArt.Library(null, isArtist = false), albumCardArt("al-2", rg, heldAlbums))
		// Not held — unowned, or not synced to this device: the release group's front cover.
		assertEquals(LbArt.Remote(LbBotManager.caaCoverUrl(rg)), albumCardArt("al-gone", rg, heldAlbums))
		assertEquals(LbArt.Remote(LbBotManager.caaCoverUrl(rg)), albumCardArt("", rg, heldAlbums))
		// Nothing names a picture.
		assertEquals(LbArt.Placeholder(isArtist = false), albumCardArt("", "", heldAlbums))
	}

	@Test
	fun aNotInLibraryCardShowsWhatTheAppUsesForSomethingItDoesNotHold() {
		val album = LbUnownedRow(kind = "album", title = "Currents", artist = "Tame Impala", rgid = rg, plays = 12)
		assertEquals(LbArt.Remote(LbBotManager.caaCoverUrl(rg)), unownedCardArt(isArtist = false, album, heldArtists))
		assertEquals(LbArt.Placeholder(isArtist = false), unownedCardArt(false, album.copy(rgid = ""), heldArtists))
		// An artist the library does not hold has no picture here: the artist placeholder.
		val artist = LbUnownedRow(kind = "artist", artist = "Pond", plays = 30)
		assertEquals(LbArt.Placeholder(isArtist = true), unownedCardArt(isArtist = true, artist, heldArtists))
		// ...unless the row names an artist Room does hold.
		assertEquals(
			LbArt.Library("ar-ar-1_0", isArtist = true),
			unownedCardArt(true, artist.copy(artistOwned = true, artistId = "ar-1"), heldArtists)
		)
	}


	// --- a History row's menu ------------------------------------------------------------------
	// A recent play carries no Navidrome song id. The song is looked for in the album it is
	// filed under, and a menu is only ever opened on a song when exactly one fits.

	private val play = LbRecentPlay(
		track = "Let It Happen",
		artist = "Tame Impala",
		release = "Currents",
		recordingMbid = "rec-1",
		ndAlbumId = "al-1",
		ndArtistId = "ar-1"
	)
	private val currents = listOf(
		HistorySong("s-1", "Let It Happen", "rec-1"),
		HistorySong("s-2", "Nangs", "rec-2"),
		HistorySong("s-3", "The Moment", "")
	)

	@Test
	fun theSongIsFoundByItsRecordingId() {
		assertEquals(HistoryMenu.Song("s-1"), historyMenu(play, currents, artistHeld = true))
		// The id decides, whatever the title says: a retitled track is still that recording.
		assertEquals(HistoryMenu.Song("s-2"), historyMenu(play.copy(recordingMbid = "rec-2"), currents, true))
	}

	@Test
	fun withNoRecordingIdToGoByTheTitleDecidesWithCaseAndPunctuationAside() {
		val untagged = play.copy(recordingMbid = "", track = "the moment!")
		assertEquals(HistoryMenu.Song("s-3"), historyMenu(untagged, currents, true))
		// A recording id the album does not carry is no match either: the title is asked next.
		assertEquals(HistoryMenu.Song("s-2"), historyMenu(play.copy(recordingMbid = "rec-x", track = "NANGS"), currents, true))
	}

	@Test
	fun aSongThatCannotBeToldApartOpensTheAlbumNotAGuess() {
		val twice = currents + HistorySong("s-4", "Let It Happen", "rec-1")
		// Two songs carry the id and the title: which was played is not known.
		assertEquals(HistoryMenu.Album("al-1"), historyMenu(play, twice, true))
		// Nothing fits.
		assertEquals(HistoryMenu.Album("al-1"), historyMenu(play.copy(recordingMbid = "", track = "Eventually"), currents, true))
		// A title that is nothing but punctuation matches nothing — not every such song.
		val odd = listOf(HistorySong("s-9", "!!!", ""), HistorySong("s-8", "???", ""))
		assertEquals(HistoryMenu.Album("al-1"), historyMenu(play.copy(recordingMbid = "", track = "..."), odd, true))
	}

	@Test
	fun withNoAlbumInTheLibraryTheArtistsMenuOrNone() {
		assertEquals(HistoryMenu.Artist("ar-1"), historyMenu(play, albumSongs = null, artistHeld = true))
		assertEquals(HistoryMenu.None, historyMenu(play, null, artistHeld = false))
		assertEquals(HistoryMenu.None, historyMenu(play.copy(ndArtistId = ""), null, artistHeld = true))
	}

	// --- fix round 1 ---------------------------------------------------------------------------

	@Test
	fun theHeatMapOutlivesAPeriodChangeAndAFailedRead() {
		val days = consecutiveDays(LocalDate(2026, 10, 3), n = 14)
		assertEquals(days, keptHeatmap(emptyList(), LbListeningSummary(period = "30d", heatmap = days)))
		// A period was tapped: the summary is cleared while the next one is read. The map does
		// not depend on the period, and the picker sits under it — it must not blink out.
		assertEquals(days, keptHeatmap(days, null))
		// A summary with no heat map in it does not blank the one on screen either.
		assertEquals(days, keptHeatmap(days, LbListeningSummary(period = "7d")))
		val newer = consecutiveDays(LocalDate(2026, 10, 4), n = 14)
		assertEquals(newer, keptHeatmap(days, LbListeningSummary(period = "7d", heatmap = newer)))
	}

	@Test
	fun thePlaysPerDayChartIsCalendarDaysWithNoGaps() {
		val daily = listOf(
			LbDayCount("2026-10-05", 4), LbDayCount("2026-10-01", 2), LbDayCount("2026-10-02", -1),
			LbDayCount("", 9), LbDayCount("nonsense", 3)
		)
		// A day lb-bot left out is a day with no plays, not a bar that is not there.
		assertEquals(
			listOf(
				LbDayCount("2026-10-01", 2), LbDayCount("2026-10-02", 0), LbDayCount("2026-10-03", 0),
				LbDayCount("2026-10-04", 0), LbDayCount("2026-10-05", 4)
			),
			daySeries(daily)
		)
		assertEquals(listOf(2, 0, 0, 0, 4), dayBars(daily))
		assertEquals(emptyList(), daySeries(emptyList()))
	}

	@Test
	fun theDayChartsAxisIsDatesFirstLastAndAFewBetween() {
		val week = daySeries(consecutiveDays(LocalDate(2026, 10, 7), n = 7))
		assertEquals(listOf(0 to "10-01", 2 to "10-03", 4 to "10-05", 6 to "10-07"), dayAxisLabels(week, most = 4))
		val ninety = dayAxisLabels(daySeries(consecutiveDays(LocalDate(2026, 10, 7), n = 90)), most = 5)
		assertEquals(5, ninety.size)
		assertEquals(0, ninety.first().first)
		assertEquals(89 to "10-07", ninety.last())
		assertEquals(ninety.map { it.first }.sorted().distinct(), ninety.map { it.first })
		// One day is one label; no days, none. Never an hour.
		assertEquals(listOf(0 to "10-07"), dayAxisLabels(daySeries(consecutiveDays(LocalDate(2026, 10, 7), n = 1)), most = 5))
		assertEquals(emptyList(), dayAxisLabels(emptyList(), most = 5))
	}

	@Test
	fun aHeatMapSquareHasALargestSize() {
		// Sixteen weeks across a phone: the width decides.
		assertEquals((360f - 2f * 15) / 16, heatmapCellSize(360f, columns = 16, gap = 2f, cap = 24f))
		// One or two weeks: the cap decides, not a square as wide as the screen.
		assertEquals(24f, heatmapCellSize(360f, columns = 1, gap = 2f, cap = 24f))
		assertEquals(24f, heatmapCellSize(360f, columns = 2, gap = 2f, cap = 24f))
		assertEquals(0f, heatmapCellSize(360f, columns = 0, gap = 2f, cap = 24f))
	}

	@Test
	fun aHistoryRowsTapDoesSomethingOrShowsNothing() {
		assertEquals(HistoryRowGesture.TAP_AND_MENU, historyRowGesture(hasTarget = true, hasMenu = true))
		assertEquals(HistoryRowGesture.TAP, historyRowGesture(hasTarget = true, hasMenu = false))
		// A menu and nowhere to go: the long press alone — a tap neither acts nor ripples.
		assertEquals(HistoryRowGesture.MENU_ONLY, historyRowGesture(hasTarget = false, hasMenu = true))
		assertEquals(HistoryRowGesture.NONE, historyRowGesture(hasTarget = false, hasMenu = false))
	}
}
