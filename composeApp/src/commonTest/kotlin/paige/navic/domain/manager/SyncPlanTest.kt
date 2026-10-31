package paige.navic.domain.manager

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/*
 * B-098, B-100, B-109, B-101: no sync without a session; the login's pull is a full pull like any
 * other (it clears the failure flag); a Subsonic error envelope is a failed read, not "0 artists".
 */
class SyncPlanTest {
	private val hour = 3_600_000L
	private val now = 1_800_000_000_000L

	@Test
	fun aLoggedOutStartSchedulesNoWork() {
		assertFalse(periodicSyncAllowed(loggedIn = false))
		for (albums in listOf(0, 4_304)) for (last in listOf(0L, now - 5 * 60_000, now - 2 * hour)) {
			assertFalse(fullPullDue(false, albums, last, now, hour), "albums=$albums last=$last")
		}
		assertTrue(periodicSyncAllowed(loggedIn = true))
	}

	@Test
	fun withASessionAPullIsDueWhenNothingIsHeldNoneFinishedOrTheLastIsOld() {
		assertTrue(fullPullDue(true, albumCount = 4_304, lastFullMs = 0L, nowMs = now, thresholdMs = hour))
		assertTrue(fullPullDue(true, albumCount = 4_304, lastFullMs = now - 2 * hour, nowMs = now, thresholdMs = hour))
		assertFalse(fullPullDue(true, albumCount = 4_304, lastFullMs = now - 5 * 60_000, nowMs = now, thresholdMs = hour))
		// B-167: a cache that was dropped holds no album while the last pull's stamp is recent.
		assertTrue(fullPullDue(true, albumCount = 0, lastFullMs = now - 5 * 60_000, nowMs = now, thresholdMs = hour))
	}

	@Test
	fun aSuccessfulLoginPullClearsLastSyncFailed() {
		val leftOver = SyncState(lastSyncFailed = true)
		val running = leftOver.fullPullStarted()
		assertTrue(running.isSyncing)
		assertFalse(running.lastSyncFailed)
		val done = running.fullPullEnded(succeeded = true)
		assertFalse(done.lastSyncFailed)
		assertFalse(done.isSyncing)
		assertTrue(SyncState().fullPullStarted().fullPullEnded(succeeded = false).lastSyncFailed)
	}

	private val json = Json {
		ignoreUnknownKeys = true
		isLenient = true
		coerceInputValues = true
	}

	@Test
	fun aStatusFailedEnvelopeIsAFailure() {
		val failed = json.decodeFromString(
			SubsonicArtistsEnvelope.serializer(),
			"""{"subsonic-response":{"status":"failed","version":"1.16.1","error":{"code":40,"message":"Wrong username or password"}}}"""
		)
		val e = assertFailsWith<SubsonicStatusException> { artistsOf(failed) }
		assertEquals(40, e.code)
		assertEquals(LoginFailureKind.WRONG_CREDENTIALS, loginFailureKind(Exception("sync", e)))

		val ok = json.decodeFromString(
			SubsonicArtistsEnvelope.serializer(),
			"""{"subsonic-response":{"status":"ok","version":"1.16.1","artists":{"index":[{"name":"A","artist":[{"id":"1","name":"Abba"},{"id":"2","name":"Air"}]}]}}}"""
		)
		assertEquals(listOf("Abba", "Air"), artistsOf(ok).map { it.name })

		// An answer with no artists and status ok is still an answer (a new, empty library).
		val empty = json.decodeFromString(SubsonicArtistsEnvelope.serializer(), """{"subsonic-response":{"status":"ok"}}""")
		assertEquals(emptyList(), artistsOf(empty))
	}
}
