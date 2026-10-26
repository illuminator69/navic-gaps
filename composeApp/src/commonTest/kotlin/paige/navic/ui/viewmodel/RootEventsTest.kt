package paige.navic.ui.viewmodel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals

/*
 * Audit L2-03 (W-04, ran): the rail sits outside the pages' entries and got the Activity's
 * RootViewModel, every page got its entry's, so the rail's tap on the selected tab went into a store no
 * page listened to (Home stayed scrolled; the second tap that clears a chip did nothing). Every
 * RootViewModel speaks through one RootEventBus.
 */
class RootEventsTest {

	@Test
	fun aTapFromOneViewModelReachesAnother() {
		val bus = RootEventBus()
		val rail = RootViewModel(bus)
		val page = RootViewModel(bus)
		val seen = mutableListOf<RootViewModel.Event>()
		val scope = CoroutineScope(Dispatchers.Unconfined)
		scope.launch { page.events.collect { seen += it } }

		rail.requestScrollToTop()

		assertEquals(listOf<RootViewModel.Event>(RootViewModel.Event.ScrollToTop), seen)
		scope.cancel()
	}
}
