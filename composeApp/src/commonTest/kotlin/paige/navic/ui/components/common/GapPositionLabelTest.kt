package paige.navic.ui.components.common

import kotlinx.serialization.json.Json
import paige.navic.domain.manager.LbGapTrack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/*
 * B-071: lb-bot's gap rows carry `disc` since 2026-10-10, and a multi-disc gap listed by
 * position alone reads 1, 2, 1, 2. The rule is Feishin's too, mirrored by hand.
 */
class GapPositionLabelTest {
	private val json = Json { ignoreUnknownKeys = true }

	private fun track(position: Int, disc: Int = 0) = LbGapTrack(position = position, disc = disc)

	private fun labels(tracks: List<LbGapTrack>): List<String> {
		val showDisc = gapShowsDisc(tracks)
		return tracks.map { gapPositionLabel(it, showDisc) }
	}

	@Test
	fun aSingleDiscGapIsNumberedAsBefore() {
		val tracks = listOf(track(1, disc = 1), track(2, disc = 1), track(3, disc = 1))
		assertFalse(gapShowsDisc(tracks))
		assertEquals(listOf("1", "2", "3"), labels(tracks))
	}

	@Test
	fun aGapFromAnOlderLbBotHasNoDiscAndIsNumberedAsBefore() {
		// No `disc` on the wire at all: it reads as 0, "not known".
		val tracks = listOf(
			json.decodeFromString<LbGapTrack>("""{"position": 1, "title": "a", "state": "present"}"""),
			json.decodeFromString<LbGapTrack>("""{"position": 2, "title": "b", "state": "missing"}""")
		)
		assertEquals(listOf(0, 0), tracks.map { it.disc })
		assertFalse(gapShowsDisc(tracks))
		assertEquals(listOf("1", "2"), labels(tracks))
	}

	@Test
	fun aTwoDiscGapNamesTheDiscOnEveryRowInLbBotsOrder() {
		val tracks = listOf(
			json.decodeFromString<LbGapTrack>("""{"position": 1, "disc": 1, "title": "a"}"""),
			track(2, disc = 1), track(1, disc = 2), track(12, disc = 2), track(12, disc = 10)
		)
		assertTrue(gapShowsDisc(tracks))
		// No zero padding, and no re-sorting.
		assertEquals(listOf("1-1", "1-2", "2-1", "2-12", "10-12"), labels(tracks))
	}

	@Test
	fun aRowWithNoKnownDiscKeepsItsBarePositionInsideATwoDiscGap() {
		val tracks = listOf(track(1, disc = 1), track(7, disc = 0), track(1, disc = 2))
		assertTrue(gapShowsDisc(tracks))
		assertEquals(listOf("1-1", "7", "2-1"), labels(tracks))
	}

	@Test
	fun discsNotKnownAnywhereDoNotTurnTheNumbersOn() {
		val tracks = listOf(track(1), track(2), track(1), track(2))
		assertFalse(gapShowsDisc(tracks))
		assertEquals(listOf("1", "2", "1", "2"), labels(tracks))
	}
}
