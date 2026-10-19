package paige.navic.ui.screens.home

import paige.navic.data.database.entities.SavedQueueEntity
import paige.navic.domain.manager.LbFreshFeed
import paige.navic.domain.manager.LbFreshRelease
import paige.navic.ui.navigation.Screen
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/*
 * P1: what Home shows and where a tile goes, as plain functions. Fresh is lb-bot's feed narrowed to
 * the artists the library holds, one tile per release group, "owned" being the RELEASE's flag (the
 * same rule the Fresh page uses: an owned artist with a brand-new album is still a download). The
 * Continue listening pill is the saved queues, the playing one first. Which sections the page draws,
 * and in what order, is decided here too, so a section with nothing to say is absent with no gap.
 */
class HomeModelsTest {

	private fun release(
		rgid: String,
		artistOwned: Boolean = true,
		releaseOwned: Boolean = false,
		albumId: String = "",
		title: String = "Title $rgid",
		cover: String = "https://caa/$rgid"
	) = LbFreshRelease(
		releaseName = title,
		artist = "Artist of $rgid",
		artistMbids = listOf("ar-$rgid"),
		releaseGroupMbid = rgid,
		coverUrl = cover,
		artistOwned = artistOwned,
		artistId = if (artistOwned) "nd-ar-$rgid" else "",
		releaseOwned = releaseOwned,
		releaseAlbumId = albumId
	)

	private fun feed(vararg rows: LbFreshRelease) = LbFreshFeed(releases = rows.toList(), total = rows.size)

	private fun queue(id: String, updatedAt: Long) =
		SavedQueueEntity(id = id, queueJson = "[]", updatedAt = updatedAt)

	// --- Fresh -----------------------------------------------------------------------------------

	@Test
	fun freshKeepsOnlyArtistsYouOwn() {
		val shelf = freshShelf(feed(release("a"), release("b", artistOwned = false), release("c")))
		assertEquals(listOf("a", "c"), shelf.map { it.rgid })
	}

	@Test
	fun freshKeepsLbBotsOrder() {
		val shelf = freshShelf(feed(release("z"), release("m"), release("a")))
		assertEquals(listOf("z", "m", "a"), shelf.map { it.rgid })
	}

	@Test
	fun freshHasOneTilePerReleaseGroup() {
		val shelf = freshShelf(feed(release("a", title = "First"), release("b"), release("a", title = "Second")))
		assertEquals(listOf("a", "b"), shelf.map { it.rgid })
		assertEquals("First", shelf.first().title)
	}

	@Test
	fun freshRespectsTheLimit() {
		val rows = (1..30).map { release("rg$it") }.toTypedArray()
		assertEquals(12, freshShelf(feed(*rows)).size)
		assertEquals(5, freshShelf(feed(*rows), limit = 5).size)
	}

	@Test
	fun ownedFollowsReleaseOwned() {
		val shelf = freshShelf(
			feed(
				release("a", releaseOwned = true, albumId = "al-1"),
				release("b", releaseOwned = false)
			)
		)
		assertTrue(shelf[0].owned)
		assertFalse(shelf[1].owned)
		// The artist being owned says nothing about the album: both tiles are of owned artists.
		assertEquals(listOf("nd-ar-a", "nd-ar-b"), shelf.map { it.artistId })
	}

	@Test
	fun aNullFeedIsAnEmptyShelf() {
		assertEquals(emptyList(), freshShelf(null))
		assertEquals(emptyList(), freshShelf(LbFreshFeed()))
	}

	@Test
	fun aTileNeedsSomewhereToGo() {
		// No release group and no library album: a tap would lead nowhere, so there is no tile.
		val shelf = freshShelf(feed(release(""), release("b")))
		assertEquals(listOf("b"), shelf.map { it.rgid })
	}

	@Test
	fun aBlankCoverIsNoCover() {
		val shelf = freshShelf(feed(release("a", cover = ""), release("b")))
		assertNull(shelf[0].coverUrl)
		assertEquals("https://caa/b", shelf[1].coverUrl)
	}

	@Test
	fun theFirstArtistMbidIsKept() {
		val shelf = freshShelf(feed(release("a")))
		assertEquals("ar-a", shelf.single().artistMbid)
	}

	// --- Where a Fresh tile goes -----------------------------------------------------------------

	@Test
	fun ownedWithAnAlbumIdOpensTheAlbum() {
		val tile = freshShelf(feed(release("a", releaseOwned = true, albumId = "al-9"))).single()
		assertEquals(Screen.CollectionDetail("al-9", ""), freshTarget(tile))
	}

	@Test
	fun ownedWithoutOneOpensTheExternalPage() {
		// An album lb-bot filled itself has no Navidrome id to open: the external page, whose redirect
		// finds it, is the way in (the Fresh page's own rule).
		val tile = freshShelf(feed(release("a", releaseOwned = true, albumId = ""))).single()
		assertEquals(
			Screen.ExternalAlbum(
				rgid = "a", artistMbid = "ar-a", artistName = "Artist of a", title = "Title a", artistId = "nd-ar-a"
			),
			freshTarget(tile)
		)
	}

	@Test
	fun notOwnedOpensTheExternalPage() {
		val tile = freshShelf(feed(release("b", releaseOwned = false))).single()
		assertTrue(freshTarget(tile) is Screen.ExternalAlbum)
		assertEquals("b", (freshTarget(tile) as Screen.ExternalAlbum).rgid)
	}

	// --- Continue listening ----------------------------------------------------------------------

	@Test
	fun continueListeningPutsThePlayingQueueFirst() {
		val queues = listOf(queue("new", 300), queue("playing", 100), queue("old", 200))
		assertEquals(listOf("playing", "new", "old"), continueListening(queues, playingId = "playing").map { it.id })
	}

	@Test
	fun theRestAreByUpdatedAtDescending() {
		val queues = listOf(queue("a", 100), queue("b", 300), queue("c", 200))
		assertEquals(listOf("b", "c", "a"), continueListening(queues, playingId = null).map { it.id })
		// A playing id that is no queue here (another device, or nothing) changes nothing.
		assertEquals(listOf("b", "c", "a"), continueListening(queues, playingId = "elsewhere").map { it.id })
	}

	@Test
	fun continueListeningRespectsItsLimit() {
		val queues = (1..10).map { queue("q$it", it.toLong()) }
		assertEquals(6, continueListening(queues, null).size)
		assertEquals(3, continueListening(queues, null, limit = 3).size)
		// The playing one is kept even when it is the oldest.
		assertEquals("q1", continueListening(queues, playingId = "q1").first().id)
	}

	// --- Sections --------------------------------------------------------------------------------

	@Test
	fun sectionsAreInTheBoardsOrder() {
		assertEquals(
			listOf(HomeSection.ContinueListening, HomeSection.RecentlyAdded, HomeSection.Fresh, HomeSection.YourMix),
			homeSections(
				queueCount = 3, recentCount = 20, freshAvailable = true, freshCount = 4, yourMixAvailable = true
			)
		)
	}

	@Test
	fun noFreshWhenLbBotIsAbsent() {
		val sections = homeSections(
			queueCount = 3, recentCount = 20, freshAvailable = false, freshCount = 4, yourMixAvailable = true
		)
		assertFalse(HomeSection.Fresh in sections)
		assertEquals(
			listOf(HomeSection.ContinueListening, HomeSection.RecentlyAdded, HomeSection.YourMix), sections
		)
	}

	@Test
	fun noFreshWhenThereIsNothingNew() {
		val sections = homeSections(
			queueCount = 0, recentCount = 5, freshAvailable = true, freshCount = 0, yourMixAvailable = false
		)
		assertEquals(listOf(HomeSection.RecentlyAdded), sections)
	}

	@Test
	fun noPillWithoutQueues() {
		val sections = homeSections(
			queueCount = 0, recentCount = 20, freshAvailable = true, freshCount = 4, yourMixAvailable = true
		)
		assertFalse(HomeSection.ContinueListening in sections)
	}

	@Test
	fun yourMixOnlyWithTheEngine() {
		assertTrue(
			HomeSection.YourMix in homeSections(1, 1, true, 1, yourMixAvailable = true)
		)
		assertFalse(
			HomeSection.YourMix in homeSections(1, 1, true, 1, yourMixAvailable = false)
		)
	}

	@Test
	fun everythingAbsentIsNoSections() {
		assertEquals(emptyList(), homeSections(0, 0, false, 0, false))
	}

	// --- The pill's first state ------------------------------------------------------------------

	@Test
	fun nothingPlayingStartsExpanded() {
		// Board 1f: with nothing playing the saved queues are what there is to do, so they show.
		assertTrue(continueStartsExpanded(nothingPlaying = true, queueCount = 3))
	}

	@Test
	fun somethingPlayingStartsFolded() {
		assertFalse(continueStartsExpanded(nothingPlaying = false, queueCount = 3))
	}

	@Test
	fun noQueuesIsNothingToExpand() {
		assertFalse(continueStartsExpanded(nothingPlaying = true, queueCount = 0))
	}
}
