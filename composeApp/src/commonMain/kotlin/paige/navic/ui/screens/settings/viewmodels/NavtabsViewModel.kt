package paige.navic.ui.screens.settings.viewmodels

import androidx.lifecycle.ViewModel
import com.russhwolf.settings.Settings
import com.russhwolf.settings.set
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import paige.navic.domain.models.settings.NavbarConfig
import paige.navic.domain.models.settings.NavbarTab
import paige.navic.ui.core.UiState

class NavtabsViewModel(
	private val settings: Settings
) : ViewModel() {
	private val json = Json

	private companion object {
		const val HOME_BUTTON_TABS_MIGRATED = "navbarHomeButtonTabsMigrated"
	}

	val state: StateFlow<UiState<NavbarConfig>>
		field = MutableStateFlow<UiState<NavbarConfig>>(UiState.Loading())

	init {
		try {
			state.value = UiState.Success(loadConfig())
		} catch (e: Exception) {
			state.value = UiState.Error(e)
		}
	}

	private fun loadConfig(): NavbarConfig {
		val raw = settings.getStringOrNull(NavbarConfig.KEY)
			?: return NavbarConfig.default
		val config: NavbarConfig = json.decodeFromString(raw)
		// Merged, not discarded-on-mismatch alone: a tab id added after this config
		// was written has no row in it, and without the merge it would be missing
		// from the bar and from the reorder screen permanently. The version check
		// still handles a genuinely incompatible shape.
		val merged = config.takeIf { it.version == NavbarConfig.VERSION }
			?.let { NavbarConfig.merged(it) }
			?: return NavbarConfig.default

		// One-shot: Fresh / Discover / Mixed for You became the library home's top
		// buttons, so they come off the bar. `merged()` preserves stored visibility
		// by design, which is why flipping the defaults alone would never reach an
		// install that already has them — and bumping VERSION would throw away the
		// user's order to move three flags. Written back immediately and guarded by
		// its own flag, so turning one of them back on is not overruled next launch.
		if (!settings.getBoolean(HOME_BUTTON_TABS_MIGRATED, false)) {
			settings[HOME_BUTTON_TABS_MIGRATED] = true
			val hidden = NavbarConfig.withHomeButtonTabsHidden(merged)
			if (hidden != merged) {
				settings[NavbarConfig.KEY] = json.encodeToString(hidden)
				return hidden
			}
		}
		return merged
	}

	private fun setConfig(newConfig: NavbarConfig) {
		state.value = UiState.Success(newConfig)
		settings[NavbarConfig.KEY] = json.encodeToString(newConfig)
	}

	fun move(from: Int, to: Int) {
		val config = (state.value as UiState.Success).data
		setConfig(
			config.copy(
				tabs = config.tabs.toMutableList().apply {
					add(to, removeAt(from))
				}
			))
	}

	fun toggleVisibility(id: NavbarTab.Id) {
		val config = (state.value as UiState.Success).data
		setConfig(
			config.copy(
				tabs = config.tabs.map {
					if (it.id == id) it.copy(visible = !it.visible) else it
				}
			)
		)
	}
}
