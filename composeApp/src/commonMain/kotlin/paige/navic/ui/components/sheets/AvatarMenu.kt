package paige.navic.ui.components.sheets

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import androidx.navigation3.runtime.NavKey
import com.russhwolf.settings.Settings
import navic.composeapp.generated.resources.Res
import navic.composeapp.generated.resources.action_log_out
import navic.composeapp.generated.resources.action_view_shares
import navic.composeapp.generated.resources.option_attention_dot
import navic.composeapp.generated.resources.option_autoplay_default
import navic.composeapp.generated.resources.title_about
import navic.composeapp.generated.resources.title_app
import navic.composeapp.generated.resources.title_appearance
import navic.composeapp.generated.resources.title_downloads
import navic.composeapp.generated.resources.title_duplicates_trash
import navic.composeapp.generated.resources.title_lbbot_logs
import navic.composeapp.generated.resources.title_source_history
import navic.composeapp.generated.resources.title_source_rules
import navic.composeapp.generated.resources.title_library_matches
import navic.composeapp.generated.resources.title_library_tools
import navic.composeapp.generated.resources.title_listening
import navic.composeapp.generated.resources.title_needs_attention
import navic.composeapp.generated.resources.title_saved_queues
import navic.composeapp.generated.resources.title_settings
import navic.composeapp.generated.resources.title_statistics
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import paige.navic.di.LocalNavStack
import paige.navic.domain.manager.menuAttentionBadge
import paige.navic.domain.manager.LbBotManager
import paige.navic.domain.manager.LoginManager
import paige.navic.domain.manager.PreferenceManager
import paige.navic.domain.manager.RadioManager
import paige.navic.domain.manager.SessionManager
import paige.navic.domain.manager.ShellController
import paige.navic.domain.manager.canUserShare
import paige.navic.icons.Icons
import paige.navic.icons.brand.Musicbrainz
import paige.navic.icons.filled.Palette
import paige.navic.icons.filled.Settings
import paige.navic.icons.outlined.Code
import paige.navic.icons.outlined.Copy
import paige.navic.icons.outlined.Download
import paige.navic.icons.outlined.History
import paige.navic.icons.outlined.Error
import paige.navic.icons.outlined.Info
import paige.navic.icons.outlined.KeyboardArrowDown
import paige.navic.icons.outlined.Link
import paige.navic.icons.outlined.Logout
import paige.navic.icons.outlined.PlaylistPlay
import paige.navic.icons.outlined.Queue
import paige.navic.icons.outlined.Share
import paige.navic.icons.outlined.Sort
import paige.navic.icons.outlined.Statistics
import paige.navic.ui.components.common.Monogram
import paige.navic.ui.navigation.Screen
import paige.navic.ui.theme.NavicTheme
import paige.navic.ui.theme.defaultFont
import paige.navic.ui.theme.warning
import paige.navic.ui.util.Fold
import paige.navic.ui.util.rememberFoldMotion
import paige.navic.ui.util.rememberNowPlayingCoverAmbient

/*
 * navi-connect P1: the avatar menu. It holds what the four-button bar does not: the groups (since P6
 * lb-bot's Needs attention and Source history are the first rows of Library tools), Settings, Shares
 * where the user may make them, and Log out. What is in it, in what order, where each row goes and which groups fold is decided as
 * data by [avatarMenuItems] (`AvatarMenuItemsTest`); [AvatarMenuDrawer] only draws it.
 *
 * Hosted once, in App.kt, and opened from any header avatar through [ShellController]. It replaces
 * the old account sheet; the sleep timer that sheet held is in the player's song sheet.
 */

/** Row and group ids, so the data and its test name the same things. */
internal object AvatarMenuIds {
	const val ATTENTION = "attention"
	const val SOURCE_HISTORY = "sourceHistory"
	const val LISTENING = "listening"
	const val STATISTICS = "statistics"
	const val SAVED_QUEUES = "savedQueues"
	const val LIBRARY_TOOLS = "libraryTools"
	const val LIBRARY_MATCHES = "libraryMatches"
	const val DUPLICATES = "duplicates"
	const val SOURCE_RULES = "sourceRules"
	const val LOGS = "logs"
	const val APP = "app"
	const val AUTOPLAY = "autoplay"
	const val DOWNLOADS = "downloads"
	const val APPEARANCE = "appearance"
	const val ATTENTION_DOT = "attentionDot"
	const val ABOUT = "about"
	const val SETTINGS = "settings"
	const val SHARES = "shares"
	const val LOG_OUT = "logOut"
}

/** Whether a group rolls out and in, and which way it starts. */
internal enum class GroupFold { Never, StartsOpen, StartsClosed }

internal sealed interface AvatarMenuEntry {
	val id: String

	/** A row that opens a page. [badge] is a count on its right, absent for none. */
	data class Link(
		override val id: String,
		val icon: ImageVector,
		val label: StringResource,
		val destination: NavKey,
		val badge: Int? = null
	) : AvatarMenuEntry

	/** A switch; what it is bound to is the drawer's business ([AvatarMenuContent]). */
	data class Toggle(
		override val id: String,
		val icon: ImageVector,
		val label: StringResource
	) : AvatarMenuEntry

	/**
	 * A row that opens lb-bot's own web UI at [path] (`webUrl + "/#/" + path`): the rare tools that
	 * have no screen here (P6). Drawn only while the hub advertises a `webUrl`.
	 */
	data class External(
		override val id: String,
		val icon: ImageVector,
		val label: StringResource,
		val path: String
	) : AvatarMenuEntry

	/** A row that does something rather than going somewhere: Log out. */
	data class Action(
		override val id: String,
		val icon: ImageVector,
		val label: StringResource
	) : AvatarMenuEntry

	data class Group(
		override val id: String,
		val label: StringResource,
		val entries: List<AvatarMenuEntry>,
		val fold: GroupFold
	) : AvatarMenuEntry
}

/**
 * The menu, top to bottom (P6, the owner's ruling of 2026-10-21). lb-bot's operator half lives in
 * **Library tools**: Needs attention (with lb-bot's to-fix count), Source history (requests,
 * transfers, the wishlist), Library Matches (which holds artist matching and the overrides), then
 * the rare tools that open lb-bot's web UI, drawn only when the hub says where it is ([webUrl]).
 * Nothing that needs lb-bot is drawn without it, so there is no gap and no dead row.
 */
internal fun avatarMenuItems(
	lbConfigured: Boolean,
	canShare: Boolean,
	attentionCount: Int,
	webUrl: String = ""
): List<AvatarMenuEntry> = buildList {
	add(
		AvatarMenuEntry.Group(
			AvatarMenuIds.LISTENING, Res.string.title_listening,
			listOf(
				AvatarMenuEntry.Link(
					AvatarMenuIds.STATISTICS, Icons.Outlined.Statistics, Res.string.title_statistics,
					Screen.Statistics(nested = true)
				),
				AvatarMenuEntry.Link(
					AvatarMenuIds.SAVED_QUEUES, Icons.Outlined.Queue, Res.string.title_saved_queues,
					Screen.SavedQueues
				)
			),
			GroupFold.Never
		)
	)
	if (lbConfigured) {
		val web = webUrl.isNotBlank()
		add(
			AvatarMenuEntry.Group(
				AvatarMenuIds.LIBRARY_TOOLS, Res.string.title_library_tools,
				listOfNotNull(
					AvatarMenuEntry.Link(
						AvatarMenuIds.ATTENTION, Icons.Outlined.Error, Res.string.title_needs_attention,
						Screen.Attention, badge = attentionCount.takeIf { it > 0 }
					),
					AvatarMenuEntry.Link(
						AvatarMenuIds.SOURCE_HISTORY, Icons.Outlined.History, Res.string.title_source_history,
						Screen.SourceHistory()
					),
					AvatarMenuEntry.Link(
						AvatarMenuIds.LIBRARY_MATCHES, Icons.Brand.Musicbrainz,
						Res.string.title_library_matches, Screen.LibraryMatches
					),
					if (web) AvatarMenuEntry.External(
						AvatarMenuIds.DUPLICATES, Icons.Outlined.Copy, Res.string.title_duplicates_trash,
						"library/cleanup"
					) else null,
					if (web) AvatarMenuEntry.External(
						AvatarMenuIds.SOURCE_RULES, Icons.Outlined.Sort, Res.string.title_source_rules,
						"system/sources"
					) else null,
					if (web) AvatarMenuEntry.External(
						AvatarMenuIds.LOGS, Icons.Outlined.Code, Res.string.title_lbbot_logs,
						"system/logs"
					) else null
				),
				GroupFold.StartsOpen
			)
		)
	}
	add(
		AvatarMenuEntry.Group(
			AvatarMenuIds.APP, Res.string.title_app,
			listOf(
				AvatarMenuEntry.Toggle(AvatarMenuIds.AUTOPLAY, Icons.Outlined.PlaylistPlay, Res.string.option_autoplay_default),
				AvatarMenuEntry.Link(
					AvatarMenuIds.DOWNLOADS, Icons.Outlined.Download, Res.string.title_downloads,
					Screen.Downloads()
				),
				AvatarMenuEntry.Link(
					AvatarMenuIds.APPEARANCE, Icons.Filled.Palette, Res.string.title_appearance,
					Screen.Settings.Appearance
				),
				AvatarMenuEntry.Toggle(AvatarMenuIds.ATTENTION_DOT, Icons.Outlined.Error, Res.string.option_attention_dot),
				AvatarMenuEntry.Link(
					AvatarMenuIds.ABOUT, Icons.Outlined.Info, Res.string.title_about,
					Screen.Settings.About
				)
			),
			GroupFold.StartsClosed
		)
	)
	add(
		AvatarMenuEntry.Link(
			AvatarMenuIds.SETTINGS, Icons.Filled.Settings, Res.string.title_settings,
			Screen.Settings.Root
		)
	)
	if (canShare) {
		add(
			AvatarMenuEntry.Link(
				AvatarMenuIds.SHARES, Icons.Outlined.Share, Res.string.action_view_shares,
				Screen.ShareList
			)
		)
	}
	add(AvatarMenuEntry.Action(AvatarMenuIds.LOG_OUT, Icons.Outlined.Logout, Res.string.action_log_out))
}

private val MenuShape = RoundedCornerShape(topEnd = 26.dp, bottomEnd = 26.dp)

/**
 * Wraps the app's content in a modal drawer that [ShellController.avatarMenuOpen] drives, both ways:
 * the controller opens and closes it, and a scrim tap or a Back that closes it tells the controller.
 *
 * **No edge swipe to open**: the system back gesture owns the edge, so gestures are on only while the
 * menu is open (they are also what makes the scrim tap and a drag to close work: Material gates the
 * scrim's tap on them). The sheet is transparent over a gradient from the playing cover's ambient,
 * and its content is themed from that ambient, as every sheet's is.
 */
@Composable
fun AvatarMenuDrawer(content: @Composable () -> Unit) {
	val shell = koinInject<ShellController>()
	val open by shell.avatarMenuOpen.collectAsState()
	val drawerState = rememberDrawerState(DrawerValue.Closed)

	val lbBot = koinInject<LbBotManager>()
	LaunchedEffect(open) {
		if (open) drawerState.open() else drawerState.close()
	}
	// P6: opening the menu is one of the moments the hub-wide count is read again (at most every
	// 30 s). The drawer's content is composed while closed too, so it is the open state that asks.
	LaunchedEffect(open) {
		if (open) lbBot.refreshAttention()
	}
	// The other way: the scrim, a drag or Back closing the drawer says so to the controller. The
	// TARGET, not the current value, so a drawer on its way open is not read as closed.
	LaunchedEffect(drawerState) {
		snapshotFlow { drawerState.targetValue }.collect {
			if (it == DrawerValue.Closed) shell.closeAvatarMenu()
		}
	}

	val ambient = rememberNowPlayingCoverAmbient()
	ModalNavigationDrawer(
		drawerContent = {
			ModalDrawerSheet(
				modifier = Modifier
					.width(322.dp)
					.background(Brush.verticalGradient(listOf(ambient.top, ambient.top, ambient.bottom)), MenuShape),
				drawerShape = MenuShape,
				drawerContainerColor = Color.Transparent,
				drawerContentColor = ambient.onAmbient,
				drawerTonalElevation = 0.dp
			) {
				NavicTheme(ambient.scheme, contentColor = ambient.onAmbient) {
					AvatarMenuContent()
				}
			}
		},
		drawerState = drawerState,
		gesturesEnabled = open,
		content = content
	)

	// Back closes the menu and goes no further. This drawer does not do it itself (measured on the
	// AVD: with the menu open one Back left the app), so it is handled here. The dispatcher asks the
	// handler added LAST, so it is composed only while the menu is open, i.e. added when it opens:
	// since P6 Home stays under Search and the Library, NavDisplay's own handler is enabled on those
	// pages, and a handler composed for the drawer's whole life was older than it, so one Back popped
	// the page under the open menu (measured on the AVD, Library to Home).
	if (open) {
		NavigationBackHandler(
			state = rememberNavigationEventState(NavigationEventInfo.None),
			isBackEnabled = true,
			onBackCompleted = { shell.closeAvatarMenu() }
		)
	}
}

@Composable
private fun AvatarMenuContent() {
	val shell = koinInject<ShellController>()
	val backStack = LocalNavStack.current
	val settings = koinInject<Settings>()
	val sessionManager = koinInject<SessionManager>()
	val loginManager = koinInject<LoginManager>()
	val lbBot = koinInject<LbBotManager>()
	val radioManager = koinInject<RadioManager>()
	val preferenceManager = koinInject<PreferenceManager>()

	val attention by lbBot.attentionBadge.collectAsState()
	val webUrl by lbBot.webUrl.collectAsState()
	val autoplayOn by radioManager.autoplayOn.collectAsState()
	val lbConfigured = lbBot.isConfigured
	val canShare = sessionManager.canUserShare()
	val items = remember(lbConfigured, canShare, attention, webUrl) {
		avatarMenuItems(lbConfigured, canShare, menuAttentionBadge(attention, lbConfigured), webUrl)
	}
	val uriHandler = LocalUriHandler.current

	// Navigating from a row closes the menu first, then pushes the page; the double-tap guard is the
	// bars': a page already on top is not pushed again.
	val go = { destination: NavKey ->
		shell.closeAvatarMenu()
		if (backStack.lastOrNull() != destination) backStack.add(destination)
	}
	// The rare tools open lb-bot's own web UI; the menu closes first, as for a page.
	val openExternal = { path: String ->
		shell.closeAvatarMenu()
		if (webUrl.isNotBlank()) runCatching { uriHandler.openUri("$webUrl/#/$path") }
	}
	val toggles = { id: String ->
		when (id) {
			AvatarMenuIds.AUTOPLAY -> autoplayOn to { on: Boolean -> radioManager.setAutoplayOn(on) }
			else -> preferenceManager.showAttentionDot to { on: Boolean -> preferenceManager.showAttentionDot = on }
		}
	}
	val logOut: () -> Unit = {
		shell.closeAvatarMenu()
		loginManager.logout()
		backStack.clear()
		backStack.add(Screen.Login)
	}

	Column(
		modifier = Modifier
			.fillMaxHeight()
			.verticalScroll(rememberScrollState())
			.padding(bottom = 12.dp)
	) {
		MenuHeader(
			name = settings.getString("username", ""),
			address = settings.getString("instanceUrl", "")
		)
		items.forEach { entry ->
			when (entry) {
				is AvatarMenuEntry.Group -> MenuGroup(entry, go, toggles, openExternal)
				else -> MenuEntry(entry, onNavigate = go, toggles = toggles, onAction = logOut, onExternal = openExternal, inFold = false)
			}
		}
	}
}

/** Who is signed in and where: the server's address, which is read, not a claim about the connection. */
@Composable
private fun MenuHeader(name: String, address: String) {
	Row(
		modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 12.dp),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(12.dp)
	) {
		Monogram(modifier = Modifier.size(48.dp), text = name)
		Column(Modifier.weight(1f)) {
			if (name.isNotBlank()) {
				Text(
					text = name,
					style = MaterialTheme.typography.titleMedium,
					fontWeight = FontWeight.Bold,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis
				)
			}
			if (address.isNotBlank()) {
				Text(
					text = address,
					style = MaterialTheme.typography.labelMedium,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis
				)
			}
		}
	}
}

@Composable
private fun MenuEntry(
	entry: AvatarMenuEntry,
	onNavigate: (NavKey) -> Unit,
	toggles: (String) -> Pair<Boolean, (Boolean) -> Unit>,
	onAction: () -> Unit,
	onExternal: (String) -> Unit,
	inFold: Boolean
) {
	when (entry) {
		is AvatarMenuEntry.Link -> MenuRow(
			icon = entry.icon,
			label = stringResource(entry.label),
			// The attention row's glyph is the one colour on the menu: it is the one thing that asks.
			iconTint = if (entry.id == AvatarMenuIds.ATTENTION) MaterialTheme.colorScheme.warning
			else MaterialTheme.colorScheme.onSurfaceVariant,
			inFold = inFold,
			onClick = dropUnlessResumed { onNavigate(entry.destination) },
			trailing = entry.badge?.let { count -> { CountPill(count) } }
		)
		is AvatarMenuEntry.External -> MenuRow(
			icon = entry.icon,
			label = stringResource(entry.label),
			iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
			inFold = inFold,
			onClick = dropUnlessResumed { onExternal(entry.path) },
			trailing = {
				// It leaves the app: say so where the row ends, as a link out does.
				Icon(
					Icons.Outlined.Link, contentDescription = null,
					tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp)
				)
			}
		)
		is AvatarMenuEntry.Action -> MenuRow(
			icon = entry.icon,
			label = stringResource(entry.label),
			iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
			inFold = inFold,
			onClick = dropUnlessResumed { onAction() }
		)
		is AvatarMenuEntry.Toggle -> {
			val (checked, set) = toggles(entry.id)
			MenuRow(
				icon = entry.icon,
				label = stringResource(entry.label),
				iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
				inFold = inFold,
				onClick = { set(!checked) },
				trailing = { Switch(checked = checked, onCheckedChange = null) }
			)
		}
		is AvatarMenuEntry.Group -> Unit
	}
}

@Composable
private fun MenuRow(
	icon: ImageVector,
	label: String,
	iconTint: Color,
	inFold: Boolean,
	onClick: () -> Unit,
	trailing: (@Composable () -> Unit)? = null
) {
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.heightIn(min = if (inFold) 44.dp else 46.dp)
			.clickable(role = Role.Button, onClick = onClick)
			.padding(horizontal = if (inFold) 12.dp else 20.dp),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(14.dp)
	) {
		Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(24.dp))
		Text(
			text = label,
			modifier = Modifier.weight(1f),
			fontSize = 15.sp,
			fontWeight = FontWeight.SemiBold,
			fontFamily = defaultFont(),
			maxLines = 2,
			overflow = TextOverflow.Ellipsis
		)
		trailing?.invoke()
	}
}

/** The count on "Needs attention". */
@Composable
private fun CountPill(count: Int) {
	Surface(
		color = MaterialTheme.colorScheme.warning.copy(alpha = 0.18f),
		shape = RoundedCornerShape(50)
	) {
		Text(
			text = count.toString(),
			color = MaterialTheme.colorScheme.warning,
			style = MaterialTheme.typography.labelMedium,
			fontWeight = FontWeight.Bold,
			modifier = Modifier.padding(horizontal = 9.dp, vertical = 2.dp)
		)
	}
}

/**
 * A titled group: a rule, a header (with its row count and a chevron when it folds), and its rows on
 * a lifted box. A folding group rolls out and in with [Fold]; Listening is a plain section.
 */
@Composable
private fun MenuGroup(
	group: AvatarMenuEntry.Group,
	onNavigate: (NavKey) -> Unit,
	toggles: (String) -> Pair<Boolean, (Boolean) -> Unit>,
	onExternal: (String) -> Unit
) {
	val folds = group.fold != GroupFold.Never
	var open by rememberSaveable(group.id) { mutableStateOf(group.fold != GroupFold.StartsClosed) }

	Column(Modifier.fillMaxWidth()) {
		HorizontalDivider(
			modifier = Modifier.padding(horizontal = 20.dp),
			color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
		)
		val header = @Composable {
			Row(
				modifier = Modifier
					.fillMaxWidth()
					.height(48.dp)
					.then(
						if (folds) Modifier
							.semantics { if (open) collapse { open = false; true } else expand { open = true; true } }
							.clickable(role = Role.Button) { open = !open }
						else Modifier
					)
					.padding(horizontal = 20.dp),
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(10.dp)
			) {
				Text(
					text = stringResource(group.label).uppercase(),
					modifier = Modifier.weight(1f),
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					fontSize = 12.sp,
					fontWeight = FontWeight.ExtraBold,
					letterSpacing = 0.72.sp
				)
				if (folds) {
					Text(
						text = group.entries.size.toString(),
						color = MaterialTheme.colorScheme.onSurfaceVariant,
						style = MaterialTheme.typography.labelMedium
					)
					// Down is "closed"; it turns to point up when open (the board's chevrons).
					val turn by animateFloatAsState(
						targetValue = if (open) 180f else 0f,
						animationSpec = rememberFoldMotion().spatial()
					)
					Icon(
						Icons.Outlined.KeyboardArrowDown,
						contentDescription = null,
						tint = MaterialTheme.colorScheme.onSurfaceVariant,
						modifier = Modifier.size(20.dp).rotate(turn)
					)
				}
			}
		}
		header()
		val rows = @Composable {
			Surface(
				modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 6.dp),
				color = MaterialTheme.colorScheme.surfaceContainer,
				shape = RoundedCornerShape(14.dp)
			) {
				Column(Modifier.padding(vertical = 2.dp)) {
					group.entries.forEach { entry ->
						MenuEntry(entry, onNavigate = onNavigate, toggles = toggles, onAction = {}, onExternal = onExternal, inFold = true)
					}
				}
			}
		}
		if (folds) Fold(visible = open) { rows() } else rows()
		Spacer(Modifier.height(2.dp))
	}
}
