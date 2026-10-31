package paige.navic.data.database

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteStatement
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/*
 * B-167 after the N9 walk: the cache.db check runs on the thread of Room's first open, once, and
 * no connection is opened before it has finished, however many open at once.
 */
class CheckedFirstOpenDriverTest {
	private class Recording : SQLiteDriver {
		val opens = CopyOnWriteArrayList<String>()
		var checkDoneAtOpen = CopyOnWriteArrayList<Boolean>()
		@Volatile var checkDone = false
		override fun open(fileName: String): SQLiteConnection {
			checkDoneAtOpen += checkDone
			opens += fileName
			return object : SQLiteConnection {
				override fun prepare(sql: String): SQLiteStatement = error("not used")
				override fun close() {}
			}
		}
	}

	@Test
	fun theCheckRunsOnceOnTheFirstOpensThreadBeforeAnyConnection() {
		val delegate = Recording()
		val checks = AtomicInteger()
		val checkThreads = CopyOnWriteArrayList<String>()
		val driver = CheckedFirstOpenDriver(delegate) {
			checks.incrementAndGet()
			checkThreads += Thread.currentThread().name
			Thread.sleep(150)
			delegate.checkDone = true
		}
		assertEquals(0, checks.get(), "building the driver checks nothing (it used to run at Koin build, on main)")

		val start = CountDownLatch(1)
		val workers = (1..8).map { i ->
			thread(name = "room-open-$i") {
				start.await()
				driver.open("/data/cache.db")
			}
		}
		start.countDown()
		workers.forEach { it.join(5_000) }

		assertEquals(1, checks.get())
		assertTrue(checkThreads.single().startsWith("room-open-"), checkThreads.toString())
		assertEquals(8, delegate.opens.size)
		assertTrue(delegate.checkDoneAtOpen.all { it }, "a connection opened before the check ended: ${delegate.checkDoneAtOpen}")

		driver.open("/data/cache.db")
		assertEquals(1, checks.get())
	}

	@Test
	fun aCheckThatThrowsStillLetsRoomOpen() {
		val delegate = Recording()
		var checks = 0
		val driver = CheckedFirstOpenDriver(delegate) {
			checks++
			throw IllegalStateException("boom")
		}
		runCatching { driver.open("a") }
		driver.open("a")
		assertEquals(1, checks)
		assertEquals(1, delegate.opens.size)
	}
}
