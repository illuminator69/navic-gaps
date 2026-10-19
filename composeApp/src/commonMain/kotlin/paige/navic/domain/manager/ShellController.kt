package paige.navic.domain.manager

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
}
