package paige.navic.domain.manager

import paige.navic.data.database.entities.SavedQueueEntity
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Home's moods (P7 Task A2, `MoodModels.kt`): the mix draw (the library's songs only, the length
 * limit, the depth, a new draw each time, the artist spacing, never padded), the page's states, the
 * catalogue's gates, the tile colours' contrast, the saved-queue carousel and the chip selection.
 */
class MoodModelsTest {

	private fun owned(n: Int, artists: Int = n, seconds: (Int) -> Int = { 200 }): Map<String, OwnedSong> =
		(0 until n).associate { i -> "s$i" to OwnedSong("s$i", seconds(i), "a${i % artists}") }

	private fun ids(n: Int) = (0 until n).map { "s$it" }

	@Test
	fun aDrawIsFiftyOfTheFirstDepthWithNoDuplicateAndNothingTooLong() {
		val lib = owned(400, seconds = { if (it % 7 == 0) 600 else 200 })
		val drawn = drawMix(ids(400), lib, maxSeconds = 480, depth = 300, random = Random(1))
		assertEquals(MOOD_MIX_SIZE, drawn.size)
		assertEquals(drawn.size, drawn.toSet().size)
		assertTrue(drawn.all { it.removePrefix("s").toInt() < 300 })
		assertTrue(drawn.all { lib.getValue(it).durationSec <= 480 })
	}

	@Test
	fun theLengthLimitIsInclusiveAndNullKeepsAnyLength() {
		val lib = mapOf(
			"in" to OwnedSong("in", 480, "a"),
			"out" to OwnedSong("out", 481, "b"),
			"long" to OwnedSong("long", 20 * 60, "c")
		)
		val limited = drawMix(listOf("in", "out", "long"), lib, 480, 300, Random(0))
		assertEquals(setOf("in"), limited.toSet())
		val free = drawMix(listOf("in", "out", "long"), lib, null, 300, Random(0))
		assertEquals(setOf("in", "out", "long"), free.toSet())
	}

	@Test
	fun idsTheLibraryLacksAreDroppedAndAShortPoolIsNeverPadded() {
		val lib = owned(30)
		val drawn = drawMix(ids(30) + listOf("x1", "x2", "x3"), lib, null, 300, Random(3))
		assertEquals(30, drawn.size)
		assertTrue(drawn.none { it.startsWith("x") })
	}

	@Test
	fun theSameSeedGivesTheSameMixAndTheNextSeedAnother() {
		val lib = owned(300)
		val a = drawMix(ids(300), lib, null, 300, Random(42))
		val b = drawMix(ids(300), lib, null, 300, Random(42))
		val c = drawMix(ids(300), lib, null, 300, Random(43))
		assertEquals(a, b)
		val differ = a.indices.count { a[it] != c[it] }
		assertTrue(differ >= 20, "only $differ of 50 positions differ")
	}

	@Test
	fun noArtistComesBackWithinTheGapWhenThePoolAllowsIt() {
		// 60 songs by 12 artists, five each: fifty drawn still space out.
		val lib = owned(60, artists = 12)
		repeat(20) { seed ->
			val drawn = drawMix(ids(60), lib, null, 300, Random(seed))
			val artists = drawn.map { lib.getValue(it).artistKey }
			for (i in artists.indices) {
				val window = artists.subList(maxOf(0, i - MOOD_ARTIST_GAP + 1), i)
				assertFalse(artists[i] in window, "seed $seed: ${artists[i]} again at $i in $artists")
			}
		}
	}

	@Test
	fun anIdPastTheDepthNeverAppears() {
		val lib = owned(300)
		repeat(10) { seed ->
			val drawn = drawMix(ids(300), lib, null, depth = 200, random = Random(seed))
			assertTrue(drawn.none { it.removePrefix("s").toInt() >= 200 })
		}
	}

	@Test
	fun thePageStates() {
		assertEquals(MixState.Short(19), mixState(ids(19), eligible = 19, failed = false))
		assertEquals(MixState.Ready(ids(20)), mixState(ids(20), eligible = 20, failed = false))
		assertEquals(MixState.Unavailable, mixState(ids(50), eligible = 300, failed = true))
		assertEquals(MixState.Unavailable, mixState(null, eligible = 0, failed = true))
		assertEquals(MixState.Loading, mixState(null, eligible = 0, failed = false))
	}

	private val catalogue = MoodCatalogue(
		configured = true, available = true, ready = true, version = "v",
		moods = listOf(
			MoodEntry("relax", "Relax", MOOD_GROUP_HOME, variants = listOf(MoodVariant("a", "Acoustic evenings"))),
			MoodEntry("pump-up", "Pump-up", MOOD_GROUP_EXTRA),
			MoodEntry("sleep", "Sleep", MOOD_GROUP_HOME, depth = 200)
		)
	)

	@Test
	fun homeMoodsKeepTheHubsOrderAndDropTheExtraGroup() {
		assertEquals(listOf("relax", "sleep"), catalogue.homeMoods().map { it.id })
		assertTrue(catalogue.chipsVisible())
		assertFalse(catalogue.copy(available = false).chipsVisible())
		assertFalse(catalogue.copy(moods = catalogue.moods.filter { it.group == MOOD_GROUP_EXTRA }).chipsVisible())
		assertFalse(MoodCatalogue.NONE.chipsVisible())
		assertEquals(200, catalogue.find("sleep")?.depth)
		assertNull(catalogue.find("nope"))
	}

	@Test
	fun everyHueKeepsAWhiteTitleReadable() {
		for (h in 0 until 360 step 5) {
			val c = tileColors(MoodVariant("t", "T", listOf(h, (h + 35) % 360)))
			assertTrue(whiteContrast(c.top) >= TILE_TITLE_CONTRAST, "hue $h top: ${whiteContrast(c.top)}")
			assertTrue(whiteContrast(c.bottom) >= TILE_TITLE_CONTRAST, "hue $h bottom: ${whiteContrast(c.bottom)}")
		}
	}

	@Test
	fun tileColoursAreStable() {
		assertEquals(
			tileColors(MoodVariant("a", "A", listOf(30, 60))),
			tileColors(MoodVariant("b", "B", listOf(30, 60)))
		)
		val noHue = tileColors(MoodVariant("acoustic", "A"))
		assertEquals(noHue, tileColors(MoodVariant("acoustic", "A")))
		assertNotEquals(noHue, tileColors(MoodVariant("lo-fi", "A")))
		assertTrue(whiteContrast(noHue.top) >= TILE_TITLE_CONTRAST)
	}

	private fun queue(id: String, sourceName: String?, updatedAt: Long, name: String? = null) =
		SavedQueueEntity(id = id, name = name, sourceName = sourceName, queueJson = "[]", updatedAt = updatedAt)

	@Test
	fun savedQueuesFromAMoodMatchItsTileTitlesOnly() {
		val relax = MoodEntry(
			"relax", "Relax",
			variants = listOf(MoodVariant("a", "Acoustic evenings"), MoodVariant("b", "Mellow beats"))
		)
		val queues = listOf(
			queue("1", "Acoustic evenings", 10),
			queue("2", "Mellow beats", 30),
			queue("3", "House", 40),                       // another mood's tile
			queue("4", "acoustic evenings", 50),           // case differs
			queue("5", "Acoustic evenings", 60, name = "Mine now"), // renamed by the user
			queue("6", null, 70)
		)
		assertEquals(listOf("2", "1"), queuesFromMood(queues, relax).map { it.id })
		assertEquals(listOf("2"), queuesFromMood(queues, relax, limit = 1).map { it.id })
	}

	@Test
	fun tappingTheSelectedChipClearsIt() {
		val none = HomeMoodSelection()
		val relax = none.tap("relax")
		assertEquals("relax", relax.moodId)
		assertNull(relax.tap("relax").moodId)
		assertEquals("sleep", relax.tap("sleep").moodId)
		assertNull(relax.clear().moodId)
	}

	// Audit L4a-01: with the hub's station refused or unavailable, a filtered radio fell back to
	// Navidrome's similar songs, which know no mood, while its row still said "· Pump-up".
	@Test
	fun aFilteredRadioHasNoUnfilteredFallback() {
		assertTrue(stationFallbackAllowed(""))
		assertFalse(stationFallbackAllowed("relax"))
		assertTrue(stationFallbackAllowed("  "))
	}
}
