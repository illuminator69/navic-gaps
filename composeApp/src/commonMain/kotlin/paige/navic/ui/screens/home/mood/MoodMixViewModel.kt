package paige.navic.ui.screens.home.mood

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import paige.navic.data.database.dao.SongDao
import paige.navic.data.database.mappers.toDomainModel
import paige.navic.domain.manager.DownloadManager
import paige.navic.domain.manager.MixState
import paige.navic.domain.manager.MoodEntry
import paige.navic.domain.manager.MoodManager
import paige.navic.domain.manager.MoodVariant
import paige.navic.domain.manager.OwnedSong
import paige.navic.domain.manager.RadioManager
import paige.navic.domain.manager.TileIdsResult
import paige.navic.domain.manager.drawMix
import paige.navic.domain.manager.find
import paige.navic.domain.manager.mixState
import paige.navic.domain.manager.moodEligible
import paige.navic.domain.models.DomainSong
import paige.navic.domain.repositories.SongRepository
import kotlin.random.Random

/** One draw of a tile's mix: what the page shows, and the songs that play. */
data class MoodDraw(val state: MixState, val songs: List<DomainSong>)

/**
 * A tile's mix (P7 Task A4): its ids from the hub, resolved in Room (so every song is the library's),
 * cut to the mood's length rule and depth, and [MOOD_MIX_SIZE] of them drawn. The page and Home's play
 * button both draw through here, so what a play button plays is what a page would have shown.
 */
suspend fun drawMoodMix(
	moodManager: MoodManager,
	songDao: SongDao,
	mood: MoodEntry,
	variantId: String,
	random: Random = Random.Default
): MoodDraw {
	val ids = when (val answer = moodManager.tileIds(mood.id, variantId)) {
		is TileIdsResult.Ok -> answer.ids.take(mood.depth)
		TileIdsResult.Unavailable, TileIdsResult.Absent -> return MoodDraw(MixState.Unavailable, emptyList())
	}
	val entities = if (ids.isEmpty()) emptyList() else songDao.getSongsByIds(ids)
	val owned = entities.associate {
		it.songId to OwnedSong(it.songId, it.duration.inWholeSeconds.toInt(), it.artistId)
	}
	val eligible = moodEligible(ids, owned, mood.maxSeconds, mood.depth).size
	val drawn = drawMix(ids, owned, mood.maxSeconds, mood.depth, random)
	val byId = entities.associateBy { it.songId }
	val songs = drawn.mapNotNull { byId[it]?.toDomainModel() }
	return MoodDraw(mixState(drawn, eligible, failed = false), songs)
}

/**
 * A mood mix's page. One per page entry (its key is the mood and the tile), so reopening the page
 * is a new draw (P7 call 2); the draw is held while the page is open, and Play plays exactly it.
 * Retry draws again. An answer for an older read (a Retry pressed twice) is dropped.
 */
class MoodMixViewModel(
	private val moodId: String,
	private val variantId: String,
	private val moodManager: MoodManager,
	private val songDao: SongDao,
	private val radioManager: RadioManager,
	private val songRepository: SongRepository,
	private val downloadManager: DownloadManager
) : ViewModel() {

	/** The mood as the cached catalogue knows it; null for one the hub no longer lists. */
	val mood: StateFlow<MoodEntry?> = moodManager.catalogue
		.map { it.find(moodId) }
		.stateIn(viewModelScope, SharingStarted.Eagerly, moodManager.catalogue.value.find(moodId))

	val variant: StateFlow<MoodVariant?> = mood
		.map { m -> m?.variants?.firstOrNull { it.id == variantId } }
		.stateIn(viewModelScope, SharingStarted.Eagerly, mood.value?.variants?.firstOrNull { it.id == variantId })

	private val _draw = MutableStateFlow(MoodDraw(MixState.Loading, emptyList()))
	val draw: StateFlow<MoodDraw> = _draw.asStateFlow()

	private var reading: Job? = null
	private var generation = 0

	init {
		load()
	}

	fun retry() = load()

	private fun load() {
		reading?.cancel()
		val mine = ++generation
		_draw.value = MoodDraw(MixState.Loading, emptyList())
		reading = viewModelScope.launch {
			val entry = mood.value ?: run {
				// A tile the cached catalogue lacks: the hub is asked again, and the page says it could not.
				moodManager.refresh()
				mood.value
			}
			val result = if (entry == null || entry.variants.none { it.id == variantId }) {
				MoodDraw(MixState.Unavailable, emptyList())
			} else try {
				drawMoodMix(moodManager, songDao, entry, variantId)
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				MoodDraw(MixState.Unavailable, emptyList())
			}
			if (mine == generation) _draw.value = result
		}
	}

	/** Play the drawn songs from [index], or shuffled; the queue is named after the tile. */
	fun play(index: Int = 0, shuffle: Boolean = false) {
		val songs = _draw.value.songs.takeIf { _draw.value.state is MixState.Ready } ?: return
		val title = variant.value?.title ?: return
		radioManager.playMoodTile(songs, title, moodId, shuffle, index)
	}

	val allDownloads = downloadManager.allDownloads
		.stateIn(viewModelScope, SharingStarted.Lazily, persistentListOf())

	private val _selected = MutableStateFlow<DomainSong?>(null)
	val selected: StateFlow<DomainSong?> = _selected.asStateFlow()

	private val _selectedLiked = MutableStateFlow(false)
	val selectedLiked: StateFlow<Boolean> = _selectedLiked.asStateFlow()

	/** Likes changed on this page, by song id: the drawn songs are a snapshot. */
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
}
