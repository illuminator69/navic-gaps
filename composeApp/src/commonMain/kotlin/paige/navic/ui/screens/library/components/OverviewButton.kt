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
import navic.composeapp.generated.resources.title_library_matches
import navic.composeapp.generated.resources.title_listening
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import paige.navic.di.LocalNavStack
import paige.navic.domain.manager.AudioMuseManager
import paige.navic.domain.manager.LbBotManager
import paige.navic.domain.manager.PreferenceManager
import paige.navic.domain.models.DomainAlbumListType
import paige.navic.icons.Icons
import paige.navic.icons.brand.Musicbrainz
import paige.navic.icons.outlined.Album
import paige.navic.icons.outlined.History
import paige.navic.icons.outlined.LibraryAdd
import paige.navic.icons.filled.Headphones
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
 * How many buttons the home page shows: four, or six once there are five to show. Always even —
 * an odd count leaves a hole in the two-column grid — and never fewer than the primaries, so a
 * fifth button (My Listening) adds a row rather than pushing Random off the page.
 */
internal fun overviewButtonCount(primary: Int): Int = if (primary <= 4) 4 else 6

/**
 * Whether the home offers Library Matches: **wherever the lb-bot layer is on**
 * ([lbConfigured] — a hub is set up and has not said its lb-bot proxy is off). That is how the
 * settings section it replaced was reachable, and it must stay so: on a cold start before the hub
 * has spoken in this process, with lb-bot out of reach, and against an lb-bot older than the
 * override routes, the page is where "the copy loaded last", "not reachable" and "this lb-bot is
 * older" are said. [overridesSupported] gates the page's WRITES, not the way in — it is false
 * in every one of those three cases.
 */
@Suppress("UNUSED_PARAMETER")
internal fun libraryMatchesOffered(lbConfigured: Boolean, overridesSupported: Boolean): Boolean =
	lbConfigured

/**
 * The longest the primary list gets: Discover, Mixed for You, Fresh, My Listening, Library
 * Matches and Random. [overviewButtonCount] never shows fewer than there are, so this is also
 * the most buttons the home page has; a seventh primary would have to raise both.
 */
internal const val OVERVIEW_PRIMARY_MAX = 6

/**
 * The home page's four buttons — six where My Listening is offered too.
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
 *
 * My Listening is a fifth: gated on the hub ADVERTISING the listening routes rather than on
 * lb-bot answering right now, since its page works from cache. With all five the home is six
 * long ([overviewButtonCount]) — a row more, never Random pushed off.
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
	// My Listening (I-006) exists only where the hub advertises the listening routes. Not gated
	// on lb-bot being reachable right now, unlike Fresh: the screen paints its cached summary
	// with lb-bot down or the device offline, and says which it is.
	val listeningAdvertised by lbBot.listeningSupported.collectAsState()
	val listening = listeningAdvertised && lbBot.isConfigured
	// Library Matches (I-026) is offered wherever the lb-bot layer is on, as the settings
	// section it replaced was (`libraryMatchesOffered`). NOT on the override routes being
	// supported: that is false until the hub has spoken in this process and for an lb-bot that
	// predates them, and the page is exactly where those cases are explained and where the
	// copy this device loaded last can still be read. Its writes keep their own gates.
	val overridesSupported by lbBot.artistOverridesSupported.collectAsState()
	val matches = libraryMatchesOffered(lbBot.isConfigured, overridesSupported)

	return remember(lbBotAvailable, discoverHasSources, hubConfigured, listening, matches) {
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
			if (listening) {
				add(
					OverviewButtonSpec(
						Icons.Filled.Headphones,
						Res.string.title_listening,
						Screen.Listening
					)
				)
			}
			if (matches) {
				add(
					OverviewButtonSpec(
						Icons.Brand.Musicbrainz,
						Res.string.title_library_matches,
						Screen.LibraryMatches
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
		// `primary` is 1..6 long and `filler` is 3, so there is always enough to fill the count.
		(primary + filler).take(overviewButtonCount(primary.size))
	}
}
