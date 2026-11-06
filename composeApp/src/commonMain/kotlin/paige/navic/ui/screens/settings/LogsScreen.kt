package paige.navic.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import navic.composeapp.generated.resources.Res
import navic.composeapp.generated.resources.action_delete
import navic.composeapp.generated.resources.action_share_logs
import navic.composeapp.generated.resources.action_logs_copy_all
import navic.composeapp.generated.resources.action_logs_follow
import navic.composeapp.generated.resources.action_logs_unfollow
import navic.composeapp.generated.resources.hint_logs_filter
import navic.composeapp.generated.resources.info_logs_empty
import navic.composeapp.generated.resources.title_logs
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import paige.navic.di.LocalPlatformContext
import paige.navic.domain.manager.HubManager
import paige.navic.domain.manager.LogManager
import paige.navic.domain.manager.ShareManager
import paige.navic.generated.BuildInfo
import paige.navic.util.LogsHeader
import paige.navic.util.logsFileName
import paige.navic.util.logsFileText
import paige.navic.util.redact
import paige.navic.util.versionLine
import kotlin.time.Clock
import paige.navic.domain.parser.LogLine
import paige.navic.icons.Icons
import paige.navic.icons.outlined.Close
import paige.navic.icons.outlined.Copy
import paige.navic.icons.outlined.Delete
import paige.navic.icons.outlined.KeyboardArrowDown
import paige.navic.icons.outlined.Search
import paige.navic.icons.outlined.Share
import paige.navic.ui.components.layouts.NestedTopBar
import paige.navic.ui.components.layouts.TopBarButton
import androidx.compose.runtime.rememberCoroutineScope

@Composable
fun SettingsLogsScreen() {
	val logManager = koinInject<LogManager>()
	val shareManager = koinInject<ShareManager>()
	val hubManager = koinInject<HubManager>()
	val platformContext = LocalPlatformContext.current
	val logs = logManager.logs
	val listState = rememberLazyListState()
	val scope = rememberCoroutineScope()

	@Suppress("DEPRECATION")
	val clipboardManager = LocalClipboardManager.current

	var filter by remember { mutableStateOf("") }
	val visible by remember(logs) {
		derivedStateOf {
			if (filter.isBlank()) logs.toList()
			else logs.filter { it.rawText.contains(filter, ignoreCase = true) }
		}
	}

	DisposableEffect(Unit) {
		logManager.startStreaming()
		onDispose {
			logManager.stopStreaming()
		}
	}

	// Follow the tail only while the list is actually AT the tail. Scrolling up used to be
	// pointless: every new line yanked the view back down, so a busy tag made the screen
	// impossible to read. Scrolling up now parks the view; the button below returns to live.
	val atBottom by remember { derivedStateOf { !listState.canScrollForward } }
	LaunchedEffect(visible.size, atBottom) {
		if (atBottom && visible.isNotEmpty()) {
			listState.requestScrollToItem(visible.lastIndex)
		}
	}

	Scaffold(
		topBar = {
			NestedTopBar(
				title = { Text(stringResource(Res.string.title_logs)) },
				actions = {
					TopBarButton(
						onClick = {
							scope.launch {
								if (visible.isNotEmpty()) {
									listState.animateScrollToItem(visible.lastIndex)
								}
							}
						},
						enabled = !atBottom
					) {
						Icon(
							Icons.Outlined.KeyboardArrowDown,
							stringResource(
								if (atBottom) Res.string.action_logs_unfollow
								else Res.string.action_logs_follow
							)
						)
					}
					// Worbus P4 N3: the whole buffer (up to 500 lines), redacted, as a text file under a
					// header naming this build, the phone and the server's version.
					TopBarButton(
						onClick = {
							val header = LogsHeader(
								appVersion = versionLine(platformContext.appVersion, BuildInfo.GIT_COMMIT, release = !platformContext.isDebugBuild),
								androidVersion = platformContext.osVersion,
								deviceModel = platformContext.deviceModel,
								serverVersion = hubManager.serverVersion.value
							)
							val text = logsFileText(header, logs.map { it.rawText })
							val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
							val name = logsFileName(now.year, now.month.ordinal + 1, now.day, now.hour, now.minute, now.second)
							scope.launch { shareManager.shareTextFile(text, name) }
						},
						enabled = logs.isNotEmpty()
					) {
						Icon(
							Icons.Outlined.Share,
							stringResource(Res.string.action_share_logs)
						)
					}
					TopBarButton(
						onClick = {
							// Copied text leaves the app as surely as a shared file: the same rule.
							clipboardManager.setText(
								AnnotatedString(visible.joinToString("\n") { redact(it.rawText) })
							)
						},
						enabled = visible.isNotEmpty()
					) {
						Icon(
							Icons.Outlined.Copy,
							stringResource(Res.string.action_logs_copy_all)
						)
					}
					TopBarButton(
						onClick = { logManager.clearLogs() },
						enabled = logs.isNotEmpty()
					) {
						Icon(Icons.Outlined.Delete, stringResource(Res.string.action_delete))
					}
				}
			)
		}
	) { innerPadding ->
		Column(modifier = Modifier.padding(innerPadding)) {
			OutlinedTextField(
				value = filter,
				onValueChange = { filter = it },
				modifier = Modifier
					.fillMaxWidth()
					.padding(horizontal = 12.dp, vertical = 4.dp),
				placeholder = { Text(stringResource(Res.string.hint_logs_filter)) },
				leadingIcon = { Icon(Icons.Outlined.Search, null) },
				trailingIcon = {
					if (filter.isNotEmpty()) {
						TopBarButton(onClick = { filter = "" }) {
							Icon(Icons.Outlined.Close, null)
						}
					}
				},
				singleLine = true
			)

			if (visible.isEmpty() && filter.isNotBlank()) {
				Text(
					text = stringResource(Res.string.info_logs_empty),
					style = MaterialTheme.typography.bodyMedium,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
				)
			}

			CompositionLocalProvider(
				LocalMinimumInteractiveComponentSize provides 0.dp
			) {
				// Selection across lines, rather than one-line-at-a-time copying. Lines wrap
				// instead of living on a horizontal scroller, so a long message is readable
				// without dragging the whole list sideways.
				SelectionContainer {
					LazyColumn(state = listState) {
						items(visible) { line ->
							LogLineRow(line = line)
						}
					}
				}
			}
		}
	}
}

@Composable
private fun LogLineRow(
	line: LogLine
) {
	Row(
		modifier = Modifier.padding(horizontal = 3.dp, vertical = 1.5.dp),
		verticalAlignment = Alignment.Top
	) {
		// The severity chip is decoration. Excluding it means a dragged selection yields
		// pasteable log text instead of "IWID" running down the margin.
		DisableSelection {
			Box(
				modifier = Modifier
					.size(22.dp)
					.clip(MaterialTheme.shapes.extraSmall)
					.background(line.type.backgroundColor()),
				contentAlignment = Alignment.Center
			) {
				Text(
					text = line.type.name.first().toString(),
					fontSize = 12.sp,
					color = line.type.contentColor()
				)
			}
		}

		Text(
			text = line.text,
			fontFamily = FontFamily.Monospace,
			fontSize = 12.sp,
			modifier = Modifier.padding(start = 6.dp)
		)
	}
}
