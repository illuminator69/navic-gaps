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
import paige.navic.domain.models.DomainSong
import paige.navic.domain.models.Mix
import paige.navic.domain.models.MixKind
import paige.navic.domain.manager.StationCatalogue
import paige.navic.domain.models.settings.StationCharacter

/** What the list screen needs to say after an action that can fail silently. */
enum class MixMessage { RegenerateFailed, UnknownKind, NotConnected }

/**
 * Mixed for You.
 *
 * Thin on purpose: the recipes live on the hub and the engine lives in
 * [RadioManager], so this owns neither. What it does own is the one piece of state
 * that is genuinely local — **which mix is currently being regenerated** — because
 * a regeneration is a round trip to AudioMuse or Navidrome and a row with no
 * feedback reads as a dead tap.
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

	private val _busyId = MutableStateFlow<String?>(null)
	val busyId: StateFlow<String?> = _busyId.asStateFlow()

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

	/** Whether [mix] plays as a station right now, in which case it has no preview: it is built as it plays. */
	fun stationBacked(mix: Mix): Boolean = radioManager.stationBacked(mix)

	/**
	 * Play Your mix, the seedless station that sits at the head of this list. Not a stored
	 * recipe: there is one, it is always there, and saving it is the queue's "Save as a mix".
	 */
	fun playYourMix(name: String) {
		if (_busyId.value != null) return
		_busyId.value = YOUR_MIX_ID
		viewModelScope.launch {
			try {
				if (!radioManager.playYourMix(name)) _message.value = MixMessage.RegenerateFailed
			} finally {
				_busyId.value = null
			}
		}
	}

	fun clearMessage() { _message.value = null }

	fun clearHubMessage() { _hubMessage.value = null }


	/**
	 * A mix's tracks, built but not played.
	 *
	 * Its own state rather than a parameter on the sheet, because the build is a
	 * round trip to AudioMuse or Navidrome and the sheet has to open immediately —
	 * a tap that shows nothing for several seconds reads as a dead control, which
	 * is the same reason `busyId` exists for the play path.
	 */
	private val _previewing = MutableStateFlow<Mix?>(null)
	val previewing: StateFlow<Mix?> = _previewing.asStateFlow()

	private val _previewSongs = MutableStateFlow<List<DomainSong>>(emptyList())
	val previewSongs: StateFlow<List<DomainSong>> = _previewSongs.asStateFlow()

	private val _previewLoading = MutableStateFlow(false)
	val previewLoading: StateFlow<Boolean> = _previewLoading.asStateFlow()

	fun preview(mix: Mix) {
		if (MixKind.fromWire(mix.kind) == null) {
			_message.value = MixMessage.UnknownKind
			return
		}
		_previewing.value = mix
		_previewSongs.value = emptyList()
		_previewLoading.value = true
		viewModelScope.launch {
			try {
				_previewSongs.value = radioManager.generate(mix)
			} finally {
				_previewLoading.value = false
			}
		}
	}

	fun dismissPreview() {
		_previewing.value = null
		_previewSongs.value = emptyList()
		_previewLoading.value = false
	}

	/**
	 * Play what the preview already built, rather than running the engine a second
	 * time — which would produce a *different* tracklist from the one on screen and
	 * make the preview a lie.
	 */
	fun playPreviewed() {
		val mix = _previewing.value ?: return
		radioManager.playGenerated(mix, _previewSongs.value)
	}

	fun enqueuePreviewed() {
		radioManager.enqueueMoodMix(_previewSongs.value)
	}

	fun play(mix: Mix) {
		if (_busyId.value != null) return
		if (MixKind.fromWire(mix.kind) == null) {
			_message.value = MixMessage.UnknownKind
			return
		}
		_busyId.value = mix.id
		viewModelScope.launch {
			try {
				if (!radioManager.regenerate(mix)) _message.value = MixMessage.RegenerateFailed
			} finally {
				// In a `finally` because cancelling the scope mid-regeneration (the
				// user navigating away) would otherwise leave the row spinning
				// forever the next time this screen is opened.
				_busyId.value = null
			}
		}
	}

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

	companion object {
		/** [busyId] while Your mix is starting. No hub id looks like it (`mx_…`). */
		const val YOUR_MIX_ID = "your-mix"
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
		variety = recipe.variety
	)
}
