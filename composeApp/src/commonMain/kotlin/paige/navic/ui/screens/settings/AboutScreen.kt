package paige.navic.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import navic.composeapp.generated.resources.Res
import navic.composeapp.generated.resources.info_app_version
import navic.composeapp.generated.resources.action_copy_logs_for_report
import navic.composeapp.generated.resources.option_check_for_updates
import navic.composeapp.generated.resources.subtitle_check_for_updates
import navic.composeapp.generated.resources.title_about
import navic.composeapp.generated.resources.title_source_code
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import paige.navic.di.LocalNavStack
import paige.navic.di.LocalPlatformContext
import paige.navic.di.PlatformType
import paige.navic.domain.manager.PreferenceManager
import paige.navic.generated.BuildInfo
import androidx.lifecycle.compose.dropUnlessResumed
import paige.navic.ui.components.sheets.updateChecksOffered
import paige.navic.icons.Icons
import paige.navic.icons.outlined.ChevronForward
import paige.navic.ui.components.common.SegmentedListItem
import paige.navic.ui.components.common.SegmentedListItemDefaults
import paige.navic.ui.components.dialogs.LinkConfirmationDialog
import paige.navic.ui.components.layouts.NestedTopBar
import paige.navic.ui.components.layouts.NestedTopBarDefaults
import paige.navic.ui.navigation.Screen
import paige.navic.ui.screens.settings.components.SettingsGroup
import paige.navic.ui.screens.settings.components.SettingsGroupDefaults
import paige.navic.ui.screens.settings.components.SettingsNavItem
import paige.navic.ui.screens.settings.components.SettingsToggleItem
import paige.navic.util.versionLine

/** This fork's repository: the one link About offers (upstream's repository, Codeberg mirror and Discord are not ours). */
private const val SOURCE_CODE_URL = "https://github.com/illuminator69/navic-gaps"

/** Every link About offers. */
val aboutLinks = listOf(SOURCE_CODE_URL)

@Composable
fun SettingsAboutScreen() {
	val preferenceManager = koinInject<PreferenceManager>()
	val backStack = LocalNavStack.current

	@Suppress("DEPRECATION")
	val clipboard = LocalClipboardManager.current
	val platformContext = LocalPlatformContext.current
	val hideBack = platformContext.sizeClass.widthSizeClass >= WindowWidthSizeClass.Medium
	var linkToOpen by rememberSaveable { mutableStateOf<String?>(null) }

	Scaffold(
		topBar = {
			NestedTopBar(
				title = { Text(stringResource(Res.string.title_about)) },
				navigationAction = {
					if (!hideBack) {
						NestedTopBarDefaults.NavigationAction()
					}
				}
			)
		}
	) { innerPadding ->
		Column(
			modifier = Modifier
				.padding(innerPadding)
				.verticalScroll(rememberScrollState())
				.padding(horizontal = 16.dp),
			verticalArrangement = Arrangement.spacedBy(SettingsGroupDefaults.GapBetweenGroups)
		) {
			SettingsGroup {
				val text = buildString {
					append(platformContext.name + "\n")
					append(
						stringResource(
							Res.string.info_app_version,
							versionLine(platformContext.appVersion, BuildInfo.GIT_COMMIT, release = !platformContext.isDebugBuild)
						)
					)
				}
				SegmentedListItem(
					onClick = { clipboard.setText(AnnotatedString(text)) },
					shapes = SegmentedListItemDefaults.segmentedShapes(index = 0, count = 1),
					content = { Text(text) }
				)
			}

			SettingsGroup {
				val count = if (platformContext.platformType == PlatformType.Android) 2 else 1
				SettingsNavItem(
					onClick = { linkToOpen = SOURCE_CODE_URL },
					shapes = SegmentedListItemDefaults.segmentedShapes(index = 0, count = count),
					content = { Text(stringResource(Res.string.title_source_code)) }
				)
				if (platformContext.platformType == PlatformType.Android) {
					// The Logs page holds the copy-all button; a release build has no Developer options to reach it from.
					SettingsNavItem(
						onClick = dropUnlessResumed {
							backStack.lastOrNull()?.let {
								if (it is Screen.Settings.About) {
									backStack.add(Screen.Settings.Logs)
								}
							}
						},
						shapes = SegmentedListItemDefaults.segmentedShapes(index = 1, count = count),
						content = { Text(stringResource(Res.string.action_copy_logs_for_report)) }
					)
				}
			}

			// Only a debug build checks for updates (updateChecksOffered); a release has no such row.
			if (updateChecksOffered(
					platformContext.platformType,
					fdroid = BuildInfo.FDROID,
					debugBuild = platformContext.isDebugBuild
				)
			) {
				SettingsGroup {
					SettingsToggleItem(
						content = { Text(stringResource(Res.string.option_check_for_updates)) },
						supportingContent = { Text(stringResource(Res.string.subtitle_check_for_updates)) },
						checked = preferenceManager.checkForUpdates,
						onCheckedChange = { preferenceManager.checkForUpdates = it },
						shapes = SegmentedListItemDefaults.segmentedShapes(index = 0, count = 1)
					)
				}
			}
		}
	}

	if (linkToOpen != null) {
		LinkConfirmationDialog(
			linkToOpen = linkToOpen!!,
			onDismissRequest = { linkToOpen = null }
		)
	}
}
