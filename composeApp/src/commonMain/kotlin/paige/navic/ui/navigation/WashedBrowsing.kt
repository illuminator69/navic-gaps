package paige.navic.ui.navigation

import androidx.navigation3.runtime.NavKey
import paige.navic.util.CoverPlaceholder
import kotlin.reflect.KClass

/*
 * B-008 (option 2): which page the tablet rail takes its colours from.
 *
 * The rail sits beside NavDisplay, so it cannot read a page's cover ambient; it reads the back
 * stack instead and themes itself from the now-playing cover while the page on top is one that
 * `BrowsingAmbient` washes (`RailAmbient` in CoverAmbientBackground.kt). Fork-only file.
 */

/**
 * The browsing pages `BrowsingAmbient` washes: every entry App.kt wraps in `Washed`, plus the
 * library home, which wraps itself. By destination, not by value, so a `nested` copy pushed from
 * the home counts too. WashedBrowsingDriftTest (androidHostTest) fails if this and App.kt disagree.
 */
internal val WashedBrowsingScreens: Set<KClass<out NavKey>> = setOf(
	Screen.Library::class, Screen.Starred::class, Screen.AlbumList::class,
	Screen.PlaylistList::class, Screen.ArtistList::class, Screen.GenreList::class,
	Screen.SongList::class, Screen.RadioList::class, Screen.Fresh::class, Screen.Discover::class,
	Screen.MixList::class, Screen.Search::class, Screen.Statistics::class
)

/**
 * Entries shown as an `OverlayScene` (a sheet) over the page beneath, which stays on screen: their
 * App.kt metadata is a `NowPlayingSceneStrategy` / `BottomSheetSceneStrategy` bottom sheet.
 * `ImageView` is not one: it has only transition metadata and replaces the page.
 */
internal val PageOverlayScreens: Set<KClass<out NavKey>> = setOf(
	Screen.NowPlaying::class, Screen.Lyrics::class, Screen.Queue::class,
	Screen.PlaybackSpeed::class, Screen.SongDetailSheet::class
)

internal fun NavKey.isWashedBrowsing(): Boolean = this::class in WashedBrowsingScreens

internal fun NavKey.isPageOverlay(): Boolean = this::class in PageOverlayScreens

/** The page the user is looking at: the last entry that is not a sheet over it. */
internal fun List<NavKey>.topPage(): NavKey? = lastOrNull { !it.isPageOverlay() }

/**
 * Whether the rail follows the now-playing cover. A two-pane `CollectionDetail` beside a washed
 * list is a detail page on top, so it keeps the base rail like every other detail page.
 */
internal fun List<NavKey>.railFollowsCover(): Boolean = topPage()?.isWashedBrowsing() == true

/**
 * Whether the rail wears the cover's scheme: a washed page on top ([railFollowsCover]),
 * BrowsingAmbient's own gate (`themed && resolved`), AND a now-playing cover the engine would
 * actually fetch.
 *
 * The last is not redundant with `resolved`. `rememberCoverColorScheme` keeps its palette in a
 * key-less `remember` so a song change eases from the old colour, and nothing clears it when the id
 * goes away: nothing playing (no current song), a radio stream (`coverArtId = null`), a track with
 * no art (null or blank), or Navidrome's generic avatar, which the engine never fetches. `resolved`
 * then stays true on the LAST cover's colours. A page opened after that starts from an empty palette
 * and shows the base; the rail lives as long as the app and would wear the stale colours beside it.
 *
 * Residue, accepted: an id that is present but whose fetch fails (all three attempts) keeps the
 * previous palette on the rail until the next cover resolves, exactly as it does on a page that was
 * already open.
 */
internal fun railWearsCover(
	followsCover: Boolean,
	coverArtId: String?,
	themed: Boolean,
	resolved: Boolean
): Boolean = followsCover && themed && resolved &&
	!coverArtId.isNullOrBlank() && !CoverPlaceholder.isPlaceholder(coverArtId)
