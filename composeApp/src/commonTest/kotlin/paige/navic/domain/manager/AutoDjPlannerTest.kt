package paige.navic.domain.manager

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Auto DJ's decision ([AutoDjPick], [autoDjDue]): upstream Feishin's `collectSongsSimilar` walked
 * as similar → genre → album artist → library, with its "only similar" and duplicate rules.
 */
class AutoDjPlannerTest {

	private val noShuffle: (List<String>) -> List<String> = { it }

	/** Walk [sources] in order the way `AutoDj.collect` does. */
	private fun walk(
		sources: List<List<String>>,
		itemCount: Int = 5,
		onlySimilar: Boolean = false,
		allowDuplicates: Boolean = false,
		queue: Set<String> = emptySet()
	): Pair<List<String>, Int> {
		val pick = AutoDjPick<String>(itemCount, onlySimilar, allowDuplicates, queue) { it }
		var asked = 0
		for (source in sources) {
			if (pick.done) break
			asked++
			pick.offer(source, noShuffle)
		}
		return pick.selected to asked
	}

	@Test
	fun enoughSimilarSongsEndTheWalkAtTheFirstSource() {
		val (picked, asked) = walk(listOf(listOf("s1", "s2", "s3", "s4", "s5", "s6"), listOf("g1"), listOf("a1"), listOf("r1")))
		assertEquals(listOf("s1", "s2", "s3", "s4", "s5"), picked)
		assertEquals(1, asked)
	}

	@Test
	fun aShortSourceIsFilledFromTheNextInOrder() {
		val (picked, asked) = walk(listOf(listOf("s1", "s2"), listOf("g1"), listOf("a1", "a2", "a3"), listOf("r1")))
		assertEquals(listOf("s1", "s2", "g1", "a1", "a2"), picked)
		assertEquals(3, asked)
	}

	@Test
	fun onlySimilarStopsAfterTheFirstSourceThatFoundAnything() {
		val (picked, asked) = walk(
			listOf(listOf("s1"), listOf("g1", "g2"), listOf("a1"), listOf("r1")),
			onlySimilar = true
		)
		assertEquals(listOf("s1"), picked)
		assertEquals(1, asked)
	}

	@Test
	fun onlySimilarStillFallsThroughASourceThatFoundNothing() {
		// Upstream's rule, kept: "only similar" ends the walk once something was found, not before.
		val (picked, asked) = walk(
			listOf(emptyList(), listOf("g1", "g2"), listOf("a1"), listOf("r1")),
			onlySimilar = true
		)
		assertEquals(listOf("g1", "g2"), picked)
		assertEquals(2, asked)
	}

	@Test
	fun theLibraryIsTheLastResortOnly() {
		val (picked, asked) = walk(listOf(emptyList(), emptyList(), emptyList(), listOf("r1", "r2", "r3")))
		assertEquals(listOf("r1", "r2", "r3"), picked)
		assertEquals(4, asked)
	}

	@Test
	fun songsAlreadyQueuedAreSkippedUnlessDuplicatesAreAllowed() {
		val queue = setOf("s1", "s2")
		val (dedup, _) = walk(listOf(listOf("s1", "s2", "s3"), listOf("g1")), itemCount = 2, queue = queue)
		assertEquals(listOf("s3", "g1"), dedup)
		val (dupes, _) = walk(listOf(listOf("s1", "s2", "s3")), itemCount = 2, allowDuplicates = true, queue = queue)
		assertEquals(listOf("s1", "s2"), dupes)
	}

	@Test
	fun noSongIsPickedTwiceInOneTopUpEvenWithDuplicatesAllowed() {
		val (picked, _) = walk(listOf(listOf("x", "x", "y"), listOf("y", "z")), itemCount = 3, allowDuplicates = true)
		assertEquals(listOf("x", "y", "z"), picked)
	}

	@Test
	fun theShuffleDecidesWhichOfASourceAreTaken() {
		val pick = AutoDjPick<String>(2, false, false, emptySet()) { it }
		pick.offer(listOf("a", "b", "c", "d")) { it.reversed() }
		assertEquals(listOf("d", "c"), pick.selected)
		assertTrue(pick.done)
	}

	@Test
	fun theTriggerIsFewerSongsLeftThanTheTiming() {
		// Upstream's default timing 1: a top-up once the last song is playing.
		assertTrue(autoDjDue(remaining = 0, timing = 1))
		assertFalse(autoDjDue(remaining = 1, timing = 1))
		assertTrue(autoDjDue(remaining = 2, timing = 3))
		assertFalse(autoDjDue(remaining = 3, timing = 3))
		// A timing below 1 is read as 1, never "never".
		assertTrue(autoDjDue(remaining = 0, timing = 0))
	}
}
