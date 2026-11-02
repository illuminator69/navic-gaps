package paige.navic.ui.screens.settings

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.dropUnlessResumed
import com.russhwolf.settings.Settings
import navic.composeapp.generated.resources.Res
import navic.composeapp.generated.resources.action_cancel
import navic.composeapp.generated.resources.action_ok
import navic.composeapp.generated.resources.action_open_settings
import navic.composeapp.generated.resources.info_server_advanced
import navic.composeapp.generated.resources.info_server_footer
import navic.composeapp.generated.resources.info_server_not_set
import navic.composeapp.generated.resources.info_server_set
import navic.composeapp.generated.resources.info_signed_in_as
import navic.composeapp.generated.resources.option_audiomuse_address
import navic.composeapp.generated.resources.option_audiomuse_token
import navic.composeapp.generated.resources.option_custom_headers
import navic.composeapp.generated.resources.option_server_address
import navic.composeapp.generated.resources.option_server_connect
import navic.composeapp.generated.resources.option_server_device_name
import navic.composeapp.generated.resources.option_server_sign_in_as
import navic.composeapp.generated.resources.option_server_sign_out
import navic.composeapp.generated.resources.option_server_token_old
import navic.composeapp.generated.resources.title_navidrome
import navic.composeapp.generated.resources.title_server
import navic.composeapp.generated.resources.title_server_advanced
import navic.composeapp.generated.resources.title_worbus_server
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import paige.navic.di.LocalNavStack
import paige.navic.di.LocalPlatformContext
import paige.navic.domain.manager.HubAuth
import paige.navic.domain.manager.HubManager
import paige.navic.domain.manager.PermissionManager
import paige.navic.domain.manager.PreferenceManager
import paige.navic.domain.manager.AdvancedServerRow
import paige.navic.domain.manager.ServerStatus
import paige.navic.domain.manager.WorbusServerRow
import paige.navic.domain.manager.advancedServerRows
import paige.navic.domain.manager.worbusServerRows
import paige.navic.domain.manager.serverStatus
import paige.navic.icons.Icons
import paige.navic.icons.outlined.KeyboardArrowDown
import paige.navic.ui.components.common.SegmentedListButton
import paige.navic.ui.components.common.SegmentedListButtonDefaults
import paige.navic.ui.components.common.SegmentedListItem
import paige.navic.ui.components.common.SegmentedListItemDefaults
import paige.navic.ui.components.dialogs.FormDialog
import paige.navic.ui.components.layouts.NestedTopBar
import paige.navic.ui.components.layouts.NestedTopBarDefaults
import paige.navic.ui.navigation.Screen
import paige.navic.ui.screens.settings.components.SettingsGroup
import paige.navic.ui.screens.settings.components.SettingsGroupDefaults
import paige.navic.ui.screens.settings.components.SettingsNavItem
import paige.navic.ui.screens.settings.components.SettingsToggleItem
import paige.navic.ui.util.Fold

/** What a Server page dialog edits. Saved by name, so a rotation keeps the dialog open. */
private enum class ServerField { Address, Token, DeviceName, AudioMuseAddress, AudioMuseToken }

/**
 * Server (Worbus settings N3): the connection to the music server and to the Worbus Server, in the
 * host's settings parts. The Worbus Server rows are values that open a dialog; each dialog saves and
 * reconnects on OK. Reached by [Screen.Settings.NaviConnect], whose route keeps its old name so a
 * saved back stack, and the link from Library Matches, still lead here.
 *
 * The device list that used to be here is gone: devices are data, not configuration, and the device
 * picker already lists and transfers to them.
 */
@Composable
fun ServerScreen() {
	val preferenceManager = koinInject<PreferenceManager>()
	val hubManager = koinInject<HubManager>()
	val hubAuth = koinInject<HubAuth>()
	val settings = koinInject<Settings>()
	val permissionManager = koinInject<PermissionManager>()
	val platformContext = LocalPlatformContext.current
	val hideBack = platformContext.sizeClass.widthSizeClass >= WindowWidthSizeClass.Medium
	val backStack = LocalNavStack.current

	val connected by hubManager.connected.collectAsState()
	val connectionError by hubManager.connectionError.collectAsState()
	val authRefused by hubManager.authRefused.collectAsState()
	val unreachable by hubManager.unreachable.collectAsState()
	// Worbus P2: who this device is signed in as, and where the last sign-in stands.
	val user by hubManager.user.collectAsState()
	val authState by hubManager.auth.collectAsState()
	// B-108: the status is then "Android is blocking Navic from your local network.", and the
	// page offers the app's settings page, where the permission is.
	val localNetworkBlocked by hubManager.hubLocalNetworkBlocked.collectAsState()
	// Back from Open settings with the permission granted: dial now, not at the end of the backoff.
	LifecycleResumeEffect(localNetworkBlocked) {
		hubManager.retryIfLocalNetworkGranted()
		onPauseOrDispose { }
	}

	var editing by rememberSaveable { mutableStateOf<ServerField?>(null) }
	var advancedOpen by rememberSaveable { mutableStateOf(false) }
	// The tokens are never shown, only whether they are set. Read again from the secrets store on
	// every save (plain `remember`, B-279: a saveable value would go into the saved-state Bundle).
	// Whether a device secret is held is read off the store too: the sign-in and sign-out finish on
	// the manager's scope, and both move [authState], which recomposes this read.
	var hubTokenSet by remember { mutableStateOf(hubAuth.hasLegacyToken) }
	var audioMuseTokenSet by remember { mutableStateOf(preferenceManager.audioMuseToken.isNotBlank()) }
	val signedIn = authState.let { hubAuth.signedIn }
	val oldServerSeen = authState.let { hubAuth.oldServerSeen }
	// The account a one-tap sign-in uses: the one this app is signed in to Navidrome with.
	val accountName = remember { hubAuth.accountName() }

	val status = serverStatus(
		enabled = preferenceManager.hubEnabled,
		hasAddress = preferenceManager.hubUrl.isNotBlank(),
		hasCredential = signedIn || hubTokenSet,
		connected = connected,
		user = user,
		auth = authState,
		authRefused = authRefused,
		localNetworkBlocked = localNetworkBlocked,
		unreachable = unreachable
	)
	val serverRows = worbusServerRows(signedIn, accountName)
	val advancedRows = advancedServerRows(oldServerSeen)

	// A hub-side error (a transfer refused, a device not answering) the moment it arrives. The
	// token and the local network have their own sentence in the status line already.
	val snackbarHostState = remember { SnackbarHostState() }
	LaunchedEffect(connectionError, status) {
		if (!status.isProblem && status != ServerStatus.OldServer) {
			connectionError?.let { snackbarHostState.showSnackbar(it) }
		}
	}

	Scaffold(
		topBar = {
			NestedTopBar(
				title = { Text(stringResource(Res.string.title_server)) },
				navigationAction = {
					if (!hideBack) {
						NestedTopBarDefaults.NavigationAction()
					}
				}
			)
		},
		snackbarHost = { SnackbarHost(snackbarHostState) }
	) { innerPadding ->
		CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
			Column(
				modifier = Modifier
					.padding(innerPadding)
					.verticalScroll(rememberScrollState())
					.padding(horizontal = 16.dp),
				verticalArrangement = Arrangement.spacedBy(SettingsGroupDefaults.GapBetweenGroups)
			) {
				SettingsGroup(title = { Text(stringResource(Res.string.title_navidrome)) }) {
					// Who is signed in and where: read, not a claim about the connection.
					SegmentedListItem(
						onClick = {},
						shapes = SegmentedListItemDefaults.segmentedShapes(index = 0, count = 2),
						content = { Text(stringResource(Res.string.info_signed_in_as, settings.getString("username", ""))) },
						supportingContent = { Text(settings.getString("instanceUrl", "")) }
					)
					SettingsNavItem(
						onClick = dropUnlessResumed {
							backStack.lastOrNull()?.let {
								if (it is Screen.Settings.NaviConnect) backStack.add(Screen.Settings.CustomHeaders)
							}
						},
						shapes = SegmentedListItemDefaults.segmentedShapes(index = 1, count = 2),
						content = { Text(stringResource(Res.string.option_custom_headers)) }
					)
				}

				SettingsGroup(title = { Text(stringResource(Res.string.title_worbus_server)) }) {
					val blocked = status == ServerStatus.LocalNetworkBlocked
					val rows = 1 + (if (blocked) 1 else 0) + serverRows.size
					SettingsToggleItem(
						checked = preferenceManager.hubEnabled,
						onCheckedChange = {
							preferenceManager.hubEnabled = it
							hubManager.restart()
						},
						content = { Text(stringResource(Res.string.option_server_connect)) },
						supportingContent = {
							Text(
								stringResource(status.line, *status.args.toTypedArray()),
								color = if (status.isProblem) MaterialTheme.colorScheme.error
								else MaterialTheme.colorScheme.onSurfaceVariant
							)
						},
						shapes = SegmentedListItemDefaults.segmentedShapes(index = 0, count = rows)
					)
					if (blocked) {
						SegmentedListItem(
							onClick = { permissionManager.openPermissionsSettings() },
							shapes = SegmentedListItemDefaults.segmentedShapes(index = 1, count = rows),
							content = { Text(stringResource(Res.string.action_open_settings)) }
						)
					}
					val first = rows - serverRows.size
					serverRows.forEachIndexed { i, row ->
						when (row) {
							WorbusServerRow.Address -> ValueRow(
								label = Res.string.option_server_address,
								value = preferenceManager.hubUrl.ifBlank { null },
								index = first + i,
								count = rows,
								onClick = { editing = ServerField.Address }
							)
							// One tap, with the app's own Navidrome account (design §6.5). Not while
							// one is on its way; the status line says how it went.
							WorbusServerRow.SignIn -> SettingsNavItem(
								onClick = { hubManager.signInAndConnect() },
								enabled = preferenceManager.hubUrl.isNotBlank() && status != ServerStatus.SigningIn,
								shapes = SegmentedListItemDefaults.segmentedShapes(index = first + i, count = rows),
								content = { Text(stringResource(Res.string.option_server_sign_in_as, accountName.orEmpty())) }
							)
							WorbusServerRow.SignOut -> SettingsNavItem(
								onClick = { hubManager.signOutOfServer() },
								shapes = SegmentedListItemDefaults.segmentedShapes(index = first + i, count = rows),
								content = { Text(stringResource(Res.string.option_server_sign_out)) }
							)
							WorbusServerRow.DeviceName -> ValueRow(
								label = Res.string.option_server_device_name,
								value = preferenceManager.hubDeviceName.ifBlank { null },
								index = first + i,
								count = rows,
								onClick = { editing = ServerField.DeviceName }
							)
						}
					}
				}

				// AudioMuse's direct address: the Worbus Server holds it, so most people leave these
				// alone. Closed until asked for.
				SettingsGroup {
					val turn by animateFloatAsState(if (advancedOpen) 180f else 0f)
					SegmentedListItem(
						onClick = { advancedOpen = !advancedOpen },
						shapes = SegmentedListItemDefaults.segmentedShapes(index = 0, count = if (advancedOpen) advancedRows.size + 1 else 1),
						content = { Text(stringResource(Res.string.title_server_advanced)) },
						supportingContent = { Text(stringResource(Res.string.info_server_advanced)) },
						trailingContent = { Icon(Icons.Outlined.KeyboardArrowDown, null, Modifier.rotate(turn)) }
					)
					Fold(visible = advancedOpen) {
						Column(verticalArrangement = Arrangement.spacedBy(SegmentedListItemDefaults.SegmentedGap)) {
							val count = advancedRows.size + 1
							advancedRows.forEachIndexed { i, row ->
								when (row) {
									AdvancedServerRow.AudioMuseAddress -> ValueRow(
										label = Res.string.option_audiomuse_address,
										value = preferenceManager.audioMuseUrl.ifBlank { null },
										index = i + 1,
										count = count,
										onClick = { editing = ServerField.AudioMuseAddress }
									)
									AdvancedServerRow.AudioMuseToken -> ValueRow(
										label = Res.string.option_audiomuse_token,
										value = if (audioMuseTokenSet) stringResource(Res.string.info_server_set) else null,
										index = i + 1,
										count = count,
										onClick = { editing = ServerField.AudioMuseToken }
									)
									// Worbus P2: only after this address answered as an older server (R6).
									AdvancedServerRow.ServerToken -> ValueRow(
										label = Res.string.option_server_token_old,
										value = if (hubTokenSet) stringResource(Res.string.info_server_set) else null,
										index = i + 1,
										count = count,
										onClick = { editing = ServerField.Token }
									)
								}
							}
						}
					}
				}

				// What lb-bot holds about the library used to be a section here (I-020); it is
				// the Library Matches page now, and the listening sync is in Stats.
				Text(
					stringResource(Res.string.info_server_footer),
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					modifier = Modifier.padding(bottom = 24.dp)
				)
			}
		}
	}

	editing?.let { field ->
		val (title, masked, initial, placeholder) = when (field) {
			ServerField.Address -> FieldSpec(Res.string.option_server_address, false, preferenceManager.hubUrl, "ws://192.168.1.10:4790")
			ServerField.Token -> FieldSpec(Res.string.option_server_token_old, true, hubAuth.legacyTokenForEditing(), "")
			ServerField.DeviceName -> FieldSpec(Res.string.option_server_device_name, false, preferenceManager.hubDeviceName, "")
			ServerField.AudioMuseAddress -> FieldSpec(Res.string.option_audiomuse_address, false, preferenceManager.audioMuseUrl, "http://192.168.1.10:8000")
			ServerField.AudioMuseToken -> FieldSpec(Res.string.option_audiomuse_token, true, preferenceManager.audioMuseToken, "")
		}
		TextEntryDialog(
			title = stringResource(title),
			initial = initial,
			placeholder = placeholder,
			masked = masked,
			onDismissRequest = { editing = null },
			onSave = { text ->
				when (field) {
					ServerField.Address -> {
						preferenceManager.hubUrl = text.trim()
						hubManager.restart()
					}
					ServerField.Token -> {
						hubAuth.setLegacyToken(text)
						hubTokenSet = hubAuth.hasLegacyToken
						hubManager.restart()
					}
					ServerField.DeviceName -> {
						preferenceManager.hubDeviceName = text.trim().ifBlank { "Navic" }
						hubManager.restart()
					}
					ServerField.AudioMuseAddress -> preferenceManager.audioMuseUrl = text.trim()
					ServerField.AudioMuseToken -> {
						preferenceManager.audioMuseToken = text.trim()
						audioMuseTokenSet = preferenceManager.audioMuseToken.isNotBlank()
					}
				}
				editing = null
			}
		)
	}
}

private data class FieldSpec(
	val title: org.jetbrains.compose.resources.StringResource,
	val masked: Boolean,
	val initial: String,
	val placeholder: String
)

/** A setting whose value is read off the row; tapping opens its dialog. */
@Composable
private fun ValueRow(
	label: org.jetbrains.compose.resources.StringResource,
	value: String?,
	index: Int,
	count: Int,
	onClick: () -> Unit
) {
	SettingsNavItem(
		onClick = onClick,
		shapes = SegmentedListItemDefaults.segmentedShapes(index = index, count = count),
		content = { Text(stringResource(label)) },
		supportingContent = { Text(value ?: stringResource(Res.string.info_server_not_set)) }
	)
}

/**
 * One line of text in a dialog. A masked field is a password (the token is a shared secret) and its
 * text stays out of the saved state: a saveable value would go into the Bundle, which outlives the
 * screen (B-279).
 */
@Composable
private fun TextEntryDialog(
	title: String,
	initial: String,
	placeholder: String,
	masked: Boolean,
	onDismissRequest: () -> Unit,
	onSave: (String) -> Unit
) {
	var text by if (masked) remember { mutableStateOf(initial) } else rememberSaveable { mutableStateOf(initial) }
	FormDialog(
		onDismissRequest = onDismissRequest,
		title = { Text(title) },
		buttons = {
			SegmentedListButton(
				modifier = Modifier.fillMaxWidth(),
				onClick = { onSave(text) },
				colors = SegmentedListButtonDefaults.primaryColors(),
				shapes = SegmentedListButtonDefaults.shapes(index = 0, count = 2)
			) {
				Text(stringResource(Res.string.action_ok))
			}
			SegmentedListButton(
				modifier = Modifier.fillMaxWidth(),
				onClick = onDismissRequest,
				shapes = SegmentedListButtonDefaults.shapes(index = 1, count = 2)
			) {
				Text(stringResource(Res.string.action_cancel))
			}
		},
		content = {
			TextField(
				value = text,
				onValueChange = { text = it },
				placeholder = if (placeholder.isNotEmpty()) ({ Text(placeholder) }) else null,
				singleLine = true,
				visualTransformation = if (masked) PasswordVisualTransformation() else VisualTransformation.None,
				keyboardOptions = KeyboardOptions(keyboardType = if (masked) KeyboardType.Password else KeyboardType.Uri)
			)
		}
	)
}
