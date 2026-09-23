package paige.navic.ui.screens.mixes

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TextField
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
import navic.composeapp.generated.resources.action_rename
import navic.composeapp.generated.resources.info_no_mixes
import navic.composeapp.generated.resources.info_no_mixes_hint
import navic.composeapp.generated.resources.mix_new
import navic.composeapp.generated.resources.mix_preview
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
import paige.navic.domain.models.settings.BottomBarVisibilityMode
import paige.navic.icons.Icons
import paige.navic.icons.outlined.Badge
import paige.navic.icons.outlined.Delete
import paige.navic.icons.outlined.List
import paige.navic.icons.outlined.MoreVert
import paige.navic.icons.outlined.PlaylistPlay
import paige.navic.ui.components.common.ContentUnavailable
import paige.navic.ui.components.common.Dropdown
import paige.navic.ui.components.common.DropdownItem
import paige.navic.ui.components.common.Form
import paige.navic.ui.components.common.FormButton
import paige.navic.ui.components.common.FormRow
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
	val viewModel = koinViewModel<MixListViewModel>()
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

	// A root tab, so it wears the root chrome — `App.kt` wraps it in `Washed` and it
	// must not re-enter `NavicTheme` (§6 rule 6). Reached both as a tab and from
	// Discover's "See all", hence `nested`, exactly like Fresh and Discover.
	Scaffold(
		topBar = {
			val title = @Composable { Text(stringResource(Res.string.title_discover_mixes)) }
			if (!nested) RootTopBar(title, scrollBehavior) else NestedTopBar(title)
		},
		snackbarHost = { SnackbarHost(snackbarHostState) },
		bottomBar = {
			val scrollManager = LocalBottomBarScrollManager.current
			if (!nested ||
				preferenceManager.bottomBarVisibilityMode == BottomBarVisibilityMode.AllScreens
			) {
				RootBottomBar(scrolled = scrollManager.isTriggered)
			}
		}
	) { innerPadding ->
		Column(
			Modifier
				.padding(innerPadding)
				.fillMaxSize()
				.verticalScroll(rememberScrollState())
				.padding(top = 8.dp, start = 16.dp, end = 16.dp, bottom = 32.dp)
		) {
			if (mixes.isEmpty()) {
				ContentUnavailable(
					// NOT fillMaxSize(): this sits inside a verticalScroll, where the
					// height constraint is infinite and filling it would blow up. Same
					// trap the Download Center's empty state hit.
					modifier = Modifier.fillMaxWidth().padding(vertical = 64.dp),
					icon = Icons.Outlined.PlaylistPlay,
					label = stringResource(Res.string.info_no_mixes),
					description = stringResource(Res.string.info_no_mixes_hint)
				)
			} else {
				Form {
					mixes.forEach { mix ->
						MixRow(
							mix = mix,
							busy = busyId == mix.id,
							// `dropUnlessResumed` because regenerating navigates
							// nowhere but does start playback, and a double tap
							// during the transition would run the recipe twice.
							onClick = dropUnlessResumed { viewModel.play(mix) },
							onPreview = { viewModel.preview(mix) },
							onRename = { renameTarget = mix },
							onDelete = { deleteTarget = mix }
						)
					}
				}
			}

			// Two create paths, and they are not redundant.
			//
			// "New mix" is the general one, and the only way to reach the `genre`
			// and `artist` kinds at all — the autoplay modes below cannot express
			// them, so before it existed those two recipes could only arrive from
			// Feishin.
			//
			// "Save what's playing" is the one-tap one: the autoplay settings
			// already in force ARE a recipe, and until mixes existed they were
			// simply discarded at the end of the session. Offered only while
			// something is actually steering playback, so it never saves an empty
			// recipe.
			if (connected) {
				Spacer(Modifier.height(8.dp))
				FormButton(onClick = {
					viewModel.loadSeedOptions()
					formOpen = true
				}) {
					Text(stringResource(Res.string.mix_new))
				}
			}
			val liveRecipe = recipe
			if (liveRecipe != null && connected) {
				Spacer(Modifier.height(8.dp))
				FormButton(onClick = { saveTarget = liveRecipe }) {
					Text(stringResource(Res.string.mix_save_current))
				}
			}
		}
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
				FormButton(
					onClick = {
						viewModel.delete(target.id)
						deleteTarget = null
					},
					color = MaterialTheme.colorScheme.error
				) { Text(stringResource(Res.string.action_delete)) }
				FormButton(onClick = { deleteTarget = null }) {
					Text(stringResource(Res.string.action_cancel))
				}
			},
			content = { Text(target.name) }
		)
	}
}

@Composable
private fun MixRow(
	mix: Mix,
	busy: Boolean,
	onClick: () -> Unit,
	onPreview: () -> Unit,
	onRename: () -> Unit,
	onDelete: () -> Unit
) {
	var menuOpen by remember { mutableStateOf(false) }
	FormRow(onClick = if (busy) null else onClick) {
		Box(Modifier.size(44.dp).padding(end = 12.dp), contentAlignment = Alignment.Center) {
			if (busy) {
				// Regenerating is a round trip to AudioMuse or Navidrome, and without
				// this the tap looks like it did nothing for as long as it takes.
				CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
			} else {
				MixArtwork(mix = mix)
			}
		}
		Column(Modifier.weight(1f)) {
			Text(mix.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
			Text(
				mixKindLabel(mix),
				style = MaterialTheme.typography.bodyMedium,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis
			)
		}
		Row(verticalAlignment = Alignment.CenterVertically) {
			IconButton(onClick = { menuOpen = true }) {
				Icon(Icons.Outlined.MoreVert, contentDescription = null, Modifier.size(20.dp))
			}
			Dropdown(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
				// First, because it is the only entry that answers "what is in this"
				// — and unlike every other list in the app, that question has no
				// answer here until the recipe has actually been run.
				DropdownItem(
					text = { Text(stringResource(Res.string.mix_preview)) },
					onClick = {
						menuOpen = false
						onPreview()
					},
					leadingIcon = { Icon(Icons.Outlined.List, null) }
				)
				DropdownItem(
					text = { Text(stringResource(Res.string.action_rename)) },
					onClick = {
						menuOpen = false
						onRename()
					},
					leadingIcon = { Icon(Icons.Outlined.Badge, null) }
				)
				DropdownItem(
					text = { Text(stringResource(Res.string.action_delete)) },
					onClick = {
						menuOpen = false
						onDelete()
					},
					leadingIcon = { Icon(Icons.Outlined.Delete, null) }
				)
			}
		}
	}
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
			FormButton(
				onClick = { onConfirm(nameState.text.toString()) },
				color = MaterialTheme.colorScheme.primary
			) { Text(stringResource(Res.string.action_ok)) }
			FormButton(onClick = onDismissRequest) {
				Text(stringResource(Res.string.action_cancel))
			}
		},
		content = {
			TextField(
				state = nameState,
				label = { Text(stringResource(Res.string.option_mix_name)) },
				lineLimits = TextFieldLineLimits.SingleLine
			)
		}
	)
}
