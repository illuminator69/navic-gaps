package paige.navic.domain.manager

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import paige.navic.domain.repositories.DbRepository
import paige.navic.ui.core.LoginUiState
import paige.navic.ui.util.AddressUtil

class LoginManager(
    private val repository: DbRepository,
    private val sessionManager: SessionManager,
    // B-279 review: its cached token is this user's; a logout forgets it.
    private val nativeApi: NativeApiManager
) {
	val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

	val loginState: StateFlow<LoginUiState>
		field = MutableStateFlow<LoginUiState>(LoginUiState.Idle)

	val instanceState = TextFieldState()
	val usernameState = TextFieldState()
	val passwordState = TextFieldState()

	var instanceError by mutableStateOf(false)
		private set
	var usernameError by mutableStateOf(false)
		private set
	var passwordError by mutableStateOf(false)
		private set

	/**
	 * A stored password that could not be read on this launch (B-279): the screen says why it
	 * asks again. A session whose password is missing for any other reason (a device transfer)
	 * only has its server and name filled in.
	 */
	var signInAgain by mutableStateOf(false)
		private set

	fun validateInstance() {
		instanceError = instanceState.text.isBlank()
	}

	fun validateUsername() {
		usernameError = usernameState.text.isBlank()
	}

	fun validatePassword() {
		passwordError = passwordState.text.isBlank()
	}

	fun validateStuff(): Boolean {
		validateInstance()
		validateUsername()
		validatePassword()
		return !instanceError && !usernameError && !passwordError
	}

	fun isLocalNetworkInstance(): Boolean {
		val host = AddressUtil.extractHostFromUri(instanceState.text.toString())

		return if (host != null) {
			AddressUtil.isAddressLocal(host)
		} else {
			false
		}
	}

	init {
		loadUser()
	}

	fun loadUser() {
		sessionManager.signInAgainAs()?.let { (instanceUrl, username) ->
			signInAgain = sessionManager.passwordWasUnreadable()
			if (instanceState.text.isEmpty()) instanceState.setTextAndPlaceCursorAtEnd(instanceUrl)
			if (usernameState.text.isEmpty()) usernameState.setTextAndPlaceCursorAtEnd(username)
		}
		scope.launch {
			if (sessionManager.isLoggedIn.value) {
				loginState.value = LoginUiState.Success
			} else {
				loginState.value = LoginUiState.Idle
			}
		}
	}

	fun login(): Boolean {
		if (!validateStuff()) return false

		scope.launch {
			loginState.value = LoginUiState.Loading

			try {
				val rawUrl = instanceState.text.toString().trim().removeSuffix("/")
				val url = if (rawUrl.startsWith("https://") || rawUrl.startsWith("http://")) {
					rawUrl
				} else {
					"https://$rawUrl"
				}

				sessionManager.login(
					url,
					usernameState.text.toString(),
					passwordState.text.toString()
				)

				repository.syncEverything { progress, message ->
					loginState.value = LoginUiState.Syncing(progress, message)
				}.onSuccess {
					loginState.value = LoginUiState.Success
				}.onFailure { e ->
					loginState.value = LoginUiState.Error(e as Exception)
				}

			} catch (e: Exception) {
				loginState.value = LoginUiState.Error(e)
			}
		}

		return true
	}

	fun logout() {
		loginState.value = LoginUiState.Idle
		signInAgain = false
		// B-279: the login form outlives the session (a singleton), and kept the password typed.
		passwordState.clearText()
		sessionManager.logout()
		nativeApi.forget()
		scope.launch {
			repository.removeEverything()
		}
	}
}
