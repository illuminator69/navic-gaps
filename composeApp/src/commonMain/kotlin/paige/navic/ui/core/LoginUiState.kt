package paige.navic.ui.core

import org.jetbrains.compose.resources.StringResource
import paige.navic.domain.manager.LoginFailureKind

sealed class LoginUiState {
	object Idle : LoginUiState()
	object Loading : LoginUiState()
	object Success : LoginUiState()
	data class Syncing(val progress: Float, val message: StringResource) : LoginUiState()
	/**
	 * [kind] picks the login page's sentence (B-302), [host] is the server it names, and
	 * [localNetworkBlocked] says Android's local network permission is the likely cause (B-108).
	 */
	data class Error(
		val error: Exception,
		val kind: LoginFailureKind = LoginFailureKind.OTHER,
		val host: String? = null,
		val localNetworkBlocked: Boolean = false
	) : LoginUiState()
}
