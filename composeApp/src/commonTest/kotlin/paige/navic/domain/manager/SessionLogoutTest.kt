package paige.navic.domain.manager

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/*
 * B-279 review, the owner's ruling (2026-10-30): a Navidrome logout removes the Navidrome password
 * and nothing else. The hub and AudioMuse tokens belong to the household's hub, not to the
 * Navidrome user, and stay as they behaved before B-279. And the login screen says "couldn't be
 * read" only of a password that was stored and could not be read this launch.
 */
class SessionLogoutTest {
	private val settings = MemorySettings()

	private fun session(secrets: SecretStore = testSecrets(settings)) =
		SessionManager(settings, PreferenceManager(settings, secrets), secrets)

	@Test
	fun logoutRemovesThePasswordAndKeepsBothTokens() {
		val secrets = testSecrets(settings)
		secrets.navidromePassword = "hunter2"
		secrets.hubToken = "hub-tok"
		secrets.audioMuseToken = "am-tok"
		settings.putString("instanceUrl", "http://nd")

		val session = session(secrets)
		session.logout()

		assertEquals("", secrets.navidromePassword)
		assertEquals("hub-tok", secrets.hubToken)
		assertEquals("am-tok", secrets.audioMuseToken)
		assertNull(settings.getStringOrNull("username"))
		assertFalse(session.isLoggedIn.value)
		assertNull(session.signInAgainAs(), "a logout prefills nothing")
		assertFalse(session.passwordWasUnreadable())
	}

	@Test
	fun aUsernameWithNoStoredPasswordIsPrefilledWithoutTheLine() {
		// A device transfer: the settings file came, the secrets file never does.
		settings.putString("instanceUrl", "http://nd")
		settings.putString("username", "sandbox")
		val session = session()
		assertFalse(session.isLoggedIn.value)
		assertEquals("http://nd" to "sandbox", session.signInAgainAs())
		assertFalse(session.passwordWasUnreadable())
	}

	@Test
	fun aStoredPasswordThatCannotBeReadSaysSo() {
		settings.putString("instanceUrl", "http://nd")
		settings.putString("username", "sandbox")
		val vault = MemorySettings()
		// A sealed value, but a cipher that never opens anything: the key did not come along.
		vault.putString("password", SecretStore.PREFIX + "AQJaPz8=")
		val secrets = SecretStore(settings, SecretVault(vault, PlainSecretCipher()), onError = { _, _ -> })
		val session = session(secrets)
		assertFalse(session.isLoggedIn.value)
		assertEquals("http://nd" to "sandbox", session.signInAgainAs())
		assertTrue(session.passwordWasUnreadable())
	}
}
