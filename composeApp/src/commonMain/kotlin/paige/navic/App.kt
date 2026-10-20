package paige.navic

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.EaseOutQuart
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.navigation3.ListDetailSceneStrategy.Companion.detailPane
import androidx.compose.material3.adaptive.navigation3.ListDetailSceneStrategy.Companion.listPane
import androidx.compose.material3.adaptive.navigation.BackNavigationBehavior
import androidx.compose.material3.adaptive.navigation3.rememberListDetailSceneStrategy
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import androidx.navigation3.ui.NavDisplay.popTransitionSpec
import androidx.navigation3.ui.NavDisplay.predictivePopTransitionSpec
import androidx.navigation3.ui.NavDisplay.transitionSpec
import androidx.savedstate.serialization.SavedStateConfiguration
import kotlinx.coroutines.flow.collectLatest
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import navic.composeapp.generated.resources.Res
import navic.composeapp.generated.resources.action_open_in_lbbot
import navic.composeapp.generated.resources.info_link_unresolved
import navic.composeapp.generated.resources.lbbot_fill_landed
import navic.composeapp.generated.resources.lbbot_fill_lost
import navic.composeapp.generated.resources.lbbot_fill_lost_reason
import navic.composeapp.generated.resources.lbbot_fill_needs_sorting
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import paige.navic.di.LocalBottomBarScrollManager
import paige.navic.di.LocalNavStack
import paige.navic.di.LocalPlatformContext
import paige.navic.di.LocalSharedTransitionScope
import paige.navic.di.LocalSnackBarState
import paige.navic.di.PlatformType
import paige.navic.di.rememberPlatformContext
import paige.navic.domain.manager.BottomBarScrollManager
import paige.navic.domain.manager.FillAnnouncement
import paige.navic.domain.manager.LbBotManager
import paige.navic.domain.manager.fillAnnouncementFor
import paige.navic.domain.manager.fillAnnouncementLinkPath
import paige.navic.domain.manager.PreferenceManager
import paige.navic.domain.manager.SessionManager
import paige.navic.domain.manager.SnackBarManager
import paige.navic.domain.models.settings.ExplicitContentPlayback
import paige.navic.generated.BuildInfo
import paige.navic.shared.MediaPlayerViewModel
import paige.navic.ui.components.common.blur.LocalExpressiveBlur
import paige.navic.ui.components.common.blur.expressiveBlurSource
import paige.navic.ui.components.common.blur.rememberExpressiveBlur
import paige.navic.ui.components.layouts.SideBar
import paige.navic.ui.components.sheets.AlbumMatchHost
import paige.navic.ui.components.sheets.AvatarMenuDrawer
import paige.navic.ui.components.sheets.NewSheetHost
import paige.navic.ui.components.sheets.ChangelogSheet
import paige.navic.ui.navigation.ListPageSceneStrategy
import paige.navic.ui.navigation.AppDeepLink
import paige.navic.ui.navigation.BottomSheetSceneStrategy
import paige.navic.ui.navigation.NowPlayingSceneStrategy
import paige.navic.ui.navigation.Screen
import paige.navic.ui.screens.album.AlbumListScreen
import paige.navic.ui.screens.artist.ArtistDetailScreen
import paige.navic.ui.screens.artist.ArtistDiscographyScreen
import paige.navic.ui.screens.external.ExternalAlbumScreen
import paige.navic.ui.screens.external.ExternalArtistScreen
import paige.navic.ui.screens.browse.BrowseFeedScreen
import paige.navic.ui.screens.browse.BrowseKind
import paige.navic.ui.screens.discover.DiscoverScreen
import paige.navic.ui.screens.fresh.FreshScreen
import paige.navic.ui.screens.artist.ArtistListScreen
import paige.navic.ui.screens.collection.CollectionDetailScreen
import paige.navic.ui.screens.genre.GenreListScreen
import paige.navic.ui.screens.home.HomeScreen
import paige.navic.ui.screens.imageView.ImageViewScreen
import paige.navic.ui.screens.library.LibraryScreen
import paige.navic.ui.screens.login.LoginScreen
import paige.navic.ui.screens.lyrics.LyricsScreen
import paige.navic.ui.screens.nowPlaying.NowPlayingScreen
import paige.navic.ui.screens.nowPlaying.PlaybackSpeedScreen
import paige.navic.ui.screens.playlist.PlaylistListScreen
import paige.navic.ui.screens.queue.QueueScreen
import paige.navic.ui.screens.radio.RadioListScreen
import paige.navic.ui.screens.search.SearchScreen
import paige.navic.ui.screens.settings.AudioEffectsScreen
import paige.navic.ui.screens.settings.BottomBarScreen
import paige.navic.ui.screens.settings.FontsScreen
import paige.navic.ui.screens.playlist.SmartPlaylistEditorScreen
import paige.navic.ui.screens.settings.NaviConnectScreen
import paige.navic.ui.screens.settings.SettingsAboutScreen
import paige.navic.ui.screens.settings.SettingsAppIconScreen
import paige.navic.ui.screens.settings.SettingsAppearanceScreen
import paige.navic.ui.screens.settings.SettingsCustomHeadersScreen
import paige.navic.ui.screens.downloads.DownloadsScreen
import paige.navic.ui.screens.settings.PendingActionsScreen
import paige.navic.ui.screens.settings.SettingsDataStorageScreen
import paige.navic.ui.screens.settings.SettingsDeveloperScreen
import paige.navic.ui.screens.settings.SettingsLogsScreen
import paige.navic.ui.screens.settings.SettingsNowPlayingScreen
import paige.navic.ui.screens.settings.SettingsPlaybackScreen
import paige.navic.ui.screens.settings.SettingsScreen
import paige.navic.ui.screens.settings.SettingsStreamingQualityScreen
import paige.navic.ui.screens.mixes.MixListScreen
import paige.navic.ui.screens.radio.custom.CustomRadioScreen
import paige.navic.ui.screens.savedqueues.SavedQueuesScreen
import paige.navic.ui.screens.matches.LibraryMatchesScreen
import paige.navic.ui.screens.settings.SettingsThemesScreen
import paige.navic.ui.screens.share.ShareListScreen
import paige.navic.ui.screens.song.SongDetailScreen
import paige.navic.ui.screens.song.SongListScreen
import paige.navic.ui.screens.starred.StarredScreen
import paige.navic.ui.screens.stats.StatisticsScreen
import paige.navic.ui.theme.NavicTheme
import paige.navic.di.PlatformContext
import paige.navic.ui.components.snackbars.NavicSnackBar
import paige.navic.ui.screens.song.SongDetailSheet
import paige.navic.ui.ColourArrivalEffects
import paige.navic.ui.util.Material3Transitions
import paige.navic.ui.util.pageMotionOf
import paige.navic.ui.util.rememberLibraryTabBackground
import paige.navic.ui.components.common.AppWash
import paige.navic.ui.components.common.BrowsingAmbient
import paige.navic.ui.components.common.RailAmbient
import paige.navic.ui.components.common.RemoteVolumeHud
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import paige.navic.ui.screens.genre.GenreDetailScreen
import paige.navic.ui.screens.settings.SettingsDownloadQualityScreen
import paige.navic.ui.screens.settings.SettingsEqualiserScreen

@OptIn(ExperimentalSerializationApi::class)
private val config = SavedStateConfiguration {
	serializersModule = SerializersModule {
		polymorphic(NavKey::class) {
			subclassesOfSealed<Screen>()
		}
	}
}


// Coil's singleton factory may only be installed once per process. When the
// playback service keeps the process alive (e.g. while casting) and the UI is
// reopened, App() recomposes from scratch — installing again after the loader
// exists throws. Install once, skip on re-entry.
private var imageLoaderFactoryInstalled = false

// Whether App() should install Coil's singleton factory. FALSE on Android — the
// Application implements SingletonImageLoader.Factory, so the loader is created
// with our config no matter who calls SingletonImageLoader.get() first (the
// playback service can load album art before the Activity attaches after an
// in-place update; the composable install here would then throw "already
// created"). TRUE on iOS, which has no Application-level factory.
internal expect val installComposeSingletonImageLoader: Boolean

@OptIn(ExperimentalMaterial3AdaptiveApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun App() {
	// upstream injects the Coil ImageLoader (alpha51), so there is no singleton to install.

	val platformContext = rememberPlatformContext()
	val sessionManager = koinInject<SessionManager>()
	val preferenceManager = koinInject<PreferenceManager>()

	val isLoggedIn by sessionManager.isLoggedIn.collectAsStateWithLifecycle()
	val backStack = rememberNavBackStack(
		config, if (isLoggedIn) {
			Screen.Home
		} else {
			Screen.Login
		}
	)
	val snackBarState = remember { SnackbarHostState() }
	val snackBarManager = koinInject<SnackBarManager>()

	LaunchedEffect(Unit) {
		snackBarManager.events.collectLatest { event ->
			snackBarState.showSnackbar(getString(event.resource, *event.args.toTypedArray()))
		}
	}

	val density = LocalDensity.current
	val scrollManager = remember {
		BottomBarScrollManager(with(density) { 50.dp.toPx() })
	}

	// One shared backdrop-blur state for the whole app: the NavDisplay content is
	// the source, and the (per-screen) bottom bar reads it via LocalExpressiveBlur
	// to frost over it. Gated by the Appearance "Expressive blur" toggle.
	val expressiveBlur = rememberExpressiveBlur(preferenceManager.expressiveBlur)

	// Ask for local-network access here, not only on the login screen. Anyone who signed in
	// before the app started making direct LAN connections never gets asked otherwise, and the
	// platform then falls back to prompting per connection — a "choose a device to connect"
	// chooser on every launch, which grants nothing. No-op once granted.
	LaunchedEffect(Unit) { platformContext.checkLocalNetworkPermission() }

	// The nav graph, built once rather than on every navigation.
	//
	// `entryProvider` needs to know whether we're on a root tab (it picks a different transition
	// for those), and it used to read `backStack.size` itself — but that read happens DURING App's
	// composition, so it subscribed App to the backstack and every push/pop recomposed the whole
	// of App: rebuilding all ~50 entries, handing NavDisplay a brand-new lambda it therefore
	// couldn't skip, and re-running NavicTheme over the entire tree — all while the 450 ms
	// SharedXAxis transition was animating. Behind `derivedStateOf`, App is invalidated only when
	// the root/non-root boundary is actually crossed.
	val isRoot by remember(backStack) { derivedStateOf { backStack.size == 1 } }
	val entries = remember(backStack, isRoot) { entryProvider(isRoot) }

	// The individual strategies were already remembered; the enclosing list was not, so
	// NavDisplay still received a new `sceneStrategies` value on every pass.
	val nowPlayingScene = remember { NowPlayingSceneStrategy<NavKey>() }
	val bottomSheetScene = remember { BottomSheetSceneStrategy<NavKey>() }
	// navi-connect: Back pops until a pane shows something else. Material's default pops until the
	// LAYOUT changes, and a library page opened from the home is a second list page in the same
	// layout: with room for two panes (landscape) there was nothing to go back to, so Back from
	// Discover, Fresh, Random, Recently added or Mixed for You left the app.
	val listDetailScene = rememberListDetailSceneStrategy<NavKey>(
		backNavigationBehavior = BackNavigationBehavior.PopUntilContentChange
	)
	// navi-connect: material's, keyed per list page so a library page opened beside the rail
	// slides in as it does in portrait (see the class).
	val listPageScene = remember(listDetailScene) { ListPageSceneStrategy(listDetailScene) }
	val sceneStrategies = remember(nowPlayingScene, bottomSheetScene, listPageScene) {
		listOf(nowPlayingScene, bottomSheetScene, listPageScene)
	}


	// Announce a fill landing (or failing) wherever the user happens to be.
	//
	// Collected here rather than in the sheet that started it: a fill takes minutes and
	// routinely outlives that sheet, the artist page and the process. The manager emits
	// exactly once per fill, from `settle`, so however many screens are watching there is
	// only ever one snackbar. The system-notification half lives in androidMain — it needs
	// a runtime permission, and commonMain still has to compile for iOS.
	val lbBot = koinInject<LbBotManager>()
	val fillLanded = stringResource(Res.string.lbbot_fill_landed)
	val fillLost = stringResource(Res.string.lbbot_fill_lost)
	val fillLostReason = stringResource(Res.string.lbbot_fill_lost_reason)
	val fillNeedsSorting = stringResource(Res.string.lbbot_fill_needs_sorting)
	val openInLbBot = stringResource(Res.string.action_open_in_lbbot)
	val uriHandler = LocalUriHandler.current
	LaunchedEffect(Unit) {
		lbBot.fillEvents.collect { event ->
			val name = event.album.ifBlank { event.artist }.ifBlank { return@collect }
			// `fillAnnouncementFor` picks the words (B-026). Three outcomes are worth
			// interrupting for: done; a `failed` whose files did arrive and wait in lb-bot to be
			// filed by hand (ledger state `needs_match`: a stalled gap placement, a `picking` gap
			// with a downloaded track (Q-031), or an album fill lb-bot calls needs_match); and any
			// other `failed`. `needs_pick` is the picker waiting on the user and `cancelled` is
			// something they just did, both of which announce themselves; `gave_up` means we
			// stopped looking, not that anything happened.
			when (fillAnnouncementFor(event)) {
				FillAnnouncement.DONE -> snackBarState.showSnackbar(fillLanded.replace("%1\$s", name))
				FillAnnouncement.NEEDS_SORTING -> {
					// Not a failure, and it must not read like one or send the user after a retry
					// that would only refetch what is already on disk. The remedy is lb-bot's own
					// workspace, so the snackbar carries the way there when the hub says where it
					// is — read at settle time, not at composition.
					val webUrl = lbBot.webUrl.value
					val path = fillAnnouncementLinkPath(event)
					val link = if (webUrl.isNotBlank() && path.isNotBlank()) "$webUrl/$path" else ""
					val result = snackBarState.showSnackbar(
						message = fillNeedsSorting.replace("%1\$s", name),
						actionLabel = openInLbBot.takeIf { link.isNotBlank() },
						// Explicit: with an action the default is Indefinite, which would hold
						// this collector (and every announcement queued behind it) until tapped.
						duration = SnackbarDuration.Long
					)
					if (result == SnackbarResult.ActionPerformed) runCatching { uriHandler.openUri(link) }
				}
				// lb-bot's own sentence names the cause ("103 peers offered 2,047 files, but none
				// in FLAC…"), which is worth more than any wording of ours.
				FillAnnouncement.COULDNT_GET -> snackBarState.showSnackbar(
					if (event.reason.isNotBlank()) {
						fillLostReason.replace("%1\$s", name).replace("%2\$s", event.reason)
					} else {
						fillLost.replace("%1\$s", name)
					}
				)
				null -> Unit
			}
		}
	}

	// A screen requested from outside the composition (a Quick Picks widget tile). Held until
	// there is a signed-in session to show it in — a cold start from the widget composes this
	// before the session restores, and pushing the album over the login screen would strand it.
	val deepLink by AppDeepLink.pending.collectAsStateWithLifecycle()
	LaunchedEffect(deepLink, isLoggedIn) {
		val target = deepLink ?: return@LaunchedEffect
		if (!isLoggedIn) return@LaunchedEffect
		if (backStack.lastOrNull() != target) backStack.add(target)
		AppDeepLink.consume()
	}

	// A streaming URL shared in from another app. Resolved here rather than in
	// MainActivity because only lb-bot can say what the link IS, and the platform
	// entry point deliberately knows about neither lb-bot nor navigation3.
	//
	// Held until signed in for the same reason the album deep link is: a share into a
	// cold start composes this before the session restores, and pushing a release page
	// over the login screen strands it.
	val sharedText by AppDeepLink.sharedText.collectAsStateWithLifecycle()
	val linkUnresolved = stringResource(Res.string.info_link_unresolved)
	LaunchedEffect(sharedText, isLoggedIn) {
		val url = sharedText ?: return@LaunchedEffect
		if (!isLoggedIn) return@LaunchedEffect
		// Consumed BEFORE the round trip, not after: resolution takes seconds, and a
		// recomposition in the meantime would otherwise re-enter this effect and
		// resolve the same link a second time.
		AppDeepLink.consumeSharedText()
		val answer = lbBot.resolveLink(url)
		if (answer == null || !answer.resolved) {
			// lb-bot's own sentence when it has one — it names the provider and what
			// it could not read, which is worth more than a generic line. A null
			// answer means we could not ask at all and has nothing to say, and an
			// older lb-bot sends no `reason`; both fall back.
			snackBarState.showSnackbar(answer?.reason?.ifBlank { null } ?: linkUnresolved)
			return@LaunchedEffect
		}
		backStack.add(
			if (answer.rgid.isNotBlank()) {
				Screen.ExternalAlbum(
					rgid = answer.rgid,
					artistName = answer.artist,
					title = answer.title,
					fromLink = true
				)
			} else {
				Screen.ExternalArtist(answer.mbid, answer.artist)
			}
		)
	}

	SharedTransitionLayout {
		CompositionLocalProvider(
			LocalPlatformContext provides platformContext,
			LocalNavStack provides backStack,
			LocalSnackBarState provides snackBarState,
			LocalSharedTransitionScope provides this@SharedTransitionLayout,
			LocalBottomBarScrollManager provides scrollManager,
			LocalExpressiveBlur provides expressiveBlur
		) {
			NavicTheme {
				// navi-connect P1: the avatar menu is composed ONCE, around the page and the rail, and opened from
				// any header avatar through ShellController. A fork line: drop it and the avatar opens nothing.
				AvatarMenuDrawer {
					Row(modifier = Modifier.fillMaxSize()) {
						if (platformContext.sizeClass.widthSizeClass >= WindowWidthSizeClass.Medium
							&& Screen.Login !in backStack) {
							// navi-connect (B-008): the cover's scheme while a washed page is on top.
							RailAmbient { SideBar() }
						}
						Scaffold(
							modifier = Modifier.nestedScroll(scrollManager.connection),
							snackbarHost = {
								SnackbarHost(hostState = snackBarState) { snackBarData ->
									NavicSnackBar(snackBarData = snackBarData)
								}
							}
						) {
							// navi-connect P1 round 2: the playing cover's wash is drawn HERE, behind the pages, and a
							// washed page draws none of its own (`AppWash`). A page change used to flash the colours and a
							// back gesture showed a white bar, both of them the neutral surface showing between or through
							// the pages; now what shows is the colour the page already is. The blur source is the whole of
							// it, backdrop included, as the pages' own wash was. A fork line: drop it and the flash is back.
							AppWash(
								modifier = Modifier
									.weight(1f)
									.consumeWindowInsets(
										WindowInsets.safeDrawing.only(WindowInsetsSides.Start)
									)
									.background(rememberLibraryTabBackground())
									.expressiveBlurSource(expressiveBlur)
							) {
								NavDisplay(
									modifier = Modifier.fillMaxSize(),
									backStack = backStack,
									sceneStrategies = sceneStrategies,
									// Upstream's, restored: it arrived at alpha42 and no fork merge ever
									// carried it (App.kt is taken fork-whole), and NavDisplay's default is
									// the saveable-state decorator ALONE. Without the ViewModel-store decorator every ViewModel
									// lived in the activity's store for the life of the process — every
									// artist page ever opened stayed alive and re-read on every library
									// bump. With it, a ViewModel dies when its entry leaves the back stack —
									// and a tab switch clears the back stack, so every root tab and the
									// lyrics opt out through `PersistentViewModelStoreOwner` (the list of
									// sites is CLAUDE.md §2 rule 14; the decorator and the opt-outs are one
									// change and must not be merged apart). navigation3 keys each store by the
									// entry's content key, so an entry that is merely covered (a sheet over
									// it, a detail pane beside it) keeps its ViewModel. Merge rule: navic-gaps
									// CLAUDE.md §2 — both decorators, in this order.
									entryDecorators = listOf(
										rememberSaveableStateHolderNavEntryDecorator(),
										rememberViewModelStoreNavEntryDecorator()
									),
									onBack = {
										if (backStack.size >= 2) {
											backStack.removeLastOrNull()
										}
									},
									entryProvider = entries,
									// navi-connect I-042: page timing follows the animation style (PageMotion.kt).
									transitionSpec = {
										val motion = pageMotionOf(preferenceManager.animationStyle)
										Material3Transitions.enter(motion)(
											density
										) togetherWith Material3Transitions.exit(motion)(
											density
										)
									},
									popTransitionSpec = {
										val motion = pageMotionOf(preferenceManager.animationStyle)
										Material3Transitions.popEnter(motion)(
											density
										) togetherWith Material3Transitions.popExit(motion)(
											density
										)
									},
									predictivePopTransitionSpec = {
										if (preferenceManager.enablePredictiveBackAnimations) {
											slideInHorizontally(
												animationSpec = tween(300, easing = EaseOutQuart),
												initialOffsetX = { -it }
											) togetherWith slideOutHorizontally(
												animationSpec = tween(300, easing = EaseOutQuart),
												targetOffsetX = { it }
											)
										} else {
											ContentTransform(EnterTransition.None, ExitTransition.None)
										}
									}
								)
							}
						}
					}
				}
				// version check is annoying to do on iOS
				if (preferenceManager.checkForUpdates
					&& platformContext.platformType == PlatformType.Android
					&& !BuildInfo.FDROID
				) {
					ChangelogSheet()
				}
				// navi-connect I-022: "Fix album match", composed ONCE above the pages. A retag
				// changes the album's Navidrome id, so the album's page is left when lb-bot
				// finishes — and the sheet must go on saying what was written. A fork line:
				// without it the "Fix album match" and "Wrong edition?" rows open nothing.
				AlbumMatchHost()
				// navi-connect P1: the New sheet, composed ONCE like the one above and opened by the bar's New
				// button from any page. A fork line: drop it and New opens nothing.
				NewSheetHost()
				// navi-connect: the level the phone's volume buttons set on the device that is
				// playing, drawn by the app itself. A fork line, like the one above.
				RemoteVolumeHud()
				// navi-connect I-043: keeps cover colours ready before a page needs them (the last
				// playing cover for the cold start, then the prefetch). A fork line, like the two above.
				ColourArrivalEffects()
			}
		}
	}
}

@OptIn(ExperimentalMaterial3AdaptiveApi::class, ExperimentalMaterial3Api::class)
private fun entryProvider(
	isRoot: Boolean
): (NavKey) -> (NavEntry<NavKey>) {
	val navtabMetadata = if (isRoot)
		listPane("root") + ListPageSceneStrategy.listPage() + transitionSpec {
			ContentTransform(fadeIn(), fadeOut())
		} + popTransitionSpec {
			ContentTransform(fadeIn(), fadeOut())
		} + predictivePopTransitionSpec {
			ContentTransform(fadeIn(), fadeOut())
		}
	else listPane("root") + ListPageSceneStrategy.listPage()
	val imageViewMetadata = transitionSpec { ContentTransform(fadeIn(), ExitTransition.None) }
		.plus(popTransitionSpec { ContentTransform(EnterTransition.None, fadeOut()) })
		.plus(predictivePopTransitionSpec { ContentTransform(EnterTransition.None, fadeOut()) })

	return androidx.navigation3.runtime.entryProvider {
		// tabs
		entry<Screen.Home>(metadata = navtabMetadata) {
			Washed { HomeScreen() }
		}
		entry<Screen.Library>(metadata = navtabMetadata) {
			LibraryScreen()
		}
		entry<Screen.Starred>(metadata = navtabMetadata) {
			Washed { StarredScreen() }
		}
		entry<Screen.AlbumList>(metadata = navtabMetadata) { key ->
			Washed { AlbumListScreen(key.nested, key.listType) }
		}
		entry<Screen.PlaylistList>(metadata = navtabMetadata) { key ->
			Washed { PlaylistListScreen(key.nested) }
		}
		entry<Screen.ArtistList>(metadata = navtabMetadata) { key ->
			Washed { ArtistListScreen(key.nested, key.listType) }
		}
		entry<Screen.GenreList>(metadata = navtabMetadata) { key ->
			Washed { GenreListScreen(key.nested) }
		}
		entry<Screen.SongList>(metadata = navtabMetadata) { key ->
			Washed { SongListScreen(key.nested, key.artistId, key.artistName, key.listType) }
		}

		entry<Screen.RadioList>(metadata = navtabMetadata) { key ->
			Washed { RadioListScreen(key.nested) }
		}

		entry<Screen.Fresh>(metadata = navtabMetadata) { key ->
			Washed { FreshScreen(key.nested) }
		}

		entry<Screen.Discover>(metadata = navtabMetadata) { key ->
			Washed { DiscoverScreen(key.nested) }
		}

		// P3: pushed from Search's browse cards, with a back arrow, so no tab metadata.
		entry<Screen.Charts> {
			Washed { BrowseFeedScreen(BrowseKind.CHARTS) }
		}

		entry<Screen.EditorsPicks> {
			Washed { BrowseFeedScreen(BrowseKind.PICKS) }
		}

		entry<Screen.MixList>(metadata = navtabMetadata) { key ->
			Washed { MixListScreen(key.nested) }
		}

		// misc
		entry<Screen.Login> {
			LoginScreen()
		}
		entry<Screen.ImageView>(metadata = imageViewMetadata) { key ->
			ImageViewScreen(
				coverArtId = key.coverArtId,
				title = key.title,
				sharedTransitionKey = key.sharedTransitionKey
			)
		}
		entry<Screen.NowPlaying>(
			metadata = NowPlayingSceneStrategy.bottomSheet(maxWidth = Dp.Unspecified)
		) {
			NowPlayingScreen()
		}
		entry<Screen.Lyrics>(metadata = NowPlayingSceneStrategy.bottomSheet(isTransparent = true)) {
			val player = koinInject<MediaPlayerViewModel>()
			val playerState by player.steadyState.collectAsState()
			val song = playerState.currentSong
			LyricsScreen(song)
		}
		entry<Screen.Queue>(metadata = BottomSheetSceneStrategy.bottomSheet()) {
			QueueScreen()
		}
		entry<Screen.PlaybackSpeed>(metadata = BottomSheetSceneStrategy.bottomSheet()) {
			PlaybackSpeedScreen()
		}
		entry<Screen.CollectionDetail>(metadata = detailPane("root")) { key ->
			CollectionDetailScreen(key.collectionId, key.tab, key.openGaps)
		}
		entry<Screen.SongDetailScreen>(metadata = detailPane("root")) { key ->
			SongDetailScreen(key.songId, key.coverArtId)
		}
		entry<Screen.SongDetailSheet>(
			metadata = { key -> BottomSheetSceneStrategy.bottomSheet() }
		) { key ->
			SongDetailSheet(key.songId, key.coverArtId)
		}
		entry<Screen.Search>(metadata = navtabMetadata) { key ->
			Washed { SearchScreen(key.nested) }
		}
		entry<Screen.ShareList> {
			Washed { ShareListScreen() }
		}
		// The fork's pushed library pages wear the playing song's colours like its other pages:
		// `Washed`, i.e. `BrowsingAmbient`, and a line each in `WashedBrowsingScreens` so the
		// tablet rail follows too (`WashedBrowsingDriftTest` fails when the two disagree). Every
		// surface does but the artist and album pages (their own cover decides those), Settings and
		// the smart playlist form included.
		entry<Screen.LibraryMatches> {
			Washed { LibraryMatchesScreen() }
		}
		entry<Screen.Downloads> { key ->
			Washed { DownloadsScreen(key.section) }
		}
		entry<Screen.SavedQueues> {
			Washed { SavedQueuesScreen() }
		}
		entry<Screen.ArtistDetail> { key ->
			ArtistDetailScreen(key.artist)
		}

		// P5: the custom radio's two steps, washed like the pages it is opened over.
		entry<Screen.CustomRadio> {
			Washed { CustomRadioScreen() }
		}

		// P4 round 2: the whole discography behind chips; washed like the artist's Songs page.
		entry<Screen.ArtistDiscography> { key ->
			Washed { ArtistDiscographyScreen(key.artist) }
		}

		entry<Screen.Statistics>(metadata = navtabMetadata) { key ->
			Washed { StatisticsScreen(key.nested) }
		}

		// Not overloads of the two above: those take Navidrome ids and load from
		// Room, and keeping them strict is what makes their not-in-the-DB error
		// path correct. An lb-bot artist or release-group has no Navidrome row.
		entry<Screen.ExternalArtist> { key ->
			// P4: an unowned artist has no cover of its own, so it wears the playing song's.
			Washed { ExternalArtistScreen(key.artistMbid, key.name) }
		}

		entry<Screen.ExternalAlbum> { key ->
			ExternalAlbumScreen(key.rgid, key.artistMbid, key.artistName, key.title, key.artistId, key.fromLink)
		}

		// settings
		entry<Screen.Settings.Root>(metadata = listPane("settings")) {
			Washed { SettingsScreen() }
		}
		entry<Screen.Settings.Appearance>(metadata = detailPane("settings")) {
			Washed { SettingsAppearanceScreen() }
		}
		entry<Screen.Settings.BottomAppBar>(metadata = detailPane("settings")) {
			Washed { BottomBarScreen() }
		}
		entry<Screen.Settings.NowPlaying>(metadata = detailPane("settings")) {
			Washed { SettingsNowPlayingScreen() }
		}
		entry<Screen.Settings.Playback>(metadata = detailPane("settings")) {
			Washed { SettingsPlaybackScreen() }
		}
		entry<Screen.Settings.Effects>(metadata = detailPane("settings")) {
			Washed { AudioEffectsScreen() }
		}
		entry<Screen.Settings.Developer>(metadata = detailPane("settings")) {
			Washed { SettingsDeveloperScreen() }
		}
		entry<Screen.GenreDetail> { key ->
			Washed { GenreDetailScreen(key.genreName) }
		}
		entry<Screen.Settings.DownloadQuality> {
			Washed { SettingsDownloadQualityScreen() }
		}
		entry<Screen.Settings.AppIcon>(metadata = detailPane("settings")) {
			Washed { SettingsAppIconScreen() }
		}
		entry<Screen.Settings.Equaliser> {
			Washed { SettingsEqualiserScreen() }
		}
		entry<Screen.Settings.About>(metadata = detailPane("settings")) {
			Washed { SettingsAboutScreen() }
		}
		entry<Screen.Settings.DataStorage>(metadata = detailPane("settings")) {
			Washed { SettingsDataStorageScreen() }
		}
		entry<Screen.Settings.PendingActions>(metadata = detailPane("settings")) {
			Washed { PendingActionsScreen() }
		}
		entry<Screen.Settings.Fonts> {
			Washed { FontsScreen() }
		}
		entry<Screen.Settings.Themes> {
			Washed { SettingsThemesScreen() }
		}
		entry<Screen.Settings.CustomHeaders> {
			Washed { SettingsCustomHeadersScreen() }
		}
		entry<Screen.Settings.StreamingQuality> {
			Washed { SettingsStreamingQualityScreen() }
		}
		entry<Screen.Settings.Logs> {
			Washed { SettingsLogsScreen() }
		}
		entry<Screen.Settings.NaviConnect>(metadata = detailPane("settings")) {
			Washed { NaviConnectScreen() }
		}
		entry<Screen.SmartPlaylistEditor> { key ->
			Washed { SmartPlaylistEditorScreen(playlistId = key.playlistId) }
		}
	}
}

/**
 * Themes a browsing screen — the tabs, and the list screens they push — with the same cover
 * pipeline the album and artist detail pages use: see
 * [paige.navic.ui.components.common.BrowsingAmbient].
 *
 * Applied here rather than inside each screen because it has to wrap the whole Scaffold, bars
 * included, and because one hook is the difference between a tab that was themed and a tab
 * somebody forgot. [paige.navic.ui.screens.library.LibraryScreen] is the exception: it wraps
 * itself in the same helper.
 */
@Composable
private fun Washed(content: @Composable () -> Unit) {
	BrowsingAmbient(content = content)
}
