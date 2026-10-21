package paige.navic.domain.manager

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/*
 * P6: Needs attention. The count is lb-bot's own to-fix number wherever the hub answers, P1's
 * device-ledger count where it cannot; a read that did not happen is never drawn as zero; a folder
 * is never named by its name or path; and a fill both sides know is listed once.
 */
class AttentionTest {

	private fun entry(
		key: String,
		outcome: String = LbBotManager.OUTCOME_FAILED,
		settled: Boolean = true,
		releaseMbid: String = "",
		groupId: String = "",
		isGap: Boolean = false,
		finishedAt: Long = 0L
	) = LbFillEntry(
		key = key, isGap = isGap, rgid = "rg-$key", groupId = groupId, releaseMbid = releaseMbid,
		artist = "Artist $key", album = "Album $key", quality = "", state = if (settled) "failed" else "downloading",
		outcome = outcome, reason = "", percent = 0, done = 0, total = 5, mp3WouldHelp = false,
		failureKind = "", retryable = true, attempts = 1, settled = settled, startedAt = 0L, finishedAt = finishedAt
	)

	private val full = """
		{"version":1,"serverTime":1761000000.5,
		 "counts":{"failedGaps":3,"failedRequests":1,"folders":16,"foldersUnidentified":12,"picks":14,"future":9},
		 "albums":[
		  {"kind":"request","key":"rel-1","groupId":"","releaseMbid":"rel-1","rgid":"rg-1","albumId":"","artist":"A","album":"B",
		   "coverUrl":"https://coverartarchive.org/release-group/rg-1/front-250","state":"failed","missingCount":0,"total":10,"updatedAt":500.0,"extra":true},
		  {"kind":"gap","key":"g1","groupId":"g1","releaseMbid":"rel-g1","rgid":"","albumId":"nd-1","artist":"C","album":"D",
		   "coverUrl":"","state":"picking","missingCount":3,"total":11,"updatedAt":100.0}],
		 "folders":[{"id":"abcdef012345","fileCount":11,"formats":"flac","matchLabel":"","confidence":"unknown",
		   "canConfirm":false,"groupId":"","releaseMbid":"","identified":false}],
		 "truncated":{"albums":0,"folders":0}}
	""".trimIndent()

	private fun ok(): LbAttention = (attentionRead(200, full) as AttentionRead.Ok).attention

	// --- attentionRead ---------------------------------------------------------------------------

	@Test
	fun aBare404IsOlderAndOneWithACodeIsLbBotsOwnAnswer() {
		assertEquals(AttentionRead.Older, attentionRead(404, """{"error":"not found"}"""))
		assertEquals(AttentionRead.Older, attentionRead(404, """{"error":"unknown route"}"""))
		assertEquals(AttentionRead.Older, attentionRead(404, "<html>nope</html>"))
		assertEquals(AttentionRead.Unavailable, attentionRead(404, """{"error":"x","code":"busy"}"""))
		assertEquals(AttentionRead.Older, attentionRead(405, ""))
	}

	@Test
	fun anEmptyOrForeignBodyIsNotAnAnswer() {
		assertEquals(AttentionRead.Unavailable, attentionRead(200, "{}"))
		assertEquals(AttentionRead.Unavailable, attentionRead(200, "[]"))
		assertEquals(AttentionRead.Unavailable, attentionRead(200, """{"version":1}"""))
		assertEquals(AttentionRead.Unavailable, attentionRead(200, "not json"))
		assertEquals(AttentionRead.Unavailable, attentionRead(502, """{"error":"lb-bot unreachable","down":true}"""))
		assertEquals(AttentionRead.Unavailable, attentionRead(503, """{"busy":true}"""))
	}

	@Test
	fun aFullAnswerParsesAndToleratesUnknownFields() {
		val a = ok()
		assertEquals(AttentionCounts(3, 1, 16, 12, 14), a.counts)
		assertEquals(listOf("rel-1", "g1"), a.albums.map { it.key })
		assertTrue(a.albums[1].picking)
		assertEquals("flac", a.folders.single().formats)
		assertEquals("unknown", a.folders.single().confidence)
	}

	// --- the count ---------------------------------------------------------------------------------

	@Test
	fun theCountIsLbBotsToFixNumber() {
		assertEquals(20, toFixCount(AttentionCounts(3, 1, 16, 12, 14)))
		// picks and unidentified folders are listed, never counted
		assertEquals(20, toFixCount(AttentionCounts(3, 1, 16, 0, 0)))
		assertEquals(20, toFixCount(AttentionCounts(3, 1, 16, 16, 300)))
	}

	@Test
	fun aReadWinsOverTheLedgerAndNoReadFallsBackToIt() {
		assertEquals(20, attentionBadge(ok(), ledgerCount = 2))
		assertEquals(2, attentionBadge(null, ledgerCount = 2))
		assertEquals(0, attentionBadge(ok().copy(counts = AttentionCounts()), ledgerCount = 2))
	}

	// --- folders -----------------------------------------------------------------------------------

	private val files = { n: Int -> if (n == 1) "1 file" else "$n files" }

	@Test
	fun aFolderIsWhatItWasIdentifiedAsOrUnidentified() {
		val matched = AttentionFolder(id = "x", fileCount = 11, matchLabel = "Band — Record", identified = true)
		assertEquals("Band — Record · 11 files", folderLine(matched, "Unidentified folder", files))
		val unmatched = AttentionFolder(id = "y", fileCount = 11)
		assertEquals("Unidentified folder · 11 files", folderLine(unmatched, "Unidentified folder", files))
		assertEquals("Unidentified folder · 1 file", folderLine(unmatched.copy(fileCount = 1), "Unidentified folder", files))
		// a label without lb-bot saying it identified the folder is not shown as an identity
		assertEquals("Unidentified folder · 11 files",
			folderLine(matched.copy(identified = false), "Unidentified folder", files))
	}

	@Test
	fun aFolderLineNeverCarriesAPath() {
		// The folder type has no name or path at all, so the line is the identity and the count.
		val line = folderLine(AttentionFolder(id = "abcdef012345", fileCount = 3), "Unidentified folder", files)
		assertFalse("/" in line || "\\" in line || "abcdef" in line)
	}

	// --- the page ----------------------------------------------------------------------------------

	@Test
	fun noReadAndNoAnswerIsLoadingNotNothing() {
		assertEquals(AttentionPage.Loading, attentionPage(null, null, null, emptyList(), null))
	}

	@Test
	fun aFailedReadKeepsTheLastAnswerAndSaysSinceWhen() {
		val page = attentionPage(AttentionRead.Unavailable, ok(), lastAt = 1_000L, emptyList(), 7)
		assertIs<AttentionPage.Rows>(page)
		assertEquals(20, page.toFix)
		assertEquals(listOf(AttentionNotice.Unreachable(1_000L)), page.notices)
		assertEquals(listOf("rel-1", "g1"), page.albums.map { it.album.key })
		assertEquals(7, page.artistsToCheck)
	}

	@Test
	fun aFailedReadWithNoAnswerListsTheDeviceAndNoNumber() {
		val page = attentionPage(AttentionRead.Unavailable, null, null, listOf(entry("a")), null)
		assertIs<AttentionPage.Rows>(page)
		assertNull(page.toFix, "zero from a failed read is never drawn as zero")
		assertEquals(listOf(AttentionNotice.Unreachable(null)), page.notices)
		assertEquals(listOf("a"), page.albums.map { it.album.key })
	}

	@Test
	fun anOlderHubListsTheLedgersFailedAndWaitingRowsAndSaysSo() {
		val ledger = listOf(
			entry("pick", outcome = LbBotManager.OUTCOME_NEEDS_PICK, finishedAt = 9),
			entry("old", finishedAt = 1),
			entry("new", finishedAt = 5),
			entry("run", outcome = LbBotManager.OUTCOME_RUNNING, settled = false),
			entry("done", outcome = LbBotManager.OUTCOME_DONE)
		)
		val page = attentionPage(AttentionRead.Older, ok(), 1L, ledger, null)
		assertIs<AttentionPage.Rows>(page)
		assertEquals(listOf(AttentionNotice.Older), page.notices)
		assertNull(page.toFix)
		assertEquals(listOf("new", "old", "pick"), page.albums.map { it.album.key }, "failed first, newest first, then picks")
		assertTrue(page.albums.all { it.actionable })
		assertTrue(page.folders.isEmpty())
	}

	@Test
	fun aTruncatedAnswerSaysSo() {
		val cut = ok().copy(truncated = AttentionTruncated(albums = 12, folders = 0))
		val page = attentionPage(AttentionRead.Ok(cut), cut, 1L, emptyList(), null) as AttentionPage.Rows
		assertEquals(listOf(AttentionNotice.Truncated(12, 0)), page.notices)
	}

	@Test
	fun aFillBothSidesKnowIsListedOnceWithLbBotsStateAndTheDevicesRow() {
		val ledger = listOf(
			entry("rg-x", releaseMbid = "rel-1", finishedAt = 3),
			entry("g1", outcome = LbBotManager.OUTCOME_NEEDS_PICK, isGap = true, groupId = "g1"),
			entry("mine", finishedAt = 2)
		)
		val page = attentionPage(AttentionRead.Ok(ok()), ok(), 1L, ledger, null) as AttentionPage.Rows
		assertEquals(listOf("rel-1", "g1", "mine"), page.albums.map { it.album.key })
		assertEquals("rg-x", page.albums[0].entry?.key)
		assertEquals("picking", page.albums[1].album.state, "lb-bot's state wins")
		assertEquals("g1", page.albums[1].entry?.key)
		assertTrue(page.albums.all { it.actionable })
		assertEquals(page.albums.size, page.albums.map { it.id }.toSet().size, "ids are unique")
	}

	@Test
	fun aFillThisDeviceStartedAgainLeavesTheListBeforeTheNextRead() {
		val retried = entry("rg-x", releaseMbid = "rel-1", settled = false)
		val page = attentionPage(AttentionRead.Ok(ok()), ok(), 1L, listOf(retried), null) as AttentionPage.Rows
		assertEquals(listOf("g1"), page.albums.map { it.album.key })
		assertEquals(20, page.toFix, "the count is lb-bot's until it is read again")
	}

	@Test
	fun aRowOnlyLbBotKnowsHasNoEntry() {
		val page = attentionPage(AttentionRead.Ok(ok()), ok(), 1L, emptyList(), null) as AttentionPage.Rows
		assertTrue(page.albums.none { it.actionable })
		assertEquals(20, page.toFix)
		assertTrue(page.notices.isEmpty())
	}

	@Test
	fun artistsToCheckArePassedThroughAndNeverCounted() {
		val page = attentionPage(AttentionRead.Ok(ok()), ok(), 1L, emptyList(), 104) as AttentionPage.Rows
		assertEquals(104, page.artistsToCheck)
		assertEquals(20, page.toFix)
	}

	// --- refresh -----------------------------------------------------------------------------------

	@Test
	fun refreshAtMostEvery30sNeverTwiceAtOnceAlwaysWhenForced() {
		assertTrue(shouldRefreshAttention(now = 100_000, readAt = 0, force = false, inFlight = false))
		assertFalse(shouldRefreshAttention(now = 100_000, readAt = 80_000, force = false, inFlight = false))
		assertTrue(shouldRefreshAttention(now = 110_000, readAt = 80_000, force = false, inFlight = false))
		assertTrue(shouldRefreshAttention(now = 100_000, readAt = 99_000, force = true, inFlight = false))
		assertFalse(shouldRefreshAttention(now = 100_000, readAt = 0, force = true, inFlight = true))
	}

	@Test
	fun aFillFrameNamingAFailureOrAPickForcesARead() {
		fun frame(vararg kv: Pair<String, String>) = buildJsonObject { kv.forEach { (k, v) -> put(k, JsonPrimitive(v)) } }
		assertTrue(fillFrameForcesAttention(frame("kind" to "album", "state" to "failed")))
		assertTrue(fillFrameForcesAttention(frame("kind" to "gap", "status" to "picking")))
		assertTrue(fillFrameForcesAttention(frame("kind" to "album", "state" to "needs_match")))
		assertFalse(fillFrameForcesAttention(frame("kind" to "album", "state" to "downloading")))
		assertFalse(fillFrameForcesAttention(frame("kind" to "gap", "status" to "downloading")))
	}

	@Test
	fun olderForgetsTheLastAnswerAndAFailureKeepsIt() {
		val good = nextAttentionState(AttentionState(), AttentionRead.Ok(ok()), 10L)
		assertEquals(ok(), good.last)
		val failed = nextAttentionState(good, AttentionRead.Unavailable, 20L)
		assertEquals(ok(), failed.last)
		assertEquals(10L, failed.lastAt, "since when is the good answer's time, not the failure's")
		assertEquals(20L, failed.readAt)
		assertEquals(AttentionRead.Unavailable, failed.status)
		val older = nextAttentionState(good, AttentionRead.Older, 30L)
		assertNull(older.last)
		assertEquals(2, attentionBadge(older.last, ledgerCount = 2))
	}
}
