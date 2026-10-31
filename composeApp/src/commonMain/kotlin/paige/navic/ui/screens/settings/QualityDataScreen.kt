package paige.navic.ui.screens.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.dropUnlessResumed
import kotlinx.collections.immutable.toImmutableList
import navic.composeapp.generated.resources.Res
import navic.composeapp.generated.resources.action_cancel
import navic.composeapp.generated.resources.action_ok
import navic.composeapp.generated.resources.info_in_use
import navic.composeapp.generated.resources.info_streaming_quality
import navic.composeapp.generated.resources.option_download_format_mp3
import navic.composeapp.generated.resources.option_download_format_opus
import navic.composeapp.generated.resources.option_download_format_original
import navic.composeapp.generated.resources.option_download_kbps
import navic.composeapp.generated.resources.option_download_original
import navic.composeapp.generated.resources.option_format_aac
import navic.composeapp.generated.resources.option_offline_mode
import navic.composeapp.generated.resources.option_play_downloads_on_mobile
import navic.composeapp.generated.resources.option_quality_high
import navic.composeapp.generated.resources.option_quality_low
import navic.composeapp.generated.resources.option_quality_medium
import navic.composeapp.generated.resources.option_stream_cellular
import navic.composeapp.generated.resources.option_stream_custom
import navic.composeapp.generated.resources.option_stream_custom_value
import navic.composeapp.generated.resources.option_stream_original
import navic.composeapp.generated.resources.option_stream_preset_detail
import navic.composeapp.generated.resources.option_stream_wifi
import navic.composeapp.generated.resources.setting_download_charging_only
import navic.composeapp.generated.resources.setting_download_charging_only_desc
import navic.composeapp.generated.resources.setting_download_concurrency
import navic.composeapp.generated.resources.setting_download_concurrency_desc
import navic.composeapp.generated.resources.setting_download_wifi_only
import navic.composeapp.generated.resources.setting_download_wifi_only_desc
import navic.composeapp.generated.resources.subtitle_download_format_locked
import navic.composeapp.generated.resources.subtitle_downloads_page
import navic.composeapp.generated.resources.subtitle_offline_mode
import navic.composeapp.generated.resources.subtitle_prefer_downloads_cellular_off
import navic.composeapp.generated.resources.subtitle_prefer_downloads_cellular_on
import navic.composeapp.generated.resources.title_bitrate
import navic.composeapp.generated.resources.title_custom_stream_quality
import navic.composeapp.generated.resources.title_download_format
import navic.composeapp.generated.resources.title_download_quality
import navic.composeapp.generated.resources.title_downloads
import navic.composeapp.generated.resources.title_mobile_data
import navic.composeapp.generated.resources.title_quality_data
import navic.composeapp.generated.resources.title_stream_format
import navic.composeapp.generated.resources.title_streaming
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import paige.navic.di.LocalNavStack
import paige.navic.di.LocalPlatformContext
import paige.navic.di.PlatformType
import paige.navic.domain.manager.ConnectivityManager
import paige.navic.domain.manager.PreferenceManager
import paige.navic.domain.models.settings.OfflineMode
import paige.navic.domain.models.settings.StreamingQuality
import paige.navic.icons.Icons
import paige.navic.icons.outlined.Info
import paige.navic.ui.components.common.SegmentedListButton
import paige.navic.ui.components.common.SegmentedListButtonDefaults
import paige.navic.ui.components.common.SegmentedListItemDefaults
import paige.navic.ui.components.dialogs.FormDialog
import paige.navic.ui.components.layouts.NestedTopBar
import paige.navic.ui.components.layouts.NestedTopBarDefaults
import paige.navic.ui.navigation.Screen
import paige.navic.ui.screens.settings.components.SettingsChoiceItem
import paige.navic.ui.screens.settings.components.SettingsGroup
import paige.navic.ui.screens.settings.components.SettingsGroupDefaults
import paige.navic.ui.screens.settings.components.SettingsNavItem
import paige.navic.ui.screens.settings.components.SettingsRadioItem
import paige.navic.ui.screens.settings.components.SettingsSliderItem
import paige.navic.ui.screens.settings.components.SettingsToggleItem
import kotlin.math.roundToInt

/**
 * Quality & data (Worbus settings N2): what the app streams at on each network, what it keeps when it
 * downloads, what it does on mobile data, and offline mode, on one page. Reached by
 * [Screen.Settings.StreamingQuality], whose route keeps its old name so a saved back stack still
 * means the same screen.
 */
@Composable
fun SettingsQualityDataScreen() {
	val preferenceManager = koinInject<PreferenceManager>()
	val connectivityManager = koinInject<ConnectivityManager>()
	val platformContext = LocalPlatformContext.current
	val platform = platformContext.platformType
	val hideBack = platformContext.sizeClass.widthSizeClass >= WindowWidthSizeClass.Medium
	val backStack = LocalNavStack.current
	val isOnline by connectivityManager.isOnline.collectAsStateWithLifecycle()
	val isCellular by connectivityManager.isCellular.collectAsStateWithLifecycle()
	var customFor by rememberSaveable { mutableStateOf<StreamNetwork?>(null) }

	val keys = preferenceManager.streamingKeys()
	val inUseWifi = isOnline && !isCellular
	val inUseCellular = isOnline && isCellular

	Scaffold(
		topBar = {
			NestedTopBar(
				title = { Text(stringResource(Res.string.title_quality_data)) },
				navigationAction = {
					if (!hideBack) {
						NestedTopBarDefaults.NavigationAction()
					}
				}
			)
		}
	) { innerPadding ->
		CompositionLocalProvider(
			LocalMinimumInteractiveComponentSize provides 0.dp
		) {
			Column(
				modifier = Modifier
					.padding(innerPadding)
					.verticalScroll(rememberScrollState())
					.padding(horizontal = 16.dp),
				verticalArrangement = Arrangement.spacedBy(SettingsGroupDefaults.GapBetweenGroups)
			) {
				SettingsGroup(title = { Text(stringResource(Res.string.title_streaming)) }) {
					StreamChoiceRow(
						network = StreamNetwork.Wifi,
						keys = keys,
						platform = platform,
						inUse = inUseWifi,
						shapes = SegmentedListItemDefaults.segmentedShapes(index = 0, count = 2),
						onKeys = { writeStreamingKeys(preferenceManager, keys, it) },
						onCustom = { customFor = StreamNetwork.Wifi }
					)
					StreamChoiceRow(
						network = StreamNetwork.Cellular,
						keys = keys,
						platform = platform,
						inUse = inUseCellular,
						shapes = SegmentedListItemDefaults.segmentedShapes(index = 1, count = 2),
						onKeys = { writeStreamingKeys(preferenceManager, keys, it) },
						onCustom = { customFor = StreamNetwork.Cellular }
					)
				}

				// navi-connect: downloads get their OWN quality. A stream is thrown away; a download
				// is kept, so it shouldn't silently inherit the bitrate chosen to save mobile data.
				// Bitrate and container are separate so "original FLAC" and "320 kbps MP3" are both
				// sayable. Independent of the streaming bitrates; changing it affects NEW downloads.
				SettingsGroup(title = { Text(stringResource(Res.string.title_downloads)) }) {
					val bitrates = (DOWNLOAD_BITRATES + preferenceManager.downloadBitrate).distinct()
					SettingsChoiceItem(
						choices = bitrates.toImmutableList(),
						selectedChoice = preferenceManager.downloadBitrate,
						onChoiceSelected = { preferenceManager.downloadBitrate = it },
						label = { bitrate ->
							if (bitrate == 0) stringResource(Res.string.option_download_original)
							else stringResource(Res.string.option_download_kbps, bitrate)
						},
						content = { Text(stringResource(Res.string.title_download_quality)) },
						shapes = SegmentedListItemDefaults.segmentedShapes(index = 0, count = 6)
					)
					// Greyed at Original bitrate: there is no transcode to pick a container for.
					val transcoding = preferenceManager.downloadBitrate > 0
					val formats = (DOWNLOAD_FORMATS + preferenceManager.downloadFormat).distinct()
					SettingsChoiceItem(
						choices = formats.toImmutableList(),
						selectedChoice = preferenceManager.downloadFormat,
						onChoiceSelected = { preferenceManager.downloadFormat = it },
						enabled = transcoding,
						description = if (transcoding) null else stringResource(Res.string.subtitle_download_format_locked),
						label = { format ->
							when (format) {
								"" -> stringResource(Res.string.option_download_format_original)
								"opus" -> stringResource(Res.string.option_download_format_opus)
								"mp3" -> stringResource(Res.string.option_download_format_mp3)
								else -> format
							}
						},
						content = { Text(stringResource(Res.string.title_download_format)) },
						shapes = SegmentedListItemDefaults.segmentedShapes(index = 1, count = 6)
					)
					// navi-connect (I-039): what holds downloads back. The downloads themselves, lb-bot's
					// and the wishlist are the library's Downloads page, which the last row opens.
					SettingsToggleItem(
						checked = preferenceManager.downloadWifiOnly,
						onCheckedChange = { preferenceManager.downloadWifiOnly = it },
						content = { Text(stringResource(Res.string.setting_download_wifi_only)) },
						supportingContent = { Text(stringResource(Res.string.setting_download_wifi_only_desc)) },
						shapes = SegmentedListItemDefaults.segmentedShapes(index = 2, count = 6)
					)
					SettingsToggleItem(
						checked = preferenceManager.downloadChargingOnly,
						onCheckedChange = { preferenceManager.downloadChargingOnly = it },
						content = { Text(stringResource(Res.string.setting_download_charging_only)) },
						supportingContent = {
							Text(stringResource(Res.string.setting_download_charging_only_desc))
						},
						shapes = SegmentedListItemDefaults.segmentedShapes(index = 3, count = 6)
					)
					// Persisted; the permit count applies from the next app start (DownloadManager).
					SettingsSliderItem(
						value = preferenceManager.downloadMaxConcurrency.toFloat(),
						valueRange = 1f..10f,
						steps = 8,
						onValueChange = { preferenceManager.downloadMaxConcurrency = it.roundToInt() },
						shapes = SegmentedListItemDefaults.segmentedShapes(index = 4, count = 6),
						content = {
							Column {
								Text(stringResource(Res.string.setting_download_concurrency))
								Text(
									stringResource(Res.string.setting_download_concurrency_desc),
									style = MaterialTheme.typography.bodyMedium,
									color = MaterialTheme.colorScheme.onSurfaceVariant
								)
							}
						},
						trailingContent = { Text("${preferenceManager.downloadMaxConcurrency}") }
					)
					SettingsNavItem(
						onClick = dropUnlessResumed { backStack.add(Screen.Downloads()) },
						content = { Text(stringResource(Res.string.title_downloads)) },
						supportingContent = { Text(stringResource(Res.string.subtitle_downloads_page)) },
						shapes = SegmentedListItemDefaults.segmentedShapes(index = 5, count = 6)
					)
				}

				// Which copy wins on a metered link. Only matters once downloads are transcodes:
				// at Original bitrate the downloaded file IS the server file.
				SettingsGroup(title = { Text(stringResource(Res.string.title_mobile_data)) }) {
					SettingsToggleItem(
						checked = preferenceManager.preferDownloadsOnCellular,
						onCheckedChange = { preferenceManager.preferDownloadsOnCellular = it },
						content = { Text(stringResource(Res.string.option_play_downloads_on_mobile)) },
						supportingContent = {
							Text(
								stringResource(
									if (preferenceManager.preferDownloadsOnCellular)
										Res.string.subtitle_prefer_downloads_cellular_on
									else Res.string.subtitle_prefer_downloads_cellular_off
								)
							)
						},
						shapes = SegmentedListItemDefaults.segmentedShapes(index = 0, count = 1)
					)
				}

				SettingsGroup {
					SettingsChoiceItem(
						choices = OfflineMode.entries.toImmutableList(),
						selectedChoice = preferenceManager.offlineMode,
						onChoiceSelected = { preferenceManager.offlineMode = it },
						description = stringResource(Res.string.subtitle_offline_mode),
						content = { Text(stringResource(Res.string.option_offline_mode)) },
						label = { stringResource(it.displayName) },
						shapes = SegmentedListItemDefaults.segmentedShapes(index = 0, count = 1)
					)
				}

				Row(
					modifier = Modifier.padding(horizontal = 8.dp),
					horizontalArrangement = Arrangement.spacedBy(16.dp)
				) {
					Icon(
						Icons.Outlined.Info,
						contentDescription = null,
						tint = MaterialTheme.colorScheme.onSurfaceVariant
					)
					Text(
						stringResource(Res.string.info_streaming_quality),
						color = MaterialTheme.colorScheme.onSurfaceVariant,
						style = MaterialTheme.typography.bodyMedium
					)
				}
			}
		}
	}

	customFor?.let { network ->
		CustomStreamDialog(
			current = keys.choice(network, platform),
			platform = platform,
			onDismissRequest = { customFor = null },
			onConfirm = { bitrate, format ->
				writeStreamingKeys(
					preferenceManager,
					keys,
					keys.choose(network, StreamChoice.Custom(bitrate, format), platform)
				)
				customFor = null
			}
		)
	}
}

/** The picks one network's choice list offers, in the order shown. */
private enum class StreamPick(val quality: StreamingQuality?) {
	Original(StreamingQuality.Lossless),
	High(StreamingQuality.High),
	Medium(StreamingQuality.Medium),
	Low(StreamingQuality.Low),
	Custom(null)
}

@Composable
private fun StreamChoiceRow(
	network: StreamNetwork,
	keys: StreamingKeys,
	platform: PlatformType,
	inUse: Boolean,
	shapes: ListItemShapes,
	onKeys: (StreamingKeys) -> Unit,
	onCustom: () -> Unit
) {
	val choice = keys.choice(network, platform)
	val selected = when (choice) {
		is StreamChoice.Preset -> StreamPick.entries.first { it.quality == choice.quality }
		is StreamChoice.Custom -> StreamPick.Custom
	}
	SettingsChoiceItem(
		choices = StreamPick.entries.toImmutableList(),
		selectedChoice = selected,
		onChoiceSelected = { pick ->
			val quality = pick.quality
			if (quality == null) onCustom()
			else onKeys(keys.choose(network, StreamChoice.Preset(quality), platform))
		},
		label = { pick ->
			val quality = pick.quality
			if (quality == null) {
				if (choice is StreamChoice.Custom) {
					stringResource(Res.string.option_stream_custom_value, customSummary(choice))
				} else stringResource(Res.string.option_stream_custom)
			} else presetLabel(quality, platform)
		},
		description = if (inUse) stringResource(Res.string.info_in_use) else null,
		content = {
			Text(
				stringResource(
					if (network == StreamNetwork.Wifi) Res.string.option_stream_wifi
					else Res.string.option_stream_cellular
				)
			)
		},
		shapes = shapes
	)
}

/** "High (192 kbps, Opus)"; Original is just that. */
@Composable
private fun presetLabel(quality: StreamingQuality, platform: PlatformType): String {
	if (quality == StreamingQuality.Lossless) return stringResource(Res.string.option_stream_original)
	val (bitrate, format) = quality.equivalent(platform)
	val name = stringResource(
		when (quality) {
			StreamingQuality.Low -> Res.string.option_quality_low
			StreamingQuality.Medium -> Res.string.option_quality_medium
			else -> Res.string.option_quality_high
		}
	)
	return stringResource(Res.string.option_stream_preset_detail, name, bitrate, formatName(format))
}

/** "128 kbps, Opus"; a value this app does not offer is shown as it is stored. */
@Composable
private fun customSummary(choice: StreamChoice.Custom): String {
	val parts = buildList {
		if (choice.bitrate > 0) add(stringResource(Res.string.option_download_kbps, choice.bitrate))
		if (choice.format.isNotBlank()) add(formatName(choice.format))
	}
	return parts.joinToString(", ")
}

@Composable
private fun formatName(value: String): String = when (StreamFormat.of(value)) {
	StreamFormat.Original -> stringResource(Res.string.option_download_format_original)
	StreamFormat.Opus -> stringResource(Res.string.option_download_format_opus)
	StreamFormat.Mp3 -> stringResource(Res.string.option_download_format_mp3)
	StreamFormat.Aac -> stringResource(Res.string.option_format_aac)
	null -> value
}

/**
 * Custom: two choice lists, a bitrate and a container, over the same keys the text fields wrote. A
 * stored value that is not in a list is added to it, selected, so OK without touching anything keeps it.
 */
@Composable
private fun CustomStreamDialog(
	current: StreamChoice,
	platform: PlatformType,
	onDismissRequest: () -> Unit,
	onConfirm: (bitrate: Int, format: String) -> Unit
) {
	val startBitrate = when (current) {
		is StreamChoice.Custom -> current.bitrate
		is StreamChoice.Preset -> current.quality.equivalent(platform).first
	}
	val startFormat = when (current) {
		is StreamChoice.Custom -> current.format
		is StreamChoice.Preset -> current.quality.equivalent(platform).second
	}
	var bitrate by rememberSaveable { mutableStateOf(startBitrate) }
	var format by rememberSaveable { mutableStateOf(StreamFormat.of(startFormat)?.value ?: startFormat) }
	val bitrates = (CUSTOM_BITRATES + startBitrate).distinct()
	val formats = (streamFormatsFor(platform).map { it.value } + format).distinct()
	val rowPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)

	FormDialog(
		onDismissRequest = onDismissRequest,
		title = { Text(stringResource(Res.string.title_custom_stream_quality)) },
		content = {
			Column(verticalArrangement = Arrangement.spacedBy(SettingsGroupDefaults.GapBetweenGroups)) {
				SettingsGroup(
					modifier = Modifier.selectableGroup().fillMaxWidth(),
					title = { Text(stringResource(Res.string.title_bitrate)) }
				) {
					bitrates.forEachIndexed { index, value ->
						SettingsRadioItem(
							selected = bitrate == value,
							onClick = { bitrate = value },
							contentPadding = rowPadding,
							content = {
								Text(
									if (value == 0) stringResource(Res.string.option_stream_original)
									else stringResource(Res.string.option_download_kbps, value)
								)
							},
							shapes = SegmentedListItemDefaults.segmentedShapes(index = index, count = bitrates.size)
						)
					}
				}
				SettingsGroup(
					modifier = Modifier.selectableGroup().fillMaxWidth(),
					title = { Text(stringResource(Res.string.title_stream_format)) }
				) {
					formats.forEachIndexed { index, value ->
						SettingsRadioItem(
							selected = format == value,
							onClick = { format = value },
							contentPadding = rowPadding,
							content = {
								Text(
									if (value.isEmpty()) stringResource(Res.string.option_stream_original)
									else formatName(value)
								)
							},
							shapes = SegmentedListItemDefaults.segmentedShapes(index = index, count = formats.size)
						)
					}
				}
			}
		},
		buttons = {
			SegmentedListButton(
				modifier = Modifier.fillMaxWidth(),
				onClick = { onConfirm(bitrate, format) },
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
		}
	)
}

private fun PreferenceManager.streamingKeys() = StreamingKeys(
	presetWifi = streamingQualityWifi,
	presetCellular = streamingQualityCellular,
	advanced = isAdvancedTranscodingActive,
	bitrateWifi = customMaxBitrateWifi,
	bitrateCellular = customMaxBitrateCellular,
	formatWifi = customFormatWifi,
	formatCellular = customFormatCellular
)

/** Writes only the keys that differ, so a choice touches nothing it did not change. */
private fun writeStreamingKeys(prefs: PreferenceManager, old: StreamingKeys, new: StreamingKeys) {
	if (old.presetWifi != new.presetWifi) prefs.streamingQualityWifi = new.presetWifi
	if (old.presetCellular != new.presetCellular) prefs.streamingQualityCellular = new.presetCellular
	if (old.bitrateWifi != new.bitrateWifi) prefs.customMaxBitrateWifi = new.bitrateWifi
	if (old.bitrateCellular != new.bitrateCellular) prefs.customMaxBitrateCellular = new.bitrateCellular
	if (old.formatWifi != new.formatWifi) prefs.customFormatWifi = new.formatWifi
	if (old.formatCellular != new.formatCellular) prefs.customFormatCellular = new.formatCellular
	// Last: the player reads the custom keys only while this is on, and the presets only while it is off.
	if (old.advanced != new.advanced) prefs.isAdvancedTranscodingActive = new.advanced
}

/** navi-connect: download bitrates, kbps; 0 = the server's own file, untouched. */
private val DOWNLOAD_BITRATES = listOf(0, 320, 256, 192, 128)

/** navi-connect: download container. "" = whatever the original is; otherwise a transcode target. */
private val DOWNLOAD_FORMATS = listOf("", "opus", "mp3")
