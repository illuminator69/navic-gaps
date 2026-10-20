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
import navic.composeapp.generated.resources.title_albums
import navic.composeapp.generated.resources.title_artists
import navic.composeapp.generated.resources.title_radios
import navic.composeapp.generated.resources.title_songs
import navic.composeapp.generated.resources.title_discover_mixes
import navic.composeapp.generated.resources.title_downloads
import navic.composeapp.generated.resources.title_library_matches
import navic.composeapp.generated.resources.title_playlists
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import paige.navic.di.LocalNavStack
import paige.navic.domain.manager.LbBotManager
import paige.navic.domain.manager.PreferenceManager
import paige.navic.domain.models.DomainAlbumListType
import paige.navic.icons.outlined.Equalizer
import paige.navic.icons.Icons
import paige.navic.icons.brand.Musicbrainz
import paige.navic.icons.outlined.Album
import paige.navic.icons.outlined.Artist
import paige.navic.icons.outlined.Download
import paige.navic.icons.outlined.History
import paige.navic.icons.outlined.LibraryAdd
import paige.navic.icons.outlined.PlaylistPlay
import paige.navic.icons.outlined.Note
import paige.navic.icons.outlined.Radio
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
 * How many buttons the home page shows: four, or the primaries rounded up to even once there are
 * more than four. Always even — an odd count leaves a hole in the two-column grid — and never
 * fewer than the primaries, so a fifth button adds a row rather than pushing Random off the page.
 */
internal fun overviewButtonCount(primary: Int): Int =
	if (primary <= 4) 4 else primary + primary % 2

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
 * The longest the primary list gets: Mixed for You, Library Matches, Downloads, Random, (P1)
 * Albums, Playlists and Artists, and (P3) Songs and Radios. Discover and Fresh left for Search's
 * browse cards (P3). [overviewButtonCount] never shows fewer than there are, so the home page has at
 * most this rounded up to even: ten, one filler closing the last row. (My Listening was a seventh until I-039 made it Stats', which the home reaches by its
 * own card at the foot of the page.)
 *
 * Albums, Playlists and Artists are here because the bottom bar no longer holds them (P1: it is
 * Home, Search, Library, New). They were its default tabs, and nothing the old bar reached may
 * become unreachable. Songs, Genres and Radios were hidden by default and are not added.
 */
internal const val OVERVIEW_PRIMARY_MAX = 9

/**
 * The home page's buttons: at least eight, and up to ten where more of the fork's pages are offered.
 *
 * Mixed for You lives here instead of on the bottom bar, gated on a *configured* hub, as
 * `rememberVisibleNavigationTabs()` gated its tab (unused since P1, but its rules are these).
 * Discover and Fresh were here too until P3 moved them to Search's browse cards.
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
 * Library Matches and Downloads make five and six ([overviewButtonCount]): a row more, never
 * Random pushed off. Albums, Playlists and Artists (P1) are always there, after Random: the
 * old bar's default tabs, now one tap from the Library tab.
 */
@Composable
fun rememberOverviewButtons(): List<OverviewButtonSpec> {
	val lbBot = koinInject<LbBotManager>()
	LaunchedEffect(Unit) { lbBot.ensureAvailability() }

	val preferenceManager = koinInject<PreferenceManager>()
	val hubConfigured = preferenceManager.hubEnabled && preferenceManager.hubUrl.isNotBlank()
	// Library Matches (I-026) is offered wherever the lb-bot layer is on, as the settings
	// section it replaced was (`libraryMatchesOffered`). NOT on the override routes being
	// supported: that is false until the hub has spoken in this process and for an lb-bot that
	// predates them, and the page is exactly where those cases are explained and where the
	// copy this device loaded last can still be read. Its writes keep their own gates.
	val overridesSupported by lbBot.artistOverridesSupported.collectAsState()
	val matches = libraryMatchesOffered(lbBot.isConfigured, overridesSupported)

	return remember(hubConfigured, matches) {
		val primary = buildList {
			if (hubConfigured) {
				add(
					OverviewButtonSpec(
						Icons.Outlined.Equalizer,
						Res.string.title_discover_mixes,
						Screen.MixList(nested = true)
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
			// Downloads (I-039) is always there: this device's offline files are upstream's
			// feature, and the page is where they are listed. It was the Download Center in
			// Settings, which is where nobody looks for what is downloading.
			add(
				OverviewButtonSpec(
					Icons.Outlined.Download,
					Res.string.title_downloads,
					Screen.Downloads()
				)
			)
			add(
				OverviewButtonSpec(
					Icons.Outlined.Shuffle,
					Res.string.option_sort_random,
					Screen.AlbumList(true, DomainAlbumListType.Random)
				)
			)
			// The three lists the old bar held by default. `nested = true` for the same reason as
			// Discover above: pushed onto the library's stack, with a back arrow.
			add(
				OverviewButtonSpec(
					Icons.Outlined.Album,
					Res.string.title_albums,
					Screen.AlbumList(nested = true)
				)
			)
			add(
				OverviewButtonSpec(
					Icons.Outlined.PlaylistPlay,
					Res.string.title_playlists,
					Screen.PlaylistList(nested = true)
				)
			)
			add(
				OverviewButtonSpec(
					Icons.Outlined.Artist,
					Res.string.title_artists,
					Screen.ArtistList(nested = true)
				)
			)
			// P3: the song list and internet radio were hidden tabs, and P1's removal of the tab editor
			// left radio with no way in and the song list only behind filtered "See all"s. The same
			// pages the old tabs opened. Plan 5 turns these buttons into Library chips.
			add(
				OverviewButtonSpec(
					Icons.Outlined.Note,
					Res.string.title_songs,
					Screen.SongList(nested = true)
				)
			)
			add(
				OverviewButtonSpec(
					Icons.Outlined.Radio,
					Res.string.title_radios,
					Screen.RadioList(nested = true)
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
		// `primary` is 7..9 long and `filler` is 3, so there is always enough to fill the count.
		(primary + filler).take(overviewButtonCount(primary.size))
	}
}
