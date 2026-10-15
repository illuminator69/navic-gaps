package paige.navic.ui.screens.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import navic.composeapp.generated.resources.Res
import navic.composeapp.generated.resources.info_in_use
import navic.composeapp.generated.resources.info_streaming_quality
import navic.composeapp.generated.resources.info_transcoding_defaults
import navic.composeapp.generated.resources.option_download_format_mp3
import navic.composeapp.generated.resources.option_download_format_opus
import navic.composeapp.generated.resources.option_download_format_original
import navic.composeapp.generated.resources.option_download_kbps
import navic.composeapp.generated.resources.option_download_original
import navic.composeapp.generated.resources.option_max_bitrate_cellular
import navic.composeapp.generated.resources.option_max_bitrate_wifi
import navic.composeapp.generated.resources.option_prefer_downloads_cellular
import navic.composeapp.generated.resources.option_stream_custom_quality
import navic.composeapp.generated.resources.option_stream_format_cellular
import navic.composeapp.generated.resources.option_stream_format_wifi
import navic.composeapp.generated.resources.subtitle_download_original
import navic.composeapp.generated.resources.subtitle_prefer_downloads_cellular_off
import navic.composeapp.generated.resources.subtitle_prefer_downloads_cellular_on
import navic.composeapp.generated.resources.title_advanced
import navic.composeapp.generated.resources.title_cellular
import navic.composeapp.generated.resources.title_cellular_playback_source
import navic.composeapp.generated.resources.title_download_format
import navic.composeapp.generated.resources.title_download_quality
import navic.composeapp.generated.resources.title_streaming_quality
import navic.composeapp.generated.resources.title_wifi
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import paige.navic.domain.manager.ConnectivityManager
import paige.navic.domain.manager.PreferenceManager
import paige.navic.domain.models.settings.StreamingQuality
import paige.navic.domain.models.settings.description
import paige.navic.icons.Icons
import paige.navic.icons.outlined.Info
import paige.navic.ui.components.common.SegmentedListItemDefaults
import paige.navic.ui.components.layouts.NestedTopBar
import paige.navic.ui.screens.settings.components.SettingsGroup
import paige.navic.ui.screens.settings.components.SettingsGroupDefaults
import paige.navic.ui.screens.settings.components.SettingsRadioItem
import paige.navic.ui.screens.settings.components.SettingsToggleItem

@Composable
fun SettingsStreamingQualityScreen() {
	val preferenceManager = koinInject<PreferenceManager>()
	val connectivityManager = koinInject<ConnectivityManager>()
	val isOnline by connectivityManager.isOnline.collectAsStateWithLifecycle()
	val isCellular by connectivityManager.isCellular.collectAsStateWithLifecycle()

	Scaffold(
		topBar = { NestedTopBar({ Text(stringResource(Res.string.title_streaming_quality)) }) }
	) { innerPadding ->
		CompositionLocalProvider(
			LocalMinimumInteractiveComponentSize provides 0.dp
		) {
			Column(
				modifier = Modifier
					.padding(innerPadding)
					.verticalScroll(rememberScrollState())
					.padding(horizontal = 16.dp)
			) {
				AnimatedVisibility(visible = !preferenceManager.isAdvancedTranscodingActive) {
					SettingsGroup(
						modifier = Modifier
							.selectableGroup()
							.padding(bottom = SettingsGroupDefaults.GapBetweenGroups),
						title = {
							Text(buildString {
								append(stringResource(Res.string.title_wifi))
								if (isOnline && !isCellular) {
									append(' ' + stringResource(Res.string.info_in_use))
								}
							})
						}
					) {
						RadioButtons(
							value = preferenceManager.streamingQualityWifi,
							onChangeValue = { preferenceManager.streamingQualityWifi = it }
						)
					}
				}

				AnimatedVisibility(visible = !preferenceManager.isAdvancedTranscodingActive) {
					SettingsGroup(
						modifier = Modifier
							.selectableGroup()
							.padding(bottom = SettingsGroupDefaults.GapBetweenGroups),
						title = {
							Text(buildString {
								append(stringResource(Res.string.title_cellular))
								if (isOnline && isCellular) {
									append(' ' + stringResource(Res.string.info_in_use))
								}
							})
						}
					) {
						RadioButtons(
							value = preferenceManager.streamingQualityCellular,
							onChangeValue = { preferenceManager.streamingQualityCellular = it }
						)
					}
				}

				// navi-connect: downloads get their OWN quality. A stream is thrown away; a download
				// is kept, so it shouldn't silently inherit the bitrate chosen to save mobile data.
				// Bitrate and container are separate so "original FLAC" and "320 kbps MP3" are both
				// sayable. Independent of the streaming bitrates; changing it affects NEW downloads.
				SettingsGroup(
					modifier = Modifier
						.selectableGroup()
						.padding(bottom = SettingsGroupDefaults.GapBetweenGroups),
					title = { Text(stringResource(Res.string.title_download_quality)) }
				) {
					DOWNLOAD_BITRATES.forEachIndexed { index, bitrate ->
						val selected = preferenceManager.downloadBitrate == bitrate
						SettingsRadioItem(
							selected = selected,
							onClick = { preferenceManager.downloadBitrate = bitrate },
							content = {
								Text(
									if (bitrate == 0) stringResource(Res.string.option_download_original)
									else stringResource(Res.string.option_download_kbps, bitrate)
								)
							},
							supportingContent = if (bitrate == 0) {
								{
									AnimatedVisibility(visible = selected) {
										Text(stringResource(Res.string.subtitle_download_original))
									}
								}
							} else null,
							shapes = SegmentedListItemDefaults.segmentedShapes(
								index = index,
								count = DOWNLOAD_BITRATES.size
							)
						)
					}
				}

				SettingsGroup(
					modifier = Modifier
						.selectableGroup()
						.padding(bottom = SettingsGroupDefaults.GapBetweenGroups),
					title = { Text(stringResource(Res.string.title_download_format)) }
				) {
					// Greyed at Original bitrate: there is no transcode to pick a container for.
					val transcoding = preferenceManager.downloadBitrate > 0
					DOWNLOAD_FORMATS.forEachIndexed { index, (format, label) ->
						SettingsRadioItem(
							selected = preferenceManager.downloadFormat == format,
							enabled = transcoding,
							onClick = { preferenceManager.downloadFormat = format },
							content = { Text(stringResource(label)) },
							shapes = SegmentedListItemDefaults.segmentedShapes(
								index = index,
								count = DOWNLOAD_FORMATS.size
							)
						)
					}
				}

				// Which copy wins on a metered link. Only matters once downloads are transcodes:
				// at Original bitrate the downloaded file IS the server file.
				SettingsGroup(
					modifier = Modifier.padding(bottom = SettingsGroupDefaults.GapBetweenGroups),
					title = { Text(stringResource(Res.string.title_cellular_playback_source)) }
				) {
					SettingsToggleItem(
						checked = preferenceManager.preferDownloadsOnCellular,
						onCheckedChange = { preferenceManager.preferDownloadsOnCellular = it },
						content = { Text(stringResource(Res.string.option_prefer_downloads_cellular)) },
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

				SettingsGroup(
					modifier = Modifier.padding(bottom = SettingsGroupDefaults.GapBetweenGroups),
					title = { Text(stringResource(Res.string.title_advanced)) }
				) {
					SettingsToggleItem(
						checked = preferenceManager.isAdvancedTranscodingActive,
						onCheckedChange = { preferenceManager.isAdvancedTranscodingActive = it },
						content = { Text(stringResource(Res.string.option_stream_custom_quality)) },
						shapes = SegmentedListItemDefaults.segmentedShapes(index = 0, count = 1)
					)
				}

				AnimatedVisibility(visible = preferenceManager.isAdvancedTranscodingActive) {
					CustomOptions()
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
}

@Composable
private fun RadioButtons(
	value: StreamingQuality,
	onChangeValue: (StreamingQuality) -> Unit
) {
	StreamingQuality.entries.forEachIndexed { index, quality ->
		val selected = value == quality

		SettingsRadioItem(
			selected = selected,
			onClick = { onChangeValue(quality) },
			content = { Text(stringResource(quality.displayName)) },
			supportingContent = {
				quality.description()?.let { description ->
					AnimatedVisibility(visible = selected) {
						Text(description)
					}
				}
			},
			shapes = SegmentedListItemDefaults.segmentedShapes(
				index = index,
				count = StreamingQuality.entries.count()
			)
		)
	}
}

@Composable
private fun CustomOptions() {
	val preferenceManager = koinInject<PreferenceManager>()
	var wifiInput by remember {
		val current = preferenceManager.customMaxBitrateWifi
		mutableStateOf(if (current > 0) current.toString() else "")
	}
	var cellularInput by remember {
		val current = preferenceManager.customMaxBitrateCellular
		mutableStateOf(if (current > 0) current.toString() else "")
	}

	Column(
		modifier = Modifier.padding(16.dp),
		verticalArrangement = Arrangement.spacedBy(8.dp)
	) {
		OutlinedTextField(
			value = wifiInput,
			onValueChange = { newValue ->
				if (newValue.isEmpty() || newValue.all { it.isDigit() }) {
					wifiInput = newValue
					preferenceManager.customMaxBitrateWifi =
						newValue.toIntOrNull() ?: 0
				}
			},
			label = { Text(stringResource(Res.string.option_max_bitrate_wifi)) },
			placeholder = { Text("0") },
			keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
			modifier = Modifier.fillMaxWidth(),
			singleLine = true
		)

		OutlinedTextField(
			value = cellularInput,
			onValueChange = { newValue ->
				if (newValue.isEmpty() || newValue.all { it.isDigit() }) {
					cellularInput = newValue
					preferenceManager.customMaxBitrateCellular =
						newValue.toIntOrNull() ?: 0
				}
			},
			label = { Text(stringResource(Res.string.option_max_bitrate_cellular)) },
			placeholder = { Text("0") },
			keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
			modifier = Modifier.fillMaxWidth(),
			singleLine = true
		)

		OutlinedTextField(
			value = preferenceManager.customFormatWifi,
			onValueChange = { preferenceManager.customFormatWifi = it },
			label = { Text(stringResource(Res.string.option_stream_format_wifi)) },
			modifier = Modifier.fillMaxWidth(),
			singleLine = true
		)

		OutlinedTextField(
			value = preferenceManager.customFormatCellular,
			onValueChange = { preferenceManager.customFormatCellular = it },
			label = { Text(stringResource(Res.string.option_stream_format_cellular)) },
			supportingText = { Text(stringResource(Res.string.info_transcoding_defaults)) },
			modifier = Modifier.fillMaxWidth(),
			singleLine = true
		)
	}
}

/** navi-connect: download bitrates, kbps; 0 = the server's own file, untouched. */
private val DOWNLOAD_BITRATES = listOf(0, 320, 256, 192, 128)

/** navi-connect: download container. "" = whatever the original is; otherwise a transcode target. */
private val DOWNLOAD_FORMATS = listOf(
	"" to Res.string.option_download_format_original,
	"opus" to Res.string.option_download_format_opus,
	"mp3" to Res.string.option_download_format_mp3
)
