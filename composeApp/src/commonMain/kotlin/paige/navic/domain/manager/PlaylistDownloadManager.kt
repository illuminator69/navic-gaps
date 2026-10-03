package paige.navic.domain.manager

import com.russhwolf.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import paige.navic.data.database.dao.SongDao
import paige.navic.data.database.entities.DownloadSource
import paige.navic.data.database.entities.SongEntity
import paige.navic.data.database.mappers.toDomainModel
import paige.navic.domain.repositories.DbRepository
import paige.navic.util.Logger

@Serializable
data class PlaylistDownloadPolicy(
	val playlistId: String,
	val playlistName: String = "",
	/**
	 * "permanent" downloads the whole playlist and never deletes; "rolling" keeps the first N
	 * (and/or first X bytes) and deletes what leaves that window. See [planPlaylistSync].
	 */
	val mode: String = MODE_PERMANENT,
	val rollingLimit: Int = 50,
	/**
	 * Byte budget for rolling mode; 0 = no size cap (count-only). When both this and [rollingLimit]
	 * are set, the more restrictive one wins — songs are kept in playlist order until EITHER cap is
	 * reached. Roadmap item 7's "rolling(N songs/GB)".
	 */
	val budgetBytes: Long = 0,
	/** 0 = original quality; otherwise transcode ceiling in kbps. */
	val maxBitRate: Int = 0,
	/** "" = original container; otherwise e.g. "opus", "mp3". */
	val format: String = "",
	/** Song ids THIS policy downloaded — only these may be evicted. */
	val managed: List<String> = emptyList()
) {
	companion object {
		const val MODE_PERMANENT = "permanent"
		const val MODE_ROLLING = "rolling"
	}
}

/**
 * Auto-download for (smart) playlists, Symphonium-style: each playlist can opt
 * into a download policy — permanent (whole playlist, nothing is ever deleted)
 * or rolling cache (first N tracks; tracks that leave the window are evicted).
 * Combined with server-side smart playlists this gives self-refreshing offline
 * mixes.
 *
 * Eviction is rolling-only, and only ever touches songs this manager downloaded
 * itself (`managed`), never manual downloads, and never songs another policy
 * still wants. The rules live in [planPlaylistSync]; this class gathers their
 * inputs and carries the plan out.
 */
class PlaylistDownloadManager(
	private val settings: Settings,
	private val dbRepository: DbRepository,
	private val songDao: SongDao,
	private val downloadManager: DownloadManager
) {
	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
	private val json = Json { ignoreUnknownKeys = true }

	// One pass at a time: saving a policy starts a pass of its own, which can overlap the
	// periodic one and would plan against a `managed` list the other is about to rewrite.
	private val syncMutex = Mutex()

	/** Rolling playlists whose last pass came back empty; see [planPlaylistSync]. In memory only. */
	private val emptyLastPass = mutableSetOf<String>()

	private val _policies = MutableStateFlow<Map<String, PlaylistDownloadPolicy>>(emptyMap())
	val policies: StateFlow<Map<String, PlaylistDownloadPolicy>> = _policies.asStateFlow()

	init {
		load()
		scope.launch {
			// Refresh managed playlists shortly after startup (lets login/sync
			// settle first), then every 6 hours while the app lives.
			delay(30_000)
			while (true) {
				syncAll()
				delay(6 * 60 * 60 * 1000L)
			}
		}
	}

	fun policyFor(playlistId: String): PlaylistDownloadPolicy? = _policies.value[playlistId]

	fun setPolicy(policy: PlaylistDownloadPolicy) {
		val existing = _policies.value[policy.playlistId]
		val merged = policy.copy(managed = existing?.managed ?: emptyList())
		_policies.value = _policies.value + (policy.playlistId to merged)
		persist()
		scope.launch { syncPlaylist(policy.playlistId) }
	}

	fun removePolicy(playlistId: String, deleteDownloads: Boolean) {
		val policy = _policies.value[playlistId] ?: return
		if (deleteDownloads) {
			val keptElsewhere = _policies.value.values
				.filter { it.playlistId != playlistId }
				.flatMap { it.managed }
				.toSet()
			policy.managed
				.filter { it !in keptElsewhere }
				.forEach { downloadManager.deleteDownload(it) }
		}
		_policies.value = _policies.value - playlistId
		persist()
	}

	suspend fun syncAll() {
		_policies.value.keys.forEach { playlistId ->
			try {
				syncPlaylist(playlistId)
			} catch (e: Exception) {
				Logger.e("PlaylistDownloadManager", "sync failed for $playlistId", e)
			}
		}
	}

	suspend fun syncPlaylist(playlistId: String) = syncMutex.withLock {
		val policy = _policies.value[playlistId] ?: return@withLock

		// The server's answer on THIS pass is the only evidence of what the playlist holds. A
		// fetch that failed (offline, a timeout, sockets that died while the app was frozen)
		// changes nothing: no eviction, no downloads. Room is not consulted for membership.
		val orderedIds = dbRepository.syncPlaylistSongs(playlistId, strict = true)
			.getOrElse {
				Logger.w(
					"PlaylistDownloadManager",
					"${policy.playlistName.ifBlank { playlistId }}: not synced, playlist unreadable: ${it.message}"
				)
				return@withLock
			}
			.toList()

		val songs = orderedIds.chunked(500)
			.flatMap { songDao.getSongsByIds(it) }
			.associateBy { it.songId }
		// Prefer the real on-disk size for anything already downloaded (any policy), so a byte
		// budget tracks actual storage rather than an estimate once files exist.
		val actualSizes = downloadManager.downloadedFileSizes()
		val ordered = orderedIds.mapNotNull { id ->
			val song = songs[id] ?: return@mapNotNull null
			PlaylistSyncCandidate(
				songId = id,
				bytes = actualSizes[id]?.takeIf { it > 0 } ?: estimatedBytes(song, policy)
			)
		}

		val failedRetryCounts = downloadManager.failedRetryCounts()
		val plan = planPlaylistSync(
			policy = policy,
			ordered = ordered,
			downloadedIds = downloadManager.downloadedSongs.value.keys,
			failedRetryCounts = failedRetryCounts,
			managedByOthers = _policies.value.values
				.filter { it.playlistId != playlistId }
				.flatMap { it.managed }
				.toSet(),
			previousPassEmpty = playlistId in emptyLastPass
		)
		if (ordered.isEmpty()) emptyLastPass.add(playlistId) else emptyLastPass.remove(playlistId)

		plan.evict.forEach { downloadManager.deleteDownload(it) }

		val quality = DownloadManager.DownloadQuality(
			policy.maxBitRate,
			policy.format.ifBlank { null }
		)
		plan.download.forEach { id ->
			val song = songs[id] ?: return@forEach
			downloadManager.downloadSong(
				song.toDomainModel(),
				// The policy's own quality wins over the global download preference.
				quality = quality,
				// Tagged so the download center can show these as playlist-managed rather than
				// manual — the user didn't ask for each of these files individually.
				source = DownloadSource.PLAYLIST,
				// A re-attempt of a previously-failed track counts toward the retry cap; a fresh
				// download does not.
				incrementRetry = id in failedRetryCounts
			)
		}

		_policies.value = _policies.value +
			(playlistId to (_policies.value[playlistId] ?: policy).copy(managed = plan.managed))
		persist()
		if (plan.evict.isNotEmpty() || plan.download.isNotEmpty()) {
			Logger.i(
				"PlaylistDownloadManager",
				"${policy.playlistName.ifBlank { playlistId }}: +${plan.download.size} -${plan.evict.size}"
			)
		}
	}

	/**
	 * Best-effort byte size for a not-yet-downloaded song, for budgeting only. Uses the transcode
	 * ceiling when the policy transcodes (bytes ≈ kbps × duration), otherwise the server's reported
	 * original size, falling back to a bitrate estimate when either is missing.
	 */
	private fun estimatedBytes(song: SongEntity, policy: PlaylistDownloadPolicy): Long {
		val durationSec = song.duration.inWholeSeconds
		// kbps × 1000 bits/s ÷ 8 bits/byte = kbps × 125 bytes/s.
		fun fromBitrate(kbps: Int) = kbps.toLong() * 125L * durationSec
		return when {
			policy.maxBitRate > 0 && durationSec > 0 -> fromBitrate(policy.maxBitRate)
			song.fileSize > 0 -> song.fileSize
			(song.bitRate ?: 0) > 0 && durationSec > 0 -> fromBitrate(song.bitRate!!)
			else -> 0L
		}
	}

	private fun load() {
		try {
			val raw = settings.getString(KEY, "")
			if (raw.isNotBlank()) {
				val list: List<PlaylistDownloadPolicy> = json.decodeFromString(raw)
				_policies.value = list.associateBy { it.playlistId }
			}
		} catch (e: Exception) {
			Logger.e("PlaylistDownloadManager", "failed to load policies", e)
		}
	}

	private fun persist() {
		try {
			settings.putString(KEY, json.encodeToString(_policies.value.values.toList()))
		} catch (e: Exception) {
			Logger.e("PlaylistDownloadManager", "failed to persist policies", e)
		}
	}

	private companion object {
		const val KEY = "playlistDownloadPolicies"
	}
}
