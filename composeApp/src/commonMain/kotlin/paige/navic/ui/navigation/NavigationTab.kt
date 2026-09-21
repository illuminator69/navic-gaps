package paige.navic.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import navic.composeapp.generated.resources.Res
import navic.composeapp.generated.resources.title_albums
import navic.composeapp.generated.resources.title_artists
import navic.composeapp.generated.resources.title_fresh
import navic.composeapp.generated.resources.title_genres
import navic.composeapp.generated.resources.title_library
import navic.composeapp.generated.resources.title_playlists
import navic.composeapp.generated.resources.title_radios
import navic.composeapp.generated.resources.title_search
import navic.composeapp.generated.resources.title_songs
import navic.composeapp.generated.resources.title_statistics
import org.jetbrains.compose.resources.StringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import paige.navic.domain.manager.LbBotManager
import paige.navic.domain.models.settings.NavbarConfig
import paige.navic.domain.models.settings.NavbarTab
import paige.navic.icons.Icons
import paige.navic.icons.filled.Album
import paige.navic.icons.filled.Artist
import paige.navic.icons.filled.Genre
import paige.navic.icons.filled.LibraryMusic
import paige.navic.icons.filled.Radio
import paige.navic.icons.outlined.Album
import paige.navic.icons.outlined.Artist
import paige.navic.icons.outlined.Genre
import paige.navic.icons.outlined.LibraryMusic
import paige.navic.icons.outlined.Note
import paige.navic.icons.outlined.PlaylistPlay
import paige.navic.icons.outlined.Radio
import paige.navic.icons.outlined.Search
import paige.navic.icons.outlined.Statistics
import paige.navic.ui.screens.settings.viewmodels.NavtabsViewModel

enum class NavigationTab(
	val destination: Screen,
	val iconFilled: ImageVector,
	val iconOutlined: ImageVector = iconFilled,
	val label: StringResource
) {
	LIBRARY(
		destination = Screen.Library(),
		iconFilled = Icons.Filled.LibraryMusic,
		iconOutlined = Icons.Outlined.LibraryMusic,
		label = Res.string.title_library
	),
	ALBUMS(
		destination = Screen.AlbumList(),
		iconFilled = Icons.Filled.Album,
		iconOutlined = Icons.Outlined.Album,
		label = Res.string.title_albums
	),
	PLAYLISTS(
		destination = Screen.PlaylistList(),
		iconFilled = Icons.Outlined.PlaylistPlay,
		label = Res.string.title_playlists
	),
	ARTISTS(
		destination = Screen.ArtistList(),
		iconFilled = Icons.Filled.Artist,
		iconOutlined = Icons.Outlined.Artist,
		label = Res.string.title_artists
	),
	SEARCH(
		destination = Screen.Search(),
		iconFilled = Icons.Outlined.Search,
		iconOutlined = Icons.Outlined.Search,
		label = Res.string.title_search
	),
	GENRES(
		destination = Screen.GenreList(),
		iconFilled = Icons.Filled.Genre,
		iconOutlined = Icons.Outlined.Genre,
		label = Res.string.title_genres
	),
	SONGS(
		destination = Screen.SongList(),
		iconFilled = Icons.Outlined.Note,
		iconOutlined = Icons.Outlined.Note,
		label = Res.string.title_songs
	),
	RADIOS(
		destination = Screen.RadioList(),
		iconFilled = Icons.Filled.Radio,
		iconOutlined = Icons.Outlined.Radio,
		label = Res.string.title_radios
	),
	STATISTICS(
		destination = Screen.Statistics(),
		iconFilled = Icons.Outlined.Statistics,
		label = Res.string.title_statistics
	),
	FRESH(
		destination = Screen.Fresh(),
		iconFilled = Icons.Filled.Album,
		iconOutlined = Icons.Outlined.Album,
		label = Res.string.title_fresh
	)
}

fun NavbarTab.Id.toNavigationTab(): NavigationTab = when (this) {
	NavbarTab.Id.LIBRARY -> NavigationTab.LIBRARY
	NavbarTab.Id.ALBUMS -> NavigationTab.ALBUMS
	NavbarTab.Id.PLAYLISTS -> NavigationTab.PLAYLISTS
	NavbarTab.Id.ARTISTS -> NavigationTab.ARTISTS
	NavbarTab.Id.SEARCH -> NavigationTab.SEARCH
	NavbarTab.Id.GENRES -> NavigationTab.GENRES
	NavbarTab.Id.SONGS -> NavigationTab.SONGS
	NavbarTab.Id.RADIOS -> NavigationTab.RADIOS
	NavbarTab.Id.STATISTICS -> NavigationTab.STATISTICS
	NavbarTab.Id.FRESH -> NavigationTab.FRESH
}

/**
 * The tabs a navigation surface should show: the user's visible ones, minus any whose
 * backing service isn't there.
 *
 * Lives here, and not in [BottomBar] and [SideBar] separately, because upstream writes
 * the id -> tab mapping out longhand in each of them. Two copies of a rule is two
 * chances for them to disagree about it, and the lb-bot gate below is exactly the sort
 * of rule that gets added to one and not the other.
 *
 * [NavbarTab.Id.FRESH] is conditional: lb-bot is optional infrastructure and the tab
 * leads nowhere without it, so it is dropped from the bar entirely rather than shown
 * dead. Availability is read off [LbBotManager] rather than a local `remember`, because
 * both bars are re-mounted per screen — a local one would restart at false and re-probe
 * on every navigation, making the tab blink out for the length of a round trip.
 */
@Composable
fun rememberVisibleNavigationTabs(): List<NavigationTab> {
	val viewModel = koinViewModel<NavtabsViewModel>()
	val state by viewModel.state.collectAsStateWithLifecycle()

	val lbBot = koinInject<LbBotManager>()
	val lbBotAvailable by lbBot.available.collectAsState()
	LaunchedEffect(Unit) { lbBot.ensureAvailability() }

	return (state.data ?: NavbarConfig.default).tabs
		.filter { tab ->
			tab.visible && (tab.id != NavbarTab.Id.FRESH || lbBotAvailable)
		}
		.map { it.id.toNavigationTab() }
}
