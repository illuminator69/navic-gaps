package paige.navic.ui.screens.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.dropUnlessResumed
import kotlin.math.roundToInt
import kotlinx.collections.immutable.toImmutableList
import navic.composeapp.generated.resources.Res
import navic.composeapp.generated.resources.info_auto_dj_settings
import navic.composeapp.generated.resources.option_auto_dj_allow_duplicates
import navic.composeapp.generated.resources.title_auto_dj
import navic.composeapp.generated.resources.option_auto_dj_count
import navic.composeapp.generated.resources.option_auto_dj_only_similar
import navic.composeapp.generated.resources.option_auto_fill_queue
import navic.composeapp.generated.resources.option_enable_scrobbling
import navic.composeapp.generated.resources.option_explicit_playback
import navic.composeapp.generated.resources.option_min_duration_to_scrobble
import navic.composeapp.generated.resources.option_scrobble_percentage
import navic.composeapp.generated.resources.subtitle_audio_effects
import navic.composeapp.generated.resources.subtitle_auto_dj_only_similar
import navic.composeapp.generated.resources.subtitle_auto_fill_queue
import navic.composeapp.generated.resources.subtitle_enable_scrobbling
import navic.composeapp.generated.resources.subtitle_streaming_quality
import navic.composeapp.generated.resources.title_audio_effects
import navic.composeapp.generated.resources.title_behaviour
import navic.composeapp.generated.resources.title_playback
import navic.composeapp.generated.resources.title_streaming_quality
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import paige.navic.di.LocalNavStack
import paige.navic.di.LocalPlatformContext
import paige.navic.di.PlatformType
import paige.navic.domain.manager.PreferenceManager
import paige.navic.domain.models.settings.ExplicitContentPlayback
import paige.navic.ui.components.common.SegmentedListItemDefaults
import paige.navic.ui.components.layouts.NestedTopBar
import paige.navic.ui.components.layouts.NestedTopBarDefaults
import paige.navic.ui.navigation.Screen
import paige.navic.ui.screens.settings.components.SettingsChoiceItem
import paige.navic.ui.screens.settings.components.SettingsGroup
import paige.navic.ui.screens.settings.components.SettingsGroupDefaults
import paige.navic.ui.screens.settings.components.SettingsNavItem
import paige.navic.ui.screens.settings.components.SettingsSliderItem
import paige.navic.ui.screens.settings.components.SettingsToggleItem

@Composable
fun SettingsPlaybackScreen() {
	val platformContext = LocalPlatformContext.current
	val hideBack = platformContext.sizeClass.widthSizeClass >= WindowWidthSizeClass.Medium
	val backStack = LocalNavStack.current
	val preferenceManager = koinInject<PreferenceManager>()

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
				modifier = Modifier
					.padding(innerPadding)
					.verticalScroll(rememberScrollState())
					.padding(horizontal = 16.dp),
				verticalArrangement = Arrangement.spacedBy(SettingsGroupDefaults.GapBetweenGroups)
			) {
				SettingsGroup {
					val isAndroid = platformContext.platformType == PlatformType.Android
					val count = if (isAndroid) 4 else 2

					SettingsNavItem(
						onClick = dropUnlessResumed { backStack.add(Screen.Settings.StreamingQuality) },
						content = { Text(stringResource(Res.string.title_streaming_quality)) },
						supportingContent = { Text(stringResource(Res.string.subtitle_streaming_quality)) },
						shapes = SegmentedListItemDefaults.segmentedShapes(index = 0, count = count)
					)
					SettingsChoiceItem(
						choices = ExplicitContentPlayback.entries.toImmutableList(),
						selectedChoice = preferenceManager.explicitContentPlayback,
						onChoiceSelected = { preferenceManager.explicitContentPlayback = it },
						content = { Text(stringResource(Res.string.option_explicit_playback)) },
						label = { stringResource(it.displayName) },
						shapes = SegmentedListItemDefaults.segmentedShapes(index = 1, count = count)
					)

					if (isAndroid) {
						SettingsNavItem(
							onClick = dropUnlessResumed { backStack.add(Screen.Settings.Effects) },
							content = { Text(stringResource(Res.string.title_audio_effects)) },
							supportingContent = { Text(stringResource(Res.string.subtitle_audio_effects)) },
							shapes = SegmentedListItemDefaults.segmentedShapes(
								index = 2,
								count = count
							)
						)
						SettingsToggleItem(
							checked = preferenceManager.autoFillQueue,
							onCheckedChange = { preferenceManager.autoFillQueue = it },
							content = { Text(stringResource(Res.string.option_auto_fill_queue)) },
							supportingContent = { Text(stringResource(Res.string.subtitle_auto_fill_queue)) },
							shapes = SegmentedListItemDefaults.segmentedShapes(
								index = 3,
								count = count
							)
						)
					}
				}

				// navi-connect: Auto DJ's own settings (I-040). Autoplay itself is switched, and its
				// character chosen, by the chips in the queue (I-041); these apply when the hub has no
				// radio to offer and Auto DJ keeps the queue going instead.
				SettingsGroup(title = { Text(stringResource(Res.string.title_auto_dj)) }) {
					SettingsChoiceItem(
						choices = AUTO_DJ_COUNTS.toImmutableList(),
						selectedChoice = preferenceManager.autoDjItemCount,
						onChoiceSelected = { preferenceManager.autoDjItemCount = it },
						label = { it.toString() },
						description = stringResource(Res.string.info_auto_dj_settings),
						content = { Text(stringResource(Res.string.option_auto_dj_count)) },
						shapes = SegmentedListItemDefaults.segmentedShapes(index = 0, count = 3)
					)
					SettingsToggleItem(
						checked = preferenceManager.autoDjOnlySimilar,
						onCheckedChange = { preferenceManager.autoDjOnlySimilar = it },
						content = { Text(stringResource(Res.string.option_auto_dj_only_similar)) },
						supportingContent = { Text(stringResource(Res.string.subtitle_auto_dj_only_similar)) },
						shapes = SegmentedListItemDefaults.segmentedShapes(index = 1, count = 3)
					)
					SettingsToggleItem(
						checked = preferenceManager.autoDjAllowDuplicates,
						onCheckedChange = { preferenceManager.autoDjAllowDuplicates = it },
						content = { Text(stringResource(Res.string.option_auto_dj_allow_duplicates)) },
						shapes = SegmentedListItemDefaults.segmentedShapes(index = 2, count = 3)
					)
				}

				SettingsGroup(title = { Text(stringResource(Res.string.title_behaviour)) }) {
					val enableScrobbling = preferenceManager.enableScrobbling
					val count = if (enableScrobbling) 3 else 1

					SettingsToggleItem(
						checked = enableScrobbling,
						onCheckedChange = { preferenceManager.enableScrobbling = it },
						content = { Text(stringResource(Res.string.option_enable_scrobbling)) },
						supportingContent = { Text(stringResource(Res.string.subtitle_enable_scrobbling)) },
						shapes = SegmentedListItemDefaults.segmentedShapes(index = 0, count = count)
					)

					AnimatedVisibility(visible = enableScrobbling) {
						SettingsSliderItem(
							value = preferenceManager.scrobblePercentage,
							valueRange = 0f..1f,
							onValueChange = { preferenceManager.scrobblePercentage = it },
							trailingContent = { Text("${(preferenceManager.scrobblePercentage * 100).roundToInt()}%") },
							content = { Text(stringResource(Res.string.option_scrobble_percentage)) },
							shapes = SegmentedListItemDefaults.segmentedShapes(index = 1, count = count)
						)
					}

					AnimatedVisibility(visible = enableScrobbling) {

						SettingsSliderItem(
							value = preferenceManager.minDurationToScrobble,
							valueRange = 0f..60f,
							onValueChange = { preferenceManager.minDurationToScrobble = it },
							trailingContent = { Text("${preferenceManager.minDurationToScrobble.toInt()}s") },
							content = { Text(stringResource(Res.string.option_min_duration_to_scrobble)) },
							shapes = SegmentedListItemDefaults.segmentedShapes(index = 2, count = count)
						)
					}
				}
			}
		}
	}
}

/** Auto DJ's songs per top-up: upstream's default of 5, and the counts around it. */
private val AUTO_DJ_COUNTS = listOf(1, 3, 5, 10, 20)
