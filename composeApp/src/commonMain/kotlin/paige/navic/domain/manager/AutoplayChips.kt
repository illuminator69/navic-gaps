package paige.navic.domain.manager

import paige.navic.domain.models.settings.StationCharacter

/*
 * The queue sheet's autoplay chips (I-041), every decision as a pure function
 * (`AutoplayChipsTest`). The same spec as Feishin's `station-chips.ts`:
 *
 * - With the hub's station engine: Off, Close, Balanced, Explore. Without it: Off, Auto DJ.
 * - The selected chip: for a queue a station is bound to, Off iff the station is stopped, else its
 *   character; otherwise the preference (Off when autoplay is off, else the preferred character, or
 *   Auto DJ without the engine).
 * - Off: autoplay off; a bound station is stopped for every device, and the songs it added that are
 *   still to come are taken out. Nothing more is added while Off.
 * - A character: autoplay on with that character; a bound station that is stopped or on another
 *   character is told so, and the songs it added that are still to come are swapped for a fresh
 *   batch at once. Then the top-up decision runs at once, not at the next track change, playing or
 *   paused.
 * - Who edits (S-035): with the hub's `stationEdits` and the queue being the session's bound station
 *   ([chipEditsOnHub]), the HUB edits it (`signal` with `apply: true`) and the result reaches every
 *   device as a queue change; this device sends the signal and nothing else. Otherwise this device
 *   edits, by the hub's own cut ([STATION_EDIT_KEEP_NEXT_MS]).
 *
 * Never the playing song, the next one only with under 20 s left of it, never a reload.
 */

/** One chip. [character] for the three character chips. */
enum class AutoplayChip(val character: StationCharacter?) {
	Off(null),
	Close(StationCharacter.Close),
	Balanced(StationCharacter.Balanced),
	Explore(StationCharacter.Explore),
	AutoDj(null);

	companion object {
		fun of(character: StationCharacter): AutoplayChip = when (character) {
			StationCharacter.Close -> Close
			StationCharacter.Balanced -> Balanced
			StationCharacter.Explore -> Explore
		}
	}
}

/** The chips on offer: the three characters with the engine, Auto DJ without it. */
fun autoplayChips(stationsAvailable: Boolean): List<AutoplayChip> =
	if (stationsAvailable) listOf(AutoplayChip.Off, AutoplayChip.Close, AutoplayChip.Balanced, AutoplayChip.Explore)
	else listOf(AutoplayChip.Off, AutoplayChip.AutoDj)

/** The chip shown selected. */
fun selectedAutoplayChip(
	stationsAvailable: Boolean,
	autoplayOn: Boolean,
	preferred: StationCharacter,
	bound: StationProvenance?
): AutoplayChip {
	if (!stationsAvailable) return if (autoplayOn) AutoplayChip.AutoDj else AutoplayChip.Off
	if (bound != null) {
		if (bound.stopped) return AutoplayChip.Off
		// A character not known yet (no frame) shows the preference rather than a guess.
		StationCharacter.fromWire(bound.character)?.let { return AutoplayChip.of(it) }
	}
	return if (autoplayOn) AutoplayChip.of(preferred) else AutoplayChip.Off
}

/** What a tap on a chip does, decided before anything is sent. */
data class ChipTapPlan(
	val autoplayOn: Boolean,
	/** The preference's new character, or null to keep it. */
	val character: StationCharacter?,
	/** `signal {kind: "stop"}` to the bound station. */
	val stopStation: Boolean,
	/** `signal {kind: "character", value}` to the bound station (it also clears "stopped"). */
	val signalCharacter: StationCharacter?,
	/** Take out the bound station's unplayed songs after the next one. */
	val removeTail: Boolean,
	/** Swap the bound station's unplayed songs after the next one for a fresh `next` batch. */
	val replaceTail: Boolean,
	/** Run the top-up decision at once (paused or not). */
	val runDecision: Boolean,
	/**
	 * The signal goes with `apply: true` and the hub edits the queue (S-035): no removes, no
	 * `next`, no decision here for this tap. The hub's answer or its queue change is the result.
	 */
	val applyOnHub: Boolean = false
)

/**
 * Whether the hub edits the queue for a chip (S-035): it offers `stationEdits`, and the queue is
 * the session's own, bound to the station the chip acts on: this device playing it, or this
 * device controlling the one that does ([queueId] is then the session's). A station the hub
 * declined to bind (`unbound`) is this device's to edit.
 */
fun chipEditsOnHub(
	stationEdits: Boolean,
	bound: StationProvenance?,
	sessionStationId: String?,
	sessionQueueId: String?,
	queueId: String?
): Boolean =
	stationEdits && bound != null && !bound.unbound && sessionStationId != null &&
		bound.stationId == sessionStationId && queueId != null && queueId == sessionQueueId

fun planChipTap(chip: AutoplayChip, bound: StationProvenance?, onHub: Boolean = false): ChipTapPlan {
	val hub = onHub && bound != null
	return when (chip) {
		AutoplayChip.Off -> ChipTapPlan(
			autoplayOn = false,
			character = null,
			// The hub's edit rides the stop, so it is sent again for a station already stopped: its
			// songs still to come go either way.
			stopStation = bound != null && (hub || !bound.stopped),
			signalCharacter = null,
			removeTail = bound != null && !hub,
			replaceTail = false,
			runDecision = false,
			applyOnHub = hub
		)

		AutoplayChip.AutoDj -> ChipTapPlan(
			autoplayOn = true, character = null, stopStation = false, signalCharacter = null,
			removeTail = false, replaceTail = false, runDecision = true
		)

		else -> {
			val character = chip.character!!
			val steer = bound != null && (bound.stopped || bound.character != character.wireId)
			ChipTapPlan(
				autoplayOn = true,
				character = character,
				stopStation = false,
				signalCharacter = if (steer) character else null,
				removeTail = false,
				// A stopped station's songs went when it was stopped: there is nothing to swap.
				replaceTail = steer && !bound.stopped && !hub,
				// The hub's edit puts a fresh batch in itself (where the old songs were, or at the end).
				runDecision = !(steer && hub),
				applyOnHub = steer && hub
			)
		}
	}
}

/**
 * How long the playing song must still have to run for its next one to be edited, the hub's own
 * cut (S-035): with less, the next song may already be loading or decoding for a gapless start, and
 * it stays.
 */
const val STATION_EDIT_KEEP_NEXT_MS = 20_000L

/** Whether a chip's edit keeps the next song: under [STATION_EDIT_KEEP_NEXT_MS] left of the playing one. */
fun chipEditKeepsNext(remainingMs: Long?): Boolean = remainingMs != null && remainingMs < STATION_EDIT_KEEP_NEXT_MS

/**
 * The queue indices a chip may take out when this device edits: songs the bound station added
 * ([served]) still to come in play order ([order], the shuffle order, or null for queue order),
 * from the one after the playing song, or from the one after that when [keepNext]
 * ([chipEditKeepsNext]). Highest index first, so they can be removed one by one without shifting
 * the rest. Never the playing song; never a song the user put in.
 *
 * B-145: the next song was always kept, so the radio's second song outlived every chip.
 */
fun stationTailIndices(
	queueIds: List<String>,
	order: List<Int>?,
	currentIndex: Int,
	served: Collection<String>,
	keepNext: Boolean = false
): List<Int> {
	if (currentIndex !in queueIds.indices || served.isEmpty()) return emptyList()
	val sequence = if (order != null && order.size == queueIds.size && currentIndex in order) order
	else queueIds.indices.toList()
	val at = sequence.indexOf(currentIndex)
	val servedSet = served.toHashSet()
	return sequence.drop(at + if (keepNext) 2 else 1)
		.filter { queueIds[it] in servedSet }
		.sortedDescending()
}
