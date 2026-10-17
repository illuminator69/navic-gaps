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
 *   still to come after the next one are taken out. Nothing more is added while Off.
 * - A character: autoplay on with that character; a bound station that is stopped or on another
 *   character is told so, and the songs it added after the next one are swapped for a fresh batch
 *   at once. Then the top-up decision runs at once, not at the next track change.
 *
 * Never the playing song, never the one after it (it may already be decoding), never a reload.
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
	/** Run the top-up decision at once. */
	val runDecision: Boolean
)

fun planChipTap(chip: AutoplayChip, bound: StationProvenance?): ChipTapPlan = when (chip) {
	AutoplayChip.Off -> ChipTapPlan(
		autoplayOn = false,
		character = null,
		stopStation = bound != null && !bound.stopped,
		signalCharacter = null,
		removeTail = bound != null,
		replaceTail = false,
		runDecision = false
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
			replaceTail = steer && !bound.stopped,
			runDecision = true
		)
	}
}

/**
 * The queue indices a chip may take out: songs the bound station added ([served]) that come after
 * the NEXT one in play order ([order], the shuffle order, or null for queue order). Highest index
 * first, so they can be removed one by one without shifting the rest. Never the playing song and
 * never the next one; never a song the user put in.
 */
fun stationTailIndices(
	queueIds: List<String>,
	order: List<Int>?,
	currentIndex: Int,
	served: Collection<String>
): List<Int> {
	if (currentIndex !in queueIds.indices || served.isEmpty()) return emptyList()
	val sequence = if (order != null && order.size == queueIds.size && currentIndex in order) order
	else queueIds.indices.toList()
	val at = sequence.indexOf(currentIndex)
	val servedSet = served.toHashSet()
	return sequence.drop(at + 2)
		.filter { queueIds[it] in servedSet }
		.sortedDescending()
}
