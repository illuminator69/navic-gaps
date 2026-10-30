package paige.navic.util

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Finding 5: the album sheet's selection read — the newest wins, a cancel applies nothing, a throw opens nothing. */
class LatestLoadTest {
	@Test
	fun aNewerLoadWinsOverASlowerOlderOne() = runBlocking {
		val load = LatestLoad(this)
		val applied = mutableListOf<String>()
		val old = CompletableDeferred<String>()
		val new = CompletableDeferred<String>()
		val first = load.launch({ old.await() }) { applied += it }
		val second = load.launch({ new.await() }) { applied += it }
		new.complete("new")
		second.join()
		old.complete("old")
		first.join()
		assertEquals(listOf("new"), applied)
	}

	@Test
	fun aCancelledLoadAppliesNothing() = runBlocking {
		val load = LatestLoad(this)
		val applied = mutableListOf<String>()
		val answer = CompletableDeferred<String>()
		val job = load.launch({ answer.await() }) { applied += it }
		load.cancel()
		answer.complete("late")
		job.join()
		assertTrue(applied.isEmpty())
	}

	@Test
	fun aLoadThatThrowsAppliesNothingAndDoesNotFailTheScope() = runBlocking {
		val load = LatestLoad(this)
		val errors = mutableListOf<Throwable>()
		var applied = false
		load.launch<String>({ error("room is gone") }, onError = { errors += it }) { applied = true }.join()
		assertEquals(1, errors.size)
		assertTrue(!applied)
	}

	@Test
	fun theAlbumListUsesIt() {
		val vm = java.io.File("src/commonMain/kotlin/paige/navic/ui/screens/album/viewmodels/AlbumListViewModel.kt").readText()
		val clear = vm.substringAfter("fun clearSelection() {").substringBefore("}")
		assertTrue("selecting.cancel()" in clear, clear)
		assertTrue("selecting.launch(" in vm.substringAfter("fun selectAlbum("))
	}
}
