package paige.navic.ui.screens.discover

import paige.navic.domain.manager.LbBrowseAlbum
import paige.navic.domain.manager.LbFreshRelease
import paige.navic.ui.screens.discover.viewmodels.DiscoverArtist
import paige.navic.ui.screens.discover.viewmodels.DiscoverUi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/*
 * P3: Discover's stack is music the library does not hold, dealt round-robin from the sources that
 * can say why: new from an artist you have, similar to one artist, Deezer's chart, Deezer's editors.
 * Owned items never appear, an empty source leaves no hole, and no row is without its reason.
 */
class DiscoverStackTest {

	private fun fresh(id: String, artistOwned: Boolean = true, releaseOwned: Boolean = false) = LbFreshRelease(
		releaseName = "Release $id", artist = "Artist $id", releaseGroupMbid = "rg-$id",
		artistOwned = artistOwned, releaseOwned = releaseOwned, coverUrl = "https://caa/$id"
	)

	private fun similar(id: String, owned: Boolean = false) = DiscoverArtist(
		mbid = "mb-$id", name = "Similar $id", owned = owned, indexed = false,
		artistId = if (owned) "nd-$id" else "", coverArtId = null
	)

	private fun deezer(id: String, owned: Boolean = false) = LbBrowseAlbum(
		title = "Album $id", artist = "Artist $id", deezerId = id, releaseOwned = owned
	)

	private val full = DiscoverUi(
		fresh = (1..5).map { fresh("f$it") },
		similarArtists = (1..5).map { similar("s$it") },
		similarSeed = "Wolf Alice",
		charts = (1..5).map { deezer("c$it") },
		editorial = (1..5).map { deezer("e$it") }
	)

	@Test
	fun dealtRoundRobinWithEachSourcesCap() {
		assertEquals(
			listOf(
				"fresh:rg-f1", "similar:mb-s1", "chart:c1", "picked:e1",
				"fresh:rg-f2", "similar:mb-s2", "chart:c2", "picked:e2",
				"fresh:rg-f3", "similar:mb-s3"
			),
			discoverStack(full).map { it.key }
		)
	}

	@Test
	fun everyRowSaysWhy() {
		val stack = discoverStack(full)
		assertEquals(DiscoverReason.Fresh, stack[0].reason)
		assertEquals(DiscoverReason.Similar("Wolf Alice"), stack[1].reason)
		assertEquals(DiscoverReason.Chart, stack[2].reason)
		assertEquals(DiscoverReason.Picked, stack[3].reason)
		assertTrue(stack.all { (it.reason as? DiscoverReason.Similar)?.seed?.isNotBlank() ?: true })
	}

	@Test
	fun ownedItemsNeverAppear() {
		val ui = DiscoverUi(
			fresh = listOf(fresh("owned", releaseOwned = true), fresh("stranger", artistOwned = false), fresh("ok")),
			similarArtists = listOf(similar("have", owned = true), similar("new")),
			similarSeed = "Seed",
			charts = listOf(deezer("c-owned", owned = true), deezer("c-new")),
			editorial = listOf(deezer("e-owned", owned = true))
		)
		assertEquals(listOf("fresh:rg-ok", "similar:mb-new", "chart:c-new"), discoverStack(ui).map { it.key })
	}

	@Test
	fun anEmptySourceLeavesNoHole() {
		val ui = full.copy(fresh = emptyList(), charts = emptyList())
		assertEquals(
			listOf("similar:mb-s1", "picked:e1", "similar:mb-s2", "picked:e2", "similar:mb-s3"),
			discoverStack(ui).map { it.key }
		)
		assertEquals(emptyList(), discoverStack(DiscoverUi()))
	}

	@Test
	fun noSeedNoSimilarRows() {
		val stack = discoverStack(full.copy(similarSeed = ""))
		assertTrue(stack.none { it.reason is DiscoverReason.Similar })
		assertEquals(3 + 2 + 2, stack.size)
	}

	@Test
	fun theLimitHolds() {
		assertEquals(4, discoverStack(full, limit = 4).size)
	}
}
