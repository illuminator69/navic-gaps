package paige.navic.ui.screens.radio.viewmodels

import androidx.compose.foundation.text.input.TextFieldState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import navic.composeapp.generated.resources.Res
import navic.composeapp.generated.resources.notice_created_radio
import navic.composeapp.generated.resources.notice_updated_radio
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import paige.navic.domain.models.DomainRadio
import paige.navic.domain.manager.SessionManager
import paige.navic.domain.manager.SnackBarManager
import paige.navic.ui.core.UiState

class RadioCreateDialogViewModel(
	private val sessionManager: SessionManager,
	private val snackBarManager: SnackBarManager
) : ViewModel() {
	val creationState: StateFlow<UiState<Nothing?>>
		field = MutableStateFlow<UiState<Nothing?>>(UiState.Success(null))

	private val _events = Channel<Event>()
	val events = _events.receiveAsFlow()

	val name = TextFieldState()
	val streamUrl = TextFieldState()
	val homepageUrl = TextFieldState()

	/** The station being edited (P5 round 2: a radio could be added but never changed); null adds one. */
	private var editingId: String? = null

	/** Fill the form from [radio], once: the dialog reopened after a rotation keeps the edits. */
	fun edit(radio: DomainRadio) {
		if (editingId == radio.id) return
		editingId = radio.id
		name.setTextAndPlaceCursorAtEnd(radio.name)
		streamUrl.setTextAndPlaceCursorAtEnd(radio.streamUrl)
		homepageUrl.setTextAndPlaceCursorAtEnd(radio.homepageUrl.orEmpty())
	}

	fun create() {
		viewModelScope.launch {
			creationState.value = UiState.Loading()
			try {
				val homepage = homepageUrl.text.toString().trim().takeIf { it.isNotBlank() }
				val id = editingId
				if (id == null) {
					sessionManager.api.createInternetRadioStation(
						name = name.text.toString(),
						streamUrl = streamUrl.text.toString(),
						homepageUrl = homepage
					)
				} else {
					sessionManager.api.updateInternetRadioStation(
						id = id,
						streamUrl = streamUrl.text.toString(),
						name = name.text.toString(),
						homepageUrl = homepage
					)
				}
				_events.send(Event.Dismiss)
				creationState.value = UiState.Success(null)
				snackBarManager.notify(
					if (id == null) Res.string.notice_created_radio else Res.string.notice_updated_radio,
					name.text.toString()
				)
			} catch (e: Exception) {
				creationState.value = UiState.Error(e)
			}
		}
	}

	sealed class Event {
		object Dismiss : Event()
	}
}
