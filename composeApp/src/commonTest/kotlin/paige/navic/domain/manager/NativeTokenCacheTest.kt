package paige.navic.domain.manager

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/*
 * B-279 review: Navidrome's native token is the user's who logged in. It used to be kept for the
 * process, so after a logout, or a login as someone else, the old user's token was still sent.
 */
class NativeTokenCacheTest {
	private val alice = NativeCredentials("http://nd", "alice", "pw1").identity()

	@Test
	fun theTokenIsKeptForTheCredentialsItWasIssuedTo() {
		val cache = NativeTokenCache()
		cache.store(alice, "jwt-a")
		assertEquals("jwt-a", cache.current(alice))
		assertEquals("jwt-a", cache.current(NativeCredentials("http://nd", "alice", "pw1").identity()))
	}

	@Test
	fun anyChangeOfServerUserOrPasswordForgetsIt() {
		for (other in listOf(
			NativeCredentials("http://other", "alice", "pw1"),
			NativeCredentials("http://nd", "bob", "pw1"),
			NativeCredentials("http://nd", "alice", "pw2"),
			NativeCredentials("http://nd", "", ""), // logged out
		)) {
			val cache = NativeTokenCache()
			cache.store(alice, "jwt-a")
			assertNull(cache.current(other.identity()), "${other.base} ${other.username}")
			assertNull(cache.current(alice), "forgotten, not set aside")
		}
	}

	@Test
	fun clearForgetsIt() {
		val cache = NativeTokenCache()
		cache.store(alice, "jwt-a")
		cache.clear()
		assertNull(cache.current(alice))
	}

	@Test
	fun theIdentityHoldsNoCopyOfThePassword() {
		val id = NativeCredentials("http://nd", "alice", "hunter2").identity()
		assertFalse("hunter2" in id)
		assertEquals(64, id.length)
	}
}
