package paige.navic.domain.manager

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import paige.navic.ui.navigation.ShellTab
import paige.navic.ui.navigation.chipRequestAfterTap

/**
 * navi-connect P1: what the app's shell has open. The New sheet and the avatar menu are composed
 * once, in App.kt (as `AlbumMatchHost` is), and opened from anywhere through this: the bar's New
 * button, a header avatar on any root page. No bar or page owns their state, so they open over a
 * pushed page just as over a tab, survive the bar being rebuilt per screen, and a rotation leaves
 * them where they were. One of them is open at a time.
 *
 * Process-scoped, so closed after a process death: neither holds anything worth restoring.
 */
class ShellController {
	private val _newSheetOpen = MutableStateFlow(false)
	val newSheetOpen: StateFlow<Boolean> = _newSheetOpen.asStateFlow()

	private val _avatarMenuOpen = MutableStateFlow(false)
	val avatarMenuOpen: StateFlow<Boolean> = _avatarMenuOpen.asStateFlow()

	fun openNew() {
		_avatarMenuOpen.value = false
		_newSheetOpen.value = true
	}

	fun closeNew() {
		_newSheetOpen.value = false
	}

	fun openAvatarMenu() {
		_newSheetOpen.value = false
		_avatarMenuOpen.value = true
	}

	fun closeAvatarMenu() {
		_avatarMenuOpen.value = false
	}

	/**
	 * Straight to the New sheet's "Add from a link" step, without the sheet (P3: Search's "Nothing
	 * for …" offers it). The host shows the paste sheet and calls [linkRequestHandled].
	 */
	private val _linkRequested = MutableStateFlow(false)
	val linkRequested: StateFlow<Boolean> = _linkRequested.asStateFlow()

	fun openAddFromLink() {
		_avatarMenuOpen.value = false
		_newSheetOpen.value = false
		_linkRequested.value = true
	}

	fun linkRequestHandled() {
		_linkRequested.value = false
	}

	/**
	 * A custom radio's Done (P5) saved a mix: the flow's page has gone by the time it is said, so
	 * the New sheet's host, which is always there, shows the snackbar (with Undo, which deletes the
	 * saved mix; its page then leaves).
	 */
	private val _customRadioDone = MutableSharedFlow<CustomRadioDone>(extraBufferCapacity = 1)
	val customRadioDone: SharedFlow<CustomRadioDone> = _customRadioDone.asSharedFlow()

	fun announceCustomRadio(done: CustomRadioDone) {
		_customRadioDone.tryEmit(done)
	}

	/**
	 * A Library chip to show next (P5), by its name: the custom radio's Done lands on Mixes. The
	 * Library applies it and calls [libraryChipShown]. Not in `Screen.Library`, whose equality is
	 * what selects the Library tab.
	 */
	private val _libraryChip = MutableStateFlow<String?>(null)
	val libraryChip: StateFlow<String?> = _libraryChip.asStateFlow()

	fun showLibraryChip(chip: String) {
		_libraryChip.value = chip
	}

	fun libraryChipShown() {
		_libraryChip.value = null
	}

	/** A bar or rail tap: any tab but the Library's drops a pending chip request ([chipRequestAfterTap]). */
	fun onTabTapped(tab: ShellTab) {
		_libraryChip.value = chipRequestAfterTap(_libraryChip.value, tab)
	}
}

/** A custom radio's Done saved it under Mixes as [mixId]. */
data class CustomRadioDone(val mixId: String)
