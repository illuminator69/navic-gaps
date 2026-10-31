package paige.navic.ui.screens.login.pages

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.dropUnlessResumed
import com.kyant.capsule.ContinuousCapsule
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import navic.composeapp.generated.resources.Res
import navic.composeapp.generated.resources.action_dismiss
import navic.composeapp.generated.resources.action_log_in
import navic.composeapp.generated.resources.info_login_description
import navic.composeapp.generated.resources.info_login_sign_in_again
import navic.composeapp.generated.resources.notice_login_notifications_off
import navic.composeapp.generated.resources.option_custom_headers
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import paige.navic.di.LocalNavStack
import paige.navic.domain.manager.LoginManager
import paige.navic.domain.manager.PermissionManager
import paige.navic.icons.Icons
import paige.navic.icons.outlined.Close
import paige.navic.ui.core.LoginUiState
import paige.navic.ui.navigation.Screen
import paige.navic.ui.theme.defaultFont

@Composable
fun LoginScreenContent(innerPadding: PaddingValues) {
	val viewModel = koinInject<LoginManager>()
	val loginState by viewModel.loginState.collectAsStateWithLifecycle()

	val instanceState = viewModel.instanceState
	val usernameState = viewModel.usernameState
	val passwordState = viewModel.passwordState

	val isBusy = loginState is LoginUiState.Loading || loginState is LoginUiState.Syncing

	val haptics = LocalHapticFeedback.current
	val backStack = LocalNavStack.current
	val focusManager = LocalFocusManager.current

	val instanceFocusRequester = remember { FocusRequester() }
	val usernameFocusRequester = remember { FocusRequester() }
	val passwordFocusRequester = remember { FocusRequester() }

	val permissionManager = koinInject<PermissionManager>()
	val loginScope = rememberCoroutineScope()
	// B-301: declining notifications no longer stops the login; it leaves one line on the page.
	var notificationsOff by rememberSaveable { mutableStateOf(false) }

	// B-296, B-261, B-283: the fields are checked FIRST (the address by normalizeInstanceUrl, which
	// reads the text and resolves nothing), then the permissions are asked for, then the login
	// runs. Nothing a login throws may leave this tap: what escapes is the login's error.
	val login: () -> Unit = {
		loginScope.launch {
			try {
				if (!viewModel.validateStuff()) {
					haptics.performHapticFeedback(HapticFeedbackType.Reject)
					when {
						viewModel.instanceError -> instanceFocusRequester.requestFocus()
						viewModel.usernameError -> usernameFocusRequester.requestFocus()
						viewModel.passwordError -> passwordFocusRequester.requestFocus()
					}
					return@launch
				}
				withContext(Dispatchers.IO) {
					// B-108: a server on this network asks for the permission Android 17 wants for
					// it. A refusal does not stop the login: a routed address may still answer, and
					// if it does not, the login's error says that Android is blocking it.
					if (viewModel.isLocalNetworkInstance()) {
						permissionManager.requestLocalNetworkPermission()
					}
					notificationsOff = !permissionManager.requestNotificationsPermission()
					viewModel.login()
				}
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				viewModel.reportLoginFailure(e)
			}
		}
	}

	LaunchedEffect(loginState) {
		if (loginState is LoginUiState.Success) {
			backStack.clear()
			backStack.add(Screen.Home)
		}
	}

	Box {
		LoginScreenProgress(
			modifier = Modifier
				.align(Alignment.TopCenter)
				.padding(top = innerPadding.calculateTopPadding()),
			isBusy = isBusy,
			loginUiState = loginState
		)

		Column(
			modifier = Modifier
				.fillMaxSize()
				.padding(innerPadding)
				.consumeWindowInsets(innerPadding)
				.imePadding(),
			horizontalAlignment = Alignment.CenterHorizontally,
			verticalArrangement = Arrangement.spacedBy(6.dp)
		) {
			Column(
				modifier = Modifier
					.weight(1f)
					.widthIn(max = 600.dp)
					.verticalScroll(rememberScrollState())
			) {
				Spacer(Modifier.weight(1f))

				Text(
					text = stringResource(Res.string.action_log_in),
					style = MaterialTheme.typography.headlineMedium,
					fontFamily = defaultFont(round = 100f),
					modifier = Modifier.padding(horizontal = 16.dp)
				)
				Text(
					text = stringResource(Res.string.info_login_description),
					modifier = Modifier.padding(horizontal = 16.dp)
				)
				// B-279: a session whose password could not be read here (a restore, a transfer, a lost key).
				if (viewModel.signInAgain) {
					Spacer(Modifier.height(8.dp))
					Text(
						text = stringResource(Res.string.info_login_sign_in_again),
						color = MaterialTheme.colorScheme.error,
						modifier = Modifier.padding(horizontal = 16.dp)
					)
				}

				Spacer(Modifier.height(8.dp))

				LoginScreenError(
					loginUiState = loginState,
					onOpenSettings = { permissionManager.openPermissionsSettings() }
				)

				// B-301: said once, dismissible; the login went ahead without the permission.
				if (notificationsOff) {
					Row(
						modifier = Modifier
							.fillMaxWidth()
							.padding(start = 16.dp, end = 4.dp, bottom = 8.dp),
						verticalAlignment = Alignment.CenterVertically
					) {
						Text(
							text = stringResource(Res.string.notice_login_notifications_off),
							style = MaterialTheme.typography.bodyMedium,
							color = MaterialTheme.colorScheme.onSurfaceVariant,
							modifier = Modifier.weight(1f)
						)
						IconButton(onClick = { notificationsOff = false }) {
							Icon(Icons.Outlined.Close, contentDescription = stringResource(Res.string.action_dismiss))
						}
					}
				}

				LoginScreenFields(
					isBusy = isBusy,
					instanceState = instanceState,
					instanceError = viewModel.instanceError,
					instanceProblem = viewModel.instanceProblem,
					instanceFocusRequester = instanceFocusRequester,
					onInstanceFocusChanged = { viewModel.validateInstance() },
					usernameState = usernameState,
					usernameError = viewModel.usernameError,
					usernameFocusRequester = usernameFocusRequester,
					onUsernameFocusChanged = { viewModel.validateUsername() },
					passwordState = passwordState,
					passwordError = viewModel.passwordError,
					passwordFocusRequester = passwordFocusRequester,
					onPasswordFocusChanged = { viewModel.validatePassword() },
					onLogin = login
				)

				Spacer(Modifier.height(12.dp))

				Text(
					text = stringResource(Res.string.option_custom_headers),
					color = MaterialTheme.colorScheme.primary,
					textDecoration = TextDecoration.Underline,
					modifier = Modifier
						.padding(horizontal = 16.dp)
						.clickable(onClick = dropUnlessResumed {
							backStack.lastOrNull()?.let {
								if (it is Screen.Login) {
									backStack.add(Screen.Settings.CustomHeaders)
									focusManager.clearFocus(true)
								}
							}
						})
				)

				Spacer(Modifier.weight(2.25f))
			}

			Column(
				modifier = Modifier
					.widthIn(max = 600.dp)
					.padding(horizontal = 16.dp)
					.padding(bottom = 8.dp)
			) {
				LoginScreenSyncStatus(loginUiState = loginState)
				Button(
					modifier = Modifier.fillMaxWidth(),
					onClick = {
						login()
					},
					enabled = !isBusy,
					shape = ContinuousCapsule
				) {
					Text(
						text = stringResource(Res.string.action_log_in),
						fontFamily = defaultFont(100)
					)
				}
			}
		}
	}

}
