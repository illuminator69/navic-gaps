package paige.navic.ui.components.common

import kotlin.test.Test
import kotlin.test.assertEquals

/** M4 (audit L1-05, L1-06): a list waits with placeholders and says it is empty only once it is. */
class ListBodyTest {
	@Test
	fun anEmptyListIsLoadingWhileItsReadIsOut() {
		assertEquals(ListBody.Loading, listBody(isLoading = true, count = 0))
		assertEquals(ListBody.Empty, listBody(isLoading = false, count = 0))
	}

	@Test
	fun rowsAreShownWhateverTheRead() {
		assertEquals(ListBody.Items, listBody(isLoading = true, count = 5))
		assertEquals(ListBody.Items, listBody(isLoading = false, count = 5))
	}
}
