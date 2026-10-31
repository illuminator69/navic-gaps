package paige.navic.ui.screens.login.pages

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import navic.composeapp.generated.resources.Res
import navic.composeapp.generated.resources.action_open_settings
import navic.composeapp.generated.resources.login_error_not_https
import navic.composeapp.generated.resources.login_error_not_navidrome
import navic.composeapp.generated.resources.login_error_unreachable
import navic.composeapp.generated.resources.login_error_wrong_credentials
import navic.composeapp.generated.resources.notice_local_network_blocked
import org.jetbrains.compose.resources.stringResource
import paige.navic.domain.manager.LoginFailureKind
import paige.navic.domain.manager.loginFailureClassName
import paige.navic.ui.components.common.ErrorBox
import paige.navic.ui.core.LoginUiState
import paige.navic.ui.core.UiState

@Composable
fun LoginScreenError(
	loginUiState: LoginUiState,
	onOpenSettings: () -> Unit
) {
	val spatialSpec = MaterialTheme.motionScheme.slowSpatialSpec<Float>()
	val effectSpec = MaterialTheme.motionScheme.slowEffectsSpec<Float>()
	AnimatedContent(
		(loginUiState as? LoginUiState.Error),
		modifier = Modifier
			.fillMaxWidth()
			.padding(horizontal = 16.dp),
		transitionSpec = {
			(fadeIn(
				animationSpec = effectSpec
			) + scaleIn(
				initialScale = 0.8f,
				animationSpec = spatialSpec
			)) togetherWith (fadeOut(
				animationSpec = effectSpec
			) + scaleOut(
				animationSpec = spatialSpec
			))
		}
	) {
		if (it != null) {
			ErrorBox(
				UiState.Error(it.error, null),
				padding = PaddingValues(0.dp),
				modifier = Modifier.fillMaxWidth(),
				bottomPadding = 8.dp,
				message = loginErrorMessage(it),
				detail = if (it.kind == LoginFailureKind.OTHER && !it.localNetworkBlocked) loginFailureClassName(it.error) else null,
				actionLabel = if (it.localNetworkBlocked) stringResource(Res.string.action_open_settings) else null,
				onAction = if (it.localNetworkBlocked) onOpenSettings else null
			)
		}
	}
}

/** The one sentence for a login failure (B-302), or null for the generic "Something went wrong.". */
@Composable
private fun loginErrorMessage(error: LoginUiState.Error): String? = when {
	// B-108: only a failure where nothing answered, on a private host, without the permission.
	error.localNetworkBlocked -> stringResource(Res.string.notice_local_network_blocked)
	else -> when (error.kind) {
		LoginFailureKind.WRONG_CREDENTIALS -> stringResource(Res.string.login_error_wrong_credentials)
		LoginFailureKind.UNREACHABLE -> stringResource(Res.string.login_error_unreachable, error.host ?: "")
		LoginFailureKind.TLS_TO_PLAIN_HTTP -> stringResource(Res.string.login_error_not_https)
		LoginFailureKind.NOT_SUBSONIC -> stringResource(Res.string.login_error_not_navidrome)
		LoginFailureKind.OTHER -> null
	}
}
