package paige.navic.ui.screens.mixes

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.plus
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.MediumFloatingActionButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.IntOffset
import navic.composeapp.generated.resources.action_more
import paige.navic.di.LocalNavStack
import paige.navic.di.LocalPlatformContext
import paige.navic.di.isLandscape
import paige.navic.ui.navigation.Screen
import paige.navic.domain.models.settings.BottomBarCollapseMode
import paige.navic.icons.outlined.Equalizer
import paige.navic.icons.outlined.Add
import paige.navic.ui.components.common.SegmentedListButton
import paige.navic.ui.components.common.SegmentedListButtonDefaults
import paige.navic.ui.components.common.SegmentedListItemDefaults
import paige.navic.ui.components.common.SegmentedListItemDefaults.segmentedShapes
import paige.navic.ui.util.withoutTop
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import paige.navic.ui.navigation.PersistentViewModelStoreOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.dropUnlessResumed
import navic.composeapp.generated.resources.Res
import navic.composeapp.generated.resources.action_cancel
import navic.composeapp.generated.resources.action_delete
import navic.composeapp.generated.resources.action_ok
import navic.composeapp.generated.resources.info_no_mixes
import navic.composeapp.generated.resources.info_no_mixes_hint
import navic.composeapp.generated.resources.mix_new
import navic.composeapp.generated.resources.mix_not_connected
import navic.composeapp.generated.resources.mix_your_mix_line
import navic.composeapp.generated.resources.queue_station_line
import navic.composeapp.generated.resources.queue_your_mix
import navic.composeapp.generated.resources.option_mix_name
import navic.composeapp.generated.resources.title_delete_mix
import navic.composeapp.generated.resources.title_discover_mixes
import navic.composeapp.generated.resources.title_rename_mix
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import paige.navic.di.LocalBottomBarScrollManager
import paige.navic.domain.manager.PreferenceManager
import paige.navic.domain.models.Mix
import paige.navic.domain.models.MixKind
import paige.navic.domain.models.YOUR_MIX_ID
import paige.navic.domain.models.isCustomRadio
import paige.navic.ui.components.common.LibraryRow
import paige.navic.ui.components.common.LibraryRowDefaults
import paige.navic.domain.models.settings.BottomBarVisibilityMode
import paige.navic.icons.Icons
import paige.navic.icons.outlined.Delete
import paige.navic.icons.outlined.MoreVert
import paige.navic.ui.components.common.ContentUnavailable
import paige.navic.ui.components.dialogs.FormDialog
import paige.navic.ui.components.layouts.NestedTopBar
import paige.navic.ui.components.layouts.RootBottomBar
import paige.navic.ui.components.layouts.RootTopBar
import paige.navic.ui.screens.mixes.viewmodels.MixListViewModel
import paige.navic.ui.screens.mixes.viewmodels.MixMessage

/**
 * Mixed for You: the user's saved recipes.
 *
 * Reads like the Saved Queues screen on purpose — same rows, same overflow, same
 * dialogs — because to the user they are two lists of "things I can start". What
 * they must NOT do is read the same in one specific way: a saved-queue row says
 * how many tracks it holds and where it left off, and a mix row deliberately says
 * neither, because a mix holds no tracks until it is played. That absence is the
 * feature.
 *
 * The list is hub state with no local mirror, so every action here is a frame the
 * hub answers with a broadcast. An action taken while disconnected therefore says
 * so instead of appearing to work and silently evaporating — which is the failure
 * mode the saved-queue screen has an entire tombstone mechanism to avoid, and which
 * a mix avoids by simply refusing.
 */
/** The Mixes list's view model, by the store-owner rule (see `albumListViewModel`). */
@Composable
fun mixListViewModel(nested: Boolean): MixListViewModel =
	// A root tab keeps its ViewModel across tab switches, which clear the back stack — the
	// lifetime it had before the ViewModel-store decorator was restored (CLAUDE.md §2 rule 14).
	// Its `init` loads over the network, so without this every visit to the tab re-fetched.
	// Nested (a home-page button, Discover's "See all") it dies with its entry, like
	// upstream's list screens.
	koinViewModel(
		viewModelStoreOwner = if (nested) {
			LocalViewModelStoreOwner.current!!
		} else {
			koinInject<PersistentViewModelStoreOwner>()
		}
	)

@Composable
fun MixListScreen(nested: Boolean = false) {
	val viewModel = mixListViewModel(nested)
	val preferenceManager = koinInject<PreferenceManager>()
	val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
	val connected by viewModel.connected.collectAsStateWithLifecycle()
	val snackbarHostState = remember { SnackbarHostState() }
	val platformContext = LocalPlatformContext.current
	val backStack = LocalNavStack.current
	val scrollManager = LocalBottomBarScrollManager.current
	val slideSpec = MaterialTheme.motionScheme.defaultSpatialSpec<IntOffset>()
	val scaleInSpec = MaterialTheme.motionScheme.fastSpatialSpec<Float>()

	// "Save what's playing as a mix" is the queue sheet's (you, 2026-10-16): the autoplay
	// settings in force are a recipe for the queue they fill, so it is offered beside that queue,
	// not here.

	// A root tab, so it wears the root chrome — `App.kt` wraps it in `Washed` and it
	// must not re-enter `NavicTheme` (§6 rule 6). Reached both as a tab and from
	// Discover's "See all", hence `nested`, exactly like Fresh and Discover.
	Scaffold(
		topBar = {
			val title = @Composable { Text(stringResource(Res.string.title_discover_mixes)) }
			if (!nested) RootTopBar(title, scrollBehavior)
			else NestedTopBar(title)
		},
		snackbarHost = { SnackbarHost(snackbarHostState) },
		// "New mix" is the custom radio since P5 (it replaced the old form; a Genre mix can no
		// longer be made, an existing one still plays). Upstream's create button, as on the
		// playlist list; the New sheet offers the same.
		floatingActionButton = {
			AnimatedContent(
				connected && (!scrollManager.isTriggered ||
					preferenceManager.bottomBarCollapseMode == BottomBarCollapseMode.Never),
				transitionSpec = {
					val transformOrigin = TransformOrigin(0f, 1f)
					(slideInHorizontally(slideSpec) { it / 2 }
						+ scaleIn(scaleInSpec, transformOrigin = transformOrigin)
						+ slideInVertically(slideSpec) { it / 2 })
						.togetherWith(slideOutHorizontally(slideSpec) { it / 2 }
							+ scaleOut(transformOrigin = transformOrigin)
							+ slideOutVertically(slideSpec) { it / 2 })
						.using(SizeTransform(clip = false))
				}
			) { shown ->
				if (shown) {
					MediumFloatingActionButton(
						shape = MaterialTheme.shapes.large,
						containerColor = MaterialTheme.colorScheme.primary,
						onClick = dropUnlessResumed { backStack.add(Screen.CustomRadio) }
					) {
						Icon(
							imageVector = Icons.Outlined.Add,
							contentDescription = stringResource(Res.string.mix_new),
							modifier = Modifier.size(26.dp)
						)
					}
				}
			}
		},
		bottomBar = {
			val preferVisible =
				preferenceManager.bottomBarVisibilityMode == BottomBarVisibilityMode.AllScreens
			if (!nested || (!platformContext.isLandscape() && preferVisible)) {
				RootBottomBar(scrolled = scrollManager.isTriggered)
			}
		}
	) { innerPadding ->
		MixListBody(
			viewModel = viewModel,
			contentPadding = innerPadding,
			snackbarHostState = snackbarHostState,
			scrollConnection = if (!nested) scrollBehavior.nestedScrollConnection else null
		)
	}
}

/**
 * The Mixes list itself (P5): Your mix first while the engine is there, then the saved recipes
 * (custom radios among them), with their sheet, rename and delete. Hosted by [MixListScreen] and by
 * the Library's Mixes chip; its messages go to [snackbarHostState].
 */
@Composable
fun MixListBody(
	viewModel: MixListViewModel,
	contentPadding: PaddingValues,
	snackbarHostState: SnackbarHostState,
	modifier: Modifier = Modifier,
	scrollConnection: NestedScrollConnection? = null
) {
	val backStack = LocalNavStack.current
	val mixes by viewModel.mixes.collectAsStateWithLifecycle()
	val message by viewModel.message.collectAsStateWithLifecycle()
	val hubMessage by viewModel.hubMessage.collectAsStateWithLifecycle()
	val stationsAvailable by viewModel.stationsAvailable.collectAsStateWithLifecycle()
	val catalogue by viewModel.catalogue.collectAsStateWithLifecycle()

	var renameTarget by remember { mutableStateOf<Mix?>(null) }
	var deleteTarget by remember { mutableStateOf<Mix?>(null) }

	val offlineMsg = stringResource(Res.string.mix_not_connected)
	LaunchedEffect(message) {
		when (message) {
			MixMessage.NotConnected -> snackbarHostState.showSnackbar(offlineMsg)
			null -> {}
		}
		if (message != null) viewModel.clearMessage()
	}
	// The hub's sentence verbatim — it names what was wrong with the recipe, which
	// nothing on this side can reconstruct.
	LaunchedEffect(hubMessage) {
		hubMessage?.let {
			snackbarHostState.showSnackbar(it)
			viewModel.clearHubMessage()
		}
	}

	var sheetTarget by remember { mutableStateOf<Mix?>(null) }

	LazyColumn(
		modifier = modifier
			.padding(top = contentPadding.calculateTopPadding())
			.fillMaxSize()
			.then(if (scrollConnection != null) Modifier.nestedScroll(scrollConnection) else Modifier)
			.padding(horizontal = 16.dp),
		contentPadding = contentPadding.withoutTop() + PaddingValues(top = 8.dp, bottom = 88.dp),
		verticalArrangement = if (mixes.isEmpty() && !stationsAvailable) Arrangement.Center
		else Arrangement.spacedBy(SegmentedListItemDefaults.SegmentedGap)
	) {
		// Your mix heads the list wherever the hub's station engine is there (I-040): the
		// seedless station, from what you play most, on the character autoplay uses. Not a
		// stored recipe, so it has no sheet; saving one is the queue's "Save as a mix".
		if (stationsAvailable) {
			item(key = "your-mix") {
				val yourMixName = stringResource(Res.string.queue_your_mix)
				val character = viewModel.defaultCharacter
				YourMixRow(
					name = yourMixName,
					line = stringResource(
						Res.string.queue_station_line,
						stringResource(Res.string.mix_your_mix_line),
						catalogue.infoFor(character).label.ifBlank { character.label }
					),
					// Opened, not started (P5 round 2): its page builds it and plays from there.
					onClick = dropUnlessResumed { backStack.add(Screen.MixDetail(YOUR_MIX_ID)) },
					modifier = Modifier.animateItem()
				)
			}
		}
		if (mixes.isEmpty()) {
			item(key = "empty") {
				ContentUnavailable(
					modifier = Modifier.fillMaxWidth().padding(vertical = 64.dp),
					icon = Icons.Outlined.Equalizer,
					label = stringResource(Res.string.info_no_mixes),
					description = stringResource(Res.string.info_no_mixes_hint)
				)
			}
		}
		itemsIndexed(mixes, key = { _, mix -> mix.id }) { index, mix ->
			MixRow(
				mix = mix,
				shapes = segmentedShapes(index = index, count = mixes.size),
				onClick = dropUnlessResumed { backStack.add(Screen.MixDetail(mix.id)) },
				onMenu = { sheetTarget = mix },
				modifier = Modifier.animateItem()
			)
		}
	}

	sheetTarget?.let { target ->
		MixSheet(
			mix = target,
			onDismissRequest = { sheetTarget = null },
			onRename = { renameTarget = target },
			onDelete = { deleteTarget = target }
		)
	}

	renameTarget?.let { target ->
		MixNameDialog(
			title = stringResource(Res.string.title_rename_mix),
			initial = target.name,
			onConfirm = { name ->
				viewModel.rename(target.id, name)
				renameTarget = null
			},
			onDismissRequest = { renameTarget = null }
		)
	}

	deleteTarget?.let { target ->
		FormDialog(
			onDismissRequest = { deleteTarget = null },
			icon = { Icon(Icons.Outlined.Delete, null) },
			title = { Text(stringResource(Res.string.title_delete_mix)) },
			buttons = {
				SegmentedListButton(
					modifier = Modifier.fillMaxWidth(),
					onClick = {
						viewModel.delete(target.id)
						deleteTarget = null
					},
					shapes = SegmentedListButtonDefaults.shapes(index = 0, count = 2),
					colors = SegmentedListButtonDefaults.errorColors()
				) { Text(stringResource(Res.string.action_delete)) }
				SegmentedListButton(
					modifier = Modifier.fillMaxWidth(),
					onClick = { deleteTarget = null },
					shapes = SegmentedListButtonDefaults.shapes(index = 1, count = 2)
				) { Text(stringResource(Res.string.action_cancel)) }
			},
			content = { Text(target.name) }
		)
	}
}

/**
 * One recipe, as the Library's row ([LibraryRow]): its face, its name, and what kind of recipe it
 * is. It deliberately says nothing a saved-queue row says (how many tracks, where it left off),
 * because a mix holds no tracks until it is built. A tap opens its page; a long press, or the
 * "more" button, its sheet.
 */
@Composable
private fun MixRow(
	mix: Mix,
	shapes: ListItemShapes,
	onClick: () -> Unit,
	onMenu: () -> Unit,
	modifier: Modifier = Modifier
) {
	LibraryRow(
		title = mix.name,
		line = mixKindLabel(mix),
		onClick = onClick,
		onLongClick = onMenu,
		shapes = shapes,
		modifier = modifier,
		gutter = false,
		// A custom radio's name is its artists, so it may take two lines (P5).
		titleLines = if (isCustomRadio(mix)) 2 else 1,
		art = { MixArtwork(mix = mix, modifier = Modifier.size(LibraryRowDefaults.ArtSize)) },
		trailing = {
			IconButton(onClick = onMenu) {
				Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(Res.string.action_more))
			}
		}
	)
}

/** Your mix's row: a mix row with no menu, since it is not a stored recipe. */
@Composable
private fun YourMixRow(
	name: String,
	line: String,
	onClick: () -> Unit,
	modifier: Modifier = Modifier
) {
	LibraryRow(
		title = name,
		line = line,
		onClick = onClick,
		shapes = segmentedShapes(index = 0, count = 1),
		modifier = modifier,
		gutter = false,
		art = {
			MixArtwork(
				mix = Mix(id = YOUR_MIX_ID, name = name, kind = MixKind.STATION),
				modifier = Modifier.size(LibraryRowDefaults.ArtSize)
			)
		}
	)
}

/** A mix's name, asked for on a rename here and on "Save as a mix" in the queue sheet. */
@Composable
internal fun MixNameDialog(
	title: String,
	initial: String,
	onConfirm: (String) -> Unit,
	onDismissRequest: () -> Unit
) {
	val nameState = rememberTextFieldState(initial)
	FormDialog(
		onDismissRequest = onDismissRequest,
		title = { Text(title) },
		buttons = {
			SegmentedListButton(
				modifier = Modifier.fillMaxWidth(),
				onClick = { onConfirm(nameState.text.toString()) },
				enabled = nameState.text.isNotBlank(),
				shapes = SegmentedListButtonDefaults.shapes(index = 0, count = 2),
				colors = SegmentedListButtonDefaults.primaryColors()
			) { Text(stringResource(Res.string.action_ok)) }
			SegmentedListButton(
				modifier = Modifier.fillMaxWidth(),
				onClick = onDismissRequest,
				shapes = SegmentedListButtonDefaults.shapes(index = 1, count = 2)
			) { Text(stringResource(Res.string.action_cancel)) }
		},
		content = {
			OutlinedTextField(
				state = nameState,
				label = { Text(stringResource(Res.string.option_mix_name)) },
				lineLimits = TextFieldLineLimits.SingleLine
			)
		}
	)
}
