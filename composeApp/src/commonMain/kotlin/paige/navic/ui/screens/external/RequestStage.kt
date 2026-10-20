package paige.navic.ui.screens.external

import navic.composeapp.generated.resources.Res
import navic.composeapp.generated.resources.request_step_count
import navic.composeapp.generated.resources.request_step_downloading
import navic.composeapp.generated.resources.request_step_finding
import navic.composeapp.generated.resources.request_step_in_library
import navic.composeapp.generated.resources.request_step_placing
import navic.composeapp.generated.resources.request_step_queued
import navic.composeapp.generated.resources.request_step_source_chosen
import paige.navic.domain.manager.LbBotManager
import paige.navic.domain.manager.LbFillEntry
import paige.navic.domain.manager.LbGapSource
import paige.navic.domain.manager.LbRelease
import paige.navic.ui.components.common.FillButton
import paige.navic.ui.components.common.FillPresentation
import paige.navic.ui.components.common.FillText
import paige.navic.ui.components.common.describeFill

/*
 * navi-connect P4: which stage the album page's request box is in (`RequestBox`). One function of
 * what is known — the hub's support, this device's ledger row for the release-group, lb-bot's index
 * row, the source search, the edition on screen, a tap in flight — so the box cannot be in two
 * stages at once and every precedence below is a test (`RequestStageTest`).
 *
 * A running fill is the truth whatever else is set: it may have been started on another device or
 * before a restart, and a second request is the one thing the box must not offer then.
 */

/** Where the source search for this album is. Keyed by the edition it was asked for. */
sealed interface SourceSearch {
	data object Idle : SourceSearch
	data class Searching(val editionMbid: String) : SourceSearch
	data class Found(val editionMbid: String, val sources: List<LbGapSource>) : SourceSearch
	data class Failed(val error: LbBotManager.LbError) : SourceSearch
}

/** Whether this hub can take a request at all: lb-bot off, a hub too old for the source picker, or yes. */
enum class Support { ABSENT, NEEDS_HUB_RESTART, YES }

data class RequestInput(
	val support: Support,
	/** This device's ledger row for the release-group (its key is the rgid), if any. */
	val fill: LbFillEntry?,
	/** lb-bot's index row for the release-group, if it has one. */
	val indexed: LbRelease?,
	val search: SourceSearch,
	/** The edition the box is showing; a search for another one is not this box's. */
	val editionMbid: String,
	/** A request tap whose answer has not come back yet. */
	val starting: Boolean,
	val wishlistAvailable: Boolean,
	val now: Long
)

enum class StepStatus { DONE, NOW, PENDING }

data class RequestStep(val label: FillText, val status: StepStatus, val detail: FillText? = null)

sealed interface RequestStage {
	/** lb-bot is off: the box is not drawn. */
	data object Absent : RequestStage
	/** The hub does not proxy the source picker yet. */
	data object NeedsHubRestart : RequestStage
	/** Nothing asked yet; [cancelledBefore] when the last request was cancelled. */
	data class Idle(val cancelledBefore: Boolean) : RequestStage
	data object Searching : RequestStage
	data class SearchFailed(val error: LbBotManager.LbError) : RequestStage
	data object NoSources : RequestStage
	/** [preselected] only when [preselectedSource] found one safe to offer as the default. */
	data class Choosing(val sources: List<LbGapSource>, val preselected: Int?) : RequestStage
	data object Starting : RequestStage
	data class Active(val view: FillPresentation, val steps: List<RequestStep>) : RequestStage
	/** Settled, waiting on the user: a source to pick, or a match to sort out in lb-bot. */
	data class Attention(val view: FillPresentation) : RequestStage
	data class Failed(val view: FillPresentation) : RequestStage
	data class Done(val view: FillPresentation) : RequestStage
	/** lb-bot's index says the library holds it; Room has not caught up yet. */
	data object InLibrary : RequestStage
}

fun requestStage(input: RequestInput): RequestStage {
	if (input.support == Support.ABSENT) return RequestStage.Absent
	val fill = input.fill
	if (fill != null && !fill.settled) {
		return RequestStage.Active(
			describeFill(fill, input.now, input.wishlistAvailable),
			requestSteps(fill.state, fill.done, fill.total)
		)
	}
	if (input.indexed != null && !input.indexed.isMissing) return RequestStage.InLibrary
	var cancelledBefore = false
	if (fill != null) {
		val view = describeFill(fill, input.now, input.wishlistAvailable)
		when {
			fill.outcome == "cancelled" -> cancelledBefore = true
			fill.outcome == "done" -> return RequestStage.Done(view)
			fill.outcome == "needs_pick" || fill.state == "needs_match" -> return RequestStage.Attention(view)
			else -> return RequestStage.Failed(view)
		}
	}
	if (input.support == Support.NEEDS_HUB_RESTART) return RequestStage.NeedsHubRestart
	if (input.starting) return RequestStage.Starting
	return when (val search = input.search) {
		SourceSearch.Idle -> RequestStage.Idle(cancelledBefore)
		is SourceSearch.Searching ->
			if (search.editionMbid == input.editionMbid) RequestStage.Searching else RequestStage.Idle(cancelledBefore)
		is SourceSearch.Found -> when {
			search.editionMbid != input.editionMbid -> RequestStage.Idle(cancelledBefore)
			search.sources.isEmpty() -> RequestStage.NoSources
			else -> RequestStage.Choosing(search.sources, preselectedSource(search.sources))
		}
		is SourceSearch.Failed -> RequestStage.SearchFailed(search.error)
	}
}

/**
 * The five steps a request walks through, for a fill in [state]. The captions are Navic's own
 * (PROTOCOL §15.2 names states, not steps); a state that is not a step of a running request gives
 * no steps at all.
 */
fun requestSteps(state: String, done: Int, total: Int): List<RequestStep> {
	val now = when (state) {
		"searching" -> 0
		"queued" -> 1
		"downloading" -> 2
		"placing" -> 3
		"placed" -> 4
		"verified" -> 5
		else -> return emptyList()
	}
	val labels = listOf(
		if (state == "searching") Res.string.request_step_finding else Res.string.request_step_source_chosen,
		Res.string.request_step_queued,
		Res.string.request_step_downloading,
		Res.string.request_step_placing,
		Res.string.request_step_in_library
	)
	return labels.mapIndexed { index, res ->
		RequestStep(
			label = FillText.Res(res),
			status = when {
				index < now -> StepStatus.DONE
				index == now -> StepStatus.NOW
				else -> StepStatus.PENDING
			},
			detail = if (index == 2 && index == now && total > 0) {
				FillText.Res(Res.string.request_step_count, listOf(done, total))
			} else null
		)
	}
}

/**
 * The source the box may select on the user's behalf: lb-bot's recommendation, and only when lb-bot
 * also found the artist in the folder and reads the folder as this album. Anything less stays
 * unselected, and "Request this album" waits for a tap: a tap on an unverified folder is consent,
 * and a default is not.
 */
fun preselectedSource(sources: List<LbGapSource>): Int? =
	sources.indexOfFirst { it.recommended && it.artistVerified && it.albumMatchOk }.takeIf { it >= 0 }

private val ACTION_ORDER = listOf(
	FillButton.RETRY,
	FillButton.TRY_ANOTHER,
	FillButton.ALLOW_MP3,
	FillButton.WISHLIST,
	FillButton.CANCEL,
	FillButton.DISMISS
)

/** The box's recovery rows: [view]'s buttons, in the box's order, without Open album (the box redirects). */
fun requestActions(view: FillPresentation): List<FillButton> = ACTION_ORDER.filter { it in view.buttons }
