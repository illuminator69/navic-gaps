package paige.navic.domain.manager

import kotlinx.datetime.TimeZone
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

	private fun days(n: Int, count: (Int) -> Int = { 0 }) = List(n) { i ->
		// 2026-01-01 onward, zero-padded so the strings sort as dates.
		val month = 1 + i / 28
		val day = 1 + i % 28
		LbDayCount("2026-${month.toString().padStart(2, '0')}-${day.toString().padStart(2, '0')}", count(i))
	}

	@Test
	fun oneHundredAndTwelveDaysMakeSixteenWeeksEndingOnTheNewestDay() {
		val input = days(112) { it }
		val grid = heatmapGrid(input)
		assertEquals(16, grid.weeks.size)
		assertTrue(grid.weeks.all { it.size == 7 })
		assertEquals(input.first().date, grid.weeks.first().first()?.date)
		assertEquals(input.last().date, grid.weeks.last().last()?.date)
		assertEquals(111, grid.max)
	}

	@Test
	fun theGridIsSortedByDateWhateverOrderItArrivedIn() {
		val input = days(112) { it }
		assertEquals(heatmapGrid(input), heatmapGrid(input.reversed()))
	}

	@Test
	fun aShortHistoryIsPaddedOnTheOldSide() {
		val input = days(10) { 1 }
		val grid = heatmapGrid(input)
		assertEquals(16, grid.weeks.size)
		val cells = grid.weeks.flatten()
		assertEquals(112, cells.size)
		assertEquals(102, cells.takeWhile { it == null }.size)
		assertEquals(input.last().date, cells.last()?.date)
	}

	@Test
	fun moreThanSixteenWeeksKeepsTheNewest() {
		val input = days(140) { 1 }
		val cells = heatmapGrid(input).weeks.flatten()
		assertEquals(112, cells.size)
		assertEquals(input[28].date, cells.first()?.date)
		assertEquals(input.last().date, cells.last()?.date)
	}

	@Test
	fun anEmptyHeatmapIsSixteenEmptyWeeks() {
		val grid = heatmapGrid(emptyList())
		assertEquals(16, grid.weeks.size)
		assertTrue(grid.weeks.flatten().all { it == null })
		assertEquals(0, grid.max)
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
}
