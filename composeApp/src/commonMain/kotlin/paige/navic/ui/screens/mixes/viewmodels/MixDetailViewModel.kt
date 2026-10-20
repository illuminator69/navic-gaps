package paige.navic.ui.screens.mixes.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import paige.navic.domain.manager.DownloadManager
import paige.navic.domain.manager.HubManager
import paige.navic.domain.manager.MixBuild
import paige.navic.domain.manager.RadioManager
import paige.navic.domain.models.DomainSong
import paige.navic.domain.models.Mix
import paige.navic.domain.models.MixKind
import paige.navic.domain.models.YOUR_MIX_ID
import paige.navic.domain.repositories.SongRepository

/** A mix page's songs: being built, built, or nothing to show and why. */
sealed interface MixPageState {
	data object Building : MixPageState
	class Built(val build: MixBuild) : MixPageState
	/** The recipe ran and nothing came of it (or the engine and the fallback both failed). */
	data object Empty : MixPageState
	/** A kind this build cannot run (a newer client's recipe). */
	data object UnknownKind : MixPageState
}

/**
 * A mix's page (P5 round 2, the owner's note): a mix opens as a playlist, built afresh on each
 * opening as YouTube Music's do, and plays from there. One per page entry ([mixId] is its key),
 * so each opening builds again; a pull builds again too.
 *
 * [mix] follows the hub: a rename shows at once, and a delete (here, or on another device) empties
 * it, which the page reads as "leave". Your mix ([YOUR_MIX_ID]) is not stored: it is named by the
 * page and never goes away.
 */
class MixDetailViewModel(
	private val mixId: String,
	private val hubManager: HubManager,
	private val radioManager: RadioManager,
	private val songRepository: SongRepository,
	private val downloadManager: DownloadManager
) : ViewModel() {

	private val yourMix = mixId == YOUR_MIX_ID

	val mix: StateFlow<Mix?> = hubManager.mixes
		.map { list -> if (yourMix) YOUR_MIX else list.firstOrNull { it.id == mixId } }
		.stateIn(viewModelScope, SharingStarted.Eagerly, if (yourMix) YOUR_MIX else hubManager.mixes.value.firstOrNull { it.id == mixId })

	private val _state = MutableStateFlow<MixPageState>(MixPageState.Building)
	val state: StateFlow<MixPageState> = _state.asStateFlow()

	val connected: StateFlow<Boolean> = hubManager.connected

	private var building: Job? = null
	private var yourMixName = ""

	/** Build the songs; [name] is Your mix's, which only the page can say. Once per page entry. */
	fun open(name: String) {
		yourMixName = name
		if (building == null) build()
	}

	/** Build again (a pull): a fresh set of songs, a fresh station. */
	fun rebuild() {
		if (building?.isActive == true) return
		build()
	}

	private fun build() {
		building = viewModelScope.launch {
			// A stored mix is the hub's: wait for it rather than build from nothing on a cold start.
			val target = if (yourMix) YOUR_MIX.copy(name = yourMixName)
			else mix.value ?: withTimeoutOrNull(HUB_WAIT_MS) { mix.first { it != null } } ?: run {
				_state.value = MixPageState.Empty
				return@launch
			}
			if (MixKind.fromWire(target.kind) == null) {
				_state.value = MixPageState.UnknownKind
				return@launch
			}
			// The songs already shown stay while new ones are built: a pull does not blank the page.
			if (_state.value !is MixPageState.Built) _state.value = MixPageState.Building
			_state.value = radioManager.buildMix(target, yourMixName)
				?.let { MixPageState.Built(it) } ?: MixPageState.Empty
		}
	}

	/** Play from [index], or shuffled. The station a station-backed mix built continues after it. */
	fun play(index: Int = 0, shuffle: Boolean = false) {
		val built = (_state.value as? MixPageState.Built)?.build ?: return
		radioManager.playMixBuild(built, index, shuffle)
	}

	val allDownloads = downloadManager.allDownloads
		.stateIn(viewModelScope, SharingStarted.Lazily, persistentListOf())

	/** The song whose sheet is open, and whether it is liked (read when the sheet opens). */
	private val _selected = MutableStateFlow<DomainSong?>(null)
	val selected: StateFlow<DomainSong?> = _selected.asStateFlow()

	private val _selectedLiked = MutableStateFlow(false)
	val selectedLiked: StateFlow<Boolean> = _selectedLiked.asStateFlow()

	/** Likes changed on this page, by song id: the built songs are a snapshot. */
	private val _liked = MutableStateFlow<Map<String, Boolean>>(emptyMap())
	val liked: StateFlow<Map<String, Boolean>> = _liked.asStateFlow()

	fun select(song: DomainSong) {
		_selected.value = song
		_selectedLiked.value = _liked.value[song.id] ?: (song.starredAt != null)
		viewModelScope.launch {
			runCatching { songRepository.isSongStarred(song) }.onSuccess { _selectedLiked.value = it }
		}
	}

	fun clearSelection() {
		_selected.value = null
	}

	fun setLiked(liked: Boolean) {
		val song = _selected.value ?: return
		viewModelScope.launch {
			runCatching {
				if (liked) songRepository.starSong(song) else songRepository.unstarSong(song)
				_selectedLiked.value = liked
				_liked.value = _liked.value + (song.id to liked)
			}
		}
	}

	fun download(song: DomainSong) = downloadManager.downloadSong(song)

	fun cancelDownload(song: DomainSong) = downloadManager.cancelDownload(song.id)

	fun deleteDownload(song: DomainSong) = downloadManager.deleteDownload(song.id)

	fun rename(name: String) {
		if (!yourMix && hubManager.connected.value) hubManager.actRenameMix(mixId, name)
	}

	fun delete() {
		if (!yourMix && hubManager.connected.value) hubManager.actDeleteMix(mixId)
	}

	private companion object {
		/** How long a page opened on a cold start waits for the hub's mixes. */
		const val HUB_WAIT_MS = 8_000L

		/** Your mix as a recipe: the seedless station. Its name is the page's. */
		val YOUR_MIX = Mix(id = YOUR_MIX_ID, name = "", kind = MixKind.STATION)
	}
}
