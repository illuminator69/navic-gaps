package paige.navic.ui.util

import paige.navic.util.Logger
import kotlin.time.TimeSource

/**
 * Diagnostics for the colour-arrival work (BACKLOG I-043): when a cover's colours are asked for,
 * found, fetched, and worn, on one clock. Read with `adb logcat -s ColourArrival`. Behaviour does
 * not depend on it, and it is removed once the work is measured.
 *
 * Event names, used verbatim: `player.id <id>`, `seeds.hit <id>`, `seeds.fetch.start <id>`,
 * `seeds.fetch.done <id>`, `seeds.fetch.fail <id>`, `wash.compose resolved=<bool> id=<id>`,
 * `art.loaded <id>`.
 */
object ColourArrivalTrace {
	private var start: TimeSource.Monotonic.ValueTimeMark? = null
	private var lastPlayerId: String? = null

	fun mark(event: String) {
		val origin = start ?: TimeSource.Monotonic.markNow().also { start = it }
		Logger.i("ColourArrival", "+${origin.elapsedNow().inWholeMilliseconds} $event")
	}

	/** `player.id`, once per change of the playing cover id (it is read from many composables). */
	fun playerId(id: String?) {
		if (id == null || id == lastPlayerId) return
		lastPlayerId = id
		mark("player.id $id")
	}
}
