package paige.navic.ui.screens.attention

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import paige.navic.domain.manager.AttentionPage
import paige.navic.domain.manager.LbBotManager
import paige.navic.domain.manager.attentionPage
import paige.navic.domain.manager.matchesToShow

/**
 * Needs attention (P6): the page is [attentionPage] over what [LbBotManager] already holds (the last
 * attention read, the device's ledger, Library Matches' "to check" list). It holds no list of its
 * own, so a fill frame or a retry moves a row the moment the ledger moves, and the next read (on
 * opening, on a pull, on a frame) replaces the rest.
 */
class AttentionViewModel(private val lbBot: LbBotManager) : ViewModel() {

	val page: StateFlow<AttentionPage> = combine(
		lbBot.attention, lbBot.ledger, lbBot.matchesToCheck, lbBot.artistOverrides
	) { state, ledger, toCheck, overrides ->
		attentionPage(
			read = state.status,
			last = state.last,
			lastAt = state.lastAt.takeIf { it > 0L },
			ledger = ledger,
			artistsToCheck = matchesToShow(toCheck, overrides)?.size
		)
	}.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AttentionPage.Loading)

	val webUrl: StateFlow<String> = lbBot.webUrl

	/** Read again: on opening the page and on a pull. The hub's 20 s cache keeps this cheap. */
	fun refresh(): Job = viewModelScope.launch { lbBot.refreshAttention(force = true) }
}
