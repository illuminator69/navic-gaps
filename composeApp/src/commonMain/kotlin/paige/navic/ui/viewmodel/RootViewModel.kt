package paige.navic.ui.viewmodel

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * The one store the shell's events go through (audit L2-03). The rail sits outside the pages' entries
 * and gets the Activity's [RootViewModel], every page its entry's: with the flow in each view model the
 * rail's tap on the selected tab reached nobody. A singleton, so every [RootViewModel] shares it.
 */
class RootEventBus {
	val events: SharedFlow<RootViewModel.Event>
		field = MutableSharedFlow<RootViewModel.Event>(
			extraBufferCapacity = 1,
			onBufferOverflow = BufferOverflow.DROP_OLDEST
		)

	fun requestScrollToTop() {
		events.tryEmit(RootViewModel.Event.ScrollToTop)
	}
}

class RootViewModel(private val bus: RootEventBus) : ViewModel() {
	val events: SharedFlow<Event> get() = bus.events

	fun requestScrollToTop() = bus.requestScrollToTop()

	sealed class Event {
		object ScrollToTop : Event()
	}
}
