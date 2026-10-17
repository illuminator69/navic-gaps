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
	fun theTailIsTheStationsSongsAfterTheNextOneHighestFirst() {
		// Playing u0 (index 1); next is s1 (index 2), never touched; u1 is the user's.
		val ids = listOf("old", "u0", "s1", "s2", "u1", "s3", "s4")
		assertEquals(listOf(6, 5, 3), stationTailIndices(ids, order = null, currentIndex = 1, served = radio.served))
		// On the last song or the one before it there is nothing after the next one.
		assertTrue(stationTailIndices(ids, null, currentIndex = 5, served = radio.served).isEmpty())
		assertTrue(stationTailIndices(ids, null, currentIndex = 6, served = radio.served).isEmpty())
		// Nothing served, nothing taken.
		assertTrue(stationTailIndices(ids, null, 1, emptyList()).isEmpty())
	}

	@Test
	fun underShuffleTheTailFollowsPlayOrder() {
		val ids = listOf("s1", "u0", "s2", "s3", "s4")
		// Play order: u0 (playing), s4 (next), s1, s3, s2.
		val order = listOf(1, 4, 0, 3, 2)
		assertEquals(listOf(3, 2, 0), stationTailIndices(ids, order, currentIndex = 1, served = radio.served))
		// s4 is next in play order and stays, although it is last in the queue.
		assertFalse(4 in stationTailIndices(ids, order, 1, radio.served))
	}
}
