package paige.navic.ui.navigation

import androidx.navigation3.runtime.NavKey
import androidx.savedstate.serialization.SavedStateConfiguration
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic

/**
 * How the back stack is saved and restored: every [Screen] as a polymorphic [NavKey]. Out of `App.kt`
 * so `SavedStackGuardTest` round-trips each screen through the real configuration (audit L2-08): a
 * renamed screen or a reordered saved enum breaks a stack restored after an update, and the test
 * makes that visible before it ships.
 */
@OptIn(ExperimentalSerializationApi::class)
val navSavedStateConfiguration = SavedStateConfiguration {
	serializersModule = SerializersModule {
		polymorphic(NavKey::class) {
			subclassesOfSealed<Screen>()
		}
	}
}
