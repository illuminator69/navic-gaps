package paige.navic.domain.manager

import paige.navic.domain.models.settings.StationCharacter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * B-139: a station the hub lost comes back as itself ([stationRestart]): its character and its
 * name, its own seed when known here, else the queue's last songs under its name.
 */
class StationRestartTest {

	/** Started on another device and continued here: frames carry no seed, so none is known. */
	private val adopted = StationProvenance(
		queueId = "q_1",
		stationId = "st_lost",
		name = "Take a Picture radio",
		character = "close",
		seed = null,
		seq = 15,
		origin = ORIGIN_RADIO
	)

	@Test
	fun aLostStationWithNoSeedHereKeepsItsNameAndCharacter() {
		val restart = stationRestart(adopted, recentIds = listOf("a", "b", "c"), playingTitle = "I Don't Wanna Lose")
		// It was "I Don't Wanna Lose radio · <this device's preference>": a new station, renamed.
		assertEquals(StationSeed(StationSeed.TRACKS, listOf("a", "b", "c"), "Take a Picture"), restart?.seed)
		assertEquals(StationCharacter.Close, restart?.character)
		assertEquals(ORIGIN_RADIO, restart?.origin)
	}

	@Test
	fun aLostStationWithItsSeedStartsFromItAgain() {
		val seed = StationSeed(StationSeed.TRACK, listOf("seed"), "Take a Picture")
		val restart = stationRestart(adopted.copy(seed = seed), listOf("a", "b"), "I Don't Wanna Lose")
		assertEquals(seed, restart?.seed)
		assertEquals(StationCharacter.Close, restart?.character)
	}

	@Test
	fun aLostYourMixComesBackAsYourMix() {
		val restart = stationRestart(adopted.copy(name = "Your mix", character = "explore"), listOf("a"), "Song")
		assertEquals(StationSeed.YourMix, restart?.seed)
		assertEquals(StationCharacter.Explore, restart?.character)
	}

	@Test
	fun aCharacterNotKnownHereIsLeftToTheCaller() {
		// No frame ever came for it (the B-139 rule of boundStation: no guessed character).
		val restart = stationRestart(adopted.copy(character = ""), listOf("a"), "Song")
		assertNull(restart?.character)
	}

	@Test
	fun aNameThatIsNotTheHubsPatternFallsBackToThePlayingSong() {
		val restart = stationRestart(adopted.copy(name = "Radio"), listOf("a"), "Song")
		assertEquals("Song", restart?.seed?.name)
	}

	@Test
	fun nothingToSeedFromBringsNothingBack() {
		assertNull(stationRestart(adopted, recentIds = emptyList(), playingTitle = "Song"))
	}

	@Test
	fun theSeedNameIsTheStationNameWithoutRadio() {
		assertEquals("Take a Picture", stationSeedName("Take a Picture radio"))
		assertEquals("Radio Gaga", stationSeedName("Radio Gaga radio"))
		assertNull(stationSeedName("Radio"))
		assertNull(stationSeedName("Your mix"))
		assertNull(stationSeedName(" radio"))
	}

	@Test
	fun anotherDevicesStationForThisQueueBecomesTheRecord() {
		val mine = adopted.copy(stationId = "st_mine", name = "Cinnamon Girl radio", seed = StationSeed(StationSeed.TRACK, listOf("s")))
		// The session now names another device's station for this queue: it is kept here.
		assertEquals(adopted, adoptedStationRecord(adopted, mine))
		assertEquals(adopted, adoptedStationRecord(adopted, null))
		// Already the record (its cursor may have moved): nothing to write.
		assertNull(adoptedStationRecord(adopted.copy(seq = 40), adopted))
		assertNull(adoptedStationRecord(null, mine))
	}
}
