package paige.navic.ui.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What the cover-colour prefetcher fetches next, and what it will not ask for again ([PrefetchQueue]). */
class PrefetchQueueTest {
	private var clock = 0L
	private fun queue(capacity: Int = 48) = PrefetchQueue(capacity = capacity, retryAfterMs = 60_000, now = { clock })

	@Test
	fun aDuplicateIsNotOfferedTwice() {
		val q = queue()
		assertTrue(q.offer("a"))
		assertFalse(q.offer("a"))
	}

	@Test
	fun theNewestIsServedFirst() {
		val q = queue()
		q.offer("a"); q.offer("b"); q.offer("c")
		assertEquals("c", q.next())
	}

	@Test
	fun theOldestIsDroppedOverCapacity() {
		val q = queue(capacity = 2)
		q.offer("a"); q.offer("b"); q.offer("c")
		assertEquals("c", q.next())
		assertEquals("b", q.next())
		assertNull(q.next())
	}

	@Test
	fun aFailedIdWaitsForTheRetryWindow() {
		val q = queue()
		q.offer("a"); assertEquals("a", q.next()); q.failed("a")
		assertFalse(q.offer("a"))
		clock += 60_001
		assertTrue(q.offer("a"))
	}

	@Test
	fun anInFlightIdIsNotOfferedAgain() {
		val q = queue()
		q.offer("a"); assertEquals("a", q.next())
		assertFalse(q.offer("a"))
		q.done("a")
		assertTrue(q.offer("a"))
	}

	// Audit L4a-08: a tile grid's requests displaced the next songs', so a song change missed its colours.
	@Test
	fun nextSongsBeatTiles() {
		val q = queue(capacity = 2)
		q.offer("n1", urgent = true)
		repeat(10) { q.offer("t$it") }
		assertEquals("n1", q.next())
		assertEquals("t9", q.next())
	}

	@Test
	fun theNextSongsAreServedInTheirOrder() {
		val q = queue()
		q.offer("t1")
		q.offer("n1", urgent = true); q.offer("n2", urgent = true)
		assertEquals(listOf("n1", "n2", "t1"), listOf(q.next(), q.next(), q.next()))
	}

	@Test
	fun aTileThatBecomesANextSongMovesUp() {
		val q = queue()
		q.offer("a"); q.offer("b")
		assertTrue(q.offer("a", urgent = true))
		assertEquals("a", q.next())
		assertEquals("b", q.next())
		assertNull(q.next())
	}

	// Fix round P6: an all-black cover gives no seeds however often it is asked; it is not asked again.
	@Test
	fun anUnextractableIdIsNeverAskedAgain() {
		val q = queue()
		q.offer("black"); q.next()
		q.unextractable("black")
		clock += 10 * 60_000
		assertFalse(q.offer("black"))
		assertFalse(q.offer("black", urgent = true))
	}
}
