package paige.navic.domain.manager

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `SingleFlight` (P7 Task A3, `MoodManager.tileIds`): two in-flight reads of one tile share one
 * request, another tile has its own, and once a read ended the next one asks again.
 */
class MoodSingleFlightTest {

	@Test
	fun twoReadsOfOneKeyShareOneRequest() = runBlocking {
		val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
		try {
			val flight = SingleFlight<String, Int>(scope)
			val gate = CompletableDeferred<Unit>()
			val calls = AtomicInteger(0)
			val a = async { flight.run("relax/acoustic") { calls.incrementAndGet(); gate.await(); 7 } }
			val b = async { flight.run("relax/acoustic") { calls.incrementAndGet(); gate.await(); 8 } }
			val other = async { flight.run("sleep/piano") { calls.incrementAndGet(); 9 } }
			delay(100)
			gate.complete(Unit)
			assertEquals(listOf(7, 7, 9), listOf(a, b, other).awaitAll())
			assertEquals(2, calls.get())
			assertEquals(10, flight.run("relax/acoustic") { calls.incrementAndGet(); 10 })
			assertEquals(3, calls.get())
		} finally {
			scope.cancel()
		}
	}
}
