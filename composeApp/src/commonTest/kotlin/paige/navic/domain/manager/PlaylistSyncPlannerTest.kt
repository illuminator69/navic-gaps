package paige.navic.domain.manager

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/*
 * The playlist-policy rules in PlaylistSyncPlanner.kt. The case that matters most is the one that
 * shipped broken: a pass that sees fewer songs than the policy manages must not delete files in
 * permanent mode at all, and in rolling mode not on the strength of one empty answer.
 */
class PlaylistSyncPlannerTest {
	private fun policy(
		mode: String,
		managed: List<String> = emptyList(),
		rollingLimit: Int = 50,
		budgetBytes: Long = 0
	) = PlaylistDownloadPolicy(
		playlistId = "p",
		mode = mode,
		rollingLimit = rollingLimit,
		budgetBytes = budgetBytes,
		managed = managed
	)

	private fun songs(vararg ids: String, bytes: Long = 10) =
		ids.map { PlaylistSyncCandidate(it, bytes) }

	private fun plan(
		policy: PlaylistDownloadPolicy,
		ordered: List<PlaylistSyncCandidate>,
		downloaded: Set<String> = emptySet(),
		failed: Map<String, Int> = emptyMap(),
		others: Set<String> = emptySet(),
		previousPassEmpty: Boolean = false
	) = planPlaylistSync(policy, ordered, downloaded, failed, others, previousPassEmpty, maxRetries = 3)

	@Test
	fun permanentNeverEvictsAndKeepsWhatLeft() {
		val p = policy(PlaylistDownloadPolicy.MODE_PERMANENT, managed = listOf("a", "b", "c"))
		val result = plan(p, songs("a", "d"), downloaded = setOf("a", "b", "c"))
		assertTrue(result.evict.isEmpty())
		assertEquals(listOf("d"), result.download)
		assertEquals(listOf("a", "b", "c", "d"), result.managed)
	}

	@Test
	fun permanentSurvivesAnEmptyList() {
		val p = policy(PlaylistDownloadPolicy.MODE_PERMANENT, managed = listOf("a", "b"))
		val result = plan(p, emptyList(), downloaded = setOf("a", "b"), previousPassEmpty = true)
		assertTrue(result.evict.isEmpty())
		assertTrue(result.download.isEmpty())
		assertEquals(listOf("a", "b"), result.managed)
	}

	@Test
	fun alreadyDownloadedSongsAreNotQueuedAgain() {
		val p = policy(PlaylistDownloadPolicy.MODE_PERMANENT)
		val result = plan(p, songs("a", "b", "c"), downloaded = setOf("a", "c"))
		assertEquals(listOf("b"), result.download)
	}

	@Test
	fun rollingEvictsWhatLeftTheWindow() {
		val p = policy(PlaylistDownloadPolicy.MODE_ROLLING, managed = listOf("a", "b", "c"), rollingLimit = 2)
		val result = plan(p, songs("c", "d", "a"), downloaded = setOf("a", "b", "c"))
		assertEquals(listOf("a", "b"), result.evict)
		assertEquals(listOf("d"), result.download)
		assertEquals(listOf("c", "d"), result.managed)
	}

	@Test
	fun rollingKeepsWhatAnotherPolicyManages() {
		val p = policy(PlaylistDownloadPolicy.MODE_ROLLING, managed = listOf("a", "b"))
		val result = plan(p, songs("a"), downloaded = setOf("a", "b"), others = setOf("b"))
		assertTrue(result.evict.isEmpty())
		assertEquals(listOf("a"), result.managed)
	}

	@Test
	fun rollingIgnoresOneEmptyAnswer() {
		val p = policy(PlaylistDownloadPolicy.MODE_ROLLING, managed = listOf("a", "b"))
		val result = plan(p, emptyList(), downloaded = setOf("a", "b"))
		assertTrue(result.evict.isEmpty())
		assertEquals(listOf("a", "b"), result.managed)
	}

	@Test
	fun rollingBelievesTheSecondEmptyAnswer() {
		val p = policy(PlaylistDownloadPolicy.MODE_ROLLING, managed = listOf("a", "b"))
		val result = plan(p, emptyList(), downloaded = setOf("a", "b"), previousPassEmpty = true)
		assertEquals(listOf("a", "b"), result.evict)
		assertTrue(result.managed.isEmpty())
	}

	@Test
	fun rollingByteBudgetStopsTheWindowButKeepsTheFirstSong() {
		val p = policy(PlaylistDownloadPolicy.MODE_ROLLING, budgetBytes = 25)
		assertEquals(listOf("a", "b"), plan(p, songs("a", "b", "c")).download)
		assertEquals(listOf("a"), plan(p, songs("a", "b", bytes = 100)).download)
	}

	@Test
	fun aSongOutOfRetriesIsLeftAlone() {
		val p = policy(PlaylistDownloadPolicy.MODE_PERMANENT)
		val result = plan(p, songs("a", "b"), failed = mapOf("a" to 3, "b" to 2))
		assertEquals(listOf("b"), result.download)
	}

	@Test
	fun aSongListedTwiceIsOneDownload() {
		val p = policy(PlaylistDownloadPolicy.MODE_PERMANENT)
		assertEquals(listOf("a", "b"), plan(p, songs("a", "b", "a")).download)
	}
}
