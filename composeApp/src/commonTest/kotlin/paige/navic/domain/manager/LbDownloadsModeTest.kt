package paige.navic.domain.manager

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/*
 * Worbus P1, N1: the hub says whether lb-bot can download (`welcome.lb.downloads`, the `lb` frame,
 * `/lb/status`), and how far lb-bot's first index build has got (`welcome.lbIndex.progress`, the
 * `index` frame). Both are additive: a hub or lb-bot older than the fields sends neither, and that
 * reads as today (downloading on, no bar). A route that refuses with 409 `downloads_off` turns the
 * mode off at once, whatever the last advert said, until the next advert says otherwise.
 */
class LbDownloadsModeTest {
	private fun obj(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

	@Test
	fun older_peer_no_downloads_means_on() {
		val advert = lbAdvertOf(obj("""{"available": true, "routes": ["GET /lb/status"], "webUrl": ""}"""))!!
		assertEquals(LbDownloads.UNKNOWN, advert.downloads)
		assertTrue(advert.downloads.isOn)
		// `/lb/status` from an older hub: the same.
		val probe = lbWireJson.decodeFromString(LbStatusProbe.serializer(), """{"configured":true,"upstreamReachable":true}""")
		assertEquals(LbDownloads.UNKNOWN, lbDownloadsOf(probe.downloads))
		// An advert that says nothing leaves the mode where it is: UNKNOWN stays UNKNOWN (= on).
		assertEquals(LbDownloads.UNKNOWN, downloadsAfterAdvert(LbDownloads.UNKNOWN, LbDownloads.UNKNOWN))
	}

	@Test
	fun downloads_parsed_from_welcome_and_frame() {
		// welcome.lb
		assertEquals(
			LbDownloads.OFF,
			lbAdvertOf(obj("""{"available": true, "routes": [], "webUrl": "http://lb:8899", "downloads": "off"}"""))!!.downloads
		)
		// The `lb` frame carries the same object, with its `t`.
		val frame = lbAdvertOf(obj("""{"t": "lb", "available": true, "routes": [], "webUrl": "", "downloads": "on"}"""))!!
		assertEquals(LbDownloads.ON, frame.downloads)
		assertTrue(frame.available)
		// `/lb/status`.
		val probe = lbWireJson.decodeFromString(
			LbStatusProbe.serializer(),
			"""{"configured":true,"upstreamReachable":true,"routes":[],"webUrl":"","downloads":"off"}"""
		)
		assertEquals(LbDownloads.OFF, lbDownloadsOf(probe.downloads))
		// Anything but the two words is no answer.
		assertEquals(LbDownloads.UNKNOWN, lbDownloadsOf("maybe"))
		assertEquals(LbDownloads.UNKNOWN, lbAdvertOf(obj("""{"available": true, "downloads": 1}"""))!!.downloads)
		// An advert with no `available` is not applied at all (as before).
		assertNull(lbAdvertOf(obj("""{"downloads": "off"}""")))
		// What an advert says replaces what was known.
		assertEquals(LbDownloads.OFF, downloadsAfterAdvert(LbDownloads.ON, LbDownloads.OFF))
		assertEquals(LbDownloads.ON, downloadsAfterAdvert(LbDownloads.OFF, LbDownloads.ON))
	}

	@Test
	fun older_peer_no_progress_means_null() {
		assertNull(lbIndexProgressOf(obj("""{"seq": 12, "epoch": "e1"}""")))
		assertNull(lbIndexProgressOf(null))
		// Malformed is the same as absent: no bar, never an error.
		assertNull(lbIndexProgressOf(obj("""{"seq": 12, "epoch": "e1", "progress": "lots"}""")))
		assertNull(lbIndexProgressOf(obj("""{"seq": 12, "progress": {"unindexed": -3, "total": 10, "current": ""}}""")))
		assertNull(lbIndexProgressOf(obj("""{"seq": 12, "progress": {"total": 10}}""")))
	}

	@Test
	fun progress_parsed_from_lbIndex_and_index_frame() {
		// welcome.lbIndex
		assertEquals(
			LbIndexProgress(unindexed = 412, total = 2337, current = "Björk"),
			lbIndexProgressOf(obj("""{"seq": 9, "epoch": "e", "progress": {"unindexed": 412, "total": 2337, "current": "Björk"}}"""))
		)
		// The `index` frame, its `t` beside the hint's own keys; `current` may be missing.
		assertEquals(
			LbIndexProgress(unindexed = 0, total = 2337, current = ""),
			lbIndexProgressOf(obj("""{"t": "index", "seq": 9, "epoch": "e", "progress": {"unindexed": 0, "total": 2337}}"""))
		)
	}

	@Test
	fun a_progress_only_push_skips_the_pull() {
		// The same seq again, with progress: the last frame already pulled.
		assertTrue(indexFrameIsProgressOnly(lastEpoch = "e", lastSeq = 9, epoch = "e", seq = 9, hasProgress = true))
		// A new seq, another epoch, no progress, or nothing heard yet: pull as always.
		assertFalse(indexFrameIsProgressOnly("e", 9, "e", 10, true))
		assertFalse(indexFrameIsProgressOnly("e", 9, "f", 9, true))
		assertFalse(indexFrameIsProgressOnly("e", 9, "e", 9, false))
		assertFalse(indexFrameIsProgressOnly(null, null, "e", 9, true))
		assertFalse(indexFrameIsProgressOnly("e", 9, "e", null, true))
	}

	@Test
	fun downloads_off_rejection_flips_state() {
		val refusal = lbFailureOf(409, """{"code":"downloads_off","error":"Downloading is not set up on this server."}""")
		assertTrue(isDownloadsOffRefusal(refusal))
		assertEquals(LbDownloads.OFF, downloadsAfterError(LbDownloads.ON, refusal))
		assertEquals(LbDownloads.OFF, downloadsAfterError(LbDownloads.UNKNOWN, refusal))
		// Any other refusal leaves the mode alone.
		val busy = lbFailureOf(409, """{"code":"busy","error":"A fill for this album is in flight"}""")
		assertFalse(isDownloadsOffRefusal(busy))
		assertEquals(LbDownloads.ON, downloadsAfterError(LbDownloads.ON, busy))
		assertEquals(LbDownloads.ON, downloadsAfterError(LbDownloads.ON, lbFailureOf(500, "")))
		// Off until an advert says otherwise; an advert that says nothing keeps it off.
		assertEquals(LbDownloads.OFF, downloadsAfterAdvert(LbDownloads.OFF, LbDownloads.UNKNOWN))
		assertEquals(LbDownloads.ON, downloadsAfterAdvert(LbDownloads.OFF, LbDownloads.ON))
		// A new connection (a welcome) says from scratch: an older hub's silence is "unknown" (= on), and
		// the next 409 turns it off again.
		assertEquals(LbDownloads.UNKNOWN, downloadsAfterAdvert(LbDownloads.OFF, LbDownloads.UNKNOWN, newConnection = true))
		assertEquals(LbDownloads.OFF, downloadsAfterAdvert(LbDownloads.ON, LbDownloads.OFF, newConnection = true))
		assertFalse(LbDownloads.OFF.isOn)
	}

	@Test
	fun rejected_keeps_code() {
		val refusal = lbFailureOf(409, """{"code":"downloads_off","error":"Downloading is not set up on this server."}""")
		assertIs<LbBotManager.LbError.Rejected>(refusal)
		assertEquals(409, refusal.status)
		assertEquals("downloads_off", refusal.code)
		assertEquals("Downloading is not set up on this server.", refusal.message)
		// lb-bot's sign-in refusal, relayed by a hub whose token it does not know.
		val signin = lbFailureOf(401, """{"code":"signin_required"}""")
		assertEquals(LbBotManager.LbError.Rejected(401, "", "signin_required"), signin)
		// Today's rules are unchanged: a body with no code, 404, 502 and 504.
		assertEquals(LbBotManager.LbError.Rejected(400, "mbid is required"), lbFailureOf(400, """{"error":"mbid is required"}"""))
		assertEquals(LbBotManager.LbError.RouteUnknown, lbFailureOf(404, ""))
		assertEquals(LbBotManager.LbError.Busy, lbFailureOf(502, "lb-bot unreachable"))
		assertEquals(LbBotManager.LbError.Busy, lbFailureOf(504, ""))
		assertEquals(LbBotManager.LbError.Rejected(500, ""), lbFailureOf(500, "<html>"))
	}

	// ---- the review's fixes: one state holder every reader passes through, kept per hub ---- //

	private class Kept(var text: String = "") : LbDownloadsMemory {
		var writes = 0
		override fun read(): String = text
		override fun write(value: String) {
			text = value
			writes++
		}
	}

	private val off = """{"code":"downloads_off","error":"Downloading is not set up on this server."}"""

	@Test
	fun downloads_off_409_through_every_reader_turns_the_manager_off() {
		// `failureFor` (getJson, postJson, getText): the error it returns, and the mode.
		val viaFailure = LbDownloadsState()
		val error = viaFailure.failureOf(409, off)
		assertTrue(isDownloadsOffRefusal(error))
		assertEquals(LbDownloads.OFF, viaFailure.current)
		// `rawAnswer` (rawGet, rawPost) and the two override readers note the answer as read.
		val viaRaw = LbDownloadsState()
		assertTrue(viaRaw.noteAnswer(409, off))
		assertEquals(LbDownloads.OFF, viaRaw.value.value)
		// Nothing else moves it: another 409, a 400, a 2xx, an unreadable body.
		val untouched = LbDownloadsState()
		untouched.applyAdvert(LbDownloads.ON)
		assertFalse(untouched.noteAnswer(409, """{"code":"busy"}"""))
		untouched.failureOf(400, """{"error":"mbid is required"}""")
		assertFalse(untouched.noteAnswer(200, off))
		untouched.noteAnswer(409, "<html>")
		assertEquals(LbDownloads.ON, untouched.current)
		// Off holds through an `lb` frame that says nothing; a word replaces it.
		viaRaw.applyAdvert(LbDownloads.UNKNOWN)
		assertEquals(LbDownloads.OFF, viaRaw.current)
		viaRaw.applyAdvert(LbDownloads.ON)
		assertEquals(LbDownloads.ON, viaRaw.current)
	}

	@Test
	fun every_welcome_resets_the_mode_lb_or_not() {
		// A P1 hub said off; the next welcome is an older hub's, with no `lb` at all: unknown (= on).
		val state = LbDownloadsState()
		state.applyAdvert(LbDownloads.OFF, newConnection = true)
		state.newConnection()
		assertEquals(LbDownloads.UNKNOWN, state.current)
		assertTrue(state.current.isOn)
	}

	@Test
	fun the_last_word_per_hub_is_where_a_cold_start_begins() {
		val kept = Kept()
		var hub = "wss://hub.example/ws"
		val first = LbDownloadsState(kept) { hub }
		assertEquals(LbDownloads.UNKNOWN, first.current)
		first.applyAdvert(LbDownloads.OFF, newConnection = true)
		// A new process against the same hub starts off, before any welcome.
		assertEquals(LbDownloads.OFF, LbDownloadsState(kept) { hub }.current)
		// A refusal is kept the same way.
		val second = LbDownloadsState(kept) { hub }
		second.applyAdvert(LbDownloads.ON, newConnection = true)
		assertEquals(LbDownloads.ON, LbDownloadsState(kept) { hub }.current)
		second.noteAnswer(409, off)
		assertEquals(LbDownloads.OFF, LbDownloadsState(kept) { hub }.current)
		// Another hub knows nothing of it.
		hub = "wss://other.example/ws"
		assertEquals(LbDownloads.UNKNOWN, LbDownloadsState(kept) { hub }.current)
		// An older hub's silence is not kept as a word: that hub's next launch starts unknown too.
		hub = "wss://hub.example/ws"
		val third = LbDownloadsState(kept) { hub }
		third.newConnection()
		assertEquals(LbDownloads.UNKNOWN, LbDownloadsState(kept) { hub }.current)
		// A welcome still overrides what was kept.
		third.applyAdvert(LbDownloads.OFF, newConnection = true)
		val fourth = LbDownloadsState(kept) { hub }
		assertEquals(LbDownloads.OFF, fourth.current)
		fourth.applyAdvert(LbDownloads.ON, newConnection = true)
		assertEquals(LbDownloads.ON, fourth.current)
		// Garbage in the preference is an empty map, never a crash; no hub configured keeps nothing.
		assertEquals(LbDownloads.UNKNOWN, LbDownloadsState(Kept("{not json")) { hub }.current)
		val none = Kept()
		LbDownloadsState(none) { "" }.applyAdvert(LbDownloads.OFF, newConnection = true)
		assertEquals(0, none.writes)
	}
}
