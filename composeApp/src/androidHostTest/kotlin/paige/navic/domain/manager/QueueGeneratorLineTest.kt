package paige.navic.domain.manager

import paige.navic.domain.models.Mix
import paige.navic.domain.models.MixKind
import paige.navic.domain.models.settings.StationCharacter
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/*
 * What the queue's origin line says for a queue generated here without a station
 * (`queueGenerator`, `GeneratedQueue`, `QueueGeneratorLine`). A genre mix (a random draw from the
 * library) and an artist mix without the engine (Navidrome's similar songs) used to read
 * "AudioMuse mix"; they are said by the mix's own name, and only what AudioMuse chose is credited
 * to it. The words are string resources, which a host test cannot resolve, so the last test reads
 * them from `values/strings.xml`. Gradle runs host tests from the module directory, composeApp/.
 */
class QueueGeneratorLineTest {

	private val genre = Mix(id = "mx_1", name = "Rock", kind = MixKind.GENRE, seedId = "Rock", seedName = "Rock")

	private val artist = Mix(
		id = "mx_2",
		name = "More Neil Young",
		kind = MixKind.ARTIST,
		seedId = "ar_1",
		seedName = "Neil Young",
		character = "balanced"
	)

	private val artistStation = StationProvenance(
		queueId = "q_1",
		stationId = "st_1",
		name = "More Neil Young",
		character = "balanced",
		engine = "tier2",
		seed = StationSeed(StationSeed.TRACKS, listOf("s1", "s2"), "Neil Young"),
		seq = 5,
		rev = 0
	)

	/** The line for a queue on screen that is exactly what [what] generated, no station bound. */
	private fun lineFor(what: GeneratedQueue) = queueGenerator(null, "q_mix", null, generated = what)

	@Test
	fun aGenreMixIsSaidByItsOwnNameAndNotCreditedToAudioMuse() {
		// A genre mix never plays as a station, engine or not.
		assertFalse(MixKind.playsAsStation(MixKind.GENRE, engine = true))
		val line = lineFor(GeneratedQueue.ofMix(genre))
		assertEquals(QueueGenerator.Generated("Rock", audioMuse = false), line)
		assertFalse(wearsAudioMuseColour(line))
	}

	@Test
	fun anArtistMixWithoutTheEngineIsSaidByItsOwnName() {
		assertFalse(MixKind.playsAsStation(MixKind.ARTIST, engine = false))
		val line = lineFor(GeneratedQueue.ofMix(artist))
		assertEquals(QueueGenerator.Generated("More Neil Young", audioMuse = false), line)
		assertFalse(wearsAudioMuseColour(line))
	}

	@Test
	fun anArtistMixWithTheEngineIsItsStation() {
		assertTrue(MixKind.playsAsStation(MixKind.ARTIST, engine = true))
		val expected = QueueGenerator.Station(
			name = "More Neil Young",
			character = StationCharacter.Balanced,
			viaNavidrome = false,
			yourMix = false
		)
		assertEquals(expected, queueGenerator(artistStation, "q_1", null, generated = null))
		// A station bound to the queue is what built it, whatever was generated before it.
		assertEquals(expected, queueGenerator(artistStation, "q_1", null, generated = GeneratedQueue.ofMix(genre)))
		assertTrue(wearsAudioMuseColour(expected))
	}

	@Test
	fun aMoodSearchIsSaidAsItIsSavedAndCreditedToAudioMuse() {
		val line = lineFor(GeneratedQueue.ofMoodSearch("Search: rainy night"))
		assertEquals(QueueGenerator.Generated("Search: rainy night", audioMuse = true), line)
		assertTrue(wearsAudioMuseColour(line))
	}

	@Test
	fun onlyTheListsAudioMuseMakesAreCreditedToIt() {
		fun credited(kind: String, seedId: String = "") =
			GeneratedQueue.ofMix(Mix(id = "mx", name = "A mix", kind = kind, seedId = seedId)).audioMuse
		assertTrue(credited(MixKind.FINGERPRINT))
		assertTrue(credited(MixKind.ADAPTIVE))
		assertTrue(credited(MixKind.STATION), "a seedless station: the Sonic Fingerprint without the engine")
		// AudioMuse's similar tracks when the plugin answers, Navidrome's otherwise: not claimed.
		assertFalse(credited(MixKind.STATION, seedId = "s1"))
		assertFalse(credited(MixKind.SIMILAR, seedId = "s1"))
		assertFalse(credited(MixKind.GENRE, seedId = "Rock"))
		assertFalse(credited(MixKind.ARTIST, seedId = "ar_1"))
	}

	@Test
	fun aMixWithNoNameIsSaidByItsSeed() {
		assertEquals(QueueGenerator.Generated("Rock", audioMuse = false), lineFor(GeneratedQueue.ofMix(genre.copy(name = " "))))
		assertEquals("Radio", GeneratedQueue.ofMix(Mix(id = "mx", name = "", kind = MixKind.FINGERPRINT)).name)
	}

	@Test
	fun aNamelessGeneratedQueueSaysNothingAndAPlainQueueNothingEither() {
		assertNull(lineFor(GeneratedQueue("", audioMuse = true)))
		assertNull(queueGenerator(null, "q_album", null, generated = null))
	}

	@Test
	fun theWordsCreditAudioMuseOnlyAfterTheName() {
		val file = File("src/commonMain/composeResources/values/strings.xml")
		assertTrue(file.isFile, "${file.absolutePath} not found (host tests run from composeApp/)")
		val strings = Regex("""<string name="([^"]+)">([^<]*)</string>""")
			.findAll(file.readText())
			.associate { it.groupValues[1] to it.groupValues[2] }
		assertEquals("%1\$s · via AudioMuse", strings["queue_generated_via_audiomuse"])
		assertFalse("AudioMuse mix" in strings.values, "nothing says \"AudioMuse mix\" any more")
	}
}
