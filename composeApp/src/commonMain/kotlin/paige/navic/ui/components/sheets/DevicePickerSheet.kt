package paige.navic.ui.components.sheets

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.koin.compose.koinInject
import paige.navic.domain.manager.CastBridgeState
import paige.navic.domain.manager.CastBridgeStatus
import paige.navic.domain.manager.DeviceKindIcon
import paige.navic.domain.manager.HUB_FEATURE_VOLUME_TARGET
import paige.navic.domain.manager.HubDevice
import paige.navic.domain.manager.HubManager
import paige.navic.domain.manager.PreferenceManager
import paige.navic.domain.manager.PreviewManager
import paige.navic.domain.manager.deviceKindIcon
import paige.navic.domain.manager.deviceRowStatus
import paige.navic.domain.manager.pickerOrder
import paige.navic.domain.manager.rowVolumeOffered
import paige.navic.icons.Icons
import paige.navic.icons.outlined.Computer
import paige.navic.icons.outlined.Devices
import paige.navic.icons.outlined.Smartphone
import paige.navic.icons.outlined.Speaker
import paige.navic.icons.outlined.SpeakerGroup
import paige.navic.icons.outlined.Tablet
import paige.navic.icons.outlined.Tv
import paige.navic.icons.outlined.VolumeUp
import paige.navic.ui.util.rememberNowPlayingCoverAmbient
import paige.navic.shared.MediaPlayerViewModel
import kotlin.math.roundToInt

/**
 * Spotify-style "Connect to a device" sheet: lists navi-connect devices and
 * transfers playback to the chosen one (the hub resumes it from the same spot,
 * preserving the play/pause state). Each row shows what the device is (an icon for
 * its `kind`), its name, platform and status, so two clients are distinguishable;
 * offline devices auto-hide and manually-hidden ones are tucked behind a
 * "Show offline & hidden" toggle. What a row says and where it stands are the
 * rules in `DeviceRows.kt`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicePickerSheet(onDismissRequest: () -> Unit) {
	val hubManager = koinInject<HubManager>()
	val mediaPlayer = koinInject<MediaPlayerViewModel>()
	val castBridgeStatus = koinInject<CastBridgeStatus>()
	val preferenceManager = koinInject<PreferenceManager>()
	val connected by hubManager.connected.collectAsState()
	val connectionError by hubManager.connectionError.collectAsState()
	val devices by hubManager.devices.collectAsState()
	val myDeviceId by hubManager.myDeviceId.collectAsState()
	val activeDeviceId by hubManager.activeDeviceId.collectAsState()
	val speakers by castBridgeStatus.speakers.collectAsState()
	val hubFeatures by hubManager.hubFeatures.collectAsState()
	// A row's own volume needs a hub that reads `target`: an older one would set the
	// PLAYING device's level instead.
	val hubSetsRowVolumes = connected && HUB_FEATURE_VOLUME_TARGET in hubFeatures

	// Snapshot-backed pref → reading recomposes when the hidden set changes.
	val hiddenIds = remember(preferenceManager.hubHiddenDeviceIds) {
		preferenceManager.hubHiddenDeviceIds.split(",").filter { it.isNotBlank() }.toSet()
	}
	val toggleHidden: (String, Boolean) -> Unit = { id, hide ->
		val cur = preferenceManager.hubHiddenDeviceIds
			.split(",").filter { it.isNotBlank() }.toMutableSet()
		if (hide) cur.add(id) else cur.remove(id)
		preferenceManager.hubHiddenDeviceIds = cur.joinToString(",")
	}
	var showExtra by remember { mutableStateOf(false) }

	// The playing device, this device, the rest by name, and last what cannot take the
	// session (`pickerOrder`); the same order inside the offline-and-hidden fold.
	val ordered = pickerOrder(devices, myDeviceId)
	val visibleDevices = ordered.filter { it.online && it.id !in hiddenIds }
	val extraDevices = ordered.filter { !it.online || it.id in hiddenIds }

	// A preview's audio comes from the preview sidecar, not from Navidrome, and a
	// Chromecast fetches the stream URL ITSELF. So a cast can only work when the hub
	// is minting publicly reachable preview URLs (`PREVIEW_PUBLIC_URL` set, which is
	// what `previewCastable` reports). With it unset the URL is a LAN address and the
	// transfer commits, the speaker fetches nothing, and every device shows a playing
	// bar over silence — the exact failure the `transferable` rule above exists to
	// prevent, so it is decided here rather than discovered at the speaker.
	val previewManager = koinInject<PreviewManager>()
	val previewStatus by previewManager.status.collectAsState()
	LaunchedEffect(previewManager.routeSignature) { previewManager.ensureAvailability() }
	// The blended state: the session's while another device plays, this player's otherwise.
	// So `isPaused` is the active device's, whichever device that is.
	val playerState by mediaPlayer.steadyState.collectAsState()
	val queueHasPreview = playerState.queue.any { PreviewManager.isPreviewId(it.id) }
	val blockPreviewCast = queueHasPreview && !previewStatus.previewCastable
	val sessionPlaying = !playerState.isPaused

	val renderDevice: @Composable (HubDevice) -> Unit = { device ->
		val isHidden = device.id in hiddenIds
		Column {
		Row(
			modifier = Modifier.fillMaxWidth(),
			horizontalArrangement = Arrangement.spacedBy(16.dp),
			verticalAlignment = Alignment.CenterVertically
		) {
			// What the device is, as it said in `hello`; the playing device's in the accent colour.
			Icon(
				imageVector = deviceKindIcon(device.kind).imageVector(),
				contentDescription = null,
				tint = if (device.isActive) MaterialTheme.colorScheme.primary
					else MaterialTheme.colorScheme.onSurfaceVariant
			)
			Column(Modifier.weight(1f)) {
				Text(device.name, style = MaterialTheme.typography.bodyLarge)
				Text(
					buildString {
						append(
							when (device.platform) {
								"desktop" -> "Desktop"
								"android" -> "Android"
								"chromecast" -> "Cast"
								else -> device.platform.replaceFirstChar { it.uppercase() }
							}
						)
						append(" · ")
						append(deviceRowStatus(device, myDeviceId, sessionPlaying).label)
					},
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant
				)
			}
			Row(
				horizontalArrangement = Arrangement.spacedBy(4.dp),
				verticalAlignment = Alignment.CenterVertically
			) {
				// Shown but not offered: the speaker is real and will come back, so hiding it
				// would be a lie of omission — but handing it the session right now cannot work.
				val castBlocked = blockPreviewCast && device.platform == "chromecast"
				if (device.transferable && device.id != activeDeviceId && !castBlocked) {
					Button(
						onClick = {
							hubManager.transfer(device.id)
							onDismissRequest()
						}
					) {
						Text(if (device.id == myDeviceId) "Play here" else "Transfer")
					}
				}
				// Refused WITH A REASON, not silently dropped: "can't cast a preview"
				// is actionable (remove it from the queue, or set PREVIEW_PUBLIC_URL)
				// and an absent button is not.
				if (castBlocked && device.transferable && device.id != activeDeviceId) {
					Text(
						"Can't cast a preview",
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.error
					)
				}
				TextButton(onClick = { toggleHidden(device.id, !isHidden) }) {
					Text(if (isHidden) "Unhide" else "Hide")
				}
			}
		}
		// The volume of a device that is not playing, so that it can be turned down before
		// music is sent to it. Under its own row, in line with the name; it opens and closes
		// as the row gains or loses the right to one (`rowVolumeOffered`).
		AnimatedVisibility(visible = rowVolumeOffered(device, myDeviceId, hubSetsRowVolumes)) {
			Row(
				modifier = Modifier.fillMaxWidth().padding(start = 40.dp),
				horizontalArrangement = Arrangement.spacedBy(12.dp),
				verticalAlignment = Alignment.CenterVertically
			) {
				Icon(
					imageVector = Icons.Outlined.VolumeUp,
					contentDescription = null,
					tint = MaterialTheme.colorScheme.onSurfaceVariant,
					modifier = Modifier.size(20.dp)
				)
				HubVolumeSlider(device, hubManager, Modifier.weight(1f))
			}
		}
		}
	}

	ModalBottomSheet(
		onDismissRequest = onDismissRequest,
		sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
		// Cover-tinted surface + cover-themed content (see the shared wrapper).
		ambient = rememberNowPlayingCoverAmbient()
	) {
		Column(
			modifier = Modifier.padding(horizontal = 24.dp),
			verticalArrangement = Arrangement.spacedBy(12.dp)
		) {
			Text(
				"Connect to a device",
				style = MaterialTheme.typography.titleLarge,
				color = MaterialTheme.colorScheme.primary
			)
			if (!connected) {
				Text(
					connectionError ?: "Not connected to the hub",
					style = MaterialTheme.typography.bodyMedium,
					color = if (connectionError != null) MaterialTheme.colorScheme.error
						else MaterialTheme.colorScheme.onSurfaceVariant
				)
			} else if (visibleDevices.isEmpty() && extraDevices.isEmpty()) {
				Text(
					"No devices found",
					style = MaterialTheme.typography.bodyMedium,
					color = MaterialTheme.colorScheme.onSurfaceVariant
				)
			}

			// Keyed by id: the order follows the session, and a row's own state must follow
			// its device and not its place in the list.
			visibleDevices.forEach { key(it.id) { renderDevice(it) } }

			if (extraDevices.isNotEmpty()) {
				TextButton(onClick = { showExtra = !showExtra }) {
					Text((if (showExtra) "Hide" else "Show") + " offline & hidden (${extraDevices.size})")
				}
				if (showExtra) extraDevices.forEach { key(it.id) { renderDevice(it) } }
			}

			// Volume for the active remote device (mirrors Feishin's remote-bar
			// volume). The hub forwards `do setVolume` to that device ONLY, so it
			// never touches this device's local volume.
			val activeDevice = devices.firstOrNull { it.id == activeDeviceId }
			if (connected && activeDevice != null && activeDevice.id != myDeviceId) {
				Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
					Text(
						"Volume · ${activeDevice.name}",
						style = MaterialTheme.typography.titleMedium
					)
					HubVolumeSlider(activeDevice, hubManager)
				}
			}

			// Chromecasts reach the list ABOVE, as ordinary hub devices, once somebody bridges
			// them — so casting is just a transfer and transfer-with-resume works for free. What
			// this section adds is the part you otherwise can't see: whether this phone found the
			// speaker at all, and who is presenting it. Rendered even when empty, because a failed
			// scan and a speaker that is simply off used to look identical.
			Text("Chromecasts on this network", style = MaterialTheme.typography.titleMedium)
			if (speakers.isEmpty()) {
				Text(
					"Searching…",
					style = MaterialTheme.typography.bodyMedium,
					color = MaterialTheme.colorScheme.onSurfaceVariant
				)
			}
			speakers.forEach { speaker ->
				Column {
					Text(speaker.name, style = MaterialTheme.typography.bodyLarge)
					Text(
						when (speaker.state) {
							CastBridgeState.BRIDGING -> "ready · transfer to it above"
							CastBridgeState.CLAIMING -> "connecting…"
							CastBridgeState.BRIDGED_ELSEWHERE -> "ready · published by another device"
							CastBridgeState.HUB_DISABLED -> "found, but the hub is off"
							CastBridgeState.IDLE -> "found"
						},
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant
					)
				}
			}
			Spacer(Modifier.height(24.dp))
		}
	}
}

/**
 * A hub device's volume: the playing device's, and a picker row's own.
 *
 * It previews the drag and commits on release only, so the socket carries one act per
 * gesture. From the release on it shows the level that was let go, never the old one: the
 * hub client pins it until the hub answers for that act (`ActPins.kt`), and what shows then
 * is the hub's word: the level the device settled on, or after a refusal the level the row
 * never stopped having.
 */
@Composable
private fun HubVolumeSlider(device: HubDevice, hubManager: HubManager, modifier: Modifier = Modifier) {
	var dragged by remember { mutableStateOf<Float?>(null) }
	// A hub that answers no acts pins nothing. The released level then stands until that
	// hub's next word on this device, as it always did.
	var released by remember(device.volume) { mutableStateOf<Float?>(null) }
	// The pin a release sets is in the manager before this composition's copy of the flow
	// has it, and drawn from the copy alone the frame in between showed the old level. So
	// the manager is asked first. The copy is read on every pass all the same: it is what
	// recomposes this when a pin goes, and it still holds the pin for the moment in which
	// the manager has released it and the new device list has not been drawn yet.
	val collected = hubManager.volumePins.collectAsState().value.byDevice[device.id]?.level
	val pinned = hubManager.pinnedVolume(device.id) ?: collected
	Slider(
		value = dragged ?: pinned?.toFloat() ?: released ?: device.volume.toFloat(),
		onValueChange = { dragged = it },
		onValueChangeFinished = {
			dragged?.let { level ->
				val isPinned = hubManager.actSetVolume(level.roundToInt(), device.id)
				released = if (isPinned) null else level
			}
			dragged = null
		},
		valueRange = 0f..100f,
		modifier = modifier.semantics { contentDescription = "Volume · ${device.name}" }
	)
}

/** The outline drawn for a device of this kind. */
private fun DeviceKindIcon.imageVector(): ImageVector = when (this) {
	DeviceKindIcon.COMPUTER -> Icons.Outlined.Computer
	DeviceKindIcon.PHONE -> Icons.Outlined.Smartphone
	DeviceKindIcon.TABLET -> Icons.Outlined.Tablet
	DeviceKindIcon.SPEAKER -> Icons.Outlined.Speaker
	DeviceKindIcon.TV -> Icons.Outlined.Tv
	DeviceKindIcon.GROUP -> Icons.Outlined.SpeakerGroup
	DeviceKindIcon.GENERIC -> Icons.Outlined.Devices
}
