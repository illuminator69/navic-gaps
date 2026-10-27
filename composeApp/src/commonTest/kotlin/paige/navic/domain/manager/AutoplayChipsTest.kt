package paige.navic.domain.manager

import paige.navic.domain.models.settings.StationCharacter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The queue sheet's autoplay chips (I-041, `AutoplayChips.kt`): which chips, which is selected,
 * what a tap does, and which songs a tap may take out or swap (never the playing one or the next,
 * never one the user put in, in play order under shuffle).
 */
class AutoplayChipsTest {

	private val radio = StationProvenance(
		queueId = "q_1",
		stationId = "st_1",
		name = "Cinnamon Girl radio",
		character = "balanced",
		served = listOf("s1", "s2", "s3", "s4")
	)

	@Test
	fun theChipsAreTheCharactersWithTheEngineAndAutoDjWithout() {
		assertEquals(
			listOf(AutoplayChip.Off, AutoplayChip.Close, AutoplayChip.Balanced, AutoplayChip.Explore),
			autoplayChips(stationsAvailable = true)
		)
		assertEquals(listOf(AutoplayChip.Off, AutoplayChip.AutoDj), autoplayChips(stationsAvailable = false))
	}

	@Test
	fun aBoundStationIsSelectedByItsOwnStateNotThePreference() {
		val preferred = StationCharacter.Close
		assertEquals(AutoplayChip.Balanced, selectedAutoplayChip(true, autoplayOn = false, preferred, radio))
		assertEquals(AutoplayChip.Off, selectedAutoplayChip(true, autoplayOn = true, preferred, radio.copy(stopped = true)))
		assertEquals(AutoplayChip.Explore, selectedAutoplayChip(true, true, preferred, radio.copy(character = "explore")))
		// A character not known yet (no frame): the preference, not a guess.
		assertEquals(AutoplayChip.Close, selectedAutoplayChip(true, true, preferred, radio.copy(character = "")))
	}

	@Test
	fun withNoStationThePreferenceIsSelected() {
		assertEquals(AutoplayChip.Off, selectedAutoplayChip(true, autoplayOn = false, StationCharacter.Explore, null))
		assertEquals(AutoplayChip.Explore, selectedAutoplayChip(true, autoplayOn = true, StationCharacter.Explore, null))
		assertEquals(AutoplayChip.AutoDj, selectedAutoplayChip(false, autoplayOn = true, StationCharacter.Explore, null))
		assertEquals(AutoplayChip.Off, selectedAutoplayChip(false, autoplayOn = false, StationCharacter.Explore, radio))
	}

	@Test
	fun offStopsTheStationAndTakesItsSongsOutAndAddsNothing() {
		val plan = planChipTap(AutoplayChip.Off, radio)
		assertFalse(plan.autoplayOn)
		assertTrue(plan.stopStation)
		assertTrue(plan.removeTail)
		assertFalse(plan.replaceTail)
		assertFalse(plan.runDecision)
		// No station: only the preference.
		val none = planChipTap(AutoplayChip.Off, null)
		assertFalse(none.autoplayOn)
		assertFalse(none.stopStation)
		assertFalse(none.removeTail)
		// Already stopped (by another device): nothing to send, its songs still taken out here.
		val again = planChipTap(AutoplayChip.Off, radio.copy(stopped = true))
		assertFalse(again.stopStation)
		assertTrue(again.removeTail)
	}

	@Test
	fun anotherCharacterSteersTheStationSwapsItsSongsAndDecidesAtOnce() {
		val plan = planChipTap(AutoplayChip.Explore, radio)
		assertTrue(plan.autoplayOn)
		assertEquals(StationCharacter.Explore, plan.character)
		assertEquals(StationCharacter.Explore, plan.signalCharacter)
		assertTrue(plan.replaceTail)
		assertTrue(plan.runDecision)
		assertFalse(plan.stopStation)
	}

	@Test
	fun theSameCharacterChangesNothingButTheSwitch() {
		val plan = planChipTap(AutoplayChip.Balanced, radio)
		assertTrue(plan.autoplayOn)
		assertNull(plan.signalCharacter)
		assertFalse(plan.replaceTail)
		assertTrue(plan.runDecision)
	}

	@Test
	fun aStoppedStationIsStartedAgainWithNothingToSwap() {
		// Its songs went when it was stopped; the decision then tops the queue up from it.
		val plan = planChipTap(AutoplayChip.Balanced, radio.copy(stopped = true))
		assertEquals(StationCharacter.Balanced, plan.signalCharacter)
		assertFalse(plan.replaceTail)
		assertTrue(plan.runDecision)
	}

	@Test
	fun noStationJustTheSwitchAndADecision() {
		val plan = planChipTap(AutoplayChip.Close, null)
		assertTrue(plan.autoplayOn)
		assertEquals(StationCharacter.Close, plan.character)
		assertNull(plan.signalCharacter)
		assertFalse(plan.replaceTail)
		assertTrue(plan.runDecision)
		val autoDj = planChipTap(AutoplayChip.AutoDj, radio)
		assertTrue(autoDj.autoplayOn)
		assertNull(autoDj.character)
		assertTrue(autoDj.runDecision)
	}

	@Test
	fun theTailIsTheStationsSongsStillToComeHighestFirst() {
		// Playing u0 (index 1); next is s1 (index 2); u1 is the user's.
		val ids = listOf("old", "u0", "s1", "s2", "u1", "s3", "s4")
		// B-145: the next song goes too, so the radio's second song does not outlive the chip.
		assertEquals(listOf(6, 5, 3, 2), stationTailIndices(ids, order = null, currentIndex = 1, served = radio.served))
		// Under 20 s left of the playing song: the next one may be loading, and stays.
		assertEquals(listOf(6, 5, 3), stationTailIndices(ids, null, currentIndex = 1, served = radio.served, keepNext = true))
		// On the last song there is nothing to come; on the one before it, only the next one.
		assertTrue(stationTailIndices(ids, null, currentIndex = 6, served = radio.served).isEmpty())
		assertEquals(listOf(6), stationTailIndices(ids, null, currentIndex = 5, served = radio.served))
		assertTrue(stationTailIndices(ids, null, currentIndex = 5, served = radio.served, keepNext = true).isEmpty())
		// Nothing served, nothing taken.
		assertTrue(stationTailIndices(ids, null, 1, emptyList()).isEmpty())
	}

	@Test
	fun underShuffleTheTailFollowsPlayOrder() {
		val ids = listOf("s1", "u0", "s2", "s3", "s4")
		// Play order: u0 (playing), s4 (next), s1, s3, s2.
		val order = listOf(1, 4, 0, 3, 2)
		assertEquals(listOf(4, 3, 2, 0), stationTailIndices(ids, order, currentIndex = 1, served = radio.served))
		// Kept with under 20 s left: s4 is next in play order, although it is last in the queue.
		assertEquals(listOf(3, 2, 0), stationTailIndices(ids, order, currentIndex = 1, served = radio.served, keepNext = true))
	}

	@Test
	fun theNextSongIsKeptOnlyWithUnder20SecondsLeft() {
		assertTrue(chipEditKeepsNext(19_999))
		assertFalse(chipEditKeepsNext(20_000))
		assertFalse(chipEditKeepsNext(180_000))
		// Not known (no duration): edited like any other.
		assertFalse(chipEditKeepsNext(null))
	}

	@Test
	fun theHubEditsTheSessionsBoundStationPlayingOrControlling() {
		// Playing it: this device's queue is the session's.
		assertTrue(chipEditsOnHub(true, radio, sessionStationId = "st_1", sessionQueueId = "q_1", queueId = "q_1"))
		// Controlling the device that plays it: the queue is the session's by definition.
		assertTrue(chipEditsOnHub(true, radio.copy(queueId = "q_9"), "st_1", "q_9", queueId = "q_9"))
		// A hub without `stationEdits`, another station in the session, a station the hub would
		// not bind, a local queue that is not the session's, no station: this device edits.
		assertFalse(chipEditsOnHub(false, radio, "st_1", "q_1", "q_1"))
		assertFalse(chipEditsOnHub(true, radio, "st_2", "q_1", "q_1"))
		assertFalse(chipEditsOnHub(true, radio, null, "q_1", "q_1"))
		assertFalse(chipEditsOnHub(true, radio.copy(unbound = true), "st_1", "q_1", "q_1"))
		assertFalse(chipEditsOnHub(true, radio, "st_1", "q_1", "q_local"))
		assertFalse(chipEditsOnHub(true, null, "st_1", "q_1", "q_1"))
	}

	@Test
	fun afterATransferHereThePlayingDevicesTapIsTheHubsEditToo() {
		// A transferred queue has no local id: it is the session's while this device plays it
		// (walk W2, the user's B-143). Without that the tap swapped nothing.
		assertTrue(chipEditsOnHub(true, radio, "st_1", "q_1", queueId = null, playsSession = true))
		// Not playing the session (a local queue, the hub idle elsewhere): this device edits.
		assertFalse(chipEditsOnHub(true, radio, "st_1", "q_1", queueId = null, playsSession = false))
		// A session with no queue id names nothing to edit.
		assertFalse(chipEditsOnHub(true, radio, "st_1", null, queueId = null, playsSession = true))
		// A local queue of its own that is not the session's stays this device's.
		assertFalse(chipEditsOnHub(true, radio, "st_1", "q_1", queueId = "q_local", playsSession = true))
	}

	@Test
	fun withHubEditsACharacterTapIsTheSignalAlone() {
		val plan = planChipTap(AutoplayChip.Explore, radio, onHub = true)
		assertTrue(plan.applyOnHub)
		assertEquals(StationCharacter.Explore, plan.signalCharacter)
		assertFalse(plan.replaceTail)
		assertFalse(plan.removeTail)
		// The hub puts the fresh batch in itself: no top-up here for this tap.
		assertFalse(plan.runDecision)
		// The same character changes nothing on the hub: the switch, then the usual decision.
		val same = planChipTap(AutoplayChip.Balanced, radio, onHub = true)
		assertFalse(same.applyOnHub)
		assertNull(same.signalCharacter)
		assertTrue(same.runDecision)
		// From a stopped station: the signal with apply starts it again and fills the queue.
		val restart = planChipTap(AutoplayChip.Close, radio.copy(stopped = true), onHub = true)
		assertTrue(restart.applyOnHub)
		assertEquals(StationCharacter.Close, restart.signalCharacter)
		assertFalse(restart.runDecision)
	}

	@Test
	fun withHubEditsOffIsTheStopSignalAlone() {
		val plan = planChipTap(AutoplayChip.Off, radio, onHub = true)
		assertTrue(plan.applyOnHub)
		assertTrue(plan.stopStation)
		assertFalse(plan.removeTail)
		assertFalse(plan.runDecision)
		// Sent again for a station already stopped elsewhere: the hub's edit rides it.
		val again = planChipTap(AutoplayChip.Off, radio.copy(stopped = true), onHub = true)
		assertTrue(again.stopStation)
		assertTrue(again.applyOnHub)
		assertFalse(again.removeTail)
		// No station: nothing for the hub to edit.
		assertFalse(planChipTap(AutoplayChip.Off, null, onHub = true).applyOnHub)
	}

	@Test
	fun withoutHubEditsTheClientEditsAsBefore() {
		val plan = planChipTap(AutoplayChip.Explore, radio, onHub = false)
		assertFalse(plan.applyOnHub)
		assertTrue(plan.replaceTail)
		assertTrue(plan.runDecision)
		val off = planChipTap(AutoplayChip.Off, radio, onHub = false)
		assertFalse(off.applyOnHub)
		assertTrue(off.removeTail)
	}

	@Test
	fun aSignalAnswerIsReadWhateverShapeItsCountsTake() {
		val edited = parseStationEditAnswer("""{"rev":4,"applied":true,"edited":{"removed":5,"added":5,"from":10}}""")
		assertEquals(StationEdit(5, 5, 10), edited?.edited)
		assertTrue(edited?.applied == true)
		val asLists = parseStationEditAnswer("""{"applied":true,"edited":{"removed":["a","b"],"added":["c"],"from":3}}""")
		assertEquals(StationEdit(2, 1, 3), asLists?.edited)
		val refused = parseStationEditAnswer("""{"rev":4,"applied":true,"edited":false,"reason":"transfer"}""")
		assertNull(refused?.edited)
		assertEquals("transfer", refused?.reason)
		assertNull(parseStationEditAnswer("""{"edited":false,"reason":null}""")?.reason)
		assertNull(parseStationEditAnswer("not json"))
	}

	// The staged queue's autoplay toggle (P2): one switch over the same chip rules.

	@Test
	fun theToggleOffIsTheOffChipAndItsPlanIsUnchanged() {
		assertEquals(AutoplayChip.Off, autoplayToggleChip(turnOn = false, stationsAvailable = true, StationCharacter.Balanced))
		assertEquals(AutoplayChip.Off, autoplayToggleChip(turnOn = false, stationsAvailable = false, StationCharacter.Balanced))
		val plan = planChipTap(autoplayToggleChip(false, true, StationCharacter.Balanced), radio, onHub = false)
		assertEquals(planChipTap(AutoplayChip.Off, radio, onHub = false), plan)
		assertTrue(plan.stopStation)
		assertTrue(plan.removeTail)
		assertEquals(planChipTap(AutoplayChip.Off, radio, onHub = true), planChipTap(autoplayToggleChip(false, true, StationCharacter.Close), radio, onHub = true))
	}

	@Test
	fun theToggleOnIsThePreferredCharacterWithTheEngineAndAutoDjWithout() {
		assertEquals(AutoplayChip.Explore, autoplayToggleChip(turnOn = true, stationsAvailable = true, StationCharacter.Explore))
		assertEquals(AutoplayChip.Balanced, autoplayToggleChip(turnOn = true, stationsAvailable = true, StationCharacter.Balanced))
		assertEquals(AutoplayChip.AutoDj, autoplayToggleChip(turnOn = true, stationsAvailable = false, StationCharacter.Explore))
	}

	@Test
	fun theChipRowBesideTheToggleIsTheCharactersOnly() {
		assertEquals(
			listOf(AutoplayChip.Close, AutoplayChip.Balanced, AutoplayChip.Explore),
			characterChips(listOf(AutoplayChip.Off, AutoplayChip.Close, AutoplayChip.Balanced, AutoplayChip.Explore))
		)
		// Without the engine the toggle is the whole control and no chip row is drawn.
		assertTrue(characterChips(listOf(AutoplayChip.Off, AutoplayChip.AutoDj)).isEmpty())
		assertTrue(characterChips(autoplayChips(stationsAvailable = false)).isEmpty())
	}

	@Test
	fun theToggleShowsOnIffTheSelectedChipIsNotOff() {
		assertTrue(autoplayToggleShowsOn(AutoplayChip.Balanced))
		assertTrue(autoplayToggleShowsOn(AutoplayChip.AutoDj))
		assertFalse(autoplayToggleShowsOn(AutoplayChip.Off))
		// A stopped bound station is Off whatever the preference says.
		assertFalse(autoplayToggleShowsOn(selectedAutoplayChip(true, autoplayOn = true, StationCharacter.Close, radio.copy(stopped = true))))
		assertTrue(autoplayToggleShowsOn(selectedAutoplayChip(true, autoplayOn = false, StationCharacter.Close, radio)))
	}

	// Audit L4a-02: the switch read the station's stopped flag, the driver the local default, so
	// Off here then a character chip on another device left this switch ON and nothing topped up.
	@Test
	fun switchAndDriverAgree() {
		val bounds = listOf(null, radio.copy(stopped = false), radio.copy(stopped = true), radio.copy(character = "unknown-new"))
		for (available in listOf(false, true)) for (on in listOf(false, true)) for (bound in bounds) {
			val shown = autoplayToggleShowsOn(selectedAutoplayChip(available, on, StationCharacter.Balanced, bound))
			assertEquals(autoplayWanted(available, on, bound), shown, "available=$available on=$on bound=$bound")
		}
	}

	// B-177: the rows a hub edit swapped in are the station's; this device learns them as the ids
	// the edit brought that the queue did not hold before.
	@Test
	fun aHubEditsSwapInIsWhatTheQueueDidNotHoldBefore() {
		assertEquals(listOf("x1", "x2"), swappedInIds(listOf("s0", "s1", "s3", "s4"), listOf("s0", "s1", "x1", "x2")))
		assertEquals(emptyList(), swappedInIds(listOf("s0", "s1"), listOf("s0", "s1")))
		assertEquals(listOf("x1"), swappedInIds(listOf("s0"), listOf("s0", "x1", "x1")))
	}
}
