package paige.navic.ui.components.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ripple
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.dropUnlessResumed
import com.russhwolf.settings.Settings
import navic.composeapp.generated.resources.Res
import navic.composeapp.generated.resources.action_open_menu
import navic.composeapp.generated.resources.info_open_menu_attention
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import paige.navic.domain.manager.LbBotManager
import paige.navic.domain.manager.PreferenceManager
import paige.navic.domain.manager.ShellController
import paige.navic.domain.manager.attentionBadge
import paige.navic.ui.theme.warning

/**
 * navi-connect P1: the account's monogram at the start of every root page's header. It opens the
 * avatar menu (hosted once in App.kt through [ShellController]), which holds what the bar does not:
 * the rare tools, Settings and Log out. An amber dot sits on it while something needs attention
 * ([needsAttentionCount]) and the user has not turned the dot off.
 *
 * The username is read from the same setting the old account sheet read. It may be empty (the
 * monogram then draws a person) and long (only its first two characters are drawn).
 */
@Composable
fun AvatarButton(modifier: Modifier = Modifier) {
	val settings = koinInject<Settings>()
	val shell = koinInject<ShellController>()
	val preferenceManager = koinInject<PreferenceManager>()
	val lbBot = koinInject<LbBotManager>()
	val badge by lbBot.attentionBadge.collectAsState()

	val attention = badge > 0 && preferenceManager.showAttentionDot
	val label = stringResource(
		if (attention) Res.string.info_open_menu_attention else Res.string.action_open_menu
	)

	// Not an IconButton: it clips its content to its own 40 dp, and the dot overhangs the avatar's
	// corner. A 48 dp box with an unbounded ripple draws both. The size, the click and the label are
	// on this one node: `minimumInteractiveComponentSize` would leave the click on the 48 dp node and
	// the label and role on the 40 dp one inside it, and a screen reader would find the wrong one.
	Box(
		modifier = modifier
			.size(48.dp)
			.clickable(
				interactionSource = remember { MutableInteractionSource() },
				indication = ripple(bounded = false, radius = 24.dp),
				role = Role.Button,
				onClick = dropUnlessResumed { shell.openAvatarMenu() }
			)
			.semantics { contentDescription = label },
		contentAlignment = Alignment.Center
	) {
		Box {
			Monogram(modifier = Modifier.size(40.dp), text = settings.getString("username", ""))
			if (attention) {
				// A ring in the page's own tone round a 10 dp dot, so it reads on any cover's wash.
				Box(
					Modifier
						.align(Alignment.TopEnd)
						.offset(x = 3.dp, y = (-3).dp)
						.size(14.dp)
						.background(MaterialTheme.colorScheme.surface, CircleShape),
					contentAlignment = Alignment.Center
				) {
					Box(
						Modifier
							.size(10.dp)
							.background(MaterialTheme.colorScheme.warning, CircleShape)
					)
				}
			}
		}
	}
}
