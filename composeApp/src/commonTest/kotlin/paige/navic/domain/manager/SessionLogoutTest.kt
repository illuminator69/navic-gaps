package paige.navic.domain.manager

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/*
 * B-279, then the owner's ruling of 2026-11-02 (Worbus P2, design §6.6 #1), which replaces that of
 * 2026-10-30: a Navidrome logout signs the device out of the Worbus Server too. That half is HubAuth's
 * (`HubLogoutTest`, through LoginManager.logout → HubManager.leaveForLogout); SessionManager removes the
 * Navidrome password, and the AudioMuse token (a direct AudioMuse's, not the Worbus Server's) stays.
 * And the login screen says "couldn't be read" only of a password that was stored and could not be
 * read this launch.
 */
class SessionLogoutTest {
	private val settings = MemorySettings()

	private fun session(secrets: SecretStore = testSecrets(settings)) =
		SessionManager(settings, PreferenceManager(settings, secrets), secrets)

	@Test
	fun logoutRemovesThePasswordAndKeepsTheAudioMuseToken() {
		val secrets = testSecrets(settings)
		secrets.navidromePassword = "hunter2"
		secrets.audioMuseToken = "am-tok"
		settings.putString("instanceUrl", "http://nd")

		val session = session(secrets)
		session.logout()

		assertEquals("", secrets.navidromePassword)
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
