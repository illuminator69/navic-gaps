package paige.navic.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.dropUnlessResumed
import kotlinx.collections.immutable.toImmutableList
import navic.composeapp.generated.resources.Res
import navic.composeapp.generated.resources.option_audio_offload
import navic.composeapp.generated.resources.option_auto_fill_queue
import navic.composeapp.generated.resources.option_enable_scrobbling
import navic.composeapp.generated.resources.option_equaliser
import navic.composeapp.generated.resources.option_explicit_playback
import navic.composeapp.generated.resources.option_gapless_playback
import navic.composeapp.generated.resources.option_min_duration_to_scrobble
import navic.composeapp.generated.resources.option_replay_gain
import navic.composeapp.generated.resources.option_scrobble_percentage
import navic.composeapp.generated.resources.subtitle_audio_offload
import navic.composeapp.generated.resources.subtitle_auto_fill_queue
import navic.composeapp.generated.resources.subtitle_enable_scrobbling
import navic.composeapp.generated.resources.subtitle_equaliser
import navic.composeapp.generated.resources.subtitle_equaliser_disabled
import navic.composeapp.generated.resources.subtitle_gapless_playback
import navic.composeapp.generated.resources.subtitle_streaming_quality
import navic.composeapp.generated.resources.title_behaviour
import navic.composeapp.generated.resources.title_playback
import navic.composeapp.generated.resources.title_streaming_quality
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import paige.navic.domain.manager.AudioMuseManager
import paige.navic.domain.manager.PreferenceManager
import paige.navic.domain.manager.RadioManager
import paige.navic.domain.models.settings.AutoplayMode
import paige.navic.domain.models.settings.ExplicitContentPlayback
import paige.navic.domain.models.settings.MoodCharacter
import paige.navic.domain.models.settings.ReplayGainMode
import paige.navic.icons.Icons
import paige.navic.icons.outlined.ChevronForward
import paige.navic.ui.components.common.Form
import paige.navic.ui.components.common.FormRow
import paige.navic.ui.components.common.FormTitle
import paige.navic.ui.components.layouts.NestedTopBar
import paige.navic.ui.components.layouts.NestedTopBarDefaults
import paige.navic.ui.navigation.Screen
import paige.navic.ui.screens.settings.components.SettingSelectionRow
import paige.navic.ui.screens.settings.components.SettingSwitchRow
import paige.navic.di.PlatformType
import kotlin.math.roundToInt
import paige.navic.di.LocalNavStack
import paige.navic.di.LocalPlatformContext

@Composable
fun SettingsPlaybackScreen() {
	val platformContext = LocalPlatformContext.current
	val hideBack = platformContext.sizeClass.widthSizeClass >= WindowWidthSizeClass.Medium
	val backStack = LocalNavStack.current
	var showLyricsPriorityDialog by rememberSaveable { mutableStateOf(false) }
	val preferenceManager = koinInject<PreferenceManager>()
	val audioMuseManager = koinInject<AudioMuseManager>()
	val radioManager = koinInject<RadioManager>()
	val autoplayMode by radioManager.autoplayMode.collectAsState()

	Scaffold(
		topBar = {
			NestedTopBar(
				title = { Text(stringResource(Res.string.title_playback)) },
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
				Modifier
					.padding(innerPadding)
					.verticalScroll(rememberScrollState())
					.padding(top = 16.dp, end = 16.dp, start = 16.dp)
			) {
				Form {
					FormRow(
						onClick = dropUnlessResumed { backStack.add(Screen.Settings.StreamingQuality) },
						horizontalArrangement = Arrangement.Start
					) {
						Column(Modifier.weight(1f)) {
							Text(stringResource(Res.string.title_streaming_quality))
							Text(
								text = stringResource(Res.string.subtitle_streaming_quality),
								style = MaterialTheme.typography.bodyMedium,
								color = MaterialTheme.colorScheme.onSurfaceVariant
							)
						}
						Icon(Icons.Outlined.ChevronForward, null)
					}
					SettingSelectionRow(
						title = { Text("Autoplay") },
						description = "keep the queue topped up when it runs out",
						// Fingerprint + Mood Flow need the AudioMuse core API (Tier 2).
						items = (if (audioMuseManager.isConfigured) {
							listOf(
								AutoplayMode.Off,
								AutoplayMode.Similar,
								AutoplayMode.Fingerprint,
								AutoplayMode.Adaptive,
							)
						} else {
							listOf(AutoplayMode.Off, AutoplayMode.Similar)
						}).toImmutableList(),
						label = { it.label },
						selection = autoplayMode,
						onSelect = { radioManager.setAutoplayMode(it) }
					)
					if (audioMuseManager.isConfigured) {
						SettingSelectionRow(
							title = { Text("Mood Flow character") },
							description = "how the adaptive mix reacts to skips",
							items = MoodCharacter.entries.toImmutableList(),
							label = { it.label },
							selection = preferenceManager.moodCharacter,
							onSelect = { preferenceManager.moodCharacter = it }
						)
					}
					if (platformContext.platformType == PlatformType.Android) {
						SettingSelectionRow(
							title = { Text(stringResource(Res.string.option_replay_gain)) },
							items = ReplayGainMode.entries.toImmutableList(),
							label = { stringResource(it.displayName) },
							selection = preferenceManager.replayGainMode,
							onSelect = { preferenceManager.replayGainMode = it }
						)
						FormRow(
							onClick = dropUnlessResumed { backStack.add(Screen.Settings.Equaliser) },
							horizontalArrangement = Arrangement.Start,
							enabled = !preferenceManager.audioOffload
						) {
							Column(Modifier.weight(1f)) {
								Text(stringResource(Res.string.option_equaliser))
								Text(
									text = stringResource(
										if (!preferenceManager.audioOffload)
											Res.string.subtitle_equaliser
										else Res.string.subtitle_equaliser_disabled
									),
									style = MaterialTheme.typography.bodyMedium,
									color = MaterialTheme.colorScheme.onSurfaceVariant
								)
							}
							Icon(Icons.Outlined.ChevronForward, null)
						}
						SettingSwitchRow(
							title = { Text(stringResource(Res.string.option_gapless_playback)) },
							subtitle = { Text(stringResource(Res.string.subtitle_gapless_playback)) },
							value = preferenceManager.gaplessPlayback,
							onSetValue = { preferenceManager.gaplessPlayback = it }
						)
						SettingSwitchRow(
							title = { Text(stringResource(Res.string.option_audio_offload)) },
							subtitle = { Text(stringResource(Res.string.subtitle_audio_offload)) },
							value = preferenceManager.audioOffload,
							onSetValue = { preferenceManager.audioOffload = it }
						)
					}
					SettingSelectionRow(
						title = { Text(stringResource(Res.string.option_explicit_playback)) },
						label = { stringResource(it.displayName) },
						items = ExplicitContentPlayback.entries.toImmutableList(),
						selection = preferenceManager.explicitContentPlayback,
						onSelect = { preferenceManager.explicitContentPlayback = it }
					)
					if (platformContext.platformType == PlatformType.Android) {
						SettingSwitchRow(
							title = { Text(stringResource(Res.string.option_auto_fill_queue)) },
							subtitle = { Text(stringResource(Res.string.subtitle_auto_fill_queue)) },
							value = preferenceManager.autoFillQueue,
							onSetValue = { preferenceManager.autoFillQueue = it }
						)
					}
				}

				FormTitle(stringResource(Res.string.title_behaviour))
				Form {
					SettingSwitchRow(
						title = { Text(stringResource(Res.string.option_enable_scrobbling)) },
						subtitle = { Text(stringResource(Res.string.subtitle_enable_scrobbling)) },
						value = preferenceManager.enableScrobbling,
						onSetValue = { preferenceManager.enableScrobbling = it }
					)

					FormRow {
						Column(Modifier.fillMaxWidth()) {
							Row(
								modifier = Modifier.fillMaxWidth(),
								horizontalArrangement = Arrangement.SpaceBetween
							) {
								Text(stringResource(Res.string.option_scrobble_percentage))
								Text(
									"${(preferenceManager.scrobblePercentage * 100).roundToInt()}%",
									fontFamily = FontFamily.Monospace,
									fontWeight = FontWeight(400),
									fontSize = 13.sp,
									color = MaterialTheme.colorScheme.onSurfaceVariant,
								)
							}
							Slider(
								value = preferenceManager.scrobblePercentage,
								onValueChange = {
									preferenceManager.scrobblePercentage = it
								},
								valueRange = 0f..1f,
							)
						}
					}
					FormRow {
						Column(Modifier.fillMaxWidth()) {
							Row(
								modifier = Modifier.fillMaxWidth(),
								horizontalArrangement = Arrangement.SpaceBetween
							) {
								Text(stringResource(Res.string.option_min_duration_to_scrobble))
								Text(
									"${preferenceManager.minDurationToScrobble.toInt()}s",
									fontFamily = FontFamily.Monospace,
									fontWeight = FontWeight(400),
									fontSize = 13.sp,
									color = MaterialTheme.colorScheme.onSurfaceVariant,
								)
							}
							Slider(
								value = preferenceManager.minDurationToScrobble,
								onValueChange = {
									preferenceManager.minDurationToScrobble = it
								},
								valueRange = 0f..400f,
							)
						}
					}
				}
			}
		}
	}
}
