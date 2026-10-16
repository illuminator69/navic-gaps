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
		timing: Int = 1
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
		autoDjTiming = timing
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
