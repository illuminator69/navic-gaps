package paige.navic.domain.repositories

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/*
 * B-037 residual: the album listing is paged by offset over a list Navidrome may be editing while
 * it is walked. A removal from the part already read shifts the next album back across the page
 * boundary, the walk never reads it, and the full sync used to prune it from Room.
 */
class AlbumListingTest {
	/** The fakes never suspend, so the coroutine runs to completion inside startCoroutine. */
	private fun <T> runSuspend(block: suspend () -> T): T {
		var out: Result<T>? = null
		block.startCoroutine(Continuation(EmptyCoroutineContext) { out = it })
		return out!!.getOrThrow()
	}

	private fun library(n: Int) = MutableList(n) { "a" + it.toString().padStart(4, '0') }

	/** An offset pager over [albums] that runs [between] after each call, as a scan would. */
	private class FakePager(
		val albums: MutableList<String>,
		val between: (call: Int, albums: MutableList<String>) -> Unit = { _, _ -> }
	) {
		var calls = 0
		suspend fun page(size: Int, offset: Int): List<String> {
			val out = albums.drop(offset).take(size)
			between(calls++, albums)
			return out
		}
	}

	private val still: suspend () -> ScanMark? = { ScanMark(scanning = false, count = 1000) }

	private fun list(pager: FakePager, scan: suspend () -> ScanMark? = still) = runSuspend {
		listAllAlbums(page = pager::page, id = { it }, scanMark = scan)
	}

	@Test
	fun aRemovalAlreadyReadBetweenPagesDoesNotSkipTheAlbumThatShiftsBack() {
		// After page one (a0000..a0499), a0100 leaves the library. Everything after it moves back
		// one place, so a0500 now sits at index 499: a walk resuming at offset 500 never sees it.
		val pager = FakePager(library(1000)) { call, albums -> if (call == 0) albums.remove("a0100") }
		val listing = list(pager)
		assertTrue("a0500" in listing.albums, "a0500 was skipped")
		// a0100 was read before it left, so it is listed too: nothing here is pruned by omission.
		assertEquals(library(1000), listing.albums)
	}

	@Test
	fun upToTheOverlapRemovalsBetweenPagesAreAbsorbed() {
		val pager = FakePager(library(1200)) { call, albums ->
			if (call == 0) repeat(ALBUM_PAGE_OVERLAP) { albums.removeAt(10) }
		}
		val listing = list(pager)
		assertEquals(library(1200), listing.albums)
	}

	@Test
	fun aRemovalStillAheadOfTheWalkIsSimplyNotListed() {
		val pager = FakePager(library(1000)) { call, albums -> if (call == 0) albums.remove("a0700") }
		val listing = list(pager)
		assertFalse("a0700" in listing.albums)
		assertEquals(999, listing.albums.size)
	}

	@Test
	fun anAlbumMovedBehindTheWalkDuringAScanIsMissingButTheListingIsUnstable() {
		// A rename moves a0900 to the front while a scan runs: no overlap can recover it, and
		// the scan bracket is what stops the sync pruning it.
		val pager = FakePager(library(1000)) { call, albums ->
			if (call == 0) albums.add(0, albums.removeAt(900))
		}
		var reads = 0
		val listing = list(pager) { ScanMark(scanning = reads++ == 1, count = 1000) }
		// The residual hole, pinned: the moved album really is missing from this walk.
		assertFalse("a0900" in listing.albums)
		assertFalse(listing.stable)
	}

	@Test
	fun overlappingPagesAreListedOnceInOrder() {
		val pager = FakePager(library(1234))
		val listing = list(pager)
		assertEquals(library(1234), listing.albums)
		assertTrue(listing.stable)
	}

	@Test
	fun anExactMultipleOfThePageEndsOnTheOverlapPage() {
		val pager = FakePager(library(500))
		val listing = list(pager)
		assertEquals(library(500), listing.albums)
		assertEquals(2, pager.calls)
	}

	@Test
	fun anEmptyLibraryIsOneCall() {
		val pager = FakePager(mutableListOf())
		val listing = list(pager)
		assertTrue(listing.albums.isEmpty())
		assertEquals(1, pager.calls)
		assertTrue(listing.stable)
	}

	@Test
	fun aServerThatIgnoresTheOffsetEndsTheWalkUnstable() {
		val albums = library(500)
		var calls = 0
		val listing = runSuspend {
			listAllAlbums(page = { _, _ -> calls++; albums }, id = { it }, scanMark = still)
		}
		assertEquals(albums, listing.albums)
		assertEquals(2, calls)
		assertFalse(listing.stable)
	}

	@Test
	fun theListingIsStableOnlyWhenNoScanRanAndTheCountHeld() {
		val idle = ScanMark(scanning = false, count = 10)
		assertTrue(listingWasStill(idle, idle))
		assertFalse(listingWasStill(idle.copy(scanning = true), idle))
		assertFalse(listingWasStill(idle, idle.copy(scanning = true)))
		assertFalse(listingWasStill(idle, idle.copy(count = 11)))
		assertFalse(listingWasStill(null, idle))
		assertFalse(listingWasStill(idle, null))
	}

	@Test
	fun anUnreadableScanStatusMakesTheListingUnstable() {
		val listing = list(FakePager(library(10))) { null }
		assertEquals(10, listing.albums.size)
		assertFalse(listing.stable)
	}
}
