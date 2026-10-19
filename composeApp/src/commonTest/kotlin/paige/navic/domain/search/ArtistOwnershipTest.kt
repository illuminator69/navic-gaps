package paige.navic.domain.search

import paige.navic.data.database.dao.ArtistMbidRow
import paige.navic.domain.manager.LbArtistOverride
import kotlin.test.Test
import kotlin.test.assertEquals

/*
 * P3: a MusicBrainz artist is "in your library" when a library artist IS that MBID: by the user's
 * override first, then Navidrome's tag, then lb-bot's index mirror. Never by name.
 */
class ArtistOwnershipTest {

	@Test
	fun theTagOwns() {
		assertEquals(
			mapOf("mb-a" to "nd1"),
			ownedArtistsByMbid(listOf("mb-a", "mb-b"), emptyMap(), listOf(ArtistMbidRow("nd1", "mb-a")), emptyMap(), setOf("nd1"))
		)
	}

	@Test
	fun anOverrideWinsOverTheTag() {
		val overrides = mapOf("nd1" to LbArtistOverride(ndArtistId = "nd1", mbid = "mb-b"))
		// nd1 is tagged mb-a but the user said it is mb-b: mb-a is not owned through nd1
		assertEquals(
			mapOf("mb-b" to "nd1"),
			ownedArtistsByMbid(listOf("mb-a", "mb-b"), overrides, listOf(ArtistMbidRow("nd1", "mb-a")), emptyMap(), setOf("nd1"))
		)
	}

	@Test
	fun theMirrorOwnsAnUntaggedArtistRoomStillHolds() {
		assertEquals(
			mapOf("mb-a" to "nd2"),
			ownedArtistsByMbid(listOf("mb-a", "mb-c"), emptyMap(), emptyList(), mapOf("mb-a" to "nd2", "mb-c" to "nd9"), setOf("nd2"))
		)
	}

	@Test
	fun blanksOwnNothing() {
		assertEquals(
			emptyMap(),
			ownedArtistsByMbid(listOf("", " "), emptyMap(), listOf(ArtistMbidRow("nd1", "")), mapOf("" to "nd1"), setOf("nd1"))
		)
		assertEquals(
			emptyMap(),
			ownedArtistsByMbid(listOf("mb-a"), emptyMap(), emptyList(), mapOf("mb-a" to ""), setOf(""))
		)
	}
}
