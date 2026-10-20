package paige.navic.ui.components.sheets

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetValue
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import kotlinx.coroutines.launch
import navic.composeapp.generated.resources.Res
import navic.composeapp.generated.resources.new_add_link
import navic.composeapp.generated.resources.action_undo
import navic.composeapp.generated.resources.custom_radio_failed
import navic.composeapp.generated.resources.custom_radio_start_failed
import navic.composeapp.generated.resources.custom_radio_started
import navic.composeapp.generated.resources.custom_radio_started_unsaved
import navic.composeapp.generated.resources.new_add_link_hint
import navic.composeapp.generated.resources.new_custom_radio
import navic.composeapp.generated.resources.new_custom_radio_hint
import navic.composeapp.generated.resources.new_playlist
import navic.composeapp.generated.resources.new_playlist_hint
import navic.composeapp.generated.resources.new_smart_playlist
import navic.composeapp.generated.resources.new_smart_playlist_hint
import navic.composeapp.generated.resources.title_new
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import paige.navic.di.LocalNavStack
import paige.navic.di.LocalSnackBarState
import paige.navic.domain.manager.HubManager
import paige.navic.domain.manager.LbBotManager
import paige.navic.domain.manager.ShellController
import paige.navic.icons.Icons
import paige.navic.icons.outlined.Link
import paige.navic.icons.outlined.Note
import paige.navic.icons.outlined.Radio
import paige.navic.icons.outlined.Sparkle
import paige.navic.ui.navigation.Screen
import paige.navic.ui.screens.downloads.PasteLinkSheet
import paige.navic.ui.screens.playlist.dialogs.PlaylistCreateDialog
import paige.navic.ui.theme.NavicTheme
import paige.navic.ui.util.rememberNowPlayingCoverAmbient

/*
 * navi-connect P1: the New sheet, opened by the bar's fourth button from any page. It makes things:
 * a playlist, a smart playlist, and (with lb-bot) an album from a streaming link. Hosted once in
 * App.kt beside AlbumMatchHost, behind ShellController, so it opens over a pushed page as over a tab
 * and never changes the stack or the selected tab by opening. What a row then does may navigate: a
 * new playlist opens its page, as it does from the Playlists tab.
 */

/** The sheet's rows in the board's order (board CreateMenu). */
internal enum class NewSheetItem { Playlist, SmartPlaylist, CustomRadio, AddFromLink }

/**
 * [lbBotAvailable]: the link row is offered. It needs lb-bot to say what the link is, so it is absent
 * without the lb-bot layer, not greyed.
 */
internal fun newSheetItems(lbBotAvailable: Boolean): List<NewSheetItem> = buildList {
	add(NewSheetItem.Playlist)
	add(NewSheetItem.SmartPlaylist)
	// Always: without the hub's engine a custom radio plays Navidrome's similar songs, and without
	// the hub it plays and says it could not be saved.
	add(NewSheetItem.CustomRadio)
	if (lbBotAvailable) add(NewSheetItem.AddFromLink)
}

@Composable
fun NewSheetHost() {
	val shell = koinInject<ShellController>()
	val lbBot = koinInject<LbBotManager>()
	val backStack = LocalNavStack.current
	val open by shell.newSheetOpen.collectAsState()
	// What the picked row shows after the sheet has gone: the dialog or the paste sheet.
	var followUp by remember { mutableStateOf<NewSheetItem?>(null) }
	// A custom radio's Done (P5): its page has gone, so it is said here. Undo deletes the saved mix
	// and leaves the radio playing (starting it replaced the queue, which cannot be given back).
	val snackBarState = LocalSnackBarState.current
	val hubManager = koinInject<HubManager>()
	val startedWords = stringResource(Res.string.custom_radio_started)
	val unsavedWords = stringResource(Res.string.custom_radio_started_unsaved)
	val notStartedWords = stringResource(Res.string.custom_radio_start_failed)
	val failedWords = stringResource(Res.string.custom_radio_failed)
	val undoWords = stringResource(Res.string.action_undo)
	LaunchedEffect(Unit) {
		shell.customRadioDone.collect { done ->
			val words = when {
				done.saved && done.started -> startedWords
				done.started -> unsavedWords
				done.saved -> notStartedWords
				else -> failedWords
			}
			val result = snackBarState.showSnackbar(
				message = words,
				actionLabel = if (done.saved && done.started) undoWords else null,
				duration = SnackbarDuration.Long
			)
			if (result == SnackbarResult.ActionPerformed) hubManager.actDeleteMix(done.mixId)
		}
	}
	// Search's "Add from a link" (P3) opens the paste step without the sheet.
	val linkRequested by shell.linkRequested.collectAsState()
	LaunchedEffect(linkRequested) {
		if (linkRequested) {
			followUp = NewSheetItem.AddFromLink
			shell.linkRequestHandled()
		}
	}

	if (open) {
		// The same gate as the Downloads page's paste button, plus "the lb-bot layer is on": the route
		// check alone reads an empty advert as capable, which is true with no hub at all. Asked of the
		// hub when the sheet opens (a cached answer, not a request each time).
		LaunchedEffect(Unit) { lbBot.ensureAvailability() }
		NewSheet(
			items = newSheetItems(lbBotAvailable = lbBot.isConfigured && lbBot.supportsResolveLink),
			onDismissRequest = shell::closeNew,
			onPick = { item ->
				shell.closeNew()
				when (item) {
					NewSheetItem.Playlist, NewSheetItem.AddFromLink -> followUp = item
					NewSheetItem.SmartPlaylist -> backStack.add(Screen.SmartPlaylistEditor())
					NewSheetItem.CustomRadio -> backStack.add(Screen.CustomRadio)
				}
			}
		)
	}

	when (followUp) {
		// The new playlist's page opens after it is made; the Room insert has already put it in the
		// lists, so there is nothing for `onRefresh` to refresh.
		NewSheetItem.Playlist -> NowPlayingColours {
			DialogViewModelScope {
				PlaylistCreateDialog(
					onDismissRequest = { followUp = null },
					onRefresh = {},
					navigateAfterwards = true,
					onSmartRequested = { backStack.add(Screen.SmartPlaylistEditor()) }
				)
			}
		}
		NewSheetItem.AddFromLink -> NowPlayingColours {
			PasteLinkSheet(
				resolve = lbBot::resolveLink,
				onDismissRequest = { followUp = null },
				onResolved = { key ->
					followUp = null
					backStack.add(key)
				}
			)
		}
		else -> Unit
	}
}

/**
 * What this host shows after the sheet has gone is composed outside every page, so it would wear the
 * app's plain scheme; every surface wears the playing song's colours (the artist and album pages
 * aside), so the dialog and the paste sheet are themed from the playing cover as the sheet is.
 */
@Composable
private fun NowPlayingColours(content: @Composable () -> Unit) {
	val ambient = rememberNowPlayingCoverAmbient()
	NavicTheme(ambient.scheme, contentColor = ambient.onAmbient, content = content)
}

/**
 * A ViewModel store that lives as long as its content. [PlaylistCreateDialog]'s ViewModel is keyed
 * by its songs alone and keeps the name typed into it; opened from a page it dies with the page's
 * entry, but hosted here it would be the activity's and a second "New playlist" would open with the
 * first one's name (and its "created" state).
 */
@Composable
private fun DialogViewModelScope(content: @Composable () -> Unit) {
	val owner = remember {
		object : ViewModelStoreOwner {
			override val viewModelStore = ViewModelStore()
		}
	}
	DisposableEffect(owner) { onDispose { owner.viewModelStore.clear() } }
	CompositionLocalProvider(LocalViewModelStoreOwner provides owner, content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewSheet(
	items: List<NewSheetItem>,
	onDismissRequest: () -> Unit,
	onPick: (NewSheetItem) -> Unit
) {
	// Fully shown or gone: the one resting place the wrapper's default also has.
	val sheetState = rememberBottomSheetState(
		initialValue = SheetValue.Hidden,
		enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)
	)
	val scope = rememberCoroutineScope()
	// A row slides the sheet away first, then acts, so the dialog or page does not arrive over it.
	val pick = { item: NewSheetItem ->
		scope.launch { sheetState.hide() }.invokeOnCompletion {
			if (!sheetState.isVisible) onPick(item)
		}
		Unit
	}

	ModalBottomSheet(
		onDismissRequest = onDismissRequest,
		sheetState = sheetState,
		ambient = rememberNowPlayingCoverAmbient(),
		sheetTitle = stringResource(Res.string.title_new)
	) {
		SheetHeader(stringResource(Res.string.title_new))
		items.forEach { item ->
			when (item) {
				NewSheetItem.Playlist -> NewSheetRow(
					Icons.Outlined.Note, stringResource(Res.string.new_playlist),
					stringResource(Res.string.new_playlist_hint)
				) { pick(item) }
				NewSheetItem.SmartPlaylist -> NewSheetRow(
					Icons.Outlined.Sparkle, stringResource(Res.string.new_smart_playlist),
					stringResource(Res.string.new_smart_playlist_hint)
				) { pick(item) }
				NewSheetItem.CustomRadio -> NewSheetRow(
					Icons.Outlined.Radio, stringResource(Res.string.new_custom_radio),
					stringResource(Res.string.new_custom_radio_hint)
				) { pick(item) }
				NewSheetItem.AddFromLink -> NewSheetRow(
					Icons.Outlined.Link, stringResource(Res.string.new_add_link),
					stringResource(Res.string.new_add_link_hint)
				) { pick(item) }
			}
		}
		Spacer(Modifier.height(12.dp))
	}
}

/** An icon tile, a title and a one-line subtitle (board 6). */
@Composable
private fun NewSheetRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.clickable(role = Role.Button, onClick = onClick)
			.padding(horizontal = 20.dp, vertical = 10.dp),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(14.dp)
	) {
		Surface(
			modifier = Modifier.size(48.dp),
			shape = RoundedCornerShape(14.dp),
			color = MaterialTheme.colorScheme.surfaceContainerHigh
		) {
			Box(contentAlignment = Alignment.Center) {
				Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
			}
		}
		Column(Modifier.weight(1f)) {
			Text(
				text = title,
				style = MaterialTheme.typography.titleMedium,
				fontWeight = FontWeight.Bold,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis
			)
			Text(
				text = subtitle,
				style = MaterialTheme.typography.bodyMedium,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
				maxLines = 2,
				overflow = TextOverflow.Ellipsis
			)
		}
	}
}
