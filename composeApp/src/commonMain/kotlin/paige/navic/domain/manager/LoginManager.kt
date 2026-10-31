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
import paige.navic.util.Logger

class LoginManager(
    private val repository: DbRepository,
    private val sessionManager: SessionManager,
    // B-279 review: its cached token is this user's; a logout forgets it.
    private val nativeApi: NativeApiManager,
    // B-108: whether a failed login names Android's local network permission.
    private val localNetwork: LocalNetworkAccess
) {
	val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

	val loginState: StateFlow<LoginUiState>
		field = MutableStateFlow<LoginUiState>(LoginUiState.Idle)

	val instanceState = TextFieldState()
	val usernameState = TextFieldState()
	val passwordState = TextFieldState()

	var instanceError by mutableStateOf(false)
		private set
	/** Why the address field is red, for the line under it; null while it is not. */
	var instanceProblem by mutableStateOf<InstanceUrlProblem?>(null)
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
		val problem = (normalizeInstanceUrl(instanceState.text.toString()) as? InstanceUrlResult.Invalid)?.reason
		instanceProblem = problem
		instanceError = problem != null
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

	/**
	 * Is the typed server on this network (B-108)? Total: it reads the text and nothing else,
	 * resolves no name, and an address that does not parse is not local (B-283, B-296: this used
	 * to be `InetAddress.getByName`, which threw out of the Log in tap for a typo).
	 */
	fun isLocalNetworkInstance(): Boolean = try {
		(normalizeInstanceUrl(instanceState.text.toString()) as? InstanceUrlResult.Ok)
			?.host?.let(::isPrivateHost) ?: false
	} catch (e: Exception) {
		Logger.w("LoginManager", "isLocalNetworkInstance: ${e::class.simpleName}")
		false
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
		val address = normalizeInstanceUrl(instanceState.text.toString()) as? InstanceUrlResult.Ok
			?: return false

		scope.launch {
			loginState.value = LoginUiState.Loading

			try {
				sessionManager.login(
					address.url,
					usernameState.text.toString(),
					passwordState.text.toString()
				)

				repository.syncEverything { progress, message ->
					loginState.value = LoginUiState.Syncing(progress, message)
				}.onSuccess {
					loginState.value = LoginUiState.Success
				}.onFailure { e ->
					loginState.value = failure(e as? Exception ?: Exception(e), address.host)
				}

			} catch (e: kotlin.coroutines.cancellation.CancellationException) {
				throw e
			} catch (e: Exception) {
				loginState.value = failure(e, address.host)
			}
		}

		return true
	}

	/**
	 * Show [e] on the login page as the login's error (B-302): the Log in tap's own catch sends
	 * what escapes it here, so nothing a login throws reaches the crash screen.
	 */
	fun reportLoginFailure(e: Exception) {
		val host = (normalizeInstanceUrl(instanceState.text.toString()) as? InstanceUrlResult.Ok)?.host
		loginState.value = failure(e, host)
	}

	private fun failure(e: Exception, host: String?): LoginUiState.Error {
		val kind = loginFailureKind(e)
		val blocked = kind == LoginFailureKind.UNREACHABLE &&
			localNetworkBlocked(localNetwork.sdk, localNetwork.granted(), host)
		Logger.w("LoginManager", "login failed: $kind${if (blocked) " (local network not granted)" else ""}")
		return LoginUiState.Error(e, kind, host, blocked)
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
