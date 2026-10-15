package paige.navic.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import navic.composeapp.generated.resources.Res
import navic.composeapp.generated.resources.action_navi_connect_save
import navic.composeapp.generated.resources.action_navi_connect_save_audiomuse
import navic.composeapp.generated.resources.info_navi_connect_audiomuse
import navic.composeapp.generated.resources.info_navi_connect_connected
import navic.composeapp.generated.resources.info_navi_connect_not_connected
import navic.composeapp.generated.resources.info_navi_connect_where_matches
import navic.composeapp.generated.resources.option_navi_connect_audiomuse_token
import navic.composeapp.generated.resources.option_navi_connect_audiomuse_url
import navic.composeapp.generated.resources.option_navi_connect_device_name
import navic.composeapp.generated.resources.option_navi_connect_enable
import navic.composeapp.generated.resources.option_navi_connect_token
import navic.composeapp.generated.resources.option_navi_connect_url
import navic.composeapp.generated.resources.title_navi_connect
import navic.composeapp.generated.resources.title_navi_connect_audiomuse
import navic.composeapp.generated.resources.title_navi_connect_hub
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import paige.navic.domain.manager.HubManager
import paige.navic.domain.manager.LbBotManager
import paige.navic.domain.manager.PreferenceManager
import paige.navic.ui.components.common.SegmentedListItemDefaults
import paige.navic.ui.components.layouts.NestedTopBar
import paige.navic.ui.screens.settings.components.SettingsGroup
import paige.navic.ui.screens.settings.components.SettingsGroupDefaults
import paige.navic.ui.screens.settings.components.SettingsToggleItem

/**
 * navi-connect settings: the hub connection (enable, address, token, this device's name) and
 * AudioMuse's direct address, in upstream's settings parts (`SettingsGroup`, toggle item, the
 * group gaps). The device list that was here with its own Transfer buttons is gone: devices are
 * data, not configuration, and the device picker already lists and transfers to them.
 */
@Composable
fun NaviConnectScreen() {
	val preferenceManager = koinInject<PreferenceManager>()
	val hubManager = koinInject<HubManager>()
	val lbBotManager = koinInject<LbBotManager>()

	val connected by hubManager.connected.collectAsState()
	val connectionError by hubManager.connectionError.collectAsState()

	// Surface a hub-side error (bad token, target offline, …) the moment it arrives, so a wrong
	// token reads as an actual error instead of "just never connects".
	val snackbarHostState = remember { SnackbarHostState() }
	LaunchedEffect(connectionError) {
		connectionError?.let { snackbarHostState.showSnackbar(it) }
	}

	var enabled by rememberSaveable { mutableStateOf(preferenceManager.hubEnabled) }
	var url by rememberSaveable { mutableStateOf(preferenceManager.hubUrl) }
	var token by rememberSaveable { mutableStateOf(preferenceManager.hubToken) }
	var deviceName by rememberSaveable { mutableStateOf(preferenceManager.hubDeviceName) }

	var audioMuseUrl by rememberSaveable { mutableStateOf(preferenceManager.audioMuseUrl) }
	var audioMuseToken by rememberSaveable { mutableStateOf(preferenceManager.audioMuseToken) }

	// Counts the saves. The hub's settings are plain preferences: writing them changes no
	// Compose state, so with the hub out of reach (nothing else on this screen moves then)
	// the "lb-bot data" section below went on showing what it had computed before the save —
	// "Set the hub up above", for a hub that had just been set up. Reading this makes the
	// screen recompose on every save, and the section read again.
	var saves by remember { mutableIntStateOf(0) }

	fun applyAndReconnect() {
		preferenceManager.hubEnabled = enabled
		preferenceManager.hubUrl = url.trim()
		preferenceManager.hubToken = token.trim()
		preferenceManager.hubDeviceName = deviceName.trim().ifBlank { "Navic" }
		hubManager.restart()
		saves++
	}

	fun saveAudioMuse() {
		preferenceManager.audioMuseUrl = audioMuseUrl.trim()
		preferenceManager.audioMuseToken = audioMuseToken.trim()
	}

	Scaffold(
		topBar = { NestedTopBar({ Text(stringResource(Res.string.title_navi_connect)) }) },
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
				SettingsGroup(title = { Text(stringResource(Res.string.title_navi_connect_hub)) }) {
					SettingsToggleItem(
						checked = enabled,
						onCheckedChange = {
							enabled = it
							applyAndReconnect()
						},
						content = { Text(stringResource(Res.string.option_navi_connect_enable)) },
						supportingContent = {
							Text(
								when {
									connected -> stringResource(Res.string.info_navi_connect_connected)
									connectionError != null -> connectionError!!
									else -> stringResource(Res.string.info_navi_connect_not_connected)
								},
								color = if (!connected && connectionError != null) {
									MaterialTheme.colorScheme.error
								} else MaterialTheme.colorScheme.onSurfaceVariant
							)
						},
						shapes = SegmentedListItemDefaults.segmentedShapes(index = 0, count = 1)
					)
					Column(
						modifier = Modifier.padding(top = 8.dp),
						verticalArrangement = Arrangement.spacedBy(8.dp)
					) {
						OutlinedTextField(
							value = url,
							onValueChange = { url = it },
							label = { Text(stringResource(Res.string.option_navi_connect_url)) },
							placeholder = { Text("ws://192.168.1.10:4790") },
							singleLine = true,
							modifier = Modifier.fillMaxWidth()
						)
						// Masked, as upstream's login masks the server password: this is the
						// hub's shared secret.
						OutlinedTextField(
							value = token,
							onValueChange = { token = it },
							label = { Text(stringResource(Res.string.option_navi_connect_token)) },
							singleLine = true,
							visualTransformation = PasswordVisualTransformation(),
							keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
							modifier = Modifier.fillMaxWidth()
						)
						OutlinedTextField(
							value = deviceName,
							onValueChange = { deviceName = it },
							label = { Text(stringResource(Res.string.option_navi_connect_device_name)) },
							singleLine = true,
							modifier = Modifier.fillMaxWidth()
						)
						Button(onClick = { applyAndReconnect() }) {
							Text(stringResource(Res.string.action_navi_connect_save))
						}
					}
				}

				SettingsGroup(title = { Text(stringResource(Res.string.title_navi_connect_audiomuse)) }) {
					Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
						Text(
							stringResource(Res.string.info_navi_connect_audiomuse),
							style = MaterialTheme.typography.bodyMedium,
							color = MaterialTheme.colorScheme.onSurfaceVariant
						)
						OutlinedTextField(
							value = audioMuseUrl,
							onValueChange = { audioMuseUrl = it },
							label = { Text(stringResource(Res.string.option_navi_connect_audiomuse_url)) },
							placeholder = { Text("http://192.168.1.10:8000") },
							singleLine = true,
							modifier = Modifier.fillMaxWidth()
						)
						OutlinedTextField(
							value = audioMuseToken,
							onValueChange = { audioMuseToken = it },
							label = { Text(stringResource(Res.string.option_navi_connect_audiomuse_token)) },
							singleLine = true,
							visualTransformation = PasswordVisualTransformation(),
							keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
							modifier = Modifier.fillMaxWidth()
						)
						Button(onClick = { saveAudioMuse() }) {
							Text(stringResource(Res.string.action_navi_connect_save_audiomuse))
						}
					}
				}

				// What lb-bot holds about the library used to be a section here (I-020); it is
				// the Library Matches page now, and the listening sync is in My Listening.
				Text(
					stringResource(Res.string.info_navi_connect_where_matches),
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					modifier = Modifier.padding(bottom = 24.dp)
				)
			}
		}
	}
}
