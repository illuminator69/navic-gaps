package paige.navic.domain.manager

import com.russhwolf.settings.Settings
import com.russhwolf.settings.get
import com.russhwolf.settings.set
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import paige.navic.data.database.dao.SongDao
import paige.navic.util.Logger

/**
 * Smart playlists that collect whole **albums** rather than individual tracks.
 *
 * **Navidrome has no such thing, and this does not pretend otherwise.** Its
 * criteria language is track-scoped: `rules` match media files, and there is no
 * album mode to ask for. So an album-mode playlist here is an ordinary playlist
 * whose contents were *computed* — a snapshot, not a live rule — and the UI says
 * so and offers [refresh].
 *
 * The important part is what it does **not** do: it never re-implements
 * Navidrome's matcher. The rules are sent to the server, the server evaluates
 * them, and only the expansion from "these tracks" to "these tracks' albums"
 * happens here. A local evaluator would have to reproduce sixteen criteria types
 * and would drift from the server's answer the first time either changed.
 *
 * The dance, and why each step is there:
 *
 * 1. Create a **smart** playlist carrying the rules, so Navidrome evaluates them.
 * 2. Read its tracks back.
 * 3. Expand each track to its whole album, out of the local library.
 * 4. Write a **regular** playlist with the expanded list — regular, because
 *    Navidrome refuses membership edits on a smart playlist, so a snapshot cannot
 *    live in one.
 * 5. Delete the temporary smart playlist.
 * 6. Keep the recipe locally, so the rules can be edited and re-run later.
 *
 * Step 6 is the only state this owns, and it is local by necessity: the playlist
 * Navidrome ends up holding is an ordinary one with no `rules` field to store it
 * in, so nothing else in the stack can answer "what was this built from".
 */
class AlbumModeSmartPlaylists(
	private val settings: Settings,
	private val nativeApi: NativeApiManager,
	private val sessionManager: SessionManager,
	private val songDao: SongDao
) {
	private val json = Json { ignoreUnknownKeys = true }

	private fun stored(): Map<String, String> =
		runCatching { json.decodeFromString<Map<String, String>>(settings[KEY, "{}"]) }
			.getOrDefault(emptyMap())

	private fun write(map: Map<String, String>) {
		settings[KEY] = json.encodeToString(map)
	}

	/** The rules an album-mode playlist was built from, or null. */
	fun rulesFor(playlistId: String): JsonObject? = stored()[playlistId]
		?.let { runCatching { json.decodeFromString<JsonObject>(it) }.getOrNull() }

	fun isAlbumMode(playlistId: String): Boolean = playlistId in stored()

	fun forget(playlistId: String) = write(stored() - playlistId)

	/**
	 * Build (or rebuild) an album-mode playlist. Returns the playlist's id.
	 *
	 * [existingId] non-null rewrites that playlist's contents in place, which is
	 * what [refresh] and an edit both want: a new id would leave the old playlist
	 * behind and break every link to it.
	 */
	suspend fun build(
		name: String,
		comment: String,
		isPublic: Boolean,
		rules: JsonObject,
		existingId: String? = null
	): Result<String> {
		// A name nothing will collide with, and one that is recognisable in the
		// playlist list if a crash leaves it behind.
		val scratchName = "$TEMP_PREFIX$name"
		val scratchId = nativeApi.createSmartPlaylist(scratchName, comment, false, rules)
			.getOrElse { return Result.failure(it) }
		if (scratchId.isBlank()) {
			return Result.failure(IllegalStateException("Navidrome did not name the playlist"))
		}

		return try {
			val matched = sessionManager.api.getPlaylist(scratchId).songs
			val albumIds = matched.mapNotNull { it.albumId?.takeIf(String::isNotBlank) }
				.distinct()
			// Room, not the server: the library is already here, so this is one
			// local query per matched album rather than one request per album. A
			// plain loop because the DAO call suspends and `flatMap`'s lambda is
			// not a suspending one.
			val expanded = mutableListOf<String>()
			val seen = mutableSetOf<String>()
			for (albumId in albumIds) {
				songDao.getSongsByAlbumId(albumId)
					.sortedWith(compareBy({ it.discNumber ?: 0 }, { it.trackNumber ?: 0 }))
					.forEach { song -> if (seen.add(song.songId)) expanded += song.songId }
			}

			if (expanded.isEmpty()) {
				return Result.failure(
					IllegalStateException("Those rules matched nothing in your library")
				)
			}

			val finalId = if (existingId.isNullOrBlank()) {
				sessionManager.api.createPlaylist(name = name, songIds = expanded).id
			} else {
				// Replace the contents: drop every index, then add the new list.
				// Indices are removed in one call, so they refer to the list as it
				// was before any of them were applied.
				val current = sessionManager.api.getPlaylist(existingId).songs
				sessionManager.api.updatePlaylist(
					id = existingId,
					songIdsToAdd = expanded,
					songIndicesToRemove = current.indices.toList()
				)
				existingId
			}

			write(stored() + (finalId to rules.toString()))
			Result.success(finalId)
		} catch (e: Exception) {
			Logger.e("AlbumModeSmartPlaylists", "album-mode build failed", e)
			Result.failure(e)
		} finally {
			// Always, including on the failure path: a leftover smart playlist named
			// "(building) …" is visible in every Subsonic client on the account.
			runCatching { sessionManager.api.deletePlaylist(scratchId) }
				.onFailure {
					Logger.w("AlbumModeSmartPlaylists", "left scratch playlist $scratchId behind")
				}
		}
	}

	/** Re-run a stored recipe over the current library. */
	suspend fun refresh(playlistId: String, name: String, isPublic: Boolean): Result<String> {
		val rules = rulesFor(playlistId)
			?: return Result.failure(IllegalStateException("No stored rules for this playlist"))
		return build(
			name = name,
			comment = "Created with Navic",
			isPublic = isPublic,
			rules = rules,
			existingId = playlistId
		)
	}

	private companion object {
		const val KEY = "albumModeSmartPlaylists"
		const val TEMP_PREFIX = "(building) "
	}
}
