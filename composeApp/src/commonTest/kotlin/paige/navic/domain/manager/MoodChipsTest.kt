package paige.navic.domain.manager

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The queue's mood chips (P7 Task B2): which moods a song gets, which chip is selected, what a tap
 * does with and without a bound station and a hub that edits, the mood waiting for a continuation,
 * and the station record and wire bodies carrying the mood.
 */
class MoodChipsTest {

	private val catalogue = MoodCatalogue(
		configured = true, available = true, ready = true,
		moods = listOf(
			MoodEntry("relax", "Relax"), MoodEntry("sleep", "Sleep"), MoodEntry("party", "Party"),
			MoodEntry("focus", "Focus"), MoodEntry("pump-up", "Pump-up", MOOD_GROUP_EXTRA)
		)
	)

	private val radio = StationProvenance(
		queueId = "q_1", stationId = "st_1", name = "Cinnamon Girl radio", character = "balanced",
		served = listOf("s1", "s2")
	)

	@Test
	fun aSongsMoodsAreTheBestThreeTheCatalogueKnows() {
		assertTrue(moodChipsFor(null, catalogue).isEmpty())
		assertTrue(moodChipsFor(ForSongAnswer(ready = false, moods = listOf(MoodRank("relax", 4))), catalogue).isEmpty())
		val answer = ForSongAnswer(
			ready = true,
			moods = listOf(MoodRank("party", 300), MoodRank("relax", 5), MoodRank("gone", 1), MoodRank("sleep", 40), MoodRank("focus", 500))
		)
		assertEquals(listOf("relax", "sleep", "party"), moodChipsFor(answer, catalogue).map { it.id })
		// An extra mood is a chip too (Pump-up, Upbeat, Downbeat exist for the queue).
		val extra = ForSongAnswer(true, listOf(MoodRank("pump-up", 9)))
		assertEquals(listOf("pump-up"), moodChipsFor(extra, catalogue).map { it.id })
	}

	@Test
	fun theSelectedMoodIsTheStationsAndThePendingOneOnlyWithoutAStation() {
		assertEquals("relax", selectedMoodChip(radio.copy(mood = "relax"), pending = "sleep"))
		assertNull(selectedMoodChip(radio, pending = "sleep"))
		assertEquals("sleep", selectedMoodChip(null, pending = "sleep"))
		assertNull(selectedMoodChip(null, pending = null))
	}

	@Test
	fun aMoodOnABoundStationTheHubEditsIsOneSignal() {
		val plan = planMoodTap("relax", radio, onHub = true)
		assertEquals("relax", plan.signalMood)
		assertTrue(plan.applyOnHub)
		assertTrue(plan.autoplayOn)
		assertFalse(plan.removeTail)
		assertFalse(plan.replaceTail)
		assertFalse(plan.runDecision)
		assertNull(plan.signalCharacter)
		assertFalse(plan.stopStation)
	}

	@Test
	fun withoutTheHubThisDeviceSwapsTheTail() {
		val plan = planMoodTap("relax", radio, onHub = false)
		assertEquals("relax", plan.signalMood)
		assertFalse(plan.applyOnHub)
		assertTrue(plan.replaceTail)
		assertTrue(plan.runDecision)
	}

	@Test
	fun allIsAnEmptyValueAndTheSelectedMoodAgainIsAll() {
		val moody = radio.copy(mood = "relax")
		assertEquals("", planMoodTap(null, moody, onHub = true).signalMood)
		assertEquals("", planMoodTap("relax", moody, onHub = true).signalMood)
		assertEquals("sleep", planMoodTap("sleep", moody, onHub = true).signalMood)
		// All on a station already on its own anchors: nothing to tell it.
		val same = planMoodTap(null, radio, onHub = true)
		assertNull(same.signalMood)
		assertFalse(same.applyOnHub)
		assertFalse(same.replaceTail)
	}

	@Test
	fun aStoppedStationIsStartedAgainByAMoodAndHasNoTailToSwap() {
		val plan = planMoodTap("relax", radio.copy(stopped = true), onHub = false)
		assertEquals("relax", plan.signalMood)
		assertTrue(plan.autoplayOn)
		assertFalse(plan.replaceTail)
	}

	@Test
	fun noStationTurnsAutoplayOnAndSignalsNothing() {
		val plan = planMoodTap("relax", null, onHub = false)
		assertTrue(plan.autoplayOn)
		assertNull(plan.signalMood)
		assertTrue(plan.runDecision)
		assertFalse(plan.applyOnHub)
	}

	@Test
	fun aCharacterTapLeavesTheMoodAlone() {
		val plan = planChipTap(AutoplayChip.Explore, radio.copy(mood = "relax"), onHub = true)
		assertNull(plan.signalMood)
		assertEquals(paige.navic.domain.models.settings.StationCharacter.Explore, plan.signalCharacter)
	}

	@Test
	fun thePendingMoodBelongsToOneQueue() {
		val pending = PendingMood("q_album", "relax")
		assertEquals("relax", pendingMoodFor(pending, "q_album"))
		assertNull(pendingMoodFor(pending, "q_other"))
		assertNull(pendingMoodFor(pending, null))
		assertNull(pendingMoodFor(null, "q_album"))
		assertEquals("relax", nextPendingMood(null, "relax"))
		assertNull(nextPendingMood("relax", "relax"))
		assertEquals("sleep", nextPendingMood("relax", "sleep"))
		assertNull(nextPendingMood("relax", null))
	}

	@Test
	fun aMoodMixsQueueIsInItsTilesMoodUntilAChipSaysOtherwise() {
		assertEquals("relax", unboundMood(pending = null, fromTile = "relax"))
		assertNull(unboundMood(pending = "", fromTile = "relax"))        // All, picked
		assertEquals("sleep", unboundMood(pending = "sleep", fromTile = "relax"))
		assertNull(unboundMood(pending = null, fromTile = null))
		assertNull(unboundMood(pending = null, fromTile = ""))
		// Tapping the tile's mood (selected) is All.
		assertNull(nextPendingMood(unboundMood(null, "relax"), "relax"))
	}

	@Test
	fun theRecordFollowsTheFramesAndAnswersMood() {
		val frame = HubStationFrame("st_1", seq = 5, rev = 2, character = "balanced", exhausted = false, mood = "relax")
		assertEquals("relax", radio.withFrame(frame).mood)
		assertNull(radio.copy(mood = "relax").withFrame(frame.copy(mood = null)).mood)
		val filtered = radio.applied(StationAnswer(stationId = "st_1", moodFilter = "pump-up"))
		assertEquals("pump-up", filtered.moodFilter)
		assertNull(filtered.mood)
	}

	private val wire = Json { encodeDefaults = false; explicitNulls = false }

	@Test
	fun aStartSendsTheMoodOnlyWhenThereIsOne() {
		val seed = StationSeed(StationSeed.TRACK, listOf("s1"), "S")
		val plain = wire.encodeToString(StationStartBody(seed, "balanced", 10, emptyList(), origin = ORIGIN_RADIO))
		assertFalse("mood" in plain)
		val moody = wire.encodeToString(StationStartBody(seed, "balanced", 10, emptyList(), origin = ORIGIN_CONTINUE, mood = "relax"))
		assertTrue("\"mood\":\"relax\"" in moody)
		assertFalse("moodFilter" in moody)
		val count = wire.encodeToString(StationCountBody(seed, "close", moodFilter = "pump-up"))
		assertTrue("\"moodFilter\":\"pump-up\"" in count)
		assertFalse("moodFilter" in wire.encodeToString(StationCountBody(seed, "close")))
	}

	@Test
	fun aForSongAnswerIsReadAndOnlyA200IsOne() {
		val a = forSongOutcome(200, """{"ready":true,"moods":[{"id":"relax","rank":5}]}""")
		assertEquals(ForSongAnswer(true, listOf(MoodRank("relax", 5))), a)
		assertNull(forSongOutcome(404, "{}"))
		assertNull(forSongOutcome(200, "nope"))
		val r = forSongRequest("http://h", "tok", "s1")!!
		assertEquals("http://h/sonic/mood/for", r.url)
		assertEquals("""{"id":"s1"}""", r.body)
	}

	@Test
	fun theForSongCacheKeepsTheLastHundred() {
		val cache = ForSongCache(3)
		val a = ForSongAnswer(true)
		cache["1"] = a; cache["2"] = a; cache["3"] = a
		cache["1"]          // asked again: the newest
		cache["4"] = a
		assertEquals(3, cache.size)
		assertNull(cache["2"])
		assertEquals(a, cache["1"])
	}
}
