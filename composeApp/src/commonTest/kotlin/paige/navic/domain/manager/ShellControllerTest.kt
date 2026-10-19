package paige.navic.domain.manager

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/*
 * P1: the New sheet and the avatar menu are hosted once, in App.kt, and opened from anywhere (a bar,
 * a header avatar) through this process-scoped controller, so no bar or page owns their state. One of
 * them is open at a time.
 */
class ShellControllerTest {

	@Test
	fun bothStartClosed() {
		val shell = ShellController()
		assertFalse(shell.newSheetOpen.value)
		assertFalse(shell.avatarMenuOpen.value)
	}

	@Test
	fun newOpensAndCloses() {
		val shell = ShellController()
		shell.openNew()
		assertTrue(shell.newSheetOpen.value)
		shell.closeNew()
		assertFalse(shell.newSheetOpen.value)
	}

	@Test
	fun theAvatarMenuOpensAndCloses() {
		val shell = ShellController()
		shell.openAvatarMenu()
		assertTrue(shell.avatarMenuOpen.value)
		shell.closeAvatarMenu()
		assertFalse(shell.avatarMenuOpen.value)
	}

	@Test
	fun openingOneClosesTheOther() {
		val shell = ShellController()
		shell.openAvatarMenu()
		shell.openNew()
		assertTrue(shell.newSheetOpen.value)
		assertFalse(shell.avatarMenuOpen.value)
		shell.openAvatarMenu()
		assertTrue(shell.avatarMenuOpen.value)
		assertFalse(shell.newSheetOpen.value)
	}

	@Test
	fun closingWhatIsClosedChangesNothing() {
		val shell = ShellController()
		shell.openNew()
		shell.closeAvatarMenu()
		assertTrue(shell.newSheetOpen.value)
	}
}
