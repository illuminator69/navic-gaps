package paige.navic.domain.manager

import paige.navic.data.database.dao.SongDao
import paige.navic.data.database.mappers.toDomainModel
import paige.navic.domain.models.DomainSong
import paige.navic.util.Logger

/**
 * Auto DJ: the autoplay this app falls back on when the hub has no station engine (no hub, an
 * older hub, a hub whose engine answered `available: false` or 503; navi-connect PROTOCOL §18).
 *
 * **A port of upstream Feishin's Auto DJ** (`renderer/features/player/auto-dj/auto-dj-songs.ts`,
 * its "similar" song strategy, and the trigger in `hooks/use-auto-dj.ts`), so the two clients'
 * no-hub autoplay is one behaviour rather than two drifted ones. One top-up of [itemCount] songs
 * walks four sources in order and stops as soon as it has enough:
 *
 * 1. Navidrome's similar songs for the playing track, shuffled (`fetchSimilar`, which already
 *    prefers the AudioMuse plugin's `getSonicSimilarTracks` when the server has it);
 * 2. random songs of the playing track's genre;
 * 3. random songs of its album artist;
 * 4. random songs from the whole library.
 *
 * With "only similar" on, the walk also stops after the first source that found anything, which
 * is upstream's rule (a step that finds nothing still falls through to the next). A song already
 * in the queue is skipped unless duplicates are allowed. A top-up starts when fewer than
 * `autoDjTiming` songs are left after the playing one, in play order.
 *
 * Two deliberate differences from upstream: no "spice" step (upstream adds it only when a music
 * folder hides similar songs, which this app has no notion of), and no song is picked twice
 * within one top-up even with duplicates allowed (upstream's check would let two sources hand
 * back the same song in one batch).
 *
 * The decision is [AutoDjPick], a pure class (`AutoDjPlannerTest`); this class only fetches.
 */
class AutoDj(
	private val songDao: SongDao,
	private val preferenceManager: PreferenceManager,
	private val fetchSimilar: suspend (songId: String, count: Int) -> List<String>
) {
	/** The four sources, in the order a top-up walks them. */
	internal enum class Source { Similar, Genre, AlbumArtist, Library }

	/** One top-up's songs for [current], none of them in [queueIds] unless duplicates are allowed. */
	suspend fun collect(current: DomainSong, queueIds: Set<String>): List<DomainSong> {
		val itemCount = preferenceManager.autoDjItemCount.coerceIn(1, MAX_ITEM_COUNT)
		val pick = AutoDjPick<DomainSong>(
			itemCount = itemCount,
			onlySimilar = preferenceManager.autoDjOnlySimilar,
			allowDuplicates = preferenceManager.autoDjAllowDuplicates,
			queueIds = queueIds,
			idOf = { it.id }
		)
		for (source in Source.entries) {
			if (pick.done) break
			val candidates = try {
				candidates(source, current, itemCount)
			} catch (e: Exception) {
				// One source failing (Navidrome unreachable for the similar songs) is not the
				// end of the top-up: the local sources still answer.
				Logger.w("AutoDj", "source $source failed", e)
				emptyList()
			}
			pick.offer(candidates) { it.shuffled() }
		}
		return pick.selected
	}

	private suspend fun candidates(source: Source, current: DomainSong, itemCount: Int): List<DomainSong> =
		when (source) {
			Source.Similar -> {
				val ids = fetchSimilar(current.id, itemCount)
				if (ids.isEmpty()) emptyList()
				else {
					val byId = songDao.getSongsByIds(ids).associateBy { it.songId }
					ids.mapNotNull { byId[it]?.toDomainModel() }
				}
			}

			Source.Genre -> {
				val genre = (current.genres.firstOrNull() ?: current.genre)?.takeIf { it.isNotBlank() }
				if (genre == null) emptyList()
				else songDao.getRandomSongsByGenre(genre, SOURCE_LIMIT).map { it.toDomainModel() }
			}

			Source.AlbumArtist -> {
				val artistId = current.albumArtists.firstOrNull()?.id?.takeIf { it.isNotBlank() }
				if (artistId == null) emptyList()
				else songDao.getSongsByArtistId(artistId).shuffled().take(SOURCE_LIMIT).map { it.toDomainModel() }
			}

			Source.Library -> songDao.getRandomSongs(SOURCE_LIMIT).map { it.toDomainModel() }
		}

	companion object {
		/** Upstream asks each random source for 50. */
		const val SOURCE_LIMIT = 50
		const val MAX_ITEM_COUNT = 50
	}
}

/** Upstream's trigger: fewer than [timing] songs left after the playing one. */
internal fun autoDjDue(remaining: Int, timing: Int): Boolean = remaining < timing.coerceAtLeast(1)

/**
 * One Auto DJ top-up being filled, source by source. The rule of upstream's `collectSongsSimilar`
 * as a class with no I/O: [offer] each source's candidates in order while not [done].
 */
internal class AutoDjPick<T>(
	private val itemCount: Int,
	private val onlySimilar: Boolean,
	private val allowDuplicates: Boolean,
	private val queueIds: Set<String>,
	private val idOf: (T) -> String
) {
	private val picked = mutableListOf<T>()
	private val pickedIds = HashSet<String>()

	val selected: List<T> get() = picked.toList()

	val remaining: Int get() = itemCount - picked.size

	/**
	 * Nothing more to ask for: the top-up is full, or "only similar" is on and a source has
	 * already found something. A source that found nothing does not end it, even then.
	 */
	val done: Boolean get() = remaining <= 0 || (onlySimilar && picked.isNotEmpty())

	/** Take what fits from one source's [candidates], in the order [shuffle] gives them. */
	fun offer(candidates: List<T>, shuffle: (List<T>) -> List<T>) {
		if (remaining <= 0) return
		val usable = candidates.filter { candidate ->
			val id = idOf(candidate)
			id !in pickedIds && (allowDuplicates || id !in queueIds)
		}
		for (candidate in shuffle(usable)) {
			if (remaining <= 0) break
			if (pickedIds.add(idOf(candidate))) picked.add(candidate)
		}
	}
}
