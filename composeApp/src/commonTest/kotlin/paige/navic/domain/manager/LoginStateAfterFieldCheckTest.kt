package paige.navic.domain.manager

import paige.navic.ui.core.LoginUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/*
 * The N9 walk: after "Can't reach the server at nas…", typing "http://127.0.0.1:4534sandbox" and
 * Log in showed the field's line under the box of the attempt before. A refused attempt clears it.
 */
class LoginStateAfterFieldCheckTest {
	private val earlier = LoginUiState.Error(Exception("nas"), LoginFailureKind.UNREACHABLE, host = "nas")

	@Test
	fun anAttemptTheFieldsRefuseClearsTheEarlierError() {
		assertEquals(LoginUiState.Idle, loginStateAfterFieldCheck(earlier, fieldsOk = false))
	}

	@Test
	fun anAttemptTheFieldsAcceptLeavesItToTheLogin() {
		// The login itself replaces it with Loading at once.
		assertSame(earlier, loginStateAfterFieldCheck(earlier, fieldsOk = true))
	}

	@Test
	fun nothingElseIsTouched() {
		for (state in listOf(LoginUiState.Idle, LoginUiState.Loading, LoginUiState.Success)) {
			assertSame(state, loginStateAfterFieldCheck(state, fieldsOk = false))
		}
	}
}
