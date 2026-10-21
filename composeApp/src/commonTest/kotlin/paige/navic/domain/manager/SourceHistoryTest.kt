package paige.navic.domain.manager

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/*
 * P6: Source history. lb-bot's ledger joined with the device's: one row per request, lb-bot's state,
 * the device's row (and its buttons) beside it, and nothing hidden because lb-bot forgot it.
 */
class SourceHistoryTest {

	private fun entry(
		key: String,
		settled: Boolean = true,
		state: String = if (settled) "verified" else "downloading",
		releaseMbid: String = "",
		groupId: String = "",
		isGap: Boolean = false,
		startedAt: Long = 0L,
		finishedAt: Long = 0L
	) = LbFillEntry(
		key = key, isGap = isGap, rgid = "rg-$key", groupId = groupId, releaseMbid = releaseMbid,
		artist = "Device $key", album = "Album $key", quality = "", state = state,
		outcome = if (settled) LbBotManager.OUTCOME_DONE else LbBotManager.OUTCOME_RUNNING, reason = "",
		percent = 30, done = 3, total = 10, mp3WouldHelp = false, failureKind = "", retryable = true,
		attempts = 1, settled = settled, startedAt = startedAt, finishedAt = finishedAt
	)

	private fun req(mbid: String, state: String, updatedAt: Double, groupId: String = "") =
		ActivityRequest(releaseMbid = mbid, rgid = "rg-$mbid", groupId = groupId, artist = "Server",
			album = "Server $mbid", state = state, percent = 40, updatedAt = updatedAt)

	@Test
	fun theSameRequestByReleaseIsOneRowWithLbBotsStateAndTheDevicesEntry() {
		val rows = mergeHistory(
			listOf(req("rel-1", "failed", 10.0)),
			listOf(entry("rg-a", settled = false, releaseMbid = "rel-1"))
		)
		assertEquals(1, rows.size)
		assertEquals("failed", rows[0].state)
		assertEquals("rg-a", rows[0].entry?.key)
		assertTrue(rows[0].fromDevice)
		assertFalse(rows[0].running, "lb-bot's state wins over the device's running row")
	}

	@Test
	fun theSameRequestByGroupWithNoMbidIsOneRowToo() {
		val rows = mergeHistory(
			listOf(req("", "downloading", 10.0, groupId = "g1")),
			listOf(entry("g1", settled = false, isGap = true, groupId = "g1"))
		)
		assertEquals(1, rows.size)
		assertEquals("g1", rows[0].entry?.key)
	}

	@Test
	fun aDeviceOnlySettledRowStaysAndAServerOnlyRowHasNoEntry() {
		val rows = mergeHistory(
			listOf(req("rel-s", "verified", 50.0)),
			listOf(entry("old", finishedAt = 1_000L))
		)
		assertEquals(2, rows.size)
		val server = rows.first { it.key == "rel-s" }
		assertNull(server.entry)
		assertFalse(server.fromDevice)
		val device = rows.first { it.key == "old" }
		assertTrue(device.fromDevice)
		assertEquals(1_000L, device.updatedAt)
	}

	@Test
	fun aDeviceRowOlderThanEverythingLbBotListsIsNotHiddenByIt() {
		val rows = mergeHistory(
			listOf(req("rel-1", "verified", 1_000_000.0)),
			listOf(entry("ancient", finishedAt = 1L))
		)
		assertTrue(rows.any { it.key == "ancient" })
	}

	@Test
	fun runningIsTheFourLiveStatesAndEarlierIsEverythingElseNewestFirst() {
		val rows = mergeHistory(
			listOf(
				req("s", "searching", 1.0), req("q", "queued", 5.0), req("d", "downloading", 3.0),
				req("p", "placing", 2.0), req("pl", "placed", 9.0), req("v", "verified", 8.0),
				req("f", "failed", 7.0), req("c", "cancelled", 6.0)
			),
			emptyList()
		)
		val groups = historyGroups(rows)
		assertEquals(listOf("q", "d", "p", "s"), groups.running.map { it.key })
		assertEquals(listOf("pl", "v", "f", "c"), groups.earlier.map { it.key })
	}

	@Test
	fun aDeviceOnlyRowIsRunningByItsLedgerNotItsLastState() {
		// gave up while downloading: settled, though its last state was a live one
		val gaveUp = entry("gu", settled = true, state = "downloading", finishedAt = 5L)
		val live = entry("live", settled = false, startedAt = 9L)
		val groups = historyGroups(mergeHistory(emptyList(), listOf(gaveUp, live)))
		assertEquals(listOf("live"), groups.running.map { it.key })
		assertEquals(listOf("gu"), groups.earlier.map { it.key })
	}

	@Test
	fun aFillThatFinishesMovesFromRunningToEarlierAsTheSameRow() {
		val before = historyGroups(mergeHistory(listOf(req("rel-1", "downloading", 10.0)), emptyList()))
		val after = historyGroups(mergeHistory(listOf(req("rel-1", "verified", 11.0)), emptyList()))
		assertEquals(before.running.single().key, after.earlier.single().key)
	}

	@Test
	fun percentIsOnlySaidWhileDownloading() {
		val rows = mergeHistory(listOf(req("a", "downloading", 1.0), req("b", "verified", 1.0)), emptyList())
		assertEquals(40, rows[0].percent)
		assertNull(rows[1].percent)
	}

	@Test
	fun transfersAreShownOnlyWhenLbBotListsAny() {
		assertFalse(transfersShown(emptyList()))
		assertTrue(transfersShown(listOf(ActivityTransfer(id = "t"))))
	}

	@Test
	fun activityReadFollowsAttentionsRules() {
		assertTrue(activityRead(200, """{"version":1,"requests":[],"transfers":[]}""") is ActivityRead.Ok)
		assertEquals(ActivityRead.Unavailable, activityRead(200, "{}"))
		assertEquals(ActivityRead.Older, activityRead(404, """{"error":"not found"}"""))
		assertEquals(ActivityRead.Unavailable, activityRead(404, """{"code":"x"}"""))
		assertEquals(ActivityRead.Unavailable, activityRead(502, ""))
		val ok = activityRead(200, """{"version":1,"requests":[{"releaseMbid":"r","state":"failed","future":1}],
			"transfers":[{"id":"t","peer":"p","pct":3}]}""") as ActivityRead.Ok
		assertEquals("failed", ok.requests.single().state)
		assertEquals("p", ok.transfers.single().peer)
	}

	@Test
	fun aServerRowIsWordedAsALedgerRowWithItsOwnState() {
		val failed = req("r", "failed", 10.0).copy(failureKind = "no_source", reason = "nobody").asLedgerRow()
		assertTrue(failed.settled)
		assertEquals(LbBotManager.OUTCOME_FAILED, failed.outcome)
		assertFalse(failed.canCancel)
		val running = req("r", "downloading", 10.0).asLedgerRow()
		assertFalse(running.settled)
		assertFalse(running.canCancel, "a row only lb-bot knows is not cancelled from here")
	}
}
