package paige.navic.ui.screens.mixes.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import paige.navic.domain.manager.HubManager
import paige.navic.domain.manager.PreferenceManager
import paige.navic.domain.manager.RadioManager
import paige.navic.domain.models.Mix
import paige.navic.domain.manager.StationCatalogue
import paige.navic.domain.models.settings.StationCharacter

/** What the list screen needs to say after an action that can fail silently. */
enum class MixMessage { NotConnected }

/**
 * Mixed for You.
 *
 * Thin on purpose: the recipes live on the hub and the engine lives in
 * [RadioManager], so this owns neither. A mix is played from its page (P5 round 2,
 * `MixDetailViewModel`), which builds it; this list only opens, renames and deletes.
 *
 * Nothing here caches the list. It is a hub [StateFlow], and a local copy would go
 * stale the moment another client created a mix.
 */
class MixListViewModel(
	private val hubManager: HubManager,
	private val radioManager: RadioManager,
	private val preferenceManager: PreferenceManager
) : ViewModel() {

	/**
	 * Most recently updated first — the same ordering the hub evicts by, so the row
	 * at the bottom of this list is visibly the one that would go next.
	 */
	val mixes: StateFlow<List<Mix>> = hubManager.mixes
		.map { list -> list.sortedByDescending { it.updatedAt } }
		.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

	val connected: StateFlow<Boolean> = hubManager.connected

	private val _message = MutableStateFlow<MixMessage?>(null)
	val message: StateFlow<MixMessage?> = _message.asStateFlow()

	/**
	 * The hub's own words when it refuses a write, shown in preference to anything
	 * here. It answers rather than dropping precisely so the screen that claimed the
	 * save can take it back.
	 */
	private val _hubMessage = MutableStateFlow<String?>(null)
	val hubMessage: StateFlow<String?> = _hubMessage.asStateFlow()

	init {
		viewModelScope.launch {
			hubManager.mixErrors.collect { _hubMessage.value = it }
		}
	}

	/** The character a new radio or Your mix recipe is offered with: the one autoplay uses. */
	val defaultCharacter: StationCharacter get() = preferenceManager.stationCharacter

	/** The hub's station engine can be asked (PROTOCOL §18): Your mix is offered, and radios play as stations. */
	val stationsAvailable: StateFlow<Boolean> = radioManager.stationsAvailable

	val catalogue: StateFlow<StationCatalogue> = radioManager.catalogue

	fun clearMessage() { _message.value = null }

	fun clearHubMessage() { _hubMessage.value = null }


	/**
	 * Create or update. Every write goes through the hub and comes back as a
	 * broadcast, so nothing is written locally and nothing needs reconciling —
	 * which is also why an action taken while disconnected says so rather than
	 * appearing to work.
	 */
	fun save(recipe: Mix, name: String) {
		if (!hubManager.connected.value) {
			_message.value = MixMessage.NotConnected
			return
		}
		hubManager.saveMix(recipe, name)
	}

	fun rename(id: String, name: String) {
		if (!hubManager.connected.value) {
			_message.value = MixMessage.NotConnected
			return
		}
		hubManager.actRenameMix(id, name)
	}

	fun delete(id: String) {
		if (!hubManager.connected.value) {
			_message.value = MixMessage.NotConnected
			return
		}
		hubManager.actDeleteMix(id)
	}

}

/**
 * Write [recipe] to the hub under [name] (its own when blank). The new mix form's save and the
 * queue sheet's "Save as a mix" both come here; the mix arrives back as a broadcast.
 */
internal fun HubManager.saveMix(recipe: Mix, name: String) {
	actSaveMix(
		name = name.ifBlank { recipe.name },
		kind = recipe.kind,
		id = recipe.id.ifBlank { null },
		seedId = recipe.seedId,
		seedName = recipe.seedName,
		moodCharacter = recipe.moodCharacter,
		count = recipe.count,
		coverArtId = recipe.coverArtId,
		character = recipe.character,
		seedIds = recipe.seedIds,
		variety = recipe.variety,
		moodFilter = recipe.moodFilter
	)
}
