package paige.navic.ui.screens.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.dropUnlessResumed
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import navic.composeapp.generated.resources.Res
import navic.composeapp.generated.resources.info_auto_dj_fold
import navic.composeapp.generated.resources.option_auto_dj_allow_repeats
import navic.composeapp.generated.resources.option_auto_dj_count
import navic.composeapp.generated.resources.option_auto_dj_only_similar
import navic.composeapp.generated.resources.option_autoplay
import navic.composeapp.generated.resources.option_count_play_after
import navic.composeapp.generated.resources.option_explicit_songs
import navic.composeapp.generated.resources.option_ignore_tracks_shorter
import navic.composeapp.generated.resources.option_scrobble_plays
import navic.composeapp.generated.resources.subtitle_audio_effects
import navic.composeapp.generated.resources.subtitle_auto_dj_only_similar
import navic.composeapp.generated.resources.subtitle_autoplay
import navic.composeapp.generated.resources.subtitle_scrobble_plays
import navic.composeapp.generated.resources.title_autoplay
import navic.composeapp.generated.resources.title_auto_dj_fold
import navic.composeapp.generated.resources.title_content
import navic.composeapp.generated.resources.title_playback
import navic.composeapp.generated.resources.title_scrobbling
import navic.composeapp.generated.resources.title_sound
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import paige.navic.di.LocalNavStack
import paige.navic.di.LocalPlatformContext
import paige.navic.di.PlatformType
import paige.navic.domain.manager.PreferenceManager
import paige.navic.domain.manager.RadioManager
import paige.navic.domain.models.settings.ExplicitContentPlayback
import paige.navic.domain.models.settings.normalised
import paige.navic.icons.Icons
import paige.navic.icons.outlined.KeyboardArrowDown
import paige.navic.ui.components.common.SegmentedListItem
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
import paige.navic.ui.util.Fold

@Composable
fun SettingsPlaybackScreen() {
	val platformContext = LocalPlatformContext.current
	val hideBack = platformContext.sizeClass.widthSizeClass >= WindowWidthSizeClass.Medium
	val backStack = LocalNavStack.current
	val preferenceManager = koinInject<PreferenceManager>()
	val radioManager = koinInject<RadioManager>()
	val autoplayOn by radioManager.autoplayOn.collectAsState()
	var autoDjOpen by rememberSaveable { mutableStateOf(false) }

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
				// Autoplay is switched here, in the avatar menu and by the queue's chips: all three go through
				// RadioManager.setAutoplayOn, so they are one setting. The station's character is chosen by the
				// chips in the queue (I-041).
				SettingsGroup(title = { Text(stringResource(Res.string.title_autoplay)) }) {
					SettingsToggleItem(
						checked = autoplayOn,
						onCheckedChange = { radioManager.setAutoplayOn(it) },
						content = { Text(stringResource(Res.string.option_autoplay)) },
						supportingContent = { Text(stringResource(Res.string.subtitle_autoplay)) },
						shapes = SegmentedListItemDefaults.segmentedShapes(index = 0, count = 1)
					)
				}

				// navi-connect: Auto DJ's own settings (I-040). They apply only when the server has no radio
				// to offer and Auto DJ keeps the queue going instead, so they are folded away.
				SettingsGroup {
					val turn by animateFloatAsState(if (autoDjOpen) 180f else 0f)
					SegmentedListItem(
						onClick = { autoDjOpen = !autoDjOpen },
						shapes = SegmentedListItemDefaults.segmentedShapes(index = 0, count = if (autoDjOpen) 4 else 1),
						content = { Text(stringResource(Res.string.title_auto_dj_fold)) },
						supportingContent = { Text(stringResource(Res.string.info_auto_dj_fold)) },
						trailingContent = { Icon(Icons.Outlined.KeyboardArrowDown, null, Modifier.rotate(turn)) }
					)
					Fold(visible = autoDjOpen) {
						Column(verticalArrangement = Arrangement.spacedBy(SegmentedListItemDefaults.SegmentedGap)) {
							SettingsChoiceItem(
								choices = AUTO_DJ_COUNTS.toImmutableList(),
								selectedChoice = preferenceManager.autoDjItemCount,
								onChoiceSelected = { preferenceManager.autoDjItemCount = it },
								label = { it.toString() },
								content = { Text(stringResource(Res.string.option_auto_dj_count)) },
								shapes = SegmentedListItemDefaults.segmentedShapes(index = 1, count = 4)
							)
							SettingsToggleItem(
								checked = preferenceManager.autoDjOnlySimilar,
								onCheckedChange = { preferenceManager.autoDjOnlySimilar = it },
								content = { Text(stringResource(Res.string.option_auto_dj_only_similar)) },
								supportingContent = { Text(stringResource(Res.string.subtitle_auto_dj_only_similar)) },
								shapes = SegmentedListItemDefaults.segmentedShapes(index = 2, count = 4)
							)
							SettingsToggleItem(
								checked = preferenceManager.autoDjAllowDuplicates,
								onCheckedChange = { preferenceManager.autoDjAllowDuplicates = it },
								content = { Text(stringResource(Res.string.option_auto_dj_allow_repeats)) },
								shapes = SegmentedListItemDefaults.segmentedShapes(index = 3, count = 4)
							)
						}
					}
				}

				SettingsGroup(title = { Text(stringResource(Res.string.title_content)) }) {
					// "For this session" was never implemented, so only Play and Skip are offered, and a stored
					// "for this session" shows as Skip.
					SettingsChoiceItem(
						choices = persistentListOf(ExplicitContentPlayback.Allowed, ExplicitContentPlayback.Skip),
						selectedChoice = preferenceManager.explicitContentPlayback.normalised(),
						onChoiceSelected = { preferenceManager.explicitContentPlayback = it },
						content = { Text(stringResource(Res.string.option_explicit_songs)) },
						label = { stringResource(it.displayName) },
						shapes = SegmentedListItemDefaults.segmentedShapes(index = 0, count = 1)
					)
				}

				SettingsGroup(title = { Text(stringResource(Res.string.title_scrobbling)) }) {
					val enableScrobbling = preferenceManager.enableScrobbling
					val count = if (enableScrobbling) 3 else 1

					SettingsToggleItem(
						checked = enableScrobbling,
						onCheckedChange = { preferenceManager.enableScrobbling = it },
						content = { Text(stringResource(Res.string.option_scrobble_plays)) },
						supportingContent = { Text(stringResource(Res.string.subtitle_scrobble_plays)) },
						shapes = SegmentedListItemDefaults.segmentedShapes(index = 0, count = count)
					)

					AnimatedVisibility(visible = enableScrobbling) {
						// Ten steps: the slider offers 0, 10 ... 100 %. A value stored before that is shown as it is
						// (it is what a play is counted against) until the person moves the slider.
						val stored = preferenceManager.scrobblePercentage
						SettingsSliderItem(
							value = stored.coerceIn(0f, 1f),
							valueRange = 0f..1f,
							steps = 9,
							onValueChange = { preferenceManager.scrobblePercentage = snapToTenPercent(it) },
							trailingContent = { Text("${scrobblePercentShown(stored)}%") },
							content = { Text(stringResource(Res.string.option_count_play_after)) },
							shapes = SegmentedListItemDefaults.segmentedShapes(index = 1, count = count)
						)
					}

					AnimatedVisibility(visible = enableScrobbling) {
						// The length of the TRACK, not how long it was played (ScrobbleManager).
						SettingsSliderItem(
							value = preferenceManager.minDurationToScrobble,
							valueRange = 0f..60f,
							onValueChange = { preferenceManager.minDurationToScrobble = it },
							trailingContent = { Text("${preferenceManager.minDurationToScrobble.toInt()}s") },
							content = { Text(stringResource(Res.string.option_ignore_tracks_shorter)) },
							shapes = SegmentedListItemDefaults.segmentedShapes(index = 2, count = count)
						)
					}
				}

				if (platformContext.platformType == PlatformType.Android) {
					SettingsGroup {
						SettingsNavItem(
							onClick = dropUnlessResumed { backStack.add(Screen.Settings.Effects) },
							content = { Text(stringResource(Res.string.title_sound)) },
							supportingContent = { Text(stringResource(Res.string.subtitle_audio_effects)) },
							shapes = SegmentedListItemDefaults.segmentedShapes(index = 0, count = 1)
						)
					}
				}
			}
		}
	}
}

/** Auto DJ's songs per top-up: upstream's default of 5, and the counts around it. */
private val AUTO_DJ_COUNTS = listOf(1, 3, 5, 10, 20)
