package paige.navic.ui.screens.settings.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import paige.navic.domain.manager.SyncManager
import paige.navic.data.database.dao.SyncActionDao
import paige.navic.domain.manager.ConnectivityManager
import paige.navic.domain.manager.DownloadManager
import paige.navic.domain.manager.rebuildLibrary
import paige.navic.domain.repositories.DbRepository

class SettingsDataStorageViewModel(
	private val syncManager: SyncManager,
	private val dbRepository: DbRepository,
	private val syncDao: SyncActionDao,
	private val downloadManager: DownloadManager,
	connectivityManager: ConnectivityManager
) : ViewModel() {

	val syncState = syncManager.syncState
		.stateIn(
			scope = viewModelScope,
			started = SharingStarted.WhileSubscribed(5000),
			initialValue = syncManager.syncState.value
		)

	val pendingActionCount: StateFlow<Int>
		field = MutableStateFlow(0)

	val downloadCount = downloadManager.downloadCount.stateIn(
		viewModelScope, SharingStarted.WhileSubscribed(5000), 0
	)
	val downloadSize = downloadManager.downloadSize.stateIn(
		viewModelScope, SharingStarted.WhileSubscribed(5000), 0L
	)

	val isOnline = connectivityManager.isOnline

	init {
		loadPendingActions()
	}

	/**
	 * Observed, not read once: the row opens the pending-actions browser, where rows can be
	 * removed one by one, and a count taken when this screen opened would be wrong on the way back.
	 */
	private fun loadPendingActions() {
		viewModelScope.launch(Dispatchers.IO) {
			syncDao.observePendingActions().collect { pendingActionCount.value = it.size }
		}
	}

	fun triggerManualSync() {
		syncManager.triggerManualSync()
	}

	fun rebuildDatabase() {
		viewModelScope.launch(Dispatchers.IO) {
			// The pull starts after the wipe (rebuildLibrary), not beside it.
			rebuildLibrary(
				stopSync = syncManager::stopPeriodicSync,
				wipe = {
					dbRepository.removeEverything()
					pendingActionCount.value = 0
				},
				sync = syncManager::triggerManualSync
			)
		}
	}

	fun removeAllActions() {
		viewModelScope.launch(Dispatchers.IO) {
			syncDao.clearAllActions()
			pendingActionCount.value = 0
		}
	}

	fun clearAllDownloads() {
		downloadManager.clearAllDownloads()
	}
}
