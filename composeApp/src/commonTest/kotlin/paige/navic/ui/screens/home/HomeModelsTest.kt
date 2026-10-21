package paige.navic.ui.screens.home

import paige.navic.data.database.entities.SavedQueueEntity
import paige.navic.domain.manager.LbFreshFeed
import paige.navic.domain.manager.LbFreshRelease
import paige.navic.ui.navigation.Screen
import paige.navic.domain.models.Mix
import paige.navic.domain.models.YOUR_MIX_ID
import paige.navic.ui.screens.fresh.viewmodels.FreshFilters
import paige.navic.ui.screens.fresh.viewmodels.FreshScope
import paige.navic.ui.screens.fresh.viewmodels.FreshSort
import paige.navic.ui.screens.fresh.viewmodels.FreshType
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
		cover: String = "https://caa/$rgid",
		date: String = "",
		type: String = ""
	) = LbFreshRelease(
		releaseName = title,
		artist = "Artist of $rgid",
		artistMbids = listOf("ar-$rgid"),
		releaseGroupMbid = rgid,
		coverUrl = cover,
		artistOwned = artistOwned,
		artistId = if (artistOwned) "nd-ar-$rgid" else "",
		releaseOwned = releaseOwned,
		releaseAlbumId = albumId,
		releaseDate = date,
		type = type
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
	fun freshFollowsThePagesTypeFilter() {
		val shelf = freshShelf(
			feed(release("a", type = "Album"), release("b", type = "Single"), release("c", type = "Album")),
			filters = FreshFilters(FreshScope.YOURS, FreshSort.DATE, FreshType.SINGLE)
		)
		assertEquals(listOf("b"), shelf.map { it.rgid })
	}

	@Test
	fun freshFollowsThePagesScope() {
		val rows = feed(release("a"), release("b", artistOwned = false))
		assertEquals(listOf("a"), freshShelf(rows).map { it.rgid })
		assertEquals(
			listOf("a", "b"),
			freshShelf(rows, filters = FreshFilters(FreshScope.ALL, FreshSort.DATE, FreshType.ALL)).map { it.rgid }
		)
	}

	@Test
	fun freshIsInThePagesOrderNewestFirst() {
		val shelf = freshShelf(feed(release("old", date = "2026-01-02"), release("new", date = "2026-10-01"), release("mid", date = "2026-05-05")))
		assertEquals(listOf("new", "mid", "old"), shelf.map { it.rgid })
	}

	@Test
	fun freshSortedByArtistIsTheirOrder() {
		val shelf = freshShelf(
			feed(release("z"), release("a")),
			filters = FreshFilters(FreshScope.YOURS, FreshSort.ARTIST, FreshType.ALL)
		)
		assertEquals(listOf("a", "z"), shelf.map { it.rgid })
	}

	@Test
	fun freshKeepsLbBotsOrderWithinADate() {
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

	// --- What a read does to the shelf on screen ------------------------------------------------

	@Test
	fun aFeedReplacesTheShelf() {
		val before = freshShelf(feed(release("old")))
		val after = freshShelfAfterRead(before, feed(release("new1"), release("new2")))
		assertEquals(listOf("new1", "new2"), after.map { it.rgid })
	}

	@Test
	fun aFailedReadKeepsTheShelfOnScreen() {
		// `null` is "nothing cached and nothing came back": it must never take a shelf away.
		val before = freshShelf(feed(release("a"), release("b")))
		assertEquals(before, freshShelfAfterRead(before, null))
	}

	@Test
	fun aFailedReadOnAnEmptyShelfLeavesItEmpty() {
		assertEquals(emptyList(), freshShelfAfterRead(emptyList(), null))
	}

	@Test
	fun anAnswerOfNothingNewIsAnAnswer() {
		// lb-bot DID answer, and the answer has no release by an artist the library holds.
		val before = freshShelf(feed(release("a")))
		assertEquals(emptyList(), freshShelfAfterRead(before, feed(release("x", artistOwned = false))))
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

	// --- Sections (P6) ------------------------------------------------------------------------------

	private fun sections(
		queues: Int = 3, recent: Int = 20, freshOn: Boolean = true, fresh: Int = 4,
		played: Int = 12, frequent: Int = 12, mixes: Int = 5
	) = homeSections(queues, recent, freshOn, fresh, played, frequent, mixes)

	@Test
	fun sectionsAreInTheOwnersOrder() {
		assertEquals(
			listOf(
				HomeSection.ContinueListening, HomeSection.RecentlyAdded, HomeSection.Fresh,
				HomeSection.RecentlyPlayed, HomeSection.FrequentlyPlayed, HomeSection.YourMixes
			),
			sections()
		)
	}

	@Test
	fun eachSectionIsAbsentAtZeroAndLeavesNoHole() {
		assertEquals(
			listOf(HomeSection.ContinueListening, HomeSection.RecentlyAdded, HomeSection.FrequentlyPlayed, HomeSection.YourMixes),
			sections(fresh = 0, played = 0)
		)
		assertEquals(listOf(HomeSection.RecentlyPlayed), sections(queues = 0, recent = 0, freshOn = false, frequent = 0, mixes = 0))
		assertFalse(HomeSection.YourMixes in sections(mixes = 0))
		assertFalse(HomeSection.ContinueListening in sections(queues = 0))
	}

	@Test
	fun noFreshWhenLbBotIsAbsent() {
		assertFalse(HomeSection.Fresh in sections(freshOn = false, fresh = 4))
	}

	@Test
	fun everythingAbsentIsNoSections() {
		assertEquals(emptyList(), homeSections(0, 0, false, 0, 0, 0, 0))
	}

	// --- Your mixes (P6) -------------------------------------------------------------------------

	private fun mix(id: String, lastPlayed: Long = 0, updated: Long = 0, kind: String = "station") =
		Mix(id = id, name = id, kind = kind, lastPlayedAt = lastPlayed, updatedAt = updated)

	@Test
	fun yourMixIsFirstOnlyWithTheEngine() {
		val mixes = listOf(mix("a", lastPlayed = 5))
		assertEquals(listOf(YOUR_MIX_ID, "a"), mixTiles(mixes, yourMixAvailable = true).map { it.key })
		assertEquals(listOf("a"), mixTiles(mixes, yourMixAvailable = false).map { it.key })
		assertTrue(mixTiles(mixes, true).first().yourMix)
	}

	@Test
	fun mixesByLastPlayedThenNeverPlayedByLastChange() {
		val tiles = mixTiles(
			listOf(mix("old", lastPlayed = 10), mix("never", updated = 50), mix("new", lastPlayed = 100)),
			yourMixAvailable = false
		)
		assertEquals(listOf("new", "never", "old"), tiles.map { it.key })
	}

	@Test
	fun aTieKeepsTheHubsOrder() {
		val tiles = mixTiles(listOf(mix("x", lastPlayed = 7), mix("y", lastPlayed = 7), mix("z", lastPlayed = 7)), false)
		assertEquals(listOf("x", "y", "z"), tiles.map { it.key })
	}

	@Test
	fun everyKindIsATileUpToThirty() {
		val kinds = listOf("artist", "genre", "station", "fingerprint", "some-future-kind")
		val mixes = (1..40).map { mix("m$it", lastPlayed = it.toLong(), kind = kinds[it % kinds.size]) }
		val tiles = mixTiles(mixes, yourMixAvailable = true)
		assertEquals(1 + MIXES_MAX, tiles.size)
		assertEquals("m40", tiles[1].key)
		assertTrue(tiles.any { it.mix?.kind == "some-future-kind" })
	}

	@Test
	fun noMixesAndNoEngineIsNoSection() {
		val tiles = mixTiles(emptyList(), yourMixAvailable = false)
		assertTrue(tiles.isEmpty())
		assertFalse(HomeSection.YourMixes in sections(mixes = tiles.size))
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
