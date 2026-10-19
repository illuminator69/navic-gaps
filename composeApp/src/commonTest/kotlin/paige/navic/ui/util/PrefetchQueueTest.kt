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
}
