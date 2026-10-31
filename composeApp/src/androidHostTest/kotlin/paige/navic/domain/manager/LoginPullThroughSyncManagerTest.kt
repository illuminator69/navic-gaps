package paige.navic.domain.manager

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/*
 * B-100, B-109, B-098, a source guard beside SyncPlanTest's rules: the login's pull is
 * SyncManager's (it stamps the pull and clears the failure flag), and the periodic sync asks for a
 * session before it starts. Both are wiring a host test cannot drive; this fails when it is undone.
 */
class LoginPullThroughSyncManagerTest {
	private val dir = "src/commonMain/kotlin/paige/navic/domain/manager"
	private val login = File("$dir/LoginManager.kt").readText()
	private val sync = File("$dir/SyncManager.kt").readText()

	@Test
	fun theLoginPullGoesThroughSyncManager() {
		assertTrue("syncManager.firstPull" in login)
		assertFalse("repository.syncEverything" in login, "the login must not pull around SyncManager")
	}

	@Test
	fun thePeriodicSyncNeedsASession() {
		val start = sync.substringAfter("fun startPeriodicSync()").substringBefore("syncJob = scope.launch")
		assertTrue("periodicSyncAllowed(sessionManager.isLoggedIn.value)" in start)
		assertTrue("fullPullDue(" in sync)
	}
}
