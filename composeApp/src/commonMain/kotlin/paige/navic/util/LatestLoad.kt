package paige.navic.util

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/**
 * One read at a time, the newest wins: [launch] cancels the read before it, and [cancel] the one
 * in flight, so a slow earlier answer is never applied over a later one (or after the user has
 * moved on). A read that throws is handed to `onError` and applies nothing; it never reaches the
 * scope, where it would take the app down.
 */
class LatestLoad(private val scope: CoroutineScope) {
	private var job: Job? = null

	fun <T> launch(
		load: suspend () -> T,
		onError: (Throwable) -> Unit = {},
		apply: (T) -> Unit
	): Job {
		job?.cancel()
		return scope.launch {
			val result = try {
				load()
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				onError(e)
				return@launch
			}
			ensureActive() // cancelled while the answer was on its way back: it is stale
			apply(result)
		}.also { job = it }
	}

	fun cancel() {
		job?.cancel()
		job = null
	}
}
