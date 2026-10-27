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

/*
 * The staged queue (P2, portrait phones) shows autoplay as a switch beside a row of the character
 * chips only. The switch is a tap on a chip ([autoplayToggleChip]), so it has the chips' plan, hub
 * edit and signals, and nothing of its own.
 */

/** The chip a tap on the autoplay switch performs: Off, or on with the preferred character (Auto DJ without the engine). */
fun autoplayToggleChip(turnOn: Boolean, stationsAvailable: Boolean, preferred: StationCharacter): AutoplayChip = when {
	!turnOn -> AutoplayChip.Off
	stationsAvailable -> AutoplayChip.of(preferred)
	else -> AutoplayChip.AutoDj
}

/** The chips beside the switch: the characters only. Empty without the engine, when the switch is the whole control. */
fun characterChips(chips: List<AutoplayChip>): List<AutoplayChip> = chips.filter { it.character != null }

/** The switch shows on iff the selected chip ([selectedAutoplayChip]) is not Off. */
fun autoplayToggleShowsOn(selected: AutoplayChip): Boolean = selected != AutoplayChip.Off

/**
 * Whether autoplay is wanted for this queue: the one rule the switch ([selectedAutoplayChip]) and the
 * driver ([autoplayStep]) both read (audit L4a-02). While the engine is there and a station is bound,
 * **the hub's station is the truth**: its `stopped` is shared by every device, and the Off chip sets
 * it. Otherwise the local switch ("Autoplay by default") decides: a default for a queue with no
 * station, not a mute for a station that is playing.
 */
fun autoplayWanted(stationsAvailable: Boolean, autoplayOn: Boolean, bound: StationProvenance?): Boolean =
	if (stationsAvailable && bound != null) !bound.stopped else autoplayOn

/** The chip shown selected: Off exactly when autoplay is not wanted ([autoplayWanted]). */
fun selectedAutoplayChip(
	stationsAvailable: Boolean,
	autoplayOn: Boolean,
	preferred: StationCharacter,
	bound: StationProvenance?
): AutoplayChip {
	if (!autoplayWanted(stationsAvailable, autoplayOn, bound)) return AutoplayChip.Off
	if (!stationsAvailable) return AutoplayChip.AutoDj
	// A character not known yet (no frame) shows the preference rather than a guess.
	bound?.let { StationCharacter.fromWire(it.character) }?.let { return AutoplayChip.of(it) }
	return AutoplayChip.of(preferred)
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
	val applyOnHub: Boolean = false,
	/**
	 * P7: `signal {kind: "mood", value}` to the bound station: a mood id, or "" for All (its own
	 * anchors again). Null: not a mood tap, or nothing to tell the station.
	 */
	val signalMood: String? = null
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
	queueId: String?,
	/**
	 * This device plays the hub session. A queue a transfer brought here has no local id (it is
	 * held as a transient copy of the session's), and it IS the session's queue: without this the
	 * playing device after a transfer swapped nothing and sent the character without `apply`.
	 */
	playsSession: Boolean = false
): Boolean =
	stationEdits && bound != null && !bound.unbound && sessionStationId != null &&
		bound.stationId == sessionStationId && sessionQueueId != null &&
		(queueId == sessionQueueId || (queueId == null && playsSession))

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

// --------------------------------------------------------------------------------------------- //
// Mood chips (P7, PROTOCOL §18.5): All, then up to three moods that suit the playing song
// --------------------------------------------------------------------------------------------- //

/** One mood of `POST /sonic/mood/for`: its id and the song's rank in its merged list. */
@kotlinx.serialization.Serializable
data class MoodRank(val id: String, val rank: Int)

/** `POST /sonic/mood/for`'s answer. [ready] false: the hub's lists are still being built. */
@kotlinx.serialization.Serializable
data class ForSongAnswer(val ready: Boolean = false, val moods: List<MoodRank> = emptyList())

/** How many mood chips the queue shows at most. */
const val MOOD_CHIPS_MAX = 3

/**
 * The mood chips for the playing song: the hub's moods for it, best rank first, at most three, only
 * those the catalogue knows. None while the hub's lists are not ready or nothing was asked.
 */
fun moodChipsFor(answer: ForSongAnswer?, catalogue: MoodCatalogue): List<MoodEntry> {
	if (answer == null || !answer.ready) return emptyList()
	return answer.moods.sortedBy { it.rank }
		.mapNotNull { m -> catalogue.find(m.id) }
		.distinctBy { it.id }
		.take(MOOD_CHIPS_MAX)
}

/**
 * The mood chip shown selected: a bound station's own mood (null = All), else the mood picked for
 * the next continuation ([pending]). Null is All.
 */
fun selectedMoodChip(bound: StationProvenance?, pending: String?): String? =
	if (bound != null) bound.mood else pending

/**
 * A mood chip tapped ([moodId]; null = All). Tapping the station's own mood is All. With a bound
 * station the mood is its source from its next batch (`signal mood`): the hub edits the queue when
 * it can ([onHub]), otherwise this device swaps the songs the station added that are still to come,
 * as a character tap does. With no station, autoplay goes on and the mood waits for the next
 * continuation (the caller keeps it). A character tap never touches the mood.
 */
fun planMoodTap(moodId: String?, bound: StationProvenance?, onHub: Boolean): ChipTapPlan {
	if (bound == null) {
		return ChipTapPlan(
			autoplayOn = true, character = null, stopStation = false, signalCharacter = null,
			removeTail = false, replaceTail = false, runDecision = true
		)
	}
	val target = moodId?.takeUnless { it == bound.mood }
	val steer = target != bound.mood || bound.stopped
	val hub = onHub && steer
	return ChipTapPlan(
		autoplayOn = true,
		character = null,
		stopStation = false,
		signalCharacter = null,
		removeTail = false,
		replaceTail = steer && !bound.stopped && !hub,
		runDecision = !hub,
		applyOnHub = hub,
		signalMood = if (steer) target.orEmpty() else null
	)
}

/** The mood picked for the next continuation of one queue ([queueId]); "" is All, picked explicitly. */
@kotlinx.serialization.Serializable
data class PendingMood(val queueId: String, val moodId: String)

/** The pick for the queue playing now ("" = All), or null: dropped as soon as another queue plays. */
fun pendingMoodFor(pending: PendingMood?, queueId: String?): String? =
	pending?.takeIf { queueId != null && it.queueId == queueId }?.moodId

/**
 * The mood of a queue no station is bound to yet, which its continuation starts in: the chip picked
 * for it ([pending], "" = All), else the mood of the Home tile that made it ([fromTile]).
 */
fun unboundMood(pending: String?, fromTile: String?): String? = when {
	pending == null -> fromTile?.takeIf { it.isNotBlank() }
	pending.isEmpty() -> null
	else -> pending
}

/** A mood tap with no bound station: the pending mood after it (tapping the pending one is All). */
fun nextPendingMood(current: String?, tapped: String?): String? = tapped?.takeUnless { it == current }

/**
 * The songs a hub edit (S-035, `stationEdits`) swapped into the queue: those in [after] that [before]
 * did not hold, once each. The hub's answer gives only counts, so this device reads them off the
 * queue it was sent, and adds them to the station's `served`: they read "Autoplay", not "Up next"
 * (B-177, audit L4a-04).
 */
fun swappedInIds(before: List<String>, after: List<String>): List<String> {
	val had = before.toHashSet()
	return after.filter { it !in had }.distinct()
}
