package paige.navic.ui.screens.collection

import paige.navic.domain.manager.LbBotManager
import paige.navic.domain.manager.LbFillEntry
import paige.navic.domain.manager.LbGap
import paige.navic.domain.manager.LbGapSource
import paige.navic.domain.manager.LbGapTrack
import paige.navic.domain.manager.gapReason
import paige.navic.ui.screens.external.RequestStep
import paige.navic.ui.screens.external.preselectedSource
import paige.navic.ui.screens.external.requestSteps

/*
 * navi-connect P4, round 2: the album page's gaps box, which retired `GapFillSheet` (the owner's
 * storyboard D, "the gaps box does everything"). The "N tracks missing" line unfolds to the missing
 * songs; Fill gaps turns the same box into the ranked sources for them; a request turns it into the
 * steps; the finish folds it to a result row and then to nothing. A failure keeps it open, saying why.
 *
 * Which of those it shows is one function of what is known (lb-bot's gap record, this device's
 * ledger row for the group, where the user has the box, a tap in flight), so the box cannot be in two
 * states at once and every precedence is a test (`GapStageTest`). As with the request box, a transfer
 * in flight is the truth whatever else is set: it may have been started on another client.
 */

/** Where the user has put the box. */
enum class GapView { FOLDED, TRACKS, SOURCES }

/** How long the result row stays before the box folds away. */
const val GAP_RESULT_HOLD_MS = 6_000L

data class GapInput(
	/** lb-bot's record for the group; null until the first read lands. */
	val gap: LbGap?,
	/** The index mirror's count, for the folded line before [gap] lands. */
	val mirroredMissing: Int,
	/** This device's ledger row for the group (keyed by the group id), if any. */
	val fill: LbFillEntry?,
	val view: GapView,
	/** A fetch lb-bot accepted that its status does not show yet. */
	val committed: Boolean,
	/** A search tap whose answer, or the poll that shows it running, has not come yet. */
	val searchAsked: Boolean,
	val now: Long
)

sealed interface GapStage {
	/** Nothing missing, or the result has been shown: the box folds away. */
	data object Absent : GapStage
	/** One line: how many are missing, Fill gaps, a chevron. */
	data class Folded(val missing: Int) : GapStage
	/** The box is open and lb-bot's record has not come yet. */
	data object Loading : GapStage
	/** Unfolded to the missing songs and what they are measured against. */
	data class Tracks(val missing: List<LbGapTrack>, val total: Int) : GapStage
	data object Searching : GapStage
	data class Choosing(val sources: List<LbGapSource>, val preselected: Int?) : GapStage
	/** A search that ended with nothing; lb-bot's reason when it gave one. */
	data class NoSources(val reason: String, val mp3WouldHelp: Boolean) : GapStage
	data class Active(val done: Int, val wanted: Int, val failed: Int, val steps: List<RequestStep>) : GapStage
	/** Files that arrived and need filing in lb-bot: no search or fetch from here can help. */
	data class Attention(val reason: String) : GapStage
	data class Failed(val reason: String, val mp3WouldHelp: Boolean) : GapStage
	data class Done(val added: Int, val present: Int, val total: Int) : GapStage
}

/** States a track is in while a fill is moving it. */
private val IN_FLIGHT_TRACK_STATES = setOf("queued", "downloading")

/** Tracks that are no longer missing: in the library before, or put there by a fill. */
private val HELD_TRACK_STATES = setOf("present", "done")

fun gapStage(input: GapInput): GapStage {
	val gap = input.gap
	val fill = input.fill
	if (gap == null) {
		return when {
			input.mirroredMissing <= 0 -> GapStage.Absent
			input.view == GapView.FOLDED -> GapStage.Folded(input.mirroredMissing)
			else -> GapStage.Loading
		}
	}
	val transferring = input.committed || gap.status == "downloading" ||
		gap.tracks.any { it.state in IN_FLIGHT_TRACK_STATES }
	if (transferring) {
		return GapStage.Active(
			done = gap.tracksDone,
			wanted = gap.tracksWanted,
			failed = gap.tracksFailed,
			steps = requestSteps(gapStepState(gap), gap.tracksDone, gap.tracksWanted)
		)
	}
	val missing = missingTracks(gap)
	if (fill != null && fill.settled && fill.outcome == LbBotManager.OUTCOME_DONE) {
		if (input.now - fill.finishedAt < GAP_RESULT_HOLD_MS) {
			return GapStage.Done(
				added = fill.done.takeIf { it > 0 } ?: fill.total,
				present = gap.present,
				total = gap.total
			)
		}
		if (missing.isEmpty()) return GapStage.Absent
	}
	if (gap.status == "complete" || (missing.isEmpty() && gap.total > 0)) return GapStage.Absent
	val searching = input.searchAsked || gap.sourceTask?.status.orEmpty() in LbBotManager.SEARCH_IN_FLIGHT
	if (gap.stalledPlacement || gap.awaitingMatch(searching)) return GapStage.Attention(gapReason(gap))
	if (input.view == GapView.SOURCES) {
		return when {
			searching -> GapStage.Searching
			gap.sources.isNotEmpty() -> GapStage.Choosing(gap.sources, preselectedSource(gap.sources))
			gap.noSourceReason.isNotBlank() || gap.sourceTask != null ->
				GapStage.NoSources(gap.noSourceReason, gap.mp3WouldHelp)
			// Nothing asked yet: the box asks as it opens on the sources.
			else -> GapStage.Searching
		}
	}
	// A fill that failed keeps the box open, saying why, until it is dismissed or tried again.
	if (fill != null && fill.settled && fill.outcome == LbBotManager.OUTCOME_FAILED) {
		return GapStage.Failed(gapReason(gap).ifBlank { fill.reason }, gap.mp3WouldHelp || fill.mp3WouldHelp)
	}
	return when (input.view) {
		GapView.TRACKS -> GapStage.Tracks(missing, gap.total)
		else -> GapStage.Folded(missing.size.takeIf { it > 0 } ?: input.mirroredMissing)
	}
}

/** The tracks the album still lacks, in tracklist order. */
fun missingTracks(gap: LbGap): List<LbGapTrack> = gap.tracks.filter { it.state !in HELD_TRACK_STATES }

/**
 * A gap fill's tracks as the request box's step names (`requestSteps`): one downloading is
 * Downloading; queued ones with none moving yet are Queued; all fetched and some not yet filed is
 * Placing in your library.
 */
fun gapStepState(gap: LbGap): String {
	val wanted = gap.tracks.filter { it.state != "present" }
	return when {
		wanted.any { it.state == "downloading" } -> "downloading"
		wanted.any { it.state == "queued" || it.state == "picked" } -> "queued"
		wanted.any { it.state == "downloaded" } -> "placing"
		else -> "queued"
	}
}
