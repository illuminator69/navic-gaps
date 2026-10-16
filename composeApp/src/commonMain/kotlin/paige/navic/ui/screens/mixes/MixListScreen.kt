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
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.plus
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.MediumFloatingActionButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.IntOffset
import navic.composeapp.generated.resources.action_more
import paige.navic.di.LocalPlatformContext
import paige.navic.di.isLandscape
import paige.navic.domain.models.settings.BottomBarCollapseMode
import paige.navic.icons.outlined.Equalizer
import paige.navic.icons.outlined.Add
import paige.navic.icons.outlined.PlaylistAdd
import paige.navic.ui.components.common.SegmentedListButton
import paige.navic.ui.components.common.SegmentedListButtonDefaults
import paige.navic.ui.components.common.SegmentedListItem
import paige.navic.ui.components.common.SegmentedListItemDefaults
import paige.navic.ui.components.common.SegmentedListItemDefaults.segmentedShapes
import paige.navic.ui.components.common.SongRowDefaults
import paige.navic.ui.components.layouts.TopBarButton
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
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.text.style.TextOverflow
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
import navic.composeapp.generated.resources.mix_regenerate_failed
import navic.composeapp.generated.resources.mix_save_current
import navic.composeapp.generated.resources.mix_unknown_kind
import navic.composeapp.generated.resources.option_mix_name
import navic.composeapp.generated.resources.title_delete_mix
import navic.composeapp.generated.resources.title_discover_mixes
import navic.composeapp.generated.resources.title_rename_mix
import navic.composeapp.generated.resources.title_save_mix
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import paige.navic.di.LocalBottomBarScrollManager
import paige.navic.domain.manager.PreferenceManager
import paige.navic.domain.models.Mix
import paige.navic.domain.models.MixKind
import paige.navic.domain.models.settings.BottomBarVisibilityMode
import paige.navic.icons.Icons
import paige.navic.icons.outlined.Delete
import paige.navic.icons.outlined.MoreVert
import paige.navic.icons.outlined.PlaylistPlay
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
@Composable
fun MixListScreen(nested: Boolean = false) {
	// A root tab keeps its ViewModel across tab switches, which clear the back stack — the
	// lifetime it had before the ViewModel-store decorator was restored (CLAUDE.md §2 rule 14).
	// Its `init` loads over the network, so without this every visit to the tab re-fetched.
	// Nested (a home-page button, Discover's "See all") it dies with its entry, like
	// upstream's list screens.
	val viewModel = koinViewModel<MixListViewModel>(
		viewModelStoreOwner = if (nested) {
			LocalViewModelStoreOwner.current!!
		} else {
			koinInject<PersistentViewModelStoreOwner>()
		}
	)
	val preferenceManager = koinInject<PreferenceManager>()
	val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
	val mixes by viewModel.mixes.collectAsStateWithLifecycle()
	val busyId by viewModel.busyId.collectAsStateWithLifecycle()
	val connected by viewModel.connected.collectAsStateWithLifecycle()
	val message by viewModel.message.collectAsStateWithLifecycle()
	val recipe by viewModel.currentRecipe.collectAsStateWithLifecycle()
	val hubMessage by viewModel.hubMessage.collectAsStateWithLifecycle()

	var renameTarget by remember { mutableStateOf<Mix?>(null) }
	var deleteTarget by remember { mutableStateOf<Mix?>(null) }
	var saveTarget by remember { mutableStateOf<Mix?>(null) }
	var formOpen by remember { mutableStateOf(false) }

	val snackbarHostState = remember { SnackbarHostState() }
	val failedMsg = stringResource(Res.string.mix_regenerate_failed)
	val unknownMsg = stringResource(Res.string.mix_unknown_kind)
	val offlineMsg = stringResource(Res.string.mix_not_connected)
	LaunchedEffect(message) {
		when (message) {
			MixMessage.RegenerateFailed -> snackbarHostState.showSnackbar(failedMsg)
			MixMessage.UnknownKind -> snackbarHostState.showSnackbar(unknownMsg)
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
	val platformContext = LocalPlatformContext.current
	val scrollManager = LocalBottomBarScrollManager.current
	val slideSpec = MaterialTheme.motionScheme.defaultSpatialSpec<IntOffset>()
	val scaleInSpec = MaterialTheme.motionScheme.fastSpatialSpec<Float>()
	val liveRecipe = recipe

	// "Save what's playing" is the one-tap create: the autoplay settings already in force ARE a
	// recipe, and until mixes existed they were simply discarded at the end of the session.
	// Offered only while something is actually steering playback, so it never saves an empty
	// recipe. A top-bar action, beside the floating button's general "New mix".
	val actions: @Composable RowScope.() -> Unit = {
		if (liveRecipe != null && connected) {
			val icon = @Composable {
				Icon(
					Icons.Outlined.PlaylistAdd,
					contentDescription = stringResource(Res.string.mix_save_current)
				)
			}
			if (!nested) IconButton(onClick = { saveTarget = liveRecipe }) { icon() }
			else TopBarButton(onClick = { saveTarget = liveRecipe }) { icon() }
		}
	}

	// A root tab, so it wears the root chrome — `App.kt` wraps it in `Washed` and it
	// must not re-enter `NavicTheme` (§6 rule 6). Reached both as a tab and from
	// Discover's "See all", hence `nested`, exactly like Fresh and Discover.
	Scaffold(
		topBar = {
			val title = @Composable { Text(stringResource(Res.string.title_discover_mixes)) }
			if (!nested) RootTopBar(title, scrollBehavior, actions)
			else NestedTopBar(title, actions = actions)
		},
		snackbarHost = { SnackbarHost(snackbarHostState) },
		// "New mix" is the general create, and the only way to reach the `genre` and `artist`
		// kinds at all: the autoplay modes cannot express them. Upstream's create button, as
		// on the playlist list.
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
						onClick = {
							viewModel.loadSeedOptions()
							formOpen = true
						}
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
		LazyColumn(
			modifier = Modifier
				.padding(top = innerPadding.calculateTopPadding())
				.fillMaxSize()
				.then(if (!nested) Modifier.nestedScroll(scrollBehavior.nestedScrollConnection) else Modifier)
				.padding(horizontal = 16.dp),
			contentPadding = innerPadding.withoutTop() + PaddingValues(top = 8.dp, bottom = 88.dp),
			verticalArrangement = if (mixes.isEmpty()) Arrangement.Center
			else Arrangement.spacedBy(SegmentedListItemDefaults.SegmentedGap)
		) {
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
					busy = busyId == mix.id,
					// `dropUnlessResumed` because regenerating navigates nowhere but does
					// start playback, and a double tap during the transition would run the
					// recipe twice.
					onClick = dropUnlessResumed { viewModel.play(mix) },
					onMenu = { sheetTarget = mix },
					modifier = Modifier.animateItem()
				)
			}
		}
	}

	sheetTarget?.let { target ->
		MixSheet(
			mix = target,
			onDismissRequest = { sheetTarget = null },
			onPreview = { viewModel.preview(target) }.takeIf { target.kind != MixKind.ADAPTIVE },
			onRename = { renameTarget = target },
			onDelete = { deleteTarget = target }
		)
	}

	val previewing by viewModel.previewing.collectAsStateWithLifecycle()
	previewing?.let { target ->
		val previewSongs by viewModel.previewSongs.collectAsStateWithLifecycle()
		val previewLoading by viewModel.previewLoading.collectAsStateWithLifecycle()
		MixPreviewSheet(
			mix = target,
			songs = previewSongs,
			loading = previewLoading,
			// Plays what is ON SCREEN rather than re-running the recipe, which
			// would give a different tracklist and make the preview a lie.
			onPlay = { viewModel.playPreviewed() },
			onAddToQueue = { viewModel.enqueuePreviewed() },
			onDismissRequest = { viewModel.dismissPreview() }
		)
	}

	if (formOpen) {
		val artists by viewModel.seedArtists.collectAsStateWithLifecycle()
		val genres by viewModel.seedGenres.collectAsStateWithLifecycle()
		val playing by viewModel.playingSong.collectAsStateWithLifecycle()
		MixFormSheet(
			playing = playing,
			artists = artists,
			genres = genres,
			defaultMoodCharacter = viewModel.defaultMoodCharacter,
			onSave = { recipe ->
				// `save` takes the name separately because the rename dialog reuses
				// it; the form has already put the name on the recipe.
				viewModel.save(recipe, recipe.name)
				formOpen = false
			},
			onDismissRequest = { formOpen = false }
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

	saveTarget?.let { recipe ->
		MixNameDialog(
			title = stringResource(Res.string.title_save_mix),
			initial = recipe.name,
			onConfirm = { name ->
				viewModel.save(recipe, name)
				saveTarget = null
			},
			onDismissRequest = { saveTarget = null }
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
 * One recipe, as a segmented library row: its face, its name, and what kind of recipe it is. It
 * deliberately says nothing a saved-queue row says (how many tracks, where it left off), because a
 * mix holds no tracks until it is played; that absence is the feature. A tap plays it; a long
 * press, or the "more" button, opens its sheet.
 */
@Composable
private fun MixRow(
	mix: Mix,
	shapes: ListItemShapes,
	busy: Boolean,
	onClick: () -> Unit,
	onMenu: () -> Unit,
	modifier: Modifier = Modifier
) {
	SegmentedListItem(
		onClick = onClick,
		onLongClick = onMenu,
		enabled = !busy,
		shapes = shapes,
		modifier = modifier,
		colors = ListItemDefaults.colors(
			containerColor = SongRowDefaults.containerColor(false),
			supportingContentColor = SongRowDefaults.supportingContentColor(false),
			disabledContainerColor = SongRowDefaults.containerColor(false)
		),
		verticalAlignment = Alignment.CenterVertically,
		contentPadding = SongRowDefaults.ContentPadding,
		leadingContent = {
			Box(Modifier.size(50.dp), contentAlignment = Alignment.Center) {
				if (busy) {
					// Regenerating is a round trip to AudioMuse or Navidrome, and without this
					// the tap looks like it did nothing for as long as it takes.
					CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
				} else {
					MixArtwork(mix = mix, modifier = Modifier.size(50.dp))
				}
			}
		},
		content = { Text(mix.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
		supportingContent = {
			Text(mixKindLabel(mix), maxLines = 1, overflow = TextOverflow.Ellipsis)
		},
		trailingContent = {
			IconButton(onClick = onMenu) {
				Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(Res.string.action_more))
			}
		}
	)
}

@Composable
private fun MixNameDialog(
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
