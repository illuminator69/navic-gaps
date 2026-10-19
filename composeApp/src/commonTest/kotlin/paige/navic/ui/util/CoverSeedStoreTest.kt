package paige.navic.ui.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The persisted store of cover seed colours ([CoverSeedStore], [CoverSeedCodec]). */
class CoverSeedStoreTest {

	/** A scope whose job is already over: the delayed write never runs, so only [CoverSeedStore.flush] saves. */
	private val idle = CoroutineScope(Job().apply { cancel() })

	private fun store(cap: Int = 512, load: () -> String = { "" }, save: (String) -> Unit = {}) =
		CoverSeedStore(load = load, save = save, scope = idle, cap = cap)

	private val navy = CoverSeeds(dominant = 0xFF0A1430.toInt(), vivid = 0xFFE8D020.toInt(), accent = 0xFFD02020.toInt())
	private val grey = CoverSeeds(dominant = 0xFF808080.toInt(), vivid = null)

	@Test
	fun anEntryRoundTripsThroughTheCodec() {
		val entries = listOf("al-1_abc" to navy, "mf-2" to grey)
		assertEquals(entries, CoverSeedCodec.decode(CoverSeedCodec.encode(entries)))
	}

	@Test
	fun garbageNeverThrowsAndReadsAsEmpty() {
		for (raw in listOf("", "{", "null", "{\"v\":1}", "not json")) {
			assertEquals(emptyList(), CoverSeedCodec.decode(raw), "for '$raw'")
		}
	}

	@Test
	fun aBlobFromAnotherVersionIsDiscarded() {
		val raw = CoverSeedCodec.encode(listOf("al-1" to navy))
		val other = raw.replace("\"v\":${CoverSeedCodec.VERSION}", "\"v\":${CoverSeedCodec.VERSION + 1}")
		assertTrue(other != raw)
		assertEquals(emptyList(), CoverSeedCodec.decode(other))
	}

	@Test
	fun theOldestEntryLeavesAtTheCap() {
		val s = store(cap = 3)
		s.put("a", navy); s.put("b", navy); s.put("c", navy); s.put("d", navy)
		assertFalse(s.contains("a"))
		assertTrue(s.contains("b") && s.contains("c") && s.contains("d"))
	}

	@Test
	fun aReadRefreshesRecency() {
		val s = store(cap = 3)
		s.put("a", navy); s.put("b", grey); s.put("c", navy)
		assertEquals(navy, s["a"])
		s.put("d", grey)
		assertTrue(s.contains("a"))
		assertFalse(s.contains("b"))
		assertNull(s["b"])
	}

	@Test
	fun flushWritesTheCurrentContents() {
		var saved = ""
		val s = store(save = { saved = it })
		s.put("al-1", navy); s.put("mf-2", grey)
		s.flush()
		assertEquals(listOf("al-1" to navy, "mf-2" to grey), CoverSeedCodec.decode(saved))
		val again = store(load = { saved })
		assertEquals(navy, again["al-1"])
		assertEquals(grey, again["mf-2"])
	}
}
