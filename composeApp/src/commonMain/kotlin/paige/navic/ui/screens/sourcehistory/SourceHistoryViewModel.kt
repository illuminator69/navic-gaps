package paige.navic.ui.screens.sourcehistory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import paige.navic.domain.manager.ActivityRead
import paige.navic.domain.manager.ActivityTransfer
import paige.navic.domain.manager.HistoryGroups
import paige.navic.domain.manager.LbActivity
import paige.navic.domain.manager.LbBotManager
import paige.navic.domain.manager.historyGroups
import paige.navic.domain.manager.mergeHistory

/** What lb-bot said last ([last], null before its first good answer) and how the last read went. */
data class SourceHistoryServer(val last: LbActivity? = null, val status: ActivityRead? = null)

/**
 * Source history (P6): lb-bot's ledger read on demand (`GET /lb/activity`) joined with this device's
 * ([mergeHistory]). The device's rows move the moment the ledger does; lb-bot's are read on opening,
 * on a pull and every [RUNNING_REREAD_MS] while one of its rows is running and the page is on screen
 * (the screen drives that; nobody watching, no read).
 */
class SourceHistoryViewModel(private val lbBot: LbBotManager) : ViewModel() {
	private val _server = MutableStateFlow(SourceHistoryServer())
	val server: StateFlow<SourceHistoryServer> = _server.asStateFlow()

	val groups: StateFlow<HistoryGroups> = combine(_server, lbBot.ledger) { server, ledger ->
		historyGroups(mergeHistory(server.last?.requests.orEmpty(), ledger))
	}.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HistoryGroups(emptyList(), emptyList()))

	val transfers: StateFlow<List<ActivityTransfer>> get() = _transfers
	private val _transfers = MutableStateFlow<List<ActivityTransfer>>(emptyList())

	val lbConfigured: Boolean get() = lbBot.isConfigured

	/** A good answer replaces the last; an older lb-bot forgets it; a failure keeps it on screen. */
	fun load(): Job = viewModelScope.launch {
		val read = lbBot.activity()
		_server.value = when (read) {
			is ActivityRead.Ok -> SourceHistoryServer(LbActivity(version = 1, requests = read.requests, transfers = read.transfers), read)
			ActivityRead.Older -> SourceHistoryServer(null, read)
			ActivityRead.Unavailable -> _server.value.copy(status = read)
		}
		_transfers.value = _server.value.last?.transfers.orEmpty()
	}

	companion object {
		const val RUNNING_REREAD_MS = 10_000L
	}
}
