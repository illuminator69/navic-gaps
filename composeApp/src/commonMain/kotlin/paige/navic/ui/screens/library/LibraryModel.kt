package paige.navic.ui.screens.library

import paige.navic.data.database.entities.SavedQueueEntity
import paige.navic.domain.models.DomainAlbum
import paige.navic.domain.models.DomainPlaylist
import paige.navic.domain.models.Mix
import paige.navic.domain.models.isCustomRadio

/*
 * The Library (P5): one list under chips, "Recents" when no chip is chosen, Liked pinned above it.
 * What Recents holds and in what order, as pure functions (`LibraryModelTest`); the screen only
 * draws the entries.
 *
 * A Recents clock is "last played" for albums, artists and mixes (a mix never played: when it was
 * last changed), and "last changed" for playlists and saved queues. Nothing without a clock is in
 * the list: the chips are the full lists.
 */

/** The chips, in the board's order. Radios is last and always shown. */
enum class LibraryChip { Playlists, Albums, Artists, Mixes, Songs, Radios }

enum class EntryKind { Liked, Mix, CustomRadio, SavedQueue, Artist, Album, Playlist }

/**
 * One Recents row. [key] is unique across kinds ("al:<id>", "ar:<id>", ...); [source] is what the
 * row opens and draws from (the album, the mix, ...), null for Liked; [recentAt] null only for
 * Liked, which is pinned.
 */
data class LibraryEntry(
	val key: String,
	val kind: EntryKind,
	val title: String,
	val coverArtId: String?,
	val recentAt: Long?,
	val source: Any? = null,
	/** Liked's song count. */
	val count: Int? = null
)

/** How many entries Recents shows under Liked. */
const val RECENTS_LIMIT = 100

/** An artist's clock: the newest `lastPlayedAt` among their albums; null when none was played. */
fun artistRecentAt(artistId: String, albums: List<DomainAlbum>): Long? =
	albums.asSequence()
		.filter { it.artistId == artistId }
		.mapNotNull { it.lastPlayedAt?.toEpochMilliseconds() }
		.maxOrNull()

/**
 * Recents: Liked first when [liked] (its song count) is known, then everything with a clock, newest
 * first, ties by title, cut to [limit] after ordering. Two entries with one key are one (the newer).
 */
fun recentsEntries(
	liked: Int?,
	mixes: List<Mix>,
	savedQueues: List<SavedQueueEntity>,
	playlists: List<DomainPlaylist>,
	albums: List<DomainAlbum>,
	/** Each artist's own cover id from Room, where it holds the artist. */
	artistCovers: Map<String, String?> = emptyMap(),
	limit: Int = RECENTS_LIMIT
): List<LibraryEntry> {
	val dated = ArrayList<LibraryEntry>()
	for (mix in mixes) {
		val clock = mix.lastPlayedAt.takeIf { it > 0 } ?: mix.updatedAt.takeIf { it > 0 } ?: continue
		val kind = if (isCustomRadio(mix)) EntryKind.CustomRadio else EntryKind.Mix
		dated += LibraryEntry("mx:${mix.id}", kind, mix.name, mix.coverArtId.ifBlank { null }, clock, mix)
	}
	for (queue in savedQueues) {
		if (queue.updatedAt <= 0) continue
		val title = queue.name?.takeIf { it.isNotBlank() }
			?: queue.currentSongName?.takeIf { it.isNotBlank() }
			?: queue.sourceName.orEmpty()
		dated += LibraryEntry("sq:${queue.id}", EntryKind.SavedQueue, title, queue.coverArtId, queue.updatedAt, queue)
	}
	for (playlist in playlists) {
		dated += LibraryEntry(
			"pl:${playlist.id}", EntryKind.Playlist, playlist.name.orEmpty(), playlist.coverArtId,
			playlist.modifiedAt.toEpochMilliseconds(), playlist
		)
	}
	// Artists from their albums: one pass, keeping each artist's newest played album.
	val newestByArtist = HashMap<String, DomainAlbum>()
	for (album in albums) {
		val played = album.lastPlayedAt ?: continue
		dated += LibraryEntry(
			"al:${album.id}", EntryKind.Album, album.name.orEmpty(), album.coverArtId,
			played.toEpochMilliseconds(), album
		)
		val held = newestByArtist[album.artistId]
		if (held == null || held.lastPlayedAt!! < played) newestByArtist[album.artistId] = album
	}
	for ((artistId, album) in newestByArtist) {
		if (artistId.isBlank()) continue
		dated += LibraryEntry(
			"ar:$artistId", EntryKind.Artist, album.artistName.orEmpty(), artistCovers[artistId],
			album.lastPlayedAt!!.toEpochMilliseconds(), album
		)
	}
	val ordered = dated
		.sortedWith(compareByDescending<LibraryEntry> { it.recentAt }.thenBy { it.title.lowercase() })
		.distinctBy { it.key }
		.take(limit)
	return if (liked == null) ordered
	else listOf(LibraryEntry("liked", EntryKind.Liked, "", null, null, count = liked)) + ordered
}
