package paige.navic.domain.manager

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/*
 * I-019: the rules in ArtistMbidOverride.kt. Until this round the MBID sent to lb-bot was always
 * Navidrome's tag, so an artist tagged wrongly was indexed as somebody else and an untagged one
 * could not be indexed at all. The rule under test is the contract's
 * `effectiveArtistMbid(ndId, tagMbid) = overrides[ndId]?.mbid ?? tagMbid`, plus the two inputs
 * around it: what the picker accepts as a pasted id, and how an override write's refusal reads.
 */
class ArtistMbidOverrideTest {
	private val tag = "11111111-1111-4111-8111-111111111111"
	private val pick = "22222222-2222-4222-8222-222222222222"

	private fun overrides(vararg rows: Pair<String, String>): Map<String, LbArtistOverride> =
		overrideMap(
			LbOverrides(rows.map { (ndId, mbid) -> LbArtistOverride(ndArtistId = ndId, mbid = mbid) })
		).orEmpty()

	// --- effectiveArtistMbid ---------------------------------------------------------------

	@Test
	fun noOverrideAndNoTagIsBlank() {
		assertEquals("", effectiveArtistMbid(emptyMap(), "ar-1", null))
		assertEquals("", effectiveArtistMbid(emptyMap(), "ar-1", ""))
	}

	@Test
	fun noOverrideUsesNavidromesTag() {
		assertEquals(tag, effectiveArtistMbid(emptyMap(), "ar-1", tag))
	}

	@Test
	fun anOverrideReplacesTheTag() {
		assertEquals(pick, effectiveArtistMbid(overrides("ar-1" to pick), "ar-1", tag))
	}

	@Test
	fun anOverrideSuppliesTheMbidAnUntaggedArtistLacks() {
		assertEquals(pick, effectiveArtistMbid(overrides("ar-1" to pick), "ar-1", null))
	}

	@Test
	fun anOverrideEqualToTheTagIsStillThatMbid() {
		assertEquals(tag, effectiveArtistMbid(overrides("ar-1" to tag), "ar-1", tag))
	}

	@Test
	fun anotherArtistsOverrideDoesNotApply() {
		assertEquals(tag, effectiveArtistMbid(overrides("ar-2" to pick), "ar-1", tag))
		assertEquals("", effectiveArtistMbid(overrides("ar-2" to pick), "ar-1", null))
	}

	@Test
	fun aBlankArtistIdNeverMatchesAnOverride() {
		val map = mapOf("" to LbArtistOverride(ndArtistId = "", mbid = pick))
		assertEquals(tag, effectiveArtistMbid(map, "", tag))
	}

	@Test
	fun anOverrideWithNoMbidFallsBackToTheTag() {
		val map = mapOf("ar-1" to LbArtistOverride(ndArtistId = "ar-1", mbid = ""))
		assertEquals(tag, effectiveArtistMbid(map, "ar-1", tag))
	}

	// --- overrideMap -----------------------------------------------------------------------

	@Test
	fun theMapIsKeyedByNavidromeArtistId() {
		val map = overrides("ar-1" to pick, "ar-2" to tag)
		assertEquals(setOf("ar-1", "ar-2"), map.keys)
		assertEquals(pick, map["ar-1"]?.mbid)
	}

	@Test
	fun rowsThatNameNoArtistOrNoMbidAreDropped() {
		assertEquals(setOf("ar-1"), overrides("" to pick, "ar-1" to pick, "ar-2" to "").keys)
	}

	@Test
	fun anEmptyListIsAnAnswerAndABodyWithNoListIsNot() {
		// "Nobody has overridden anything" replaces the map; a stray `{}` must not.
		assertEquals(emptyMap(), overrideMap(LbOverrides(emptyList())))
		assertNull(overrideMap(LbOverrides()))
	}

	// --- parseArtistMbid -------------------------------------------------------------------

	@Test
	fun aBareMbidIsAccepted() {
		assertEquals(pick, parseArtistMbid(pick))
	}

	@Test
	fun whitespaceAndCaseAreNormalised() {
		assertEquals(
			"a74b1b7f-71a5-4011-9441-d0b5e4122711",
			parseArtistMbid("  A74B1B7F-71A5-4011-9441-D0B5E4122711\n")
		)
	}

	@Test
	fun aMusicBrainzArtistUrlIsAccepted() {
		val id = "a74b1b7f-71a5-4011-9441-d0b5e4122711"
		assertEquals(id, parseArtistMbid("https://musicbrainz.org/artist/$id"))
		assertEquals(id, parseArtistMbid("http://musicbrainz.org/artist/$id"))
		assertEquals(id, parseArtistMbid("musicbrainz.org/artist/$id"))
		assertEquals(id, parseArtistMbid("https://beta.musicbrainz.org/artist/$id"))
		assertEquals(id, parseArtistMbid("https://musicbrainz.org/artist/$id/releases"))
		assertEquals(id, parseArtistMbid("https://musicbrainz.org/artist/$id?tab=works#top"))
	}

	@Test
	fun aUrlForSomethingOtherThanAnArtistIsRefused() {
		// A release-group's id is a well-formed UUID and names no artist.
		assertNull(parseArtistMbid("https://musicbrainz.org/release-group/$pick"))
		assertNull(parseArtistMbid("https://musicbrainz.org/release/$pick"))
		assertNull(parseArtistMbid("https://example.org/artist/$pick"))
		assertNull(parseArtistMbid("https://notmusicbrainz.org/artist/$pick"))
	}

	@Test
	fun anythingElseIsRefused() {
		assertNull(parseArtistMbid(""))
		assertNull(parseArtistMbid("Radiohead"))
		assertNull(parseArtistMbid("22222222-2222-4222-8222"))
		assertNull(parseArtistMbid("$pick-extra"))
		assertNull(parseArtistMbid("see $pick"))
	}

	// --- overrideWriteError ----------------------------------------------------------------

	@Test
	fun aCodedNotFoundIsLbBotsOwnRefusal() {
		assertEquals(
			LbBotManager.LbError.Rejected(404, "MusicBrainz has no such artist"),
			overrideWriteError(404, "unknown_mbid", "MusicBrainz has no such artist", retryable = false)
		)
		assertEquals(
			LbBotManager.LbError.Rejected(404, "No override is set for that artist"),
			overrideWriteError(404, "no_override", "No override is set for that artist", retryable = false)
		)
	}

	@Test
	fun aBareNotFoundIsARouteNobodyAnswers() {
		// The hub's `{"error": "unknown route"}`, or an lb-bot older than the hub.
		assertEquals(
			LbBotManager.LbError.RouteUnknown,
			overrideWriteError(404, "", "unknown route", retryable = false)
		)
	}

	@Test
	fun methodNotAllowedIsAnLbBotThatPredatesTheRoute() {
		// lb-bot's SPA fallback is a GET-only catch-all, so a POST to a path it has never heard
		// of matches that rule and answers 405 rather than 404.
		assertEquals(
			LbBotManager.LbError.RouteUnknown,
			overrideWriteError(405, "", "Method Not Allowed", retryable = false)
		)
	}

	@Test
	fun aFillInFlightIsAConflictInLbBotsWords() {
		assertEquals(
			LbBotManager.LbError.Rejected(409, "A download for this artist is running"),
			overrideWriteError(409, "busy", "A download for this artist is running", retryable = false)
		)
	}

	@Test
	fun musicBrainzBeingDownIsNotTheHubBeingDown() {
		// lb-bot's own 502 says `retryable` and keeps its sentence; the hub's 502 (`down`) and
		// 504 (`busy`) carry neither and stay the generic "busy or unreachable".
		assertEquals(
			LbBotManager.LbError.Rejected(502, "MusicBrainz did not answer"),
			overrideWriteError(502, "", "MusicBrainz did not answer", retryable = true)
		)
		assertEquals(
			LbBotManager.LbError.Busy,
			overrideWriteError(502, "", "lb-bot unreachable", retryable = false)
		)
		assertEquals(
			LbBotManager.LbError.Busy,
			overrideWriteError(504, "", "lb-bot timed out", retryable = false)
		)
	}

	@Test
	fun aMalformedRequestKeepsItsStatusAndSentence() {
		assertEquals(
			LbBotManager.LbError.Rejected(400, "mbid is not a UUID"),
			overrideWriteError(400, "", "mbid is not a UUID", retryable = false)
		)
	}

	// --- overrideScanToFollow --------------------------------------------------------------
	//
	// lb-bot starts the scan and answers its id. It answers "" on purpose — a set onto an MBID
	// whose discography another library artist already holds (a scan from this artist would
	// rewrite that artist's row against the wrong albums), and a remove after which nothing
	// resolves — and the client must then start none of its own.

	private val wire = Json { ignoreUnknownKeys = true }

	@Test
	fun aSetThatStartedAScanFollowsIt() {
		assertEquals(
			"task-1",
			overrideScanToFollow(LbOverrideWrite(ok = true, mbid = pick, name = "A", taskId = "task-1"))
		)
	}

	@Test
	fun aSetThatStartedNoScanStartsNoneEither() {
		assertNull(overrideScanToFollow(LbOverrideWrite(ok = true, mbid = pick, name = "A", taskId = "")))
	}

	@Test
	fun aRemoveThatStartedAScanFollowsIt() {
		assertEquals("task-2", overrideScanToFollow(LbOverrideWrite(ok = true, taskId = "task-2")))
	}

	@Test
	fun aRemoveThatLeftNothingToScanStartsNone() {
		assertNull(overrideScanToFollow(LbOverrideWrite(ok = true, taskId = "")))
	}

	@Test
	fun theTaskIdIsReadOffTheWireAsLbBotSpellsIt() {
		val set = wire.decodeFromString<LbOverrideWrite>(
			"""{"ok":true,"mbid":"$pick","name":"A","task_id":"task-3"}"""
		)
		assertEquals("task-3", overrideScanToFollow(set))
		// The answered MBID is the one to use: MusicBrainz merges artists.
		assertEquals(pick, set.mbid)
	}

	@Test
	fun aMissingTaskIdFieldIsTheSameAsABlankOne() {
		assertNull(overrideScanToFollow(wire.decodeFromString<LbOverrideWrite>("""{"ok":true}""")))
		assertNull(overrideScanToFollow(wire.decodeFromString<LbOverrideWrite>("""{"ok":true,"task_id":"  "}""")))
	}
}
