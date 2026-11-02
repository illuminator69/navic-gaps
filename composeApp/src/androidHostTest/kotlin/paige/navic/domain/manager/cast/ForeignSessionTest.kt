package paige.navic.domain.manager.cast

import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/*
 * Worbus P2, lane N4 (design §6.3, Chromecasts are per user): when another user's sender takes the
 * speaker, this bridge reports it released (its own user's session pauses) and never adopts the
 * foreign session. Navic used to adopt any mediaSessionId that arrived (`CastChannel`'s spontaneous
 * MEDIA_STATUS), and so went on driving the other user's music.
 */
class ForeignSessionTest {
	private val ours = "http://nd/rest/stream.view?id=song-1&u=bob&t=x&s=y"
	private val ours2 = "http://nd/rest/stream.view?id=song-2&u=bob&t=x&s=y"
	private val theirs = "http://nd/rest/stream.view?id=song-9&u=alice&t=p&s=q"
	private val queue = listOf(ours, ours2)

	@Test
	fun foreign_media_session_releases_not_adopts() {
		val own = setOf(7L)
		// A mediaSessionId this bridge did not start: another sender's LOAD.
		assertTrue(castStatusIsForeign(8L, theirs, own, queue, ours, holdsPlayback = true))
		assertTrue(castStatusIsForeign(8L, ours, own, queue, ours, true), "even playing the same song, it is their session")
		// No own session known yet, playing for this user, and content that is not of its queue.
		assertTrue(castStatusIsForeign(null, theirs, emptySet(), queue, ours, holdsPlayback = true))
		assertTrue(castStatusIsForeign(8L, theirs, emptySet(), queue, ours, holdsPlayback = true))
		// Ours: our session, or with none known our content or the track we last loaded.
		assertFalse(castStatusIsForeign(7L, ours2, own, queue, ours, true))
		assertFalse(castStatusIsForeign(null, null, own, queue, ours, true))
		assertFalse(castStatusIsForeign(null, ours2, emptySet(), queue, ours, true))
		assertFalse(castStatusIsForeign(null, "http://nd/rest/stream.view?id=old&u=bob", emptySet(), queue, "http://nd/rest/stream.view?id=old&u=bob", true))
		// Before this bridge plays anything here, a status is a join's first look, not a takeover.
		assertFalse(castStatusIsForeign(8L, theirs, emptySet(), queue, null, holdsPlayback = false))

		// Another sender's app replaced ours on the receiver.
		assertTrue(receiverStatusIsForeign(listOf("their-app"), ownAppSessionId = "our-app", holdsOwnMedia = true))
		assertFalse(receiverStatusIsForeign(listOf("our-app"), "our-app", true))
		assertFalse(receiverStatusIsForeign(emptyList(), "our-app", true), "an app that idled out is not a takeover")
		assertFalse(receiverStatusIsForeign(listOf("their-app"), "our-app", holdsOwnMedia = false))

		// Released, not adopted: the frames say "not playing, released here", with no transferId.
		val frames = speakerTakenFrames(index = 3, positionMs = 61_000)
		assertEquals(listOf("report", "released"), frames.map { it["t"]!!.jsonPrimitive.content })
		assertEquals("false", frames[0]["isPlaying"]!!.jsonPrimitive.content)
		for (f in frames) {
			assertEquals("3", f["index"]!!.jsonPrimitive.content)
			assertEquals("61000", f["positionMs"]!!.jsonPrimitive.content)
			assertFalse("transferId" in f, "not an answer to a transfer")
		}
		// …and a session another user's sender started is never adopted later.
		assertFalse(mayAdoptSession(8L, foreignSessionIds = setOf(8L)))
		assertTrue(mayAdoptSession(7L, foreignSessionIds = setOf(8L)))
	}

	private val bridge = File("src/androidMain/kotlin/paige/navic/domain/manager/cast/CastDeviceBridge.kt").readText()
	private val channel = File("src/androidMain/kotlin/paige/navic/domain/manager/cast/CastChannel.kt").readText()

	private fun body(text: String, signature: String): String {
		val start = text.indexOf(signature).also { assertTrue(it >= 0, signature) }
		var depth = 0
		var i = text.indexOf('{', start)
		val from = i
		while (i < text.length) {
			when (text[i]) {
				'{' -> depth++
				'}' -> if (--depth == 0) return text.substring(from, i + 1)
			}
			i++
		}
		error("unbalanced $signature")
	}

	@Test
	fun the_taken_speaker_is_left_playing_for_its_new_user() {
		val taken = body(bridge, "private suspend fun speakerTaken(")
		assertFalse(Regex("""\.(pause|stop)\(""").containsMatchIn(taken), "never PAUSE or STOP the other user's music")
		assertTrue("speakerTakenFrames(" in taken)
		// The status paths check before anything follows the session.
		assertTrue("castStatusIsForeign(" in body(bridge, "private fun onCastStatus("))
		assertTrue("mayAdoptSession(" in body(bridge, "private suspend fun adoptRunningSession("))
	}

	@Test
	fun the_channel_no_longer_adopts_a_spontaneous_session() {
		val spontaneous = channel.substring(channel.indexOf("envelope.type == \"MEDIA_STATUS\""))
			.substringBefore("val id = envelope.requestId")
		assertFalse("mediaSessionId =" in spontaneous, spontaneous)
		assertFalse("mediaSessionId = st.mediaSessionId" in body(channel, "private suspend fun attachTo("))
	}

	@Test
	fun logout_leaves_the_worbus_server_first() {
		val logout = body(
			File("src/commonMain/kotlin/paige/navic/domain/manager/LoginManager.kt").readText(),
			"fun logout()"
		)
		val leave = logout.indexOf("hubManager.leaveForLogout()")
		assertTrue(leave >= 0, "LoginManager.logout signs out of the Worbus Server")
		assertTrue(leave < logout.indexOf("sessionManager.logout()"), "before the Navidrome session goes")
		assertFalse(
			"stay as they always did" in File("src/commonMain/kotlin/paige/navic/domain/manager/SessionManager.kt").readText(),
			"the 2026-10-30 ruling's comment is replaced"
		)
	}

	@Test
	fun own_session_is_never_foreign() {
		// R25: a queue edit that drops the playing track leaves the speaker on content the queue no
		// longer holds; with this bridge's own session id that is still its own playback.
		val edited = listOf(ours2)
		assertFalse(castStatusIsForeign(7L, ours, setOf(7L), edited, lastLoadedContentId = null, holdsPlayback = true))
		assertFalse(castStatusIsForeign(7L, theirs, setOf(7L), edited, null, true))
		// With a session of its own known, a status with no id is not judged on its content.
		assertFalse(castStatusIsForeign(null, theirs, setOf(7L), edited, null, true))
	}
}
