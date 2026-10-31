package paige.navic.ui.screens.settings

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.dropUnlessResumed
import navic.composeapp.generated.resources.Res
import navic.composeapp.generated.resources.option_audio_offload
import navic.composeapp.generated.resources.option_dynamic_replaygain_tip
import navic.composeapp.generated.resources.option_equaliser
import navic.composeapp.generated.resources.option_gapless_playback
import navic.composeapp.generated.resources.option_preamp_tip
import navic.composeapp.generated.resources.option_preamp_with_rg
import navic.composeapp.generated.resources.option_preamp_without_rg
import navic.composeapp.generated.resources.option_replaygain_mode
import navic.composeapp.generated.resources.option_title_preamp
import navic.composeapp.generated.resources.subtitle_audio_offload
import navic.composeapp.generated.resources.subtitle_equaliser
import navic.composeapp.generated.resources.subtitle_equaliser_disabled
import navic.composeapp.generated.resources.subtitle_gapless_playback
import navic.composeapp.generated.resources.title_output
import navic.composeapp.generated.resources.title_sound
import navic.composeapp.generated.resources.title_sound_advanced
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import paige.navic.di.LocalNavStack
import paige.navic.domain.manager.PreferenceManager
import paige.navic.domain.models.settings.ReplayGainMode
import paige.navic.icons.Icons
import paige.navic.icons.outlined.Info
import paige.navic.icons.outlined.KeyboardArrowDown
import paige.navic.shared.MediaPlayerViewModel
import paige.navic.ui.components.common.SegmentedListItem
import paige.navic.ui.components.common.SegmentedListItemDefaults
import paige.navic.ui.components.layouts.NestedTopBar
import paige.navic.ui.util.Fold
import paige.navic.ui.navigation.Screen
import paige.navic.ui.screens.settings.components.SettingsGroup
import paige.navic.ui.screens.settings.components.SettingsGroupDefaults
import paige.navic.ui.screens.settings.components.SettingsNavItem
import paige.navic.ui.screens.settings.components.SettingsRadioItem
import paige.navic.ui.screens.settings.components.SettingsSliderItem
import paige.navic.ui.screens.settings.components.SettingsToggleItem
import kotlin.math.round

@Composable
fun AudioEffectsScreen() {
	val preferenceManager = koinInject<PreferenceManager>()
	// The gain goes through the player, which resolves Dynamic by the same rule as at each song.
	val player = koinInject<MediaPlayerViewModel>()
	val backStack = LocalNavStack.current
	var advancedOpen by rememberSaveable { mutableStateOf(preferenceManager.audioOffload) }

	Scaffold(
		topBar = {
			NestedTopBar(
				title = { Text(stringResource(Res.string.title_sound)) }
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
				SettingsGroup(title = { Text(stringResource(Res.string.title_output)) }) {
					SettingsNavItem(
						onClick = dropUnlessResumed { backStack.add(Screen.Settings.Equaliser) },
						content = { Text(stringResource(Res.string.option_equaliser)) },
						enabled = !preferenceManager.audioOffload,
						supportingContent = {
							Text(
								text = stringResource(
									if (!preferenceManager.audioOffload)
										Res.string.subtitle_equaliser
									else Res.string.subtitle_equaliser_disabled
								)
							)
						},
						shapes = SegmentedListItemDefaults.segmentedShapes(index = 0, count = 2)
					)
					SettingsToggleItem(
						checked = preferenceManager.gaplessPlayback,
						onCheckedChange = { preferenceManager.gaplessPlayback = it },
						content = { Text(stringResource(Res.string.option_gapless_playback)) },
						supportingContent = { Text(stringResource(Res.string.subtitle_gapless_playback)) },
						shapes = SegmentedListItemDefaults.segmentedShapes(index = 1, count = 2)
					)
				}

				SettingsGroup(
					modifier = Modifier.selectableGroup(),
					title = { Text(stringResource(Res.string.option_replaygain_mode)) }
				) {
					ReplayGainMode.entries.forEachIndexed { index, mode ->
						SettingsRadioItem(
							selected = preferenceManager.replayGainMode == mode,
							onClick = {
								preferenceManager.replayGainMode = mode
								player.applyAudioGain()
							},
							content = { Text(stringResource(mode.displayName)) },
							shapes = SegmentedListItemDefaults.segmentedShapes(
								index = index,
								count = ReplayGainMode.entries.count()
							)
						)
					}
				}

				InformationTip(stringResource(Res.string.option_dynamic_replaygain_tip))

				SettingsGroup(title = { Text(stringResource(Res.string.option_title_preamp)) }) {
					SettingsSliderItem(
						content = { Text(stringResource(Res.string.option_preamp_with_rg)) },
						trailingContent = { Text(decibelsLabel(preferenceManager.rgAmpGain)) },
						value = preferenceManager.rgAmpGain,
						valueRange = -12f..12f,
						onValueChange = {
							preferenceManager.rgAmpGain = it.round(1)
							player.applyAudioGain()
						},
						shapes = SegmentedListItemDefaults.segmentedShapes(index = 0, count = 2)
					)
					SettingsSliderItem(
						content = { Text(stringResource(Res.string.option_preamp_without_rg)) },
						trailingContent = { Text(decibelsLabel(preferenceManager.ampGain)) },
						value = preferenceManager.ampGain,
						valueRange = -12f..12f,
						onValueChange = {
							preferenceManager.ampGain = it.round(1)
							player.applyAudioGain()
						},
						shapes = SegmentedListItemDefaults.segmentedShapes(index = 1, count = 2)
					)
				}

				InformationTip(stringResource(Res.string.option_preamp_tip))

				// Audio offload hands decoding to the phone's DSP: it switches the equaliser off. Few people
				// want it, so it is folded away, and open when it is already on.
				SettingsGroup {
					val turn by animateFloatAsState(if (advancedOpen) 180f else 0f)
					SegmentedListItem(
						onClick = { advancedOpen = !advancedOpen },
						shapes = SegmentedListItemDefaults.segmentedShapes(index = 0, count = if (advancedOpen) 2 else 1),
						content = { Text(stringResource(Res.string.title_sound_advanced)) },
						trailingContent = { Icon(Icons.Outlined.KeyboardArrowDown, null, Modifier.rotate(turn)) }
					)
					Fold(visible = advancedOpen) {
						SettingsToggleItem(
							checked = preferenceManager.audioOffload,
							onCheckedChange = { preferenceManager.audioOffload = it },
							content = { Text(stringResource(Res.string.option_audio_offload)) },
							supportingContent = { Text(stringResource(Res.string.subtitle_audio_offload)) },
							shapes = SegmentedListItemDefaults.segmentedShapes(index = 1, count = 2)
						)
					}
				}
			}
		}
	}
}


private fun Float.round(decimals: Int): Float {
	var multiplier = 1.0
	repeat(decimals) { multiplier *= 10 }
	return (round(this * multiplier) / multiplier).toFloat()
}

@Composable
private fun InformationTip(text: String) {
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
			text = text,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
			style = MaterialTheme.typography.bodyMedium
		)
	}
}
