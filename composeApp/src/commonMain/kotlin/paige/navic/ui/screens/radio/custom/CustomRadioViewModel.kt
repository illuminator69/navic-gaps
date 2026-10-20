package paige.navic.ui.screens.radio.custom

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import paige.navic.data.database.dao.ArtistDao
import paige.navic.data.database.dao.SongDao
import paige.navic.data.database.mappers.toDomainModel
import paige.navic.domain.manager.CustomRadioDone
import paige.navic.domain.manager.HubManager
import paige.navic.domain.manager.PreferenceManager
import paige.navic.domain.manager.ShellController
import paige.navic.domain.manager.StationCatalogue
import paige.navic.domain.manager.StationCountAnswer
import paige.navic.domain.manager.StationManager
import paige.navic.domain.manager.customRadioPlan
import paige.navic.domain.models.DomainArtist
import paige.navic.domain.models.settings.StationCharacter
import paige.navic.ui.screens.mixes.viewmodels.saveMix
import kotlin.random.Random
import kotlin.time.Clock

/**
 * The custom radio's flow (P5): the artists picked, the character and the variety, the live count,
 * and Done. One instance for both steps, so going back from Tune to Pick keeps the choice.
 *
 * Leaving the flow saves nothing: the mix is written only by [finish].
 */
class CustomRadioViewModel(
	private val artistDao: ArtistDao,
	private val songDao: SongDao,
	private val stationManager: StationManager,
	private val hubManager: HubManager,
	private val shell: ShellController,
	preferenceManager: PreferenceManager
) : ViewModel() {

	/** Every artist in the library, the most played first; null until read. */
	private val _artists = MutableStateFlow<List<DomainArtist>?>(null)
	val artists: StateFlow<List<DomainArtist>?> = _artists.asStateFlow()

	private val _picked = MutableStateFlow<List<PickedArtist>>(emptyList())
	val picked: StateFlow<List<PickedArtist>> = _picked.asStateFlow()

	/** The character autoplay uses, to start from. */
	private val _character = MutableStateFlow(preferenceManager.stationCharacter)
	val character: StateFlow<StationCharacter> = _character.asStateFlow()

	private val _variety = MutableStateFlow(Variety.Medium)
	val variety: StateFlow<Variety> = _variety.asStateFlow()

	private val _count = MutableStateFlow<CountState>(CountState.Idle)
	val count: StateFlow<CountState> = _count.asStateFlow()

	val catalogue: StateFlow<StationCatalogue> = stationManager.catalogue

	/** Artist variety is drawn only when the hub says `start` obeys it. */
	val varietyOffered: StateFlow<Boolean> = combine(stationManager.available, stationManager.catalogue) { on, cat ->
		on && Variety.entries.all { cat.takesVariety(it.wire) }
	}.stateIn(viewModelScope, SharingStarted.Eagerly, false)

	private val _finishing = MutableStateFlow(false)
	val finishing: StateFlow<Boolean> = _finishing.asStateFlow()

	init {
		viewModelScope.launch {
			_artists.value = artistDao.getArtistsMostPlayed().map { it.toDomainModel() }
		}
		// One count per artist set and character, never per tap: the spinner at once, the request
		// 250 ms after the last change, and an answer only for the key still on screen.
		viewModelScope.launch {
			combine(_picked, _character) { picked, character -> CountKey.of(picked.map { it.id }, character) }
				.distinctUntilChanged()
				.collectLatest { key ->
					if (key.artistIds.isEmpty()) {
						_count.value = CountState.Idle
						return@collectLatest
					}
					_count.value = reduceCount(_count.value, key, key, null)
					delay(COUNT_DEBOUNCE_MS)
					val answer = askCount(key)
					_count.value = reduceCount(_count.value, key, currentKey(), answer)
				}
		}
	}

	private fun currentKey(): CountKey = CountKey.of(_picked.value.map { it.id }, _character.value)

	private suspend fun askCount(key: CountKey): StationCountAnswer {
		val character = StationCharacter.fromWire(key.character) ?: return StationCountAnswer.Failed
		val songs = key.artistIds.map { id -> songDao.getSongsByArtistId(id).map { it.toDomainModel() } }
		// The seeds the radio would start from, so the count is of the radio that Done plays.
		val seed = customRadioPlan(engine = true, name = null, songsPerArtist = songs).station
			?: return StationCountAnswer.Unavailable
		return stationManager.count(seed, character)
	}

	/** Pick or unpick [artist]; at [CUSTOM_RADIO_MAX_ARTISTS] an unpicked one is not added. */
	fun toggle(artist: DomainArtist) {
		_picked.value = _picked.value.let { now ->
			when {
				now.any { it.id == artist.id } -> now.filterNot { it.id == artist.id }
				now.size >= CUSTOM_RADIO_MAX_ARTISTS -> now
				else -> now + PickedArtist(artist.id, artist.name, artist.coverArtId.orEmpty())
			}
		}
	}

	fun setCharacter(character: StationCharacter) {
		_character.value = character
	}

	/** Not a count key: tapping it asks the hub nothing. */
	fun setVariety(variety: Variety) {
		_variety.value = variety
	}

	/**
	 * Done: save the recipe and wait for the hub to have it, then call [onSaved] with its id (the
	 * page opens the mix, which is built there and not played: P5 round 2, the owner's note) and
	 * say so through [ShellController], since this page is gone by then. A save the hub never
	 * confirms calls [onFailed] and leaves the choices as they are, to try again.
	 */
	fun finish(onSaved: (String) -> Unit, onFailed: () -> Unit) {
		val artists = _picked.value
		if (_finishing.value || !canFinish(artists.size, _count.value)) return
		_finishing.value = true
		viewModelScope.launch {
			val recipe = customRadioRecipe(
				id = mintMixId(), name = radioTitle(artists), artists = artists,
				character = _character.value,
				// Medium is what an absent value means, so it is not written.
				variety = _variety.value
			).let { if (it.variety == Variety.Medium.wire) it.copy(variety = "") else it }
			val saved = withContext(NonCancellable) { save(recipe) }
			_finishing.value = false
			if (saved) {
				shell.announceCustomRadio(CustomRadioDone(recipe.id))
				onSaved(recipe.id)
			} else onFailed()
		}
	}

	/** Write [recipe] and wait for the hub's `mixes` broadcast to hold it. */
	private suspend fun save(recipe: paige.navic.domain.models.Mix): Boolean {
		if (!hubManager.connected.value) return false
		hubManager.saveMix(recipe, recipe.name)
		return withTimeoutOrNull(SAVE_WAIT_MS) {
			hubManager.mixes.map { list -> list.any { it.id == recipe.id } }.first { it }
		} ?: false
	}

	private companion object {
		const val COUNT_DEBOUNCE_MS = 250L
		const val SAVE_WAIT_MS = 6_000L

		/**
		 * `mx_<ms>_<hex>`, the hub's own shape. Minted here, unlike other mixes, so Undo and the
		 * start can name the mix before the hub has answered; the hub takes a client-minted id
		 * (PROTOCOL §17.2: a client may have minted it offline).
		 */
		fun mintMixId(): String =
			"mx_${Clock.System.now().toEpochMilliseconds()}_${Random.nextInt(0x1000000).toString(16).padStart(6, '0')}"
	}
}
