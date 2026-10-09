package paige.navic.domain.manager

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/*
 * I-023 "Merge into one name": the wire and the rules in ArtistMerge.kt.
 *
 * Navidrome keys artists by NAME, so "Belle & Sebastian" and "Belle and Sebastian" are two
 * artists even with one MusicBrainz id. lb-bot retags the variant spelling's files to the kept
 * name. Three things here are easy to get wrong and costly when they are: which of the two ids
 * is the one whose name is KEPT (a swapped pair rewrites the wrong artist's files); that the
 * result must go on being shown after the merge has removed the very sibling group that offered
 * it; and that `siblings`, `merges` and `albums` being absent is not the same as their being
 * empty.
 */
class ArtistMergeTest {
	private val mbid = "a74b1b7f-71a5-4011-9441-d0b5e4122711"

	private fun <T> value(result: LbBotManager.LbResult<T>): T =
		assertIs<LbBotManager.LbResult.Ok<T>>(result).value

	private fun error(result: LbBotManager.LbResult<*>): LbBotManager.LbError =
		assertIs<LbBotManager.LbResult.Failed>(result).error

	private fun rejected(status: Int, message: String, code: String = "") =
		LbBotManager.LbError.Rejected(status, message, code)

	private val amp = LbSibling("ar-amp", "Belle & Sebastian", albums = 9)
	private val and = LbSibling("ar-and", "Belle and Sebastian", albums = 2)
	private val plus = LbSibling("ar-plus", "Belle + Sebastian", albums = 1)
	private val group = LbSiblingGroup(mbid, listOf(amp, and, plus))

	// --- the wire: GET /lb/overrides gains three lists -----------------------------------------

	private val wire = Json { ignoreUnknownKeys = true; coerceInputValues = true }

	@Test
	fun theThreeListsAreReadOffTheOverridesBody() {
		val body = wire.decodeFromString<LbOverrides>(
			"""{"artists":[],
			"albums":[{"id":"ov-1","state":"applied","releaseMbid":"r-1","prevReleaseMbid":"","artist":"Tame Impala","album":"Currents","albumId":"al-2","done":12,"total":12,"failures":[]}],
			"merges":[{"id":"mg-1","state":"merged","into":{"ndArtistId":"ar-amp","name":"Belle & Sebastian"},"from":{"name":"Belle and Sebastian"},"mbid":"$mbid","done":24,"total":24,"failures":[],"setAt":1760000000.5}],
			"siblings":[{"mbid":"$mbid","artists":[{"ndArtistId":"ar-amp","name":"Belle & Sebastian","albums":9},{"ndArtistId":"ar-and","name":"Belle and Sebastian","albums":2}]}]}"""
		)
		assertEquals(
			listOf(LbAlbumOverrideRow(id = "ov-1", state = "applied", releaseMbid = "r-1", artist = "Tame Impala", album = "Currents", albumId = "al-2", done = 12, total = 12)),
			body.albums
		)
		assertEquals(
			listOf(
				LbMergeRow(
					id = "mg-1",
					state = "merged",
					into = LbMergeArtist("ar-amp", "Belle & Sebastian"),
					from = LbMergeArtist(name = "Belle and Sebastian"),
					mbid = mbid,
					done = 24,
					total = 24,
					setAt = 1760000000.5
				)
			),
			body.merges
		)
		assertEquals(listOf(LbSiblingGroup(mbid, listOf(amp, and))), body.siblings)
	}

	@Test
	fun anAbsentListIsNotAnEmptyOne() {
		// An older lb-bot sends none of the three; a current one that could not work one out
		// leaves that one out. Neither is "there are none".
		val older = wire.decodeFromString<LbOverrides>("""{"artists":[],"toCheck":[]}""")
		assertNull(older.albums)
		assertNull(older.merges)
		assertNull(older.siblings)
		val empty = wire.decodeFromString<LbOverrides>("""{"artists":[],"albums":[],"merges":[],"siblings":[]}""")
		assertEquals(emptyList(), empty.albums)
		assertEquals(emptyList(), empty.merges)
		assertEquals(emptyList(), empty.siblings)
		// Each on its own: one list arriving says nothing about the other two.
		val partial = wire.decodeFromString<LbOverrides>("""{"artists":[],"merges":[]}""")
		assertNull(partial.albums)
		assertEquals(emptyList(), partial.merges)
		assertNull(partial.siblings)
	}

	@Test
	fun theDifferenceSurvivesThePersistedCopy() {
		// The body is stored as text and decoded again at the next start: what was absent then
		// is absent now, and what was empty is empty.
		val stored = """{"artists":[],"siblings":[]}"""
		val restored = wire.decodeFromString<LbOverrides>(stored)
		assertNull(restored.albums)
		assertNull(restored.merges)
		assertEquals(emptyList(), restored.siblings)
	}

	// --- the wire: the four merge routes -------------------------------------------------------

	@Test
	fun thePreviewDecodesAsTheContractWritesIt() {
		val preview = value(
			mergePreviewAnswer(
				200,
				"""{"into":{"ndArtistId":"ar-amp","name":"Belle & Sebastian"},"from":{"ndArtistId":"ar-and","name":"Belle and Sebastian"},
				"mbid":"$mbid","albums":2,"files":24,"sample":[{"album":"Tigermilk","tracks":10},{"album":"The Boy With the Arab Strap","tracks":14}],"extra":true}"""
			)
		)
		assertEquals(
			LbMergePreview(
				into = LbMergeArtist("ar-amp", "Belle & Sebastian"),
				from = LbMergeArtist("ar-and", "Belle and Sebastian"),
				mbid = mbid,
				albums = 2,
				files = 24,
				sample = listOf(LbMergeSample("Tigermilk", 10), LbMergeSample("The Boy With the Arab Strap", 14))
			),
			preview
		)
	}

	@Test
	fun aPreviewThatNamesNoArtistsIsNotAnAnswer() {
		// `{}` would read as "0 files in 0 albums": a count of nothing, said as fact.
		assertEquals(rejected(200, ALBUM_NOT_AN_ANSWER), error(mergePreviewAnswer(200, "{}")))
		assertEquals(rejected(200, ALBUM_NOT_AN_ANSWER), error(mergePreviewAnswer(200, "<html>")))
		assertEquals(
			rejected(200, ALBUM_NOT_AN_ANSWER),
			error(mergePreviewAnswer(200, """{"into":{"ndArtistId":"ar-amp"},"from":{},"files":3}"""))
		)
	}

	@Test
	fun aMergeAndAnUndoAnswerTheRowToFollow() {
		assertEquals(
			LbMergeWrite(ok = true, id = "mg-1", state = "merging"),
			value(mergeWriteAnswer(200, """{"ok":true,"id":"mg-1","state":"merging"}"""))
		)
		assertEquals(
			LbMergeWrite(ok = true, id = "mg-1", state = "undoing"),
			value(mergeWriteAnswer(200, """{"ok":true,"id":"mg-1","state":"undoing"}"""))
		)
		val unconfirmed = rejected(200, WRITE_NOT_CONFIRMED)
		assertEquals(unconfirmed, error(mergeWriteAnswer(200, """{"ok":false,"id":"mg-1"}""")))
		assertEquals(unconfirmed, error(mergeWriteAnswer(200, """{"ok":true}""")))
		assertEquals(unconfirmed, error(mergeWriteAnswer(200, "{}")))
	}

	@Test
	fun theMergeRowDecodesAndARowWithNoIdIsNotOne() {
		val row = value(
			mergeRowAnswer(
				200,
				"""{"id":"mg-1","state":"merged","into":{"ndArtistId":"ar-amp","name":"Belle & Sebastian"},"from":{"name":"Belle and Sebastian"},
				"mbid":"$mbid","done":23,"total":24,"failures":[{"album":"Tigermilk","title":"Electronic Renaissance","reason":"read-only file"}],"setAt":1760000000}"""
			)
		)
		assertEquals("merged", row.state)
		assertEquals("Belle and Sebastian", row.from.name)
		// The rewritten spelling's id is dead once it is merged: lb-bot sends only its name.
		assertEquals("", row.from.ndArtistId)
		assertEquals(listOf(LbMergeFailure("Tigermilk", "Electronic Renaissance", "read-only file")), row.failures)
		assertEquals(rejected(200, ALBUM_NOT_AN_ANSWER), error(mergeRowAnswer(200, "{}")))
		assertEquals(rejected(200, ALBUM_NOT_AN_ANSWER), error(mergeRowAnswer(200, """{"state":"merged"}""")))
	}

	@Test
	fun refusalsKeepLbBotsWordsAndItsCode() {
		assertEquals(
			rejected(409, "These are two different artists on MusicBrainz", "different_artists"),
			error(mergeWriteAnswer(409, """{"error":"These are two different artists on MusicBrainz","code":"different_artists"}"""))
		)
		assertEquals(
			rejected(404, "Navidrome lists neither artist", "unknown_artist"),
			error(mergePreviewAnswer(404, """{"error":"Navidrome lists neither artist","code":"unknown_artist"}"""))
		)
		assertEquals(
			rejected(409, "A download for this artist is running", "busy"),
			error(mergeWriteAnswer(409, """{"error":"A download for this artist is running","code":"busy"}"""))
		)
		assertEquals(
			rejected(404, "No such merge", "no_merge"),
			error(mergeRowAnswer(404, """{"error":"No such merge","code":"no_merge"}"""))
		)
		assertEquals(
			rejected(502, "Navidrome did not answer"),
			error(mergePreviewAnswer(502, """{"error":"Navidrome did not answer","retryable":true}"""))
		)
	}

	@Test
	fun aBareNotFoundOrAMethodNotAllowedIsAnOlderLbBot() {
		val older = LbBotManager.LbError.RouteUnknown
		assertEquals(older, error(mergePreviewAnswer(404, """{"error":"Not found"}""")))
		assertEquals(older, error(mergeRowAnswer(404, "")))
		assertEquals(older, error(mergeWriteAnswer(405, "<html>")))
		// The hub's own 502 / 504: lb-bot was not reached.
		assertEquals(LbBotManager.LbError.Busy, error(mergeWriteAnswer(502, """{"error":"lb-bot unreachable","down":true}""")))
	}

	// --- siblingGroupFor / mergeOthers ---------------------------------------------------------

	@Test
	fun theGroupIsTheOneThatHoldsThisArtist() {
		val another = LbSiblingGroup("m-2", listOf(LbSibling("ar-x", "X", 1), LbSibling("ar-y", "X.", 1)))
		assertEquals(group, siblingGroupFor(listOf(another, group), "ar-and"))
		assertNull(siblingGroupFor(listOf(another), "ar-and"))
		assertNull(siblingGroupFor(emptyList(), "ar-and"))
		// No list at all (an older lb-bot): no group, so no merge block.
		assertNull(siblingGroupFor(null, "ar-and"))
		assertNull(siblingGroupFor(listOf(group), ""))
	}

	@Test
	fun theOtherSpellingsAreEveryoneInTheGroupButThisArtist() {
		assertEquals(listOf(amp, plus), mergeOthers(group, "ar-and"))
		assertEquals(listOf(and, plus), mergeOthers(group, "ar-amp"))
		assertEquals(emptyList(), mergeOthers(null, "ar-and"))
		// A row with no id cannot be merged with, and this artist listed twice is still itself.
		val odd = LbSiblingGroup(mbid, listOf(and, LbSibling("", "Nameless", 4), and, amp))
		assertEquals(listOf(amp), mergeOthers(odd, "ar-and"))
	}

	// --- mergeDefaultKeep ----------------------------------------------------------------------

	@Test
	fun theNameKeptByDefaultIsTheSpellingWithTheMostAlbums() {
		assertEquals("ar-amp", mergeDefaultKeep(listOf(and, amp)))
		assertEquals("ar-amp", mergeDefaultKeep(listOf(amp, and)))
		// On a tie, the first listed — lb-bot lists the entry with the most albums first.
		val tied = listOf(LbSibling("ar-1", "A", 3), LbSibling("ar-2", "B", 3))
		assertEquals("ar-1", mergeDefaultKeep(tied))
		assertEquals("", mergeDefaultKeep(emptyList()))
	}

	// --- planMerge: which id is kept -----------------------------------------------------------

	@Test
	fun keepingThisArtistsNameRewritesTheOtherSpelling() {
		// `nd_id` is the entry whose name is KEPT; `from_nd_id` the spelling that is rewritten.
		assertEquals(
			MergePlan(ndId = "ar-and", fromNdId = "ar-amp"),
			planMerge(thisId = "ar-and", otherId = "ar-amp", keepId = "ar-and")
		)
	}

	@Test
	fun keepingTheOtherSpellingsNameSwapsTheIds() {
		// The page is on "Belle and Sebastian" and the user keeps "Belle & Sebastian": it is THIS
		// artist's files that are rewritten.
		assertEquals(
			MergePlan(ndId = "ar-amp", fromNdId = "ar-and"),
			planMerge(thisId = "ar-and", otherId = "ar-amp", keepId = "ar-amp")
		)
	}

	@Test
	fun aPlanNeedsTwoDifferentArtistsAndAKeptNameThatIsOneOfThem() {
		assertNull(planMerge("ar-and", "ar-and", "ar-and"))
		assertNull(planMerge("ar-and", "", "ar-and"))
		assertNull(planMerge("", "ar-amp", "ar-amp"))
		assertNull(planMerge("ar-and", "ar-amp", "ar-plus"))
		assertNull(planMerge("ar-and", "ar-amp", ""))
	}

	// --- the poll ------------------------------------------------------------------------------

	private fun row(state: String, done: Int = 0, total: Int = 24, failures: Int = 0) = LbMergeRow(
		id = "mg-1",
		state = state,
		into = LbMergeArtist("ar-amp", "Belle & Sebastian"),
		from = LbMergeArtist(name = "Belle and Sebastian"),
		mbid = mbid,
		done = done,
		total = total,
		failures = List(failures) { LbMergeFailure("Tigermilk", "Track $it", "read-only file") }
	)

	private fun answered(state: String, done: Int = 0, failures: Int = 0) =
		MergePollRead.Row(row(state, done, failures = failures))

	private fun MergePoll.then(read: MergePollRead, atMs: Long) = mergePollStep(this, read, atMs)

	@Test
	fun aMergeIsFollowedUntilLbBotSaysMerged() {
		var poll = mergePollStart(MergeJob.MERGE, nowMs = 0)
		assertEquals(LbJobPhase.POLLING, poll.phase)
		assertNull(mergeOutcome(poll))
		poll = poll.then(answered("merging", done = 5), 2_000)
		assertEquals(LbJobPhase.POLLING, poll.phase)
		assertEquals(5, poll.row?.done)
		poll = poll.then(MergePollRead.Missed, 4_000)
		// A failed poll is not an answer: nothing ended, and the progress last heard stays.
		assertEquals(LbJobPhase.POLLING, poll.phase)
		assertEquals(5, poll.row?.done)
		poll = poll.then(answered("merged", done = 24), 6_000)
		assertEquals(LbJobPhase.DONE, poll.phase)
		assertEquals(MergeOutcome.Retagged(files = 24), mergeOutcome(poll))
	}

	@Test
	fun mergedWithFailuresIsAPartialResultAndFailedDidNotFinish() {
		val partial = mergePollStart(MergeJob.MERGE, 0).then(answered("merged", done = 22, failures = 2), 2_000)
		assertEquals(MergeOutcome.RetaggedPartly(done = 22, total = 24, failed = 2), mergeOutcome(partial))
		val failed = mergePollStart(MergeJob.MERGE, 0).then(answered("failed", done = 7), 2_000)
		assertEquals(LbJobPhase.FAILED, failed.phase)
		assertEquals(MergeOutcome.DidNotFinish(done = 7, total = 24), mergeOutcome(failed))
	}

	@Test
	fun theRowVanishingUnderAMergeIsItsOwnEndingAndTheClockIsThreeMinutes() {
		val gone = mergePollStart(MergeJob.MERGE, 0).then(MergePollRead.Gone, 2_000)
		assertEquals(LbJobPhase.GONE, gone.phase)
		assertEquals(MergeOutcome.Gone, mergeOutcome(gone))
		val waiting = mergePollStart(MergeJob.MERGE, 0).then(answered("merging", done = 5), LB_JOB_GIVE_UP_MS - 1)
		assertEquals(LbJobPhase.POLLING, waiting.phase)
		val timedOut = waiting.then(MergePollRead.Missed, LB_JOB_GIVE_UP_MS)
		assertEquals(LbJobPhase.TIMED_OUT, timedOut.phase)
		assertEquals(MergeOutcome.StillWorking, mergeOutcome(timedOut))
	}

	@Test
	fun anUndoEndsWhenTheRowIsGone() {
		var poll = mergePollStart(MergeJob.UNDO, 0).then(answered("undoing"), 2_000)
		assertEquals(LbJobPhase.POLLING, poll.phase)
		poll = poll.then(MergePollRead.Gone, 4_000)
		assertEquals(LbJobPhase.DONE, poll.phase)
		assertEquals(MergeOutcome.Undone, mergeOutcome(poll))
	}

	@Test
	fun anUndoIsNotJudgedByTheStateItStartedFrom() {
		// The first poll can land before lb-bot has flipped the row: `merged` is then the OLD state.
		var poll = mergePollStart(MergeJob.UNDO, 0).then(answered("merged", done = 24), 2_000)
		assertEquals(LbJobPhase.POLLING, poll.phase)
		poll = poll.then(answered("undoing"), 4_000).then(answered("merged", done = 24), 6_000)
		// Seen undoing, and the row stands again: the undo did not finish.
		assertEquals(LbJobPhase.FAILED, poll.phase)
		assertEquals(MergeOutcome.NotUndone, mergeOutcome(poll))
	}

	@Test
	fun onlyLbBotsRowOrItsNoMergeIsAnAnswer() {
		val merging = row("merging", done = 1)
		assertEquals(MergePollRead.Row(merging), mergePollRead(LbBotManager.LbResult.Ok(merging)))
		fun failed(error: LbBotManager.LbError) = mergePollRead(LbBotManager.LbResult.Failed(error))
		assertEquals(MergePollRead.Gone, failed(rejected(404, "No such merge", "no_merge")))
		// Another feature's "gone" is not this one's.
		assertEquals(MergePollRead.Missed, failed(rejected(404, "No such override", "no_override")))
		assertEquals(MergePollRead.Missed, failed(rejected(502, "Navidrome did not answer")))
		assertEquals(MergePollRead.Missed, failed(LbBotManager.LbError.RouteUnknown))
		assertEquals(MergePollRead.Missed, failed(LbBotManager.LbError.Busy))
		assertEquals(MergePollRead.Missed, failed(LbBotManager.LbError.Unreachable("timeout")))
	}

	@Test
	fun aJobFoundRunningIsPickedUp() {
		assertEquals(MergeJob.MERGE, mergeJobInFlight(row("merging")))
		assertEquals(MergeJob.UNDO, mergeJobInFlight(row("undoing")))
		assertNull(mergeJobInFlight(row("merged")))
		assertNull(mergeJobInFlight(row("failed")))
		val seen = row("undoing")
		val poll = mergePollStart(MergeJob.UNDO, 0, seen = seen)
		assertEquals("undoing", poll.row?.state)
		assertEquals(LbJobPhase.FAILED, poll.then(answered("merged"), 2_000).phase)
	}

	// --- the job, once it is no longer the sheet's ---------------------------------------------

	private val endedAt = 50_000L

	private fun job(
		poll: MergePoll,
		ended: Boolean = true,
		intoId: String = "ar-amp",
		fromId: String = "ar-and"
	) = MergeJobState(
		rowId = "mg-1",
		intoId = intoId,
		fromId = fromId,
		intoName = "Belle & Sebastian",
		fromName = "Belle and Sebastian",
		poll = poll,
		endedAtMs = if (ended) endedAt else 0L
	)

	private val merged = mergePollStart(MergeJob.MERGE, 0).then(answered("merged", done = 24), 2_000)
	private val undone = mergePollStart(MergeJob.UNDO, 0).then(answered("undoing"), 2_000).then(MergePollRead.Gone, 4_000)

	// --- mergeBlockView: the result must survive its own cause ---------------------------------

	@Test
	fun withAnotherSpellingAndTheRouteTheBlockIsTheForm() {
		assertEquals(MergeBlockView.Form, mergeBlockView(controls = true, hasOthers = true, job = null))
	}

	@Test
	fun withNoOtherSpellingOrNoRouteThereIsNoBlock() {
		assertEquals(MergeBlockView.Hidden, mergeBlockView(controls = true, hasOthers = false, job = null))
		assertEquals(MergeBlockView.Hidden, mergeBlockView(controls = false, hasOthers = true, job = null))
	}

	@Test
	fun theGroupGoneAndTheJobSaysMerged() {
		// A finished merge leaves one spelling, and `siblings` only lists groups of two or more:
		// the group that offered the merge is gone BECAUSE it worked. The result stays, and its
		// names are the row's own.
		assertEquals(
			MergeBlockView.Job(from = "Belle and Sebastian", into = "Belle & Sebastian", undo = false),
			mergeBlockView(controls = true, hasOthers = false, job = job(merged))
		)
	}

	@Test
	fun theGroupGoneAndTheJobSaysFailedWithFailures() {
		val failed = mergePollStart(MergeJob.MERGE, 0).then(answered("failed", done = 7, failures = 3), 2_000)
		val view = mergeBlockView(controls = true, hasOthers = false, job = job(failed))
		assertEquals(MergeBlockView.Job(from = "Belle and Sebastian", into = "Belle & Sebastian", undo = false), view)
		// What the block then says is the job's, with every failure lb-bot listed.
		assertEquals(MergeOutcome.DidNotFinish(done = 7, total = 24), mergeOutcome(failed))
		assertEquals(3, failed.row?.failures?.size)
	}

	@Test
	fun aJobIsShownEvenWhereTheFormWouldNotBe() {
		// The hub stopped advertising the route mid-job, or the advert has not arrived yet.
		assertEquals(
			MergeBlockView.Job(from = "Belle and Sebastian", into = "Belle & Sebastian", undo = false),
			mergeBlockView(controls = false, hasOthers = false, job = job(merged))
		)
		// And while the form WOULD be there: the job comes first.
		assertIs<MergeBlockView.Job>(mergeBlockView(controls = true, hasOthers = true, job = job(merged)))
	}

	@Test
	fun theNamesAreTheRowsAndUntilARowArrivesTheOnesTheJobWasStartedWith() {
		val unanswered = job(mergePollStart(MergeJob.MERGE, 0), ended = false)
		assertEquals(
			MergeBlockView.Job(from = "Belle and Sebastian", into = "Belle & Sebastian", undo = false),
			mergeBlockView(controls = true, hasOthers = true, job = unanswered)
		)
		// lb-bot's row names them differently (it normalised a name): the row wins.
		val renamed = mergePollStart(MergeJob.MERGE, 0).then(
			MergePollRead.Row(row("merging").copy(into = LbMergeArtist("ar-amp", "Belle & Sebastian (UK)"), from = LbMergeArtist(name = "Belle And Sebastian"))),
			2_000
		)
		assertEquals(
			MergeBlockView.Job(from = "Belle And Sebastian", into = "Belle & Sebastian (UK)", undo = false),
			mergeBlockView(controls = true, hasOthers = true, job = job(renamed, ended = false))
		)
	}

	@Test
	fun anUndoIsShownAsAnUndo() {
		assertEquals(
			MergeBlockView.Job(from = "Belle and Sebastian", into = "Belle & Sebastian", undo = true),
			mergeBlockView(controls = true, hasOthers = false, job = job(undone))
		)
	}

	// --- mergeJobFor / mergeJobInWay -----------------------------------------------------------

	@Test
	fun aJobConcernsBothOfItsArtists() {
		val jobs = listOf(job(merged), job(merged, intoId = "ar-x", fromId = "ar-y").copy(rowId = "mg-2"))
		assertEquals("mg-1", mergeJobFor(jobs, "ar-amp")?.rowId)
		assertEquals("mg-1", mergeJobFor(jobs, "ar-and")?.rowId)
		assertEquals("mg-2", mergeJobFor(jobs, "ar-y")?.rowId)
		assertNull(mergeJobFor(jobs, "ar-plus"))
		assertNull(mergeJobFor(jobs, ""))
	}

	@Test
	fun theNewestJobForAnArtistIsTheOneShown() {
		val earlier = job(merged)
		val later = job(undone).copy(rowId = "mg-9")
		assertEquals("mg-9", mergeJobFor(listOf(earlier, later), "ar-amp")?.rowId)
	}

	@Test
	fun aSecondMergeOrUndoWhileOneIsFollowedIsRefused() {
		val running = job(mergePollStart(MergeJob.MERGE, 0), ended = false)
		assertEquals("mg-1", mergeJobInWay(listOf(running), listOf("ar-amp", "ar-plus"))?.rowId)
		assertEquals("mg-1", mergeJobInWay(listOf(running), listOf("ar-plus", "ar-and"))?.rowId)
		assertNull(mergeJobInWay(listOf(running), listOf("ar-plus", "ar-x")))
		// By the row itself, as an undo from the data panel reaches it.
		assertEquals("mg-1", mergeJobInWay(listOf(running), emptyList(), rowId = "mg-1")?.rowId)
		assertNull(mergeJobInWay(listOf(running), listOf("", ""), rowId = "mg-7"))
		// An ended job is in nobody's way.
		assertNull(mergeJobInWay(listOf(job(merged)), listOf("ar-amp", "ar-and"), rowId = "mg-1"))
	}

	// --- mergeLeave: the rewritten spelling's page ---------------------------------------------

	@Test
	fun aPageShowingTheRewrittenSpellingGoesToTheKeptArtist() {
		assertEquals(MergeLeave.ToArtist("ar-amp"), mergeLeave(job(merged), shownArtistId = "ar-and", onScreenSinceMs = 10_000))
	}

	@Test
	fun theKeptArtistsPageAndEveryOtherPageStay() {
		assertEquals(MergeLeave.Stay, mergeLeave(job(merged), "ar-amp", 10_000))
		assertEquals(MergeLeave.Stay, mergeLeave(job(merged), "ar-plus", 10_000))
		assertEquals(MergeLeave.Stay, mergeLeave(job(merged), "", 10_000))
		// Matched by the whole id.
		assertEquals(MergeLeave.Stay, mergeLeave(job(merged), "ar-and-2", 10_000))
	}

	@Test
	fun nothingMovesUntilTheMergeHasLandedAndTheLibraryIsSettled() {
		val running = job(mergePollStart(MergeJob.MERGE, 0).then(answered("merging", done = 5), 2_000), ended = false)
		assertEquals(MergeLeave.Stay, mergeLeave(running, "ar-and", 10_000))
		// lb-bot said merged; the library is still being brought to agree.
		assertEquals(MergeLeave.Stay, mergeLeave(job(merged, ended = false), "ar-and", 10_000))
	}

	@Test
	fun aMergeThatDidNotLandMovesNobody() {
		val failed = mergePollStart(MergeJob.MERGE, 0).then(answered("failed", done = 7), 2_000)
		assertEquals(MergeLeave.Stay, mergeLeave(job(failed), "ar-and", 10_000))
		val timedOut = mergePollStart(MergeJob.MERGE, 0).then(MergePollRead.Missed, LB_JOB_GIVE_UP_MS)
		assertEquals(MergeLeave.Stay, mergeLeave(job(timedOut), "ar-and", 10_000))
		val gone = mergePollStart(MergeJob.MERGE, 0).then(MergePollRead.Gone, 2_000)
		assertEquals(MergeLeave.Stay, mergeLeave(job(gone), "ar-and", 10_000))
	}

	@Test
	fun anUndoKillsNoId() {
		// The rewritten spelling comes back as an artist of its own; the kept one stays.
		assertEquals(MergeLeave.Stay, mergeLeave(job(undone), "ar-and", 10_000))
		assertEquals(MergeLeave.Stay, mergeLeave(job(undone), "ar-amp", 10_000))
	}

	@Test
	fun aPageOpenedAfterTheMergeEndedIsNotMoved() {
		// A merge with files lb-bot could not write leaves the old spelling an artist of its own.
		assertEquals(MergeLeave.Stay, mergeLeave(job(merged), "ar-and", onScreenSinceMs = endedAt + 1))
		assertEquals(MergeLeave.ToArtist("ar-amp"), mergeLeave(job(merged), "ar-and", onScreenSinceMs = endedAt))
	}

	@Test
	fun withNoIdForEitherArtistNothingMoves() {
		// An undo or a merge reached from the data panel knows no rewritten id.
		assertEquals(MergeLeave.Stay, mergeLeave(job(merged, fromId = ""), "", 10_000))
		assertEquals(MergeLeave.Stay, mergeLeave(job(merged, intoId = ""), "ar-and", 10_000))
	}

	// --- mergeAnnounced: who says what came of it ----------------------------------------------

	@Test
	fun aJobNobodyIsShowingIsAnnounced() {
		assertEquals(true, mergeAnnounced(job(merged), blocksOn = emptySet()))
		assertEquals(true, mergeAnnounced(job(merged), blocksOn = setOf("ar-plus")))
	}

	@Test
	fun aBlockOnTheKeptArtistAlreadySaysIt() {
		assertEquals(false, mergeAnnounced(job(merged), blocksOn = setOf("ar-amp")))
		assertEquals(false, mergeAnnounced(job(undone), blocksOn = setOf("ar-and")))
	}

	@Test
	fun aBlockOnAPageThatIsAboutToBeLeftDoesNotCount() {
		// The merge landed: the rewritten spelling's page goes, and its block with it.
		assertEquals(true, mergeAnnounced(job(merged), blocksOn = setOf("ar-and")))
		// A merge that failed leaves that page where it is, block and all.
		val failed = mergePollStart(MergeJob.MERGE, 0).then(answered("failed", done = 7), 2_000)
		assertEquals(false, mergeAnnounced(job(failed), blocksOn = setOf("ar-and")))
	}

	// --- mergeResyncArtistId: whose albums the library reads again -----------------------------

	@Test
	fun afterAMergeTheRewrittenSpellingsAlbumsAreReadAgain() {
		assertEquals("ar-and", mergeResyncArtistId(merged, intoId = "ar-amp", fromId = "ar-and"))
		// A merge that failed part-way moved some of them too.
		val failed = mergePollStart(MergeJob.MERGE, 0).then(answered("failed", done = 7), 2_000)
		assertEquals("ar-and", mergeResyncArtistId(failed, "ar-amp", "ar-and"))
	}

	@Test
	fun afterAnUndoTheKeptArtistsAlbumsAreReadAgain() {
		// Some of them go back to the spelling that was restored.
		assertEquals("ar-amp", mergeResyncArtistId(undone, intoId = "ar-amp", fromId = "ar-and"))
		assertEquals("ar-amp", mergeResyncArtistId(undone, intoId = "ar-amp", fromId = ""))
	}

	@Test
	fun nothingIsReadAgainForAJobThatSaidNothing() {
		val timedOut = mergePollStart(MergeJob.MERGE, 0).then(MergePollRead.Missed, LB_JOB_GIVE_UP_MS)
		assertNull(mergeResyncArtistId(timedOut, "ar-amp", "ar-and"))
		val gone = mergePollStart(MergeJob.MERGE, 0).then(MergePollRead.Gone, 2_000)
		assertNull(mergeResyncArtistId(gone, "ar-amp", "ar-and"))
		assertNull(mergeResyncArtistId(mergePollStart(MergeJob.MERGE, 0), "ar-amp", "ar-and"))
	}

	@Test
	fun aMergeWhoseRewrittenIdNobodyKnowsStillHasTheArtistListReadAgain() {
		// lb-bot's row names the rewritten spelling by name only. Blank is "no id to read albums
		// by", which is not "read nothing": the artist list needs no id.
		assertEquals("", mergeResyncArtistId(merged, intoId = "ar-amp", fromId = ""))
	}

	// --- mergeToPickUp: a merge found already running ------------------------------------------

	@Test
	fun aMergeLbBotIsStillWorkingOnIsPickedUpByTheKeptArtistsBlock() {
		val merging = row("merging", done = 3)
		assertEquals(merging, mergeToPickUp(true, listOf(merging), emptyMap(), "ar-amp"))
		val undoing = row("undoing")
		assertEquals(undoing, mergeToPickUp(true, listOf(row("merged").copy(id = "mg-0"), undoing), emptyMap(), "ar-amp"))
	}

	@Test
	fun aMergeAtRestOrAlreadyFollowedOrSomeoneElsesIsNotPickedUp() {
		val merging = row("merging")
		// At rest: there is nothing to follow.
		assertNull(mergeToPickUp(true, listOf(row("merged"), row("failed")), emptyMap(), "ar-amp"))
		// Followed here, or followed to an ending here — a poll that gave up is not restarted.
		assertNull(mergeToPickUp(true, listOf(merging), mapOf("mg-1" to job(mergePollStart(MergeJob.MERGE, 0), ended = false)), "ar-amp"))
		val gaveUp = mergePollStart(MergeJob.MERGE, 0).then(MergePollRead.Missed, LB_JOB_GIVE_UP_MS)
		assertNull(mergeToPickUp(true, listOf(merging), mapOf("mg-1" to job(gaveUp)), "ar-amp"))
		// Another artist's, and an artist with no id.
		assertNull(mergeToPickUp(true, listOf(merging), emptyMap(), "ar-and"))
		assertNull(mergeToPickUp(true, listOf(merging), emptyMap(), ""))
		// No list from lb-bot, and no merge route to follow it by.
		assertNull(mergeToPickUp(true, null, emptyMap(), "ar-amp"))
		assertNull(mergeToPickUp(false, listOf(merging), emptyMap(), "ar-amp"))
	}
}
