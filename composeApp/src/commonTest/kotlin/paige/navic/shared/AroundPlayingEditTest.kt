package paige.navic.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A hub `queueChanged` applied around the playing song ([aroundPlayingEdit], S-035): the playing
 * item is never removed or re-added, and neither is anything the new queue keeps right after it.
 */
class AroundPlayingEditTest {

	/** What the edit does to [old], as the player would end up. */
	private fun applied(old: List<String>, new: List<String>, edit: AroundPlayingEdit): List<String> {
		val out = old.toMutableList()
		repeat(edit.tailRemove) { out.removeAt(edit.tailAt) }
		out.addAll(edit.tailAt, edit.tailInsert.map { new[it] })
		repeat(edit.headRemove) { out.removeAt(edit.headAt) }
		out.addAll(edit.headAt, edit.headInsert.map { new[it] })
		return out
	}

	@Test
	fun theHubReplacingTheNextSongsLeavesThePlayingItemAlone() {
		// Playing "p" (index 2); the hub's character edit with more than 20 s left: everything
		// after "p" the station added (n, s1, s2) is replaced by a fresh batch.
		val old = listOf("a", "b", "p", "n", "s1", "s2")
		val new = listOf("a", "b", "p", "x1", "x2", "x3")
		val edit = aroundPlayingEdit(old, 2, new, 2)!!
		assertEquals(3, edit.tailAt)
		assertEquals(3, edit.tailRemove)
		assertEquals(3..5, edit.tailInsert)
		// Nothing before the playing song moves, and the playing item itself is no edit's.
		assertEquals(0, edit.headRemove)
		assertTrue(edit.headInsert.isEmpty())
		assertTrue(edit.tailAt > 2)
		assertEquals(new, applied(old, new, edit))
	}

	@Test
	fun aCutAfterTheNextSongKeepsTheNextItem() {
		// Under 20 s left: the hub keeps "n", which may already be loading for a gapless start.
		val old = listOf("a", "p", "n", "s1", "s2", "u")
		val new = listOf("a", "p", "n", "x1", "u")
		val edit = aroundPlayingEdit(old, 1, new, 1)!!
		assertEquals(3, edit.tailAt)
		// s1 and s2 go, x1 comes; "n" and the user's "u" at the end are not touched.
		assertEquals(2, edit.tailRemove)
		assertEquals(3..3, edit.tailInsert)
		assertEquals(new, applied(old, new, edit))
	}

	@Test
	fun aStopTakesTheRestOutAndAddsNothing() {
		val old = listOf("p", "n", "s1", "s2")
		val new = listOf("p")
		val edit = aroundPlayingEdit(old, 0, new, 0)!!
		assertEquals(1, edit.tailAt)
		assertEquals(3, edit.tailRemove)
		assertTrue(edit.tailInsert.isEmpty())
		assertEquals(new, applied(old, new, edit))
	}

	@Test
	fun theSameQueueIsNoEdit() {
		val q = listOf("a", "p", "b")
		assertTrue(aroundPlayingEdit(q, 1, q, 1)!!.isEmpty)
	}

	@Test
	fun anEditBeforeThePlayingSongIsAppliedThereToo() {
		// A song before the playing one removed elsewhere: the playing song moves to index 1.
		val old = listOf("a", "b", "p", "n")
		val new = listOf("a", "p", "n")
		val edit = aroundPlayingEdit(old, 2, new, 1)!!
		assertEquals(0, edit.tailRemove)
		assertEquals(1, edit.headRemove)
		assertEquals(new, applied(old, new, edit))
	}

	@Test
	fun anotherPlayingSongIsALoadNotAnEdit() {
		assertNull(aroundPlayingEdit(listOf("a", "p"), 1, listOf("a", "q"), 1))
		assertNull(aroundPlayingEdit(listOf("a", "p"), 5, listOf("a", "p"), 1))
	}

	@Test
	fun repeatedSongsStillLandWhereTheyShould() {
		val old = listOf("p", "x", "y", "x")
		val new = listOf("p", "x", "z", "x")
		val edit = aroundPlayingEdit(old, 0, new, 0)!!
		assertEquals(1, edit.tailRemove)
		assertEquals(new, applied(old, new, edit))
	}
}
