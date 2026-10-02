package paige.navic.shared

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/*
 * Q-040: once a swipe has released the media controller, the next background call reconnects on
 * demand. Callers that arrive together must share one connect: two would bind the service twice
 * and start setupController's collectors twice.
 */
class SingleFlightTest {
	@Test
	fun concurrentCallersShareOneRun() = runBlocking {
		var starts = 0
		val gate = CompletableDeferred<String>()
		val flight = SingleFlight(CoroutineScope(coroutineContext + SupervisorJob())) {
			starts++
			gate.await()
		}
		val callers = List(3) { async { flight.await() } }
		yield()
		gate.complete("controller")
		assertEquals(listOf("controller", "controller", "controller"), callers.awaitAll())
		assertEquals(1, starts)
	}

	@Test
	fun aFinishedRunIsForgottenSoTheNextCallStartsAfresh() = runBlocking {
		var starts = 0
		val flight = SingleFlight(CoroutineScope(coroutineContext + SupervisorJob())) { "c${++starts}" }
		assertEquals("c1", flight.await())
		assertEquals("c2", flight.await())
	}

	@Test
	fun aFailedRunIsNotHandedOutAgain() = runBlocking {
		val answers = ArrayDeque(listOf<String?>(null, "controller"))
		var starts = 0
		val flight = SingleFlight(CoroutineScope(coroutineContext + SupervisorJob())) {
			starts++
			answers.removeFirst()
		}
		assertNull(flight.await())
		assertEquals("controller", flight.await())
		assertEquals(2, starts)
	}

	@Test
	fun aCallerGivingUpDoesNotCancelTheRunOthersWaitOn() = runBlocking {
		var starts = 0
		val gate = CompletableDeferred<String>()
		val flight = SingleFlight(CoroutineScope(coroutineContext + SupervisorJob())) {
			starts++
			gate.await()
		}
		val patient = async { flight.await() }
		yield()
		assertNull(withTimeoutOrNull(10) { flight.await() })
		gate.complete("controller")
		assertEquals("controller", patient.await())
		assertEquals(1, starts)
	}
}
