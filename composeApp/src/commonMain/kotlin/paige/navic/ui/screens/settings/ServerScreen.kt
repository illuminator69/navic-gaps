package paige.navic.ui.screens.settings

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import navic.composeapp.generated.resources.action_close
import navic.composeapp.generated.resources.action_find_home_address
import navic.composeapp.generated.resources.action_look_on_network
import navic.composeapp.generated.resources.action_type_address
import navic.composeapp.generated.resources.action_use_this_address
import navic.composeapp.generated.resources.action_add_address
import navic.composeapp.generated.resources.action_remove_address
import navic.composeapp.generated.resources.address_add_hint
import navic.composeapp.generated.resources.address_also
import navic.composeapp.generated.resources.address_away_from_home
import navic.composeapp.generated.resources.address_not_valid
import navic.composeapp.generated.resources.address_source_found
import navic.composeapp.generated.resources.address_source_typed
import navic.composeapp.generated.resources.finder_default_name
import navic.composeapp.generated.resources.finder_found_row
import navic.composeapp.generated.resources.finder_home_found
import navic.composeapp.generated.resources.finder_looking
import navic.composeapp.generated.resources.finder_needs_permission
import navic.composeapp.generated.resources.finder_not_encrypted
import navic.composeapp.generated.resources.finder_not_on_wifi
import navic.composeapp.generated.resources.finder_nothing_beside
import navic.composeapp.generated.resources.finder_nothing_on_network
import navic.composeapp.generated.resources.finder_older_at
import navic.composeapp.generated.resources.option_use_away_from_home
import navic.composeapp.generated.resources.server_route_home
import navic.composeapp.generated.resources.server_route_not_encrypted
import navic.composeapp.generated.resources.server_route_tailscale
import navic.composeapp.generated.resources.server_route_via
import navic.composeapp.generated.resources.title_server_addresses
import paige.navic.domain.manager.FinderState
import paige.navic.domain.manager.Found
import paige.navic.domain.manager.HubAddress
import paige.navic.domain.manager.HubFinder
import paige.navic.domain.manager.HubServerRecord
import paige.navic.domain.manager.NotSetRow
import paige.navic.domain.manager.NotSetSentence
import paige.navic.domain.manager.RouteNote
import paige.navic.domain.manager.ServerRoute
import paige.navic.domain.manager.addTypedAddress
import paige.navic.domain.manager.hubAddressHost
import paige.navic.domain.manager.notSetRows
import paige.navic.domain.manager.otherAddresses
import paige.navic.domain.manager.removeServerAddress
import paige.navic.domain.manager.routeNote
import paige.navic.domain.manager.setUseAwayFromHome
import paige.navic.domain.manager.shownAddress
import paige.navic.domain.manager.signInToFound
import paige.navic.domain.manager.typeNewServer
import paige.navic.ui.util.rememberFoldMotion
import androidx.compose.animation.togetherWith
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemShapes
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
import navic.composeapp.generated.resources.info_library_refused
import navic.composeapp.generated.resources.info_library_too_old
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
import paige.navic.domain.manager.LbAccess
import paige.navic.domain.manager.LbBotManager
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
private enum class ServerField { Address, Token, DeviceName, AudioMuseAddress, AudioMuseToken, Addresses }

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
	val lbBot = koinInject<LbBotManager>()
	// Worbus P2: lb-bot refused the server (401 signin_required) or is too old for signed-in users.
	val lbAccess by lbBot.lbAccess.collectAsState()
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
	val linkedByToken by hubManager.linkedByToken.collectAsState()
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
		// The one "has a hub credential" accessor (R23: a refused account's token is not one).
		hasCredential = hubAuth.hasCredential,
		connected = connected,
		user = user,
		auth = authState,
		authRefused = authRefused,
		localNetworkBlocked = localNetworkBlocked,
		unreachable = unreachable,
		linkedByToken = linkedByToken
	)
	// Worbus P3: the address the live socket is on, what looking found, and the server record.
	val finder = koinInject<HubFinder>()
	val finderState by finder.state.collectAsState()
	val active by hubManager.activeAddress.collectAsState()
	// Bumped by every edit made here: preferences have no Flow, so the record is read again on it.
	var edits by remember { mutableIntStateOf(0) }
	val record = remember(edits, authState, connected, active, finderState) { hubManager.serverRecord() }
	val notSet = remember(edits) { preferenceManager.hubUrl.isBlank() }
	// Design §6.4: the Server screen looks beside Navidrome each time it opens while no server is set.
	LaunchedEffect(Unit) {
		if (preferenceManager.hubUrl.isBlank()) finder.startAutoLook()
	}
	val findHome = !notSet && record.serverId != null && !record.hasPrivateAddress
	val serverRows = worbusServerRows(signedIn, accountName, findHome).let { rows ->
		// Not set: the Address and Sign in rows give way to what looking found (design §6.6).
		if (notSet) rows.filter { it == WorbusServerRow.DeviceName } else rows
	}
	val lookRows = if (notSet) notSetRows(finderState) else emptyList()
	val route = if (status is ServerStatus.SignedIn || status == ServerStatus.Connected) routeNote(active) else null
	val shownActive = active ?: record.connectOrder().firstOrNull()?.url ?: preferenceManager.hubUrl.ifBlank { null }
	val signInToFoundServer: (Found, String) -> Unit = { found, tapped ->
		// A tap on Sign in during a look stops it (design §7's RACES).
		finder.stopLooking()
		signInToFound(preferenceManager, hubManager.serverRecordStore, found, tapped) { hubManager.signInAndConnect() }
		edits++
	}
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
					val rows = 1 + (if (blocked) 1 else 0) + lookRows.size + serverRows.size
					SettingsToggleItem(
						checked = preferenceManager.hubEnabled,
						onCheckedChange = {
							preferenceManager.hubEnabled = it
							hubManager.restart()
						},
						content = { Text(stringResource(Res.string.option_server_connect)) },
						supportingContent = {
							// R34: the line changes with the app's own motion (a cross-fade, its height
							// eased), one node for the screen's life.
							val lineMotion = rememberFoldMotion()
							AnimatedContent(
								targetState = status,
								transitionSpec = {
									(fadeIn(lineMotion.effects()) togetherWith fadeOut(lineMotion.effects()))
										.using(SizeTransform(clip = false) { _, _ -> lineMotion.spatial() })
								},
								label = "server status"
							) { shown ->
								// Worbus P3: the route the socket takes, after the signed-in sentence.
								val routeWords = route?.takeIf { shown is ServerStatus.SignedIn || shown == ServerStatus.Connected }
									?.let { routeLine(it) }.orEmpty()
								Text(
									stringResource(shown.line, *shown.args.toTypedArray()) + routeWords,
									color = if (shown.isProblem) MaterialTheme.colorScheme.error
									else MaterialTheme.colorScheme.onSurfaceVariant
								)
							}
						},
						shapes = SegmentedListItemDefaults.segmentedShapes(index = 0, count = rows)
					)
					Fold(visible = blocked) {
						SegmentedListItem(
							onClick = { permissionManager.openPermissionsSettings() },
							shapes = SegmentedListItemDefaults.segmentedShapes(index = 1, count = rows),
							content = { Text(stringResource(Res.string.action_open_settings)) }
						)
					}
					// Worbus P3: while no server is set, what looking found and the two ways on (design §6.6).
					val notSetFirst = 1 + (if (blocked) 1 else 0)
					Fold(visible = notSet) {
						Column(
							modifier = Modifier.animateContentSize(),
							verticalArrangement = Arrangement.spacedBy(SegmentedListItemDefaults.SegmentedGap)
						) {
							lookRows.forEachIndexed { i, row ->
								key(row) {
									NotSetRowItem(
										row = row,
										accountName = accountName.orEmpty(),
										shapes = SegmentedListItemDefaults.segmentedShapes(index = notSetFirst + i, count = rows),
										onSignIn = signInToFoundServer,
										onLook = { finder.startSweep() },
										onType = { editing = ServerField.Address },
										onOpenSettings = { permissionManager.openPermissionsSettings() }
									)
								}
							}
						}
					}
					val first = rows - serverRows.size
					// R34: every row the group can hold is composed once and folds in and out (the rows under
					// it follow the height). Re-walk NM1: Sign in and Sign out are ONE row, the action slot,
					// whose words cross-fade both ways; the slot itself folds only when neither is offered.
					val action = serverRows.firstOrNull { it == WorbusServerRow.SignIn || it == WorbusServerRow.SignOut }
					// What the slot last offered, so it folds away showing the words it had.
					val lastAction = remember { arrayOf(WorbusServerRow.SignIn) }
					if (action != null) lastAction[0] = action
					WorbusServerRow.entries.filter { it != WorbusServerRow.SignOut }.forEach { row ->
						val slot = if (row == WorbusServerRow.SignIn) action else row
						val shownAt = slot?.let { serverRows.indexOf(it) } ?: -1
						val i = if (shownAt >= 0) shownAt else 0
						Fold(visible = shownAt >= 0) {
							when (row) {
								// Worbus P3: the address the socket is on, the others under it; the list opens.
								WorbusServerRow.Address -> AddressRow(
									shown = shownActive,
									others = otherAddresses(record, shownActive),
									shapes = SegmentedListItemDefaults.segmentedShapes(index = first + i, count = rows),
									onClick = { editing = ServerField.Addresses }
								)
								WorbusServerRow.FindHome -> SegmentedListItem(
									onClick = { record.serverId?.let { finder.startFindHomeAddress(it) } },
									enabled = finderState != FinderState.Looking,
									shapes = SegmentedListItemDefaults.segmentedShapes(index = first + i, count = rows),
									content = { Text(stringResource(Res.string.action_find_home_address)) },
									supportingContent = findHomeLine(finderState)?.let { line -> { Text(stringResource(line)) } }
								)
								// One tap, with the app's own Navidrome account (design §6.5), or out of
								// this server. An action, not a page: no chevron (R34).
								WorbusServerRow.SignIn -> ServerActionRow(
									action = action ?: lastAction[0],
									accountName = accountName.orEmpty(),
									signInEnabled = preferenceManager.hubUrl.isNotBlank() && status != ServerStatus.SigningIn,
									shapes = SegmentedListItemDefaults.segmentedShapes(index = first + i, count = rows),
									onSignIn = { hubManager.signInAndConnect() },
									onSignOut = { hubManager.signOutOfServer() }
								)
								WorbusServerRow.DeviceName -> ValueRow(
									label = Res.string.option_server_device_name,
									value = preferenceManager.hubDeviceName.ifBlank { null },
									index = first + i,
									count = rows,
									onClick = { editing = ServerField.DeviceName }
								)
								// The action slot is drawn at SignIn's place (above).
								WorbusServerRow.SignOut -> Unit
							}
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
							// R34: the token row (only after an older server answered) folds in and out.
							AdvancedServerRow.entries.forEach { row ->
								val shownAt = advancedRows.indexOf(row)
								val i = if (shownAt >= 0) shownAt else 0
								Fold(visible = shownAt >= 0) {
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
				}

				// Worbus P2: the settings line for a library service that will not answer this user.
				// R34: each line folds in and out as lb-bot's answer to this user changes.
				Fold(visible = lbAccess == LbAccess.REFUSED) {
					Text(
						stringResource(Res.string.info_library_refused),
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.error
					)
				}
				Fold(visible = lbAccess == LbAccess.TOO_OLD) {
					Text(
						stringResource(Res.string.info_library_too_old),
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.error
					)
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

	if (editing == ServerField.Addresses) {
		AddressListDialog(
			record = record,
			onRemove = { url ->
				removeServerAddress(preferenceManager, hubManager.serverRecordStore, url)
				// With no address left there is no server set: the socket stops (restart reads the address).
				if (preferenceManager.hubUrl.isBlank()) hubManager.restart()
				edits++
			},
			onAwayFromHome = { on ->
				setUseAwayFromHome(hubManager.serverRecordStore, on)
				edits++
			},
			onAdd = { text ->
				val wasBlank = preferenceManager.hubUrl.isBlank()
				val added = addTypedAddress(preferenceManager, hubManager.serverRecordStore, text)
				if (added != null) {
					if (wasBlank) hubManager.restart()
					edits++
				}
				added != null
			},
			onDismissRequest = { editing = null }
		)
	}

	editing?.takeIf { it != ServerField.Addresses }?.let { field ->
		val (title, masked, initial, placeholder) = when (field) {
			// Worbus P3: "Type an address", normalised and added to the record as typed.
			ServerField.Address -> FieldSpec(Res.string.action_type_address, false, "", "192.168.1.10:4790")
			ServerField.Addresses -> return@let
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
						// Only offered while no server is set: a new server, its record starts over.
						if (typeNewServer(preferenceManager, hubManager.serverRecordStore, text) != null) {
							finder.stopLooking()
							hubManager.restart()
							edits++
						}
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
					ServerField.Addresses -> Unit
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
/**
 * Re-walk NM1: the Worbus Server group's action slot, "Sign in as <user>" or "Sign out of this server".
 * One node for both, so a sign-in that completes and a sign-out cross-fade the words in place, the
 * same way both directions; never one row swapped for another. Not while a sign-in is on its way (the
 * status line says how it went).
 */
@Composable
private fun ServerActionRow(
	action: WorbusServerRow,
	accountName: String,
	signInEnabled: Boolean,
	shapes: ListItemShapes,
	onSignIn: () -> Unit,
	onSignOut: () -> Unit
) {
	val signOut = action == WorbusServerRow.SignOut
	val motion = rememberFoldMotion()
	SegmentedListItem(
		onClick = { if (signOut) onSignOut() else onSignIn() },
		enabled = signOut || signInEnabled,
		shapes = shapes,
		content = {
			AnimatedContent(
				targetState = action,
				transitionSpec = {
					(fadeIn(motion.effects()) togetherWith fadeOut(motion.effects()))
						.using(SizeTransform(clip = false) { _, _ -> motion.spatial() })
				},
				label = "server action"
			) { shown ->
				Text(
					if (shown == WorbusServerRow.SignOut) stringResource(Res.string.option_server_sign_out)
					else stringResource(Res.string.option_server_sign_in_as, accountName)
				)
			}
		}
	)
}

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

/** Worbus P3: " · home network", " · Tailscale", " · via hub.example.com", then " · not encrypted". */
@Composable
private fun routeLine(note: RouteNote): String {
	val route = when (val r = note.route) {
		ServerRoute.Home -> stringResource(Res.string.server_route_home)
		ServerRoute.Tailscale -> stringResource(Res.string.server_route_tailscale)
		is ServerRoute.Via -> stringResource(Res.string.server_route_via, r.host)
	}
	val plain = if (note.notEncrypted) " · " + stringResource(Res.string.server_route_not_encrypted) else ""
	return " · $route$plain"
}

/** What Find home address says under itself while and after it looks; null at rest. */
private fun findHomeLine(state: FinderState): org.jetbrains.compose.resources.StringResource? = when (state) {
	FinderState.Looking -> Res.string.finder_looking
	FinderState.NothingOnNetwork -> Res.string.finder_nothing_on_network
	FinderState.NeedsPermission -> Res.string.finder_needs_permission
	FinderState.NotOnWifi -> Res.string.finder_not_on_wifi
	is FinderState.Results -> Res.string.finder_home_found
	else -> null
}

/**
 * Worbus P3 (design §6.6): the Address row of a set server. The address the socket is on (or would
 * try first), and a second line naming the others: "Away from home: hub.example.com".
 */
@Composable
private fun AddressRow(shown: String?, others: List<HubAddress>, shapes: ListItemShapes, onClick: () -> Unit) {
	SettingsNavItem(
		onClick = onClick,
		shapes = shapes,
		content = { Text(stringResource(Res.string.option_server_address)) },
		supportingContent = {
			Column(Modifier.animateContentSize()) {
				Text(shown?.let { shownAddress(it) } ?: stringResource(Res.string.info_server_not_set))
				others.forEach { other ->
					val host = shownAddress(other.url)
					Text(
						if (other.source == HubAddress.Source.Learned) stringResource(Res.string.address_away_from_home, host)
						else stringResource(Res.string.address_also, host)
					)
				}
			}
		}
	)
}

/** One row of the Worbus Server group while no server is set ([NotSetRow]). A found row says "found", never "connected". */
@Composable
private fun NotSetRowItem(
	row: NotSetRow,
	accountName: String,
	shapes: ListItemShapes,
	onSignIn: (Found, String) -> Unit,
	onLook: () -> Unit,
	onType: () -> Unit,
	onOpenSettings: () -> Unit
) {
	when (row) {
		is NotSetRow.Server -> {
			val address = row.found.addresses.first()
			SegmentedListItem(
				onClick = { onSignIn(row.found, address) },
				enabled = accountName.isNotBlank(),
				shapes = shapes,
				content = {
					Text(stringResource(Res.string.finder_found_row, row.found.name.ifBlank { stringResource(Res.string.finder_default_name) }, shownAddress(address)))
				},
				supportingContent = { Text(stringResource(Res.string.option_server_sign_in_as, accountName)) }
			)
		}
		is NotSetRow.Older -> SegmentedListItem(
			onClick = { onSignIn(row.found, row.found.address) },
			shapes = shapes,
			content = { Text(stringResource(Res.string.finder_older_at, hubAddressHost(row.found.address).orEmpty())) },
			supportingContent = { Text(stringResource(Res.string.action_use_this_address)) }
		)
		is NotSetRow.NotEncrypted -> SegmentedListItem(
			onClick = {},
			enabled = false,
			shapes = shapes,
			content = { Text(stringResource(Res.string.finder_not_encrypted, shownAddress(row.address))) }
		)
		is NotSetRow.Sentence -> SegmentedListItem(
			onClick = {},
			shapes = shapes,
			content = {
				Text(
					stringResource(
						when (row.sentence) {
							NotSetSentence.Looking -> Res.string.finder_looking
							NotSetSentence.NothingBeside -> Res.string.finder_nothing_beside
							NotSetSentence.NothingOnNetwork -> Res.string.finder_nothing_on_network
							NotSetSentence.NeedsPermission -> Res.string.finder_needs_permission
							NotSetSentence.NotOnWifi -> Res.string.finder_not_on_wifi
						}
					),
					color = MaterialTheme.colorScheme.onSurfaceVariant
				)
			}
		)
		NotSetRow.OpenSettings -> SegmentedListItem(
			onClick = onOpenSettings,
			shapes = shapes,
			content = { Text(stringResource(Res.string.action_open_settings)) }
		)
		NotSetRow.LookOnNetwork -> SegmentedListItem(
			onClick = onLook,
			shapes = shapes,
			content = { Text(stringResource(Res.string.action_look_on_network)) }
		)
		NotSetRow.TypeAddress -> SettingsNavItem(
			onClick = onType,
			shapes = shapes,
			content = { Text(stringResource(Res.string.action_type_address)) }
		)
	}
}

/**
 * Worbus P3 (design §6.6): the server's addresses with where each came from. A typed or found one can
 * be removed; the learned one is switched ("Use away from home"); a typed one can be added. Each edit is
 * saved at once and the dialog stays, so the list moves in place.
 */
@Composable
private fun AddressListDialog(
	record: HubServerRecord,
	onRemove: (String) -> Unit,
	onAwayFromHome: (Boolean) -> Unit,
	onAdd: (String) -> Boolean,
	onDismissRequest: () -> Unit
) {
	var text by rememberSaveable { mutableStateOf("") }
	var invalid by remember { mutableStateOf(false) }
	FormDialog(
		onDismissRequest = onDismissRequest,
		title = { Text(stringResource(Res.string.title_server_addresses)) },
		buttons = {
			SegmentedListButton(
				modifier = Modifier.fillMaxWidth(),
				onClick = {
					if (onAdd(text)) {
						text = ""
						invalid = false
					} else invalid = true
				},
				enabled = text.isNotBlank(),
				colors = SegmentedListButtonDefaults.primaryColors(),
				shapes = SegmentedListButtonDefaults.shapes(index = 0, count = 2)
			) {
				Text(stringResource(Res.string.action_add_address))
			}
			SegmentedListButton(
				modifier = Modifier.fillMaxWidth(),
				onClick = onDismissRequest,
				shapes = SegmentedListButtonDefaults.shapes(index = 1, count = 2)
			) {
				Text(stringResource(Res.string.action_close))
			}
		},
		content = {
			Column(
				modifier = Modifier.animateContentSize(),
				verticalArrangement = Arrangement.spacedBy(SegmentedListItemDefaults.SegmentedGap)
			) {
				record.addresses.forEachIndexed { i, address ->
					key(address.url) {
						val learned = address.source == HubAddress.Source.Learned
						SegmentedListItem(
							onClick = { if (learned) onAwayFromHome(record.learnedOff) },
							shapes = SegmentedListItemDefaults.segmentedShapes(index = i, count = record.addresses.size),
							content = { Text(shownAddress(address.url)) },
							supportingContent = {
								Text(
									stringResource(
										when (address.source) {
											HubAddress.Source.Typed -> Res.string.address_source_typed
											HubAddress.Source.Found -> Res.string.address_source_found
											HubAddress.Source.Learned -> Res.string.option_use_away_from_home
										}
									)
								)
							},
							trailingContent = {
								if (learned) {
									Switch(checked = !record.learnedOff, onCheckedChange = { onAwayFromHome(it) })
								} else {
									TextButton(onClick = { onRemove(address.url) }) {
										Text(stringResource(Res.string.action_remove_address))
									}
								}
							}
						)
					}
				}
				TextField(
					value = text,
					onValueChange = {
						text = it
						invalid = false
					},
					placeholder = { Text(stringResource(Res.string.address_add_hint)) },
					isError = invalid,
					supportingText = if (invalid) ({ Text(stringResource(Res.string.address_not_valid)) }) else null,
					singleLine = true,
					keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
				)
			}
		}
	)
}
