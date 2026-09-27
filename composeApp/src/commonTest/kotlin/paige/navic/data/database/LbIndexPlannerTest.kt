package paige.navic.data.database

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import paige.navic.domain.manager.LbIndexChanges
import paige.navic.domain.manager.LbIndexItem
import paige.navic.domain.manager.LbIndexKey
import paige.navic.domain.manager.LbRelease

/*
 * The mirror rules in LbIndexPlanner.kt, executed over hand-built pages (BACKLOG Q-003: they had
 * never run). The planner is epoch-agnostic — a `resync` answer wipes the mirror in the DAO and
 * the next page is planned against an empty map at cursor 0 — so "epoch change" here is that
 * state meeting a new epoch's (lower) seqs.
 */
class LbIndexPlannerTest {
	private fun artist(key: String, seq: Long, vararg rgids: String) = LbIndexItem(
		type = "artist",
		key = key,
		name = "Artist $key",
		seq = seq,
		rows = rgids.map { LbRelease(rgid = it, title = "Album $it") }
	)

	private fun tombstone(key: String, seq: Long) = LbIndexItem(type = "tombstone", key = key, seq = seq)

	private fun page(nextSince: Long, vararg items: LbIndexItem) =
		LbIndexChanges(epoch = "1-e", items = items.toList(), nextSince = nextSince)

	private fun LbIndexPagePlan.upsertKeys() = upserts.map { it.artist.artistKey }

	// ----- planIndexPage ------------------------------------------------------------------ //

	@Test
	fun freshEpochAppliesEveryArtistAndIgnoresTombstonesItNeverHeld() {
		val plan = planIndexPage(
			localSeqs = emptyMap(),
			page = page(3, artist("a", 1, "r1", "r2"), artist("b", 2), tombstone("c", 3)),
			cursor = 0
		)
		assertEquals(listOf("a", "b"), plan.upsertKeys())
		assertEquals(emptyList(), plan.deletes)
		assertEquals(3, plan.newCursor)
		assertEquals(1, plan.skipped)
		val a = plan.upserts.first()
		assertEquals(1, a.artist.seq)
		assertEquals(listOf("r1", "r2"), a.releases.map { it.rgid })
		assertEquals(listOf(0, 1), a.releases.map { it.position })
	}

	@Test
	fun continuationAppliesOnlyNewerSeqsAndDeletesHeldTombstones() {
		val local = mapOf("a" to 1L, "b" to 2L, "c" to 3L)
		val plan = planIndexPage(
			localSeqs = local,
			page = page(6, artist("b", 4, "r9"), tombstone("c", 5), artist("d", 6)),
			cursor = 3
		)
		assertEquals(listOf("b", "d"), plan.upsertKeys())
		assertEquals(listOf("c"), plan.deletes)
		assertEquals(6, plan.newCursor)
		assertEquals(0, plan.skipped)
	}

	@Test
	fun aReplayedPageChangesNothing() {
		// The local state after the page above was applied.
		val local = mapOf("a" to 1L, "b" to 4L, "d" to 6L)
		val plan = planIndexPage(
			localSeqs = local,
			page = page(6, artist("b", 4, "r9"), tombstone("c", 5), artist("d", 6)),
			cursor = 6
		)
		assertEquals(emptyList(), plan.upsertKeys())
		assertEquals(emptyList(), plan.deletes)
		assertEquals(6, plan.newCursor)
		assertEquals(3, plan.skipped)
	}

	@Test
	fun olderOrEqualSeqsAreSkippedAndTheCursorNeverGoesBack() {
		val plan = planIndexPage(
			localSeqs = mapOf("a" to 7L, "b" to 5L),
			page = page(4, artist("a", 7), tombstone("b", 5)),
			cursor = 9
		)
		assertEquals(emptyList(), plan.upsertKeys())
		assertEquals(emptyList(), plan.deletes)
		assertEquals(2, plan.skipped)
		assertEquals(9, plan.newCursor)
	}

	@Test
	fun epochChangeAfterTheWipeAppliesTheNewEpochsLowerSeqs() {
		// The old epoch had "a" at seq 900; after resetForEpoch the map is empty at cursor 0,
		// and the new epoch numbers from 1 again.
		val plan = planIndexPage(
			localSeqs = emptyMap(),
			page = LbIndexChanges(
				epoch = "1-new",
				items = listOf(artist("a", 1, "r1"), tombstone("gone", 2)),
				nextSince = 2,
				more = true
			),
			cursor = 0
		)
		assertEquals(listOf("a"), plan.upsertKeys())
		assertEquals(emptyList(), plan.deletes)
		assertEquals(2, plan.newCursor)
	}

	@Test
	fun anEmptyPageWritesNothingAndOnlyMovesTheCursorForward() {
		val still = planIndexPage(mapOf("a" to 3L), page(0), cursor = 5)
		assertEquals(emptyList(), still.upserts)
		assertEquals(emptyList(), still.deletes)
		assertEquals(5, still.newCursor)
		assertEquals(0, still.skipped)

		val moved = planIndexPage(mapOf("a" to 3L), page(8), cursor = 5)
		assertEquals(8, moved.newCursor)
	}

	@Test
	fun unknownTypesAndBlankKeysAreSkippedNotFatal() {
		val plan = planIndexPage(
			localSeqs = emptyMap(),
			page = page(3, LbIndexItem(type = "label", key = "x", seq = 1), artist("", 2), artist("a", 3)),
			cursor = 0
		)
		assertEquals(listOf("a"), plan.upsertKeys())
		assertEquals(2, plan.skipped)
	}

	@Test
	fun twoItemsForOneKeyResolveToTheLastInSeqOrder() {
		// Held, then artist@5 and tombstone@7 (sent out of order): the tombstone wins.
		val deleted = planIndexPage(
			localSeqs = mapOf("a" to 3L),
			page = page(7, tombstone("a", 7), artist("a", 5)),
			cursor = 3
		)
		assertEquals(emptyList(), deleted.upsertKeys())
		assertEquals(listOf("a"), deleted.deletes)

		// Held, then tombstone@5 and artist@7: the re-created artist wins, and nothing is deleted
		// separately — the upsert replaces the rows.
		val recreated = planIndexPage(
			localSeqs = mapOf("a" to 3L),
			page = page(7, tombstone("a", 5), artist("a", 7, "r1")),
			cursor = 3
		)
		assertEquals(listOf("a"), recreated.upsertKeys())
		assertEquals(7, recreated.upserts.single().artist.seq)
		assertEquals(emptyList(), recreated.deletes)

		// Never held: artist@5 then tombstone@7 leaves nothing to write.
		val neither = planIndexPage(
			localSeqs = emptyMap(),
			page = page(7, artist("a", 5), tombstone("a", 7)),
			cursor = 0
		)
		assertEquals(emptyList(), neither.upsertKeys())
		assertEquals(emptyList(), neither.deletes)
	}

	@Test
	fun forcedKeysApplyOnAnyDifferentSeqEvenALowerOne() {
		// The drift repair's premise: "b"'s local seq is wrong in the HIGH direction.
		val plan = planIndexPage(
			localSeqs = mapOf("b" to 9L, "c" to 4L, "e" to 7L),
			page = page(6, artist("b", 4), artist("c", 4), artist("e", 5), tombstone("f", 6)),
			cursor = 3,
			force = setOf("b", "c", "f")
		)
		// b: 4 != 9 → applied. c: 4 == 4 → not. e: unforced and 5 < 7 → not.
		// f: a forced tombstone for a key never held is still a no-op write.
		assertEquals(listOf("b"), plan.upsertKeys())
		assertEquals(4, plan.upserts.single().artist.seq)
		assertEquals(emptyList(), plan.deletes)
		assertEquals(2, plan.skipped)
	}

	@Test
	fun releaseRowsDropBlankAndDuplicateRgidsAndKeepTheirOrder() {
		val plan = planIndexPage(
			localSeqs = emptyMap(),
			page = page(1, artist("a", 1, "r2", "", "r1", "r2")),
			cursor = 0
		)
		val releases = plan.upserts.single().releases
		assertEquals(listOf("r2", "r1"), releases.map { it.rgid })
		assertEquals(listOf(0, 1), releases.map { it.position })
		assertTrue(releases.all { it.artistKey == "a" })
	}

	@Test
	fun aMirroredRowReadsBackAsTheWireRow() {
		val wire = LbRelease(
			rgid = "r1",
			title = "T",
			year = "2001",
			secondaryTypes = listOf("Live", "Compilation"),
			status = "incomplete",
			groupId = "g1",
			present = 9,
			total = 12,
			navidromeAlbumIds = listOf("nd1")
		)
		val item = LbIndexItem(type = "artist", key = "a", seq = 1, rows = listOf(wire))
		val stored = planIndexPage(emptyMap(), page(1, item), 0).upserts.single().releases.single()
		assertEquals(wire, stored.toWire())
	}

	// ----- planIndexDrift ----------------------------------------------------------------- //

	@Test
	fun driftDeletesWhatTheServerDroppedAndRepullsFromBelowTheOldestWrongKey() {
		val plan = planIndexDrift(
			localSeqs = mapOf("a" to 1L, "b" to 9L, "x" to 3L),
			server = listOf(LbIndexKey("a", 1), LbIndexKey("b", 4), LbIndexKey("c", 6))
		)
		assertEquals(listOf("x"), plan.deletes)
		assertEquals(setOf("b", "c"), plan.refetch)
		assertEquals(3L, plan.refetchFrom)
	}

	@Test
	fun noDriftMeansNoRepull() {
		val plan = planIndexDrift(
			localSeqs = mapOf("a" to 1L, "b" to 2L),
			server = listOf(LbIndexKey("b", 2), LbIndexKey("a", 1))
		)
		assertEquals(emptyList(), plan.deletes)
		assertEquals(emptySet(), plan.refetch)
		assertNull(plan.refetchFrom)
	}

	@Test
	fun driftIgnoresBlankServerKeysAndClampsTheRepullAtZero() {
		val plan = planIndexDrift(
			localSeqs = emptyMap(),
			server = listOf(LbIndexKey("", 5), LbIndexKey("a", 0))
		)
		assertEquals(setOf("a"), plan.refetch)
		assertEquals(0L, plan.refetchFrom)
	}

	@Test
	fun driftThenForcedRepullConverges() {
		// End to end over the two planners, as LbIndexSync chains them.
		val local = mutableMapOf("a" to 1L, "b" to 9L, "x" to 3L)
		val drift = planIndexDrift(
			local,
			listOf(LbIndexKey("a", 1), LbIndexKey("b", 4), LbIndexKey("c", 6))
		)
		drift.deletes.forEach { local.remove(it) }
		val repull = planIndexPage(
			localSeqs = local,
			page = page(6, artist("b", 4), artist("c", 6)),
			cursor = drift.refetchFrom!!,
			force = drift.refetch
		)
		repull.deletes.forEach { local.remove(it) }
		repull.upserts.forEach { local[it.artist.artistKey] = it.artist.seq }
		assertEquals(mapOf("a" to 1L, "b" to 4L, "c" to 6L), local)
		assertEquals(6, repull.newCursor)
	}

	// ----- lbIndexIsStale ----------------------------------------------------------------- //

	@Test
	fun staleByAgeOrByScanVersion() {
		val day = 86_400.0
		assertFalse(lbIndexIsStale(0.0, 3, 3, ttlDays = 30.0, nowSeconds = 29 * day))
		assertTrue(lbIndexIsStale(0.0, 3, 3, ttlDays = 30.0, nowSeconds = 31 * day))
		assertTrue(lbIndexIsStale(0.0, 2, 3, ttlDays = 30.0, nowSeconds = 1.0))
	}
}
