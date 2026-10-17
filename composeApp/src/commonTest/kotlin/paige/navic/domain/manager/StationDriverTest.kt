package paige.navic.domain.manager

import paige.navic.domain.models.settings.StationCharacter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The station driver's rules (`StationModels.kt`): when autoplay asks `next`, starts a station
 * from a queue no station built, stops, or hands over to Auto DJ; the cursor (`after`); what an
 * answer does to the record (served elsewhere, exhausted, partial); what is left in PLAY order;
 * which station a queue belongs to; and the queue sheet's line.
 */
class StationDriverTest {

	private val radio = StationProvenance(
		queueId = "q_1",
		stationId = "st_1",
		name = "Cinnamon Girl radio",
		character = "balanced",
		engine = "tier2",
		seed = StationSeed(StationSeed.TRACK, listOf("seed"), "Cinnamon Girl"),
		seq = 20,
		rev = 0
	)

	private fun look(
		remaining: Int,
		bound: StationProvenance? = radio,
		available: Boolean = true,
		on: Boolean = true,
		remote: Boolean = false,
		paused: Boolean = false,
		repeat: Int = 0,
		inFlight: Boolean = false,
		size: Int = 21,
		index: Int = 20 - remaining,
		timing: Int = 1,
		elsewhere: Boolean = false
	) = AutoplayLook(
		autoplayOn = on,
		remoteActive = remote,
		paused = paused,
		repeatMode = repeat,
		queueSize = size,
		currentIndex = index.coerceAtLeast(0),
		remaining = remaining,
		stationsAvailable = available,
		bound = bound,
		inFlight = inFlight,
		autoDjTiming = timing,
		elsewhereRecent = elsewhere
	)

	private fun answer(
		vararg ids: String,
		seq: Int = 25,
		exhausted: Boolean = false,
		partial: Boolean = false,
		servedElsewhere: Boolean = false,
		engine: String? = "tier2"
	) = StationAnswer(
		stationId = "st_1",
		seq = seq,
		rev = 0,
		engine = engine,
		tracks = ids.map { StationTrack(id = it) },
		exhausted = exhausted,
		partial = partial,
		servedElsewhere = servedElsewhere
	)

	// ---- start / next / stop ----------------------------------------------------------------- //

	@Test
	fun aStationQueueAsksNextWhenFewerThanTwoAreLeftFromItsCursor() {
		assertEquals(AutoplayStep.Idle, autoplayStep(look(remaining = 2)))
		val step = autoplayStep(look(remaining = 1))
		assertEquals(AutoplayStep.Next("st_1", after = 20), step)
		assertEquals(AutoplayStep.Next("st_1", after = 20), autoplayStep(look(remaining = 0)))
	}

	@Test
	fun aQueueNoStationBuiltStartsOneAtTheSamePoint() {
		assertEquals(AutoplayStep.Idle, autoplayStep(look(remaining = 3, bound = null)))
		assertEquals(AutoplayStep.Start, autoplayStep(look(remaining = 1, bound = null)))
	}

	@Test
	fun anExhaustedStationStops() {
		val step = autoplayStep(look(remaining = 0, bound = radio.copy(exhausted = true)))
		assertEquals(AutoplayStep.Exhausted, step)
	}

	@Test
	fun withoutTheEngineAutoDjRunsAtItsOwnTiming() {
		assertEquals(AutoplayStep.Idle, autoplayStep(look(remaining = 1, available = false)))
		assertEquals(AutoplayStep.AutoDj, autoplayStep(look(remaining = 0, available = false)))
		assertEquals(AutoplayStep.AutoDj, autoplayStep(look(remaining = 2, available = false, timing = 3)))
	}

	@Test
	fun nothingHappensWhileOffRemotePausedRepeatingBusyOrEmpty() {
		assertEquals(AutoplayStep.Idle, autoplayStep(look(remaining = 0, on = false)))
		assertEquals(AutoplayStep.Idle, autoplayStep(look(remaining = 0, remote = true)))
		assertEquals(AutoplayStep.Idle, autoplayStep(look(remaining = 0, paused = true)))
		assertEquals(AutoplayStep.Idle, autoplayStep(look(remaining = 0, repeat = 2)))
		assertEquals(AutoplayStep.Idle, autoplayStep(look(remaining = 0, inFlight = true)))
		assertEquals(AutoplayStep.Idle, autoplayStep(look(remaining = 0, size = 0, index = -1)))
	}

	// ---- answers ------------------------------------------------------------------------------- //

	@Test
	fun aBatchAppendsWhatIsNotQueuedAndMovesTheCursor() {
		val batch = stationBatch(radio, answer("a", "b", "seed", "c", seq = 25), queued = setOf("seed", "x"), fresh = false)
		assertEquals(listOf("a", "b", "c"), batch.append)
		assertEquals(25, batch.provenance.seq)
		assertFalse(batch.retrySoon)
		assertFalse(batch.provenance.exhausted)
	}

	@Test
	fun servedElsewhereAdoptsTheNewCursorAndAppendsNothing() {
		val batch = stationBatch(radio, answer(seq = 30, servedElsewhere = true), queued = emptySet(), fresh = false)
		assertTrue(batch.append.isEmpty())
		assertEquals(30, batch.provenance.seq)
		assertFalse(batch.retrySoon)
		// The next `next` goes from there.
		assertEquals(AutoplayStep.Next("st_1", after = 30), autoplayStep(look(remaining = 0, bound = batch.provenance)))
	}

	@Test
	fun afterAnotherDevicesTopUpTheQueueWaitsForThatBatch() {
		// Two devices at the queue's end: the other one's batch is on its way through the
		// session. Asking from the new cursor at once added a second batch (walk S5, 31 -> 41).
		val bound = stationBatch(radio, answer(seq = 25, servedElsewhere = true), emptySet(), fresh = false).provenance
		assertEquals(AutoplayStep.Idle, autoplayStep(look(remaining = 1, bound = bound, elsewhere = true)))
		assertEquals(AutoplayStep.Idle, autoplayStep(look(remaining = 0, bound = bound, elsewhere = true)))
		// After the grace, if the batch never came, it asks from there.
		assertEquals(AutoplayStep.Next("st_1", after = 25), autoplayStep(look(remaining = 1, bound = bound)))
		// The wait is the bound station's: a queue no station built still starts one, and an
		// exhausted station still says so.
		assertEquals(AutoplayStep.Start, autoplayStep(look(remaining = 1, bound = null, elsewhere = true)))
		assertEquals(
			AutoplayStep.Exhausted,
			autoplayStep(look(remaining = 1, bound = radio.copy(exhausted = true), elsewhere = true))
		)
	}

	@Test
	fun servedElsewhereNeverMovesTheCursorBack() {
		val batch = stationBatch(radio.copy(seq = 40), answer(seq = 30, servedElsewhere = true), emptySet(), fresh = false)
		assertEquals(40, batch.provenance.seq)
	}

	@Test
	fun exhaustedIsKeptAndStopsTheDriver() {
		val batch = stationBatch(radio, answer(exhausted = true, seq = 20), emptySet(), fresh = false)
		assertTrue(batch.provenance.exhausted)
		assertFalse(batch.retrySoon, "exhausted is an answer: nothing to ask again")
		assertEquals(AutoplayStep.Exhausted, autoplayStep(look(remaining = 0, bound = batch.provenance)))
	}

	@Test
	fun aPartialAnswerWithNothingIsAskedAgainSoonAndIsNotExhausted() {
		val batch = stationBatch(radio, answer(partial = true, seq = 20), emptySet(), fresh = false)
		assertTrue(batch.retrySoon)
		assertFalse(batch.provenance.exhausted)
		assertEquals(AutoplayStep.Next("st_1", 20), autoplayStep(look(remaining = 0, bound = batch.provenance)))
	}

	@Test
	fun aFreshStationTakesTheAnswersCursorWhateverTheRecordHeld() {
		val restarted = stationBatch(radio.copy(seq = 80), answer("a", seq = 5).copy(stationId = "st_2"), emptySet(), fresh = true)
		assertEquals("st_2", restarted.provenance.stationId)
		assertEquals(5, restarted.provenance.seq)
		assertEquals("q_1", restarted.provenance.queueId)
	}

	@Test
	fun aBatchAfterAnAutoDjTopUpClearsTheAutoDjMark() {
		val batch = stationBatch(radio.copy(lastFromAutoDj = true), answer("a"), emptySet(), fresh = false)
		assertFalse(batch.provenance.lastFromAutoDj)
	}

	@Test
	fun aStationFrameMovesTheCursorAndCanClearExhausted() {
		val frame = HubStationFrame("st_1", seq = 35, rev = 2, character = "explore", exhausted = false)
		val next = radio.copy(exhausted = true).withFrame(frame)
		assertEquals(35, next.seq)
		assertEquals(2, next.rev)
		assertEquals("explore", next.character)
		assertFalse(next.exhausted)
		// Another station's frame changes nothing.
		assertEquals(radio, radio.withFrame(frame.copy(stationId = "st_9", seq = 99)))
	}

	// ---- play order ---------------------------------------------------------------------------- //

	@Test
	fun remainingIsCountedInPlayOrderUnderShuffle() {
		// Queue order 0..4, shuffled to play 3, 0, 4, 1, 2. Playing index 4 (third): two left,
		// although it is the last in the queue.
		val order = listOf(3, 0, 4, 1, 2)
		assertEquals(2, playOrderRemaining(order, queueSize = 5, currentIndex = 4))
		assertEquals(0, playOrderRemaining(order, queueSize = 5, currentIndex = 2))
		assertEquals(4, playOrderRemaining(order, queueSize = 5, currentIndex = 3))
		// Queue order.
		assertEquals(0, playOrderRemaining(null, queueSize = 5, currentIndex = 4))
		assertEquals(3, playOrderRemaining(null, queueSize = 5, currentIndex = 1))
		// An order from a timeline mid-change is read as queue order.
		assertEquals(3, playOrderRemaining(listOf(0, 1), queueSize = 5, currentIndex = 1))
		assertEquals(0, playOrderRemaining(null, queueSize = 0, currentIndex = -1))
	}

	@Test
	fun theLastTracksPlayedAreTakenInPlayOrder() {
		val order = listOf(3, 0, 4, 1, 2)
		assertEquals(listOf(1, 4, 0), playOrderRecent(order, 5, currentIndex = 1, n = 3))
		assertEquals(listOf(3), playOrderRecent(order, 5, currentIndex = 3, n = 3))
		assertEquals(listOf(4, 3, 2), playOrderRecent(null, 5, currentIndex = 4, n = 3))
		assertTrue(playOrderRecent(null, 5, currentIndex = 9, n = 3).isEmpty())
	}

	// ---- which station a queue belongs to ------------------------------------------------------ //

	@Test
	fun theLocalRecordBelongsToItsQueueOnly() {
		assertEquals(radio, boundStation(radio, "q_1", null, { null }, null))
		assertNull(boundStation(radio, "q_album", null, { null }, null), "a new queue is no radio")
		assertNull(boundStation(radio, null, null, { null }, null))
	}

	@Test
	fun aQueueHandedHereContinuesTheSessionsStationFromItsBroadcastCursor() {
		val frame = HubStationFrame("st_7", seq = 40, rev = 1, character = "close", exhausted = false, name = "Your mix")
		val bound = boundStation(null, "q_9", "st_7", { if (it == "st_7") frame else null }, "Session name")
		assertEquals("st_7", bound?.stationId)
		assertEquals(40, bound?.seq)
		assertEquals("close", bound?.character)
		assertEquals("Your mix", bound?.name)
		assertNull(bound?.seed, "a station this device has no record of cannot be restarted from a seed")
		// Our own record of that station keeps its seed when the queue id changed (a transfer).
		val mine = radio.copy(stationId = "st_7")
		assertEquals(mine.seed, boundStation(mine, "q_9", "st_7", { frame }, null)?.seed)
	}

	@Test
	fun theSessionNamingAnotherStationWinsOverTheLocalRecord() {
		val bound = boundStation(radio, "q_1", "st_other", { null }, "Album radio")
		assertEquals("st_other", bound?.stationId)
		assertEquals("Album radio", bound?.name)
	}

	// ---- the queue sheet's line ---------------------------------------------------------------- //

	@Test
	fun theLineNamesTheStationAndItsCharacter() {
		val line = queueGenerator(radio, "q_1", null, generatedMix = false)
		assertEquals(QueueGenerator.Station("Cinnamon Girl radio", StationCharacter.Balanced, viaNavidrome = false, yourMix = false), line)
	}

	@Test
	fun tier1SaysViaNavidrome() {
		val line = queueGenerator(radio.copy(engine = ENGINE_TIER1), "q_1", null, false)
		assertIs<QueueGenerator.Station>(line)
		assertTrue(line.viaNavidrome)
	}

	@Test
	fun yourMixIsNamedAsSuch() {
		val line = queueGenerator(radio.copy(seed = StationSeed.YourMix, name = ""), "q_1", null, false)
		assertIs<QueueGenerator.Station>(line)
		assertTrue(line.yourMix)
	}

	@Test
	fun exhaustedOffersExploreUnlessAlreadyExploring() {
		assertEquals(
			QueueGenerator.Exhausted("Cinnamon Girl radio", offerExplore = true),
			queueGenerator(radio.copy(exhausted = true), "q_1", null, false)
		)
		assertEquals(
			QueueGenerator.Exhausted("Cinnamon Girl radio", offerExplore = false),
			queueGenerator(radio.copy(exhausted = true, character = "explore"), "q_1", null, false)
		)
	}

	@Test
	fun autoDjIsSaidWhereAutoDjToppedTheQueueUp() {
		assertEquals(QueueGenerator.AutoDj, queueGenerator(null, "q_album", "q_album", false))
		assertEquals(QueueGenerator.AutoDj, queueGenerator(radio.copy(lastFromAutoDj = true), "q_1", null, false))
		// Another queue's Auto DJ top-up is not this queue's.
		assertNull(queueGenerator(null, "q_other", "q_album", false))
	}

	@Test
	fun aPlainQueueSaysNothingWhateverTheAutoplaySetting() {
		assertNull(queueGenerator(null, "q_album", null, generatedMix = false))
		assertEquals(QueueGenerator.AudioMuseMix, queueGenerator(null, "q_mix", null, generatedMix = true))
	}

	// ---- origin: a radio keeps going, a continuation stops with autoplay ----------------------- //

	@Test
	fun theOriginRuleOnAndOffForRadioContinueAndUnknown() {
		val origins = listOf(ORIGIN_RADIO, ORIGIN_CONTINUE, null)
		for (origin in origins) {
			val bound = radio.copy(origin = origin)
			// Autoplay on: every bound station continues.
			assertEquals(AutoplayStep.Next("st_1", 20), autoplayStep(look(remaining = 1, bound = bound, on = true)), "on, $origin")
			// Autoplay off: only a radio does. An origin nobody said is a continuation.
			val off = autoplayStep(look(remaining = 1, bound = bound, on = false))
			if (origin == ORIGIN_RADIO) assertEquals(AutoplayStep.Next("st_1", 20), off, "off, radio")
			else assertEquals(AutoplayStep.Idle, off, "off, $origin")
		}
	}

	@Test
	fun withAutoplayOffNothingNewIsStartedAndAutoDjDoesNothing() {
		assertEquals(AutoplayStep.Start, autoplayStep(look(remaining = 1, bound = null, on = true)))
		assertEquals(AutoplayStep.Idle, autoplayStep(look(remaining = 1, bound = null, on = false)))
		assertEquals(AutoplayStep.AutoDj, autoplayStep(look(remaining = 0, available = false, on = true)))
		assertEquals(AutoplayStep.Idle, autoplayStep(look(remaining = 0, available = false, on = false)))
		// A radio with autoplay off and the engine gone: Auto DJ obeys the switch.
		assertEquals(
			AutoplayStep.Idle,
			autoplayStep(look(remaining = 0, available = false, on = false, bound = radio.copy(origin = ORIGIN_RADIO)))
		)
	}

	@Test
	fun anOriginTheHubDoesNotEchoIsWhatThisDeviceSentElseAContinuation() {
		assertEquals(ORIGIN_CONTINUE, stationOrigin(echoed = ORIGIN_CONTINUE, sentHere = ORIGIN_RADIO), "the hub's echo wins")
		assertEquals(ORIGIN_RADIO, stationOrigin(echoed = null, sentHere = ORIGIN_RADIO), "an older hub: our entry point")
		assertEquals(ORIGIN_CONTINUE, stationOrigin(echoed = null, sentHere = ORIGIN_CONTINUE))
		assertEquals(ORIGIN_CONTINUE, stationOrigin(echoed = null, sentHere = null), "another device's, unsaid")
		assertEquals(ORIGIN_RADIO, stationOrigin(echoed = "", sentHere = ORIGIN_RADIO))
	}

	@Test
	fun theAnswersAndTheFramesOriginAndNameAreKept() {
		val answered = stationBatch(radio, answer("a").copy(origin = ORIGIN_RADIO), emptySet(), fresh = false)
		assertEquals(ORIGIN_RADIO, answered.provenance.effectiveOrigin)
		// An answer that says nothing keeps what the record had.
		val silent = stationBatch(radio.copy(origin = ORIGIN_RADIO), answer("a"), emptySet(), fresh = false)
		assertEquals(ORIGIN_RADIO, silent.provenance.origin)
		val framed = radio.withFrame(
			HubStationFrame("st_1", seq = 22, rev = 0, character = "", exhausted = false, name = "Renamed radio", origin = ORIGIN_CONTINUE)
		)
		assertEquals("Renamed radio", framed.name)
		assertEquals(ORIGIN_CONTINUE, framed.effectiveOrigin)
		assertEquals("balanced", framed.character, "a frame with no character keeps it")
	}

	@Test
	fun boundFalseAfterAskingToBindLeavesTheStationUnbound() {
		val refused = stationBatch(radio, answer("a").copy(bound = false), emptySet(), fresh = true, askedToBind = true)
		assertTrue(refused.provenance.unbound)
		val taken = stationBatch(radio, answer("a").copy(bound = true), emptySet(), fresh = true, askedToBind = true)
		assertFalse(taken.provenance.unbound)
		// Not asked (a radio started here, bound by its setQueue): `bound: false` says nothing.
		val notAsked = stationBatch(radio, answer("a").copy(bound = false), emptySet(), fresh = true, askedToBind = false)
		assertFalse(notAsked.provenance.unbound)
		// An older hub that does not say.
		assertFalse(stationBatch(radio, answer("a"), emptySet(), fresh = true, askedToBind = true).provenance.unbound)
	}

	// ---- failures: transient is asked again, a fallback is Auto DJ ----------------------------- //

	@Test
	fun failuresAreClassifiedAsFeishinDoes() {
		fun r(code: String? = null, id: String? = null, fallback: Boolean = false) = StationRefusal(code, id, fallback)
		// No answer at all: transient with the hub socket up, a fallback without it.
		assertIs<StationResult.Transient>(stationFailure(null, null, hubConnected = true))
		assertIs<StationResult.Fallback>(stationFailure(null, null, hubConnected = false))
		// Fallback.
		assertIs<StationResult.Fallback>(stationFailure(503, r(fallback = true), true))
		assertIs<StationResult.Fallback>(stationFailure(503, null, true))
		assertIs<StationResult.Fallback>(stationFailure(500, r(fallback = true), true), "a fallback body is a fallback")
		assertIs<StationResult.Fallback>(stationFailure(404, null, true), "a bare 404: no route")
		assertIs<StationResult.Fallback>(stationFailure(401, null, true))
		// Transient.
		for (status in listOf(500, 502, 504, 429, 408)) {
			assertIs<StationResult.Transient>(stationFailure(status, null, true), "HTTP $status")
		}
		// Refused: wait for the next trigger.
		assertEquals(StationResult.Refused("bad_seed"), stationFailure(400, r("bad_seed"), true))
		assertEquals(StationResult.Refused("seed_unknown"), stationFailure(404, r("seed_unknown"), true))
		assertEquals(StationResult.Refused("bad_after"), stationFailure(400, r("bad_after"), true))
		// The two with their own handling.
		assertEquals(StationResult.StationUnknown, stationFailure(404, r("station_unknown"), true))
		assertEquals(StationResult.SessionBound("st_9"), stationFailure(409, r("session_bound", "st_9"), true))
	}

	@Test
	fun aTransientFailureIsRetriedThreeTimesAndNeverBecomesAutoDj() {
		val transient = StationResult.Transient("HTTP 502")
		for (attempts in 0 until STATION_TRANSIENT_RETRIES) {
			assertEquals(StationFailureAction.RetrySoon, stationFailureAction(transient, autoplayOn = true, transientAttempts = attempts))
		}
		assertEquals(StationFailureAction.WaitForNextTrigger, stationFailureAction(transient, true, STATION_TRANSIENT_RETRIES))
		assertEquals(StationFailureAction.RetrySoon, stationFailureAction(transient, autoplayOn = false, transientAttempts = 0))
		assertEquals(8_000L, STATION_TRANSIENT_RETRY_MS)
	}

	@Test
	fun aFallbackIsAutoDjOnlyWithAutoplayOnAndARefusalWaits() {
		val fallback = StationResult.Fallback("HTTP 503")
		assertEquals(StationFailureAction.AutoDj, stationFailureAction(fallback, autoplayOn = true, transientAttempts = 0))
		assertEquals(StationFailureAction.WaitForNextTrigger, stationFailureAction(fallback, autoplayOn = false, transientAttempts = 0))
		assertEquals(StationFailureAction.WaitForNextTrigger, stationFailureAction(StationResult.Refused("bad_seed"), true, 0))
		assertEquals(StationFailureAction.Restart, stationFailureAction(StationResult.StationUnknown, true, 0))
		assertEquals(
			StationFailureAction.ContinueSessionStation,
			stationFailureAction(StationResult.SessionBound("st_9"), true, 0)
		)
	}

	// ---- seeds --------------------------------------------------------------------------------- //

	@Test
	fun anAlbumSeedIsFiveTracksSpreadOverIt() {
		val album = (1..12).map { "t$it" }
		assertEquals(listOf("t1", "t4", "t7", "t9", "t12"), spreadPick(album, 5))
		assertEquals(listOf("t1", "t2", "t3"), spreadPick(listOf("t1", "t2", "t3"), 5))
		assertEquals(listOf("t1"), spreadPick(album, 1))
		assertTrue(spreadPick(emptyList<String>(), 5).isEmpty())
	}

	@Test
	fun excludeKeepsTheNewestThousand() {
		val ids = (1..1200).map { "s$it" }
		val exclude = stationExclude(ids)
		assertEquals(STATION_EXCLUDE_MAX, exclude.size)
		assertEquals("s1200", exclude.last())
		assertEquals("s201", exclude.first())
	}
}
