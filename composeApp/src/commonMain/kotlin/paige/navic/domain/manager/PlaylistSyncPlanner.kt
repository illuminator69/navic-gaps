package paige.navic.domain.manager

/** One song of the playlist as the server listed it, with its size on disk (or an estimate). */
data class PlaylistSyncCandidate(val songId: String, val bytes: Long)

/** What one pass of a playlist policy should do. [managed] is the policy's new `managed` list. */
data class PlaylistSyncPlan(
	val evict: List<String>,
	val download: List<String>,
	val managed: List<String>
)

/**
 * The rules of a playlist download policy, kept free of Room, the network and the clock so they
 * can be run in a test (see `PlaylistSyncPlannerTest`). [PlaylistDownloadManager] gathers the
 * inputs and carries the plan out.
 *
 * [ordered] must be what the SERVER answered on this pass, in playlist order. The caller does not
 * plan at all when the fetch failed: a list that could not be read is not evidence that anything
 * left the playlist. This used to be read back out of Room, where a full library sync empties
 * every playlist for minutes at a time, and a pass that met that state deleted every managed file
 * and downloaded the lot again.
 *
 * **Permanent** only ever adds. A song that leaves the playlist stays on disk and stays in
 * `managed`, so removing the policy with "delete files" still knows which files were its own.
 *
 * **Rolling** keeps the first N songs (and/or the first X bytes) and deletes what falls outside
 * that window, but only files it downloaded itself and that no other policy manages. An EMPTY
 * list deletes nothing unless the pass before it was empty too ([previousPassEmpty]): one empty
 * answer is far more often a server hiccup than a playlist somebody emptied.
 */
fun planPlaylistSync(
	policy: PlaylistDownloadPolicy,
	ordered: List<PlaylistSyncCandidate>,
	downloadedIds: Set<String>,
	failedRetryCounts: Map<String, Int>,
	managedByOthers: Set<String>,
	previousPassEmpty: Boolean,
	maxRetries: Int = DownloadManager.MAX_DOWNLOAD_RETRIES
): PlaylistSyncPlan {
	// A playlist may list one song twice; it is still one file.
	val songs = ordered.distinctBy { it.songId }
	val rolling = policy.mode == PlaylistDownloadPolicy.MODE_ROLLING

	if (rolling && songs.isEmpty() && policy.managed.isNotEmpty() && !previousPassEmpty) {
		return PlaylistSyncPlan(evict = emptyList(), download = emptyList(), managed = policy.managed)
	}

	val target = if (rolling) rollingWindow(songs, policy) else songs
	val targetIds = target.mapTo(HashSet()) { it.songId }

	// A song that has already failed [maxRetries] times is left alone until the user retries it
	// by hand, otherwise a permanently failing track is re-queued on every pass forever.
	val download = target
		.map { it.songId }
		.filter { it !in downloadedIds && (failedRetryCounts[it] ?: 0) < maxRetries }

	return if (rolling) {
		PlaylistSyncPlan(
			evict = policy.managed.filter { it !in targetIds && it !in managedByOthers },
			download = download,
			managed = (policy.managed.filter { it in targetIds } + download).distinct()
		)
	} else {
		PlaylistSyncPlan(
			evict = emptyList(),
			download = download,
			managed = (policy.managed + download).distinct()
		)
	}
}

/**
 * The prefix of [songs] a rolling policy keeps: playlist order, stopping at whichever of the count
 * cap or the byte budget is reached first. At least the first song is always kept, even if it
 * alone exceeds the budget: a rolling cache of zero songs is never what's wanted.
 */
private fun rollingWindow(
	songs: List<PlaylistSyncCandidate>,
	policy: PlaylistDownloadPolicy
): List<PlaylistSyncCandidate> {
	val byCount = songs.take(policy.rollingLimit.coerceAtLeast(1))
	if (policy.budgetBytes <= 0) return byCount

	val kept = mutableListOf<PlaylistSyncCandidate>()
	var used = 0L
	for (song in byCount) {
		if (kept.isEmpty() || used + song.bytes <= policy.budgetBytes) {
			kept.add(song)
			used += song.bytes
		} else {
			break
		}
	}
	return kept
}
