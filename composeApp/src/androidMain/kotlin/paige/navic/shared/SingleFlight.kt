package paige.navic.shared

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async

/**
 * At most one run of [start] in flight, shared by every caller of [await] (Q-040).
 *
 * The media controller is reconnected on demand from several places at once: a hub `do:load`, a
 * `play`, the activity starting. Each building its own MediaController would bind the service
 * twice and run `setupController` twice. A finished run is forgotten whatever it returned, so a
 * failed connect (null) is retried by the next caller rather than handed out again.
 *
 * The run belongs to [scope], not to a caller: one caller giving up (a timeout, its own
 * cancellation) does not cancel the connect the others are waiting on.
 *
 * Not thread-safe. Call [await] from one thread; the view model uses Main.
 */
internal class SingleFlight<T : Any>(
	private val scope: CoroutineScope,
	private val start: suspend () -> T?
) {
	private var inFlight: Deferred<T?>? = null

	suspend fun await(): T? {
		val run = inFlight ?: scope.async { start() }.also { run ->
			// Set before the handler is registered: a run that finished synchronously fires it
			// at once, and must find itself to clear.
			inFlight = run
			run.invokeOnCompletion { if (inFlight === run) inFlight = null }
		}
		return run.await()
	}
}
