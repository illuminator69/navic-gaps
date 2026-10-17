package paige.navic.domain.repositories

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import paige.navic.ui.core.PlayerUiState
import paige.navic.util.Logger
import kotlin.coroutines.cancellation.CancellationException

class PlayerStateRepository(
	private val preferences: DataStore<Preferences>
) {
	/**
	 * What was persisted, read once. Null is an answer, not "not loaded yet": nothing was
	 * persisted (a fresh install), or what was no longer decodes or cannot be read.
	 *
	 * This used to be a `StateFlow` that started null and stayed null in exactly those cases,
	 * and its one reader waited for the first non-null value (Q-049) — forever, with the code
	 * that persists the state queued behind that wait. So an install that began with nothing
	 * never saved a queue at all, and one whose state stopped decoding never replaced it.
	 *
	 * Off the caller's thread, as that flow's decode was: the blob holds the whole queue, and
	 * the caller is the player's view model on Main, during a cold start.
	 */
	suspend fun savedState(): PlayerUiState? = withContext(Dispatchers.Default) {
		val raw = try {
			preferences.data.first()[KEY_STATE]
		} catch (ex: CancellationException) {
			throw ex
		} catch (ex: Exception) {
			Logger.e("PlayerStateRepository", "failed to read state", ex)
			return@withContext null
		}
		decodePlayerState(raw).getOrElse {
			Logger.e("PlayerStateRepository", "failed to deserialise state", it)
			null
		}
	}

	suspend fun setState(value: PlayerUiState) {
		try {
			// Encoded off the main thread (I-041): the whole queue's songs go into the string, once a
			// second while playing, and the caller collects on the main dispatcher.
			val encoded = withContext(Dispatchers.Default) { playerStateJson.encodeToString(value) }
			preferences.edit { it[KEY_STATE] = encoded }
		} catch (ex: SerializationException) {
			Logger.e("PlayerStateRepository", "failed to serialise state", ex)
		} catch (ex: Exception) {
			Logger.e("PlayerStateRepository", "failed to save state", ex)
		}
	}

	private companion object {
		val KEY_STATE = stringPreferencesKey("player_state")
	}
}

private val playerStateJson = Json {
	ignoreUnknownKeys = true
}

/**
 * [raw] as it was persisted. A success holding null is "nothing persisted"; a failure is a
 * state this build can no longer decode. Kept apart from the repository so it runs in a test
 * (`PlayerStateRepositoryTest`); the caller does the logging.
 */
internal fun decodePlayerState(raw: String?): Result<PlayerUiState?> =
	runCatching { raw?.let { playerStateJson.decodeFromString<PlayerUiState>(it) } }

/**
 * What a launch puts back into the player: the [saved] state, paused — or nothing, when
 * nothing usable was saved or a queue is [current]ly loaded. Restoring is for an empty player
 * only; a queue somebody has started since the process came up is never replaced by last
 * session's.
 */
internal fun stateToRestore(saved: PlayerUiState?, current: PlayerUiState): PlayerUiState? =
	saved
		?.takeIf { current.queue.isEmpty() }
		?.copy(isPaused = true, isLoading = false)
