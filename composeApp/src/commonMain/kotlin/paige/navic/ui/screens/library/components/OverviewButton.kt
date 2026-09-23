package paige.navic.ui.screens.library.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.navigation3.runtime.NavKey
import navic.composeapp.generated.resources.Res
import navic.composeapp.generated.resources.option_sort_frequent
import navic.composeapp.generated.resources.option_sort_newest
import navic.composeapp.generated.resources.option_sort_random
import navic.composeapp.generated.resources.option_sort_starred
import navic.composeapp.generated.resources.title_discover
import navic.composeapp.generated.resources.title_discover_mixes
import navic.composeapp.generated.resources.title_fresh
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import paige.navic.di.LocalNavStack
import paige.navic.domain.manager.AudioMuseManager
import paige.navic.domain.manager.LbBotManager
import paige.navic.domain.manager.PreferenceManager
import paige.navic.domain.models.DomainAlbumListType
import paige.navic.icons.Icons
import paige.navic.icons.outlined.Album
import paige.navic.icons.outlined.History
import paige.navic.icons.outlined.LibraryAdd
import paige.navic.icons.outlined.PlaylistPlay
import paige.navic.icons.outlined.Route
import paige.navic.icons.outlined.Shuffle
import paige.navic.icons.outlined.Star
import paige.navic.ui.navigation.Screen
import paige.navic.ui.theme.defaultFont

fun LazyGridScope.libraryScreenOverviewButton(
	icon: ImageVector,
	label: StringResource,
	destination: NavKey,
	start: Boolean
) {
	item(span = { GridItemSpan(1) }) {
		val backStack = LocalNavStack.current
		Button(
			modifier = Modifier
				.fillMaxWidth()
				.height(42.dp)
				.padding(
					start = if (start) 16.dp else 0.dp,
					end = if (!start) 16.dp else 0.dp,
				),
			contentPadding = PaddingValues(horizontal = 12.dp),
			elevation = null,
			shapes = ButtonDefaults.shapes(
				shape = MaterialTheme.shapes.small,
				pressedShape = MaterialTheme.shapes.extraSmall
			),
			colors = ButtonDefaults.buttonColors(
				containerColor = MaterialTheme.colorScheme.surfaceContainer,
				contentColor = MaterialTheme.colorScheme.onSurfaceVariant
			),
			// A double-tap dedupe, not a navigation rule. It used to test only for
			// `Screen.AlbumList`, which was every destination this row had; now that
			// three of the four push Discover / Mixed for You / Fresh, comparing the
			// top of the stack against THIS button's own destination is what keeps
			// the guard meaning the same thing.
			onClick = dropUnlessResumed {
				if (backStack.lastOrNull() != destination) {
					backStack.add(destination)
				}
			}
		) {
			Row(
				modifier = Modifier.fillMaxWidth(),
				verticalAlignment = Alignment.CenterVertically
			) {
				Icon(
					icon,
					contentDescription = null
				)
				Spacer(Modifier.width(10.dp))
				Text(
					stringResource(label),
					maxLines = 1,
					fontFamily = defaultFont(100, round = 100f),
					autoSize = TextAutoSize.StepBased(minFontSize = 1.sp, maxFontSize = 14.sp),
				)
			}
		}
	}
}

/**
 * One quick-access button on the library home.
 *
 * A record rather than four hardcoded calls, because the row is now conditional:
 * three of its four entries lead into optional infrastructure, and the `start`
 * padding has to follow a button's *position* once the list can be short.
 */
data class OverviewButtonSpec(
	val icon: ImageVector,
	val label: StringResource,
	val destination: NavKey
)

/**
 * The home page's four buttons.
 *
 * Discover, Mixed for You and Fresh live here instead of on the bottom bar. They
 * are gated on exactly what `rememberVisibleNavigationTabs()` gates the matching
 * tabs on — lb-bot for Fresh, lb-bot **or** AudioMuse for Discover (it also carries
 * mood search and the rediscovery set), and a *configured* hub for Mixed for You.
 * Configured, not connected: this runs on every mount of the home page, and gating
 * on a value that flips during a round trip is the bug that made the Fresh tab
 * blink out.
 *
 * What replaced them — Recently added, Starred, Frequently played — is a Library
 * sort or a smart playlist away, so the list pads back out of those in that order
 * until it is four long and even. Four, because an odd count leaves a hole in a
 * two-column grid; from those three, because a home page that answers "nothing is
 * configured" with two buttons has lost something it used to do.
 */
@Composable
fun rememberOverviewButtons(): List<OverviewButtonSpec> {
	val lbBot = koinInject<LbBotManager>()
	val lbBotAvailable by lbBot.available.collectAsState()
	LaunchedEffect(Unit) { lbBot.ensureAvailability() }

	val audioMuse = koinInject<AudioMuseManager>()
	val preferenceManager = koinInject<PreferenceManager>()
	val discoverHasSources = lbBotAvailable || audioMuse.isConfigured
	val hubConfigured = preferenceManager.hubEnabled && preferenceManager.hubUrl.isNotBlank()

	return remember(lbBotAvailable, discoverHasSources, hubConfigured) {
		val primary = buildList {
			if (discoverHasSources) {
				add(
					OverviewButtonSpec(
						Icons.Outlined.Route,
						Res.string.title_discover,
						// `nested = true` is required: these are PUSHED onto the
						// library's own stack rather than swapped in as a root tab,
						// and without it the screen renders `RootTopBar` with no
						// back arrow. It is the same key Discover's own "See all"
						// rows already use.
						Screen.Discover(nested = true)
					)
				)
			}
			if (hubConfigured) {
				add(
					OverviewButtonSpec(
						Icons.Outlined.PlaylistPlay,
						Res.string.title_discover_mixes,
						Screen.MixList(nested = true)
					)
				)
			}
			if (lbBotAvailable) {
				add(
					OverviewButtonSpec(
						Icons.Outlined.Album,
						Res.string.title_fresh,
						Screen.Fresh(nested = true)
					)
				)
			}
			add(
				OverviewButtonSpec(
					Icons.Outlined.Shuffle,
					Res.string.option_sort_random,
					Screen.AlbumList(true, DomainAlbumListType.Random)
				)
			)
		}
		val filler = listOf(
			OverviewButtonSpec(
				Icons.Outlined.LibraryAdd,
				Res.string.option_sort_newest,
				Screen.AlbumList(true, DomainAlbumListType.Newest)
			),
			OverviewButtonSpec(
				Icons.Outlined.Star,
				Res.string.option_sort_starred,
				Screen.Starred()
			),
			OverviewButtonSpec(
				Icons.Outlined.History,
				Res.string.option_sort_frequent,
				Screen.AlbumList(true, DomainAlbumListType.Frequent)
			)
		)
		// `primary` is 1..4 long and `filler` is 3, so this is always exactly four.
		(primary + filler).take(4)
	}
}
