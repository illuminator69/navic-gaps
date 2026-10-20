package paige.navic.ui.screens.radio.viewmodels

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import navic.composeapp.generated.resources.Res
import navic.composeapp.generated.resources.notice_deleted_radio
import paige.navic.domain.manager.SessionManager
import paige.navic.domain.manager.SnackBarManager
import paige.navic.domain.models.DomainRadio
import paige.navic.domain.repositories.RadioRepository
import paige.navic.ui.core.UiState

class RadioListViewModel(
	private val repository: RadioRepository,
	private val sessionManager: SessionManager,
	private val snackBarManager: SnackBarManager
) : ViewModel() {
	val radiosState: StateFlow<UiState<ImmutableList<DomainRadio>>>
		field = MutableStateFlow<UiState<ImmutableList<DomainRadio>>>(UiState.Loading())

	val gridState = LazyGridState()

	init {
		viewModelScope.launch {
			sessionManager.isLoggedIn.collect { if (it) refreshRadios(false) }
		}
	}

	fun refreshRadios(fullRefresh: Boolean) {
		viewModelScope.launch {
			repository.getRadiosFlow(fullRefresh).collect {
				radiosState.value = it
			}
		}
	}

	/** Delete [radio] on the server (P5 round 2), then read the list again. */
	fun delete(radio: DomainRadio) {
		viewModelScope.launch {
			runCatching { sessionManager.api.deleteInternetRadioStation(radio.id) }
				.onSuccess {
					snackBarManager.notify(Res.string.notice_deleted_radio, radio.name)
					refreshRadios(true)
				}
				.onFailure { radiosState.value = UiState.Error(it as? Exception ?: Exception(it), radiosState.value.data) }
		}
	}

	fun clearError() {
		radiosState.value = UiState.Success(radiosState.value.data ?: persistentListOf())
	}
}
