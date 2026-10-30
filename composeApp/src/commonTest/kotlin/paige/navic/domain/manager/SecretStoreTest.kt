package paige.navic.domain.manager

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/*
 * B-279: the password and the two tokens, sealed in their own file. A fake cipher stands in for
 * the Keystore (a byte-wise XOR with a key, a 2-byte "IV", and a 1-byte check that fails the way
 * GCM's tag does when the key is not the one that sealed).
 */
class SecretStoreTest {
	private class FakeCipher(var key: Byte = 0x5A, var failSeal: Boolean = false) : SecretCipher {
		var seals = 0
		override val encrypts = true

		override fun seal(plain: ByteArray): ByteArray {
			if (failSeal) error("keystore unavailable")
			seals++
			val body = plain.map { (it.toInt() xor key.toInt()).toByte() }.toByteArray()
			return byteArrayOf(1, 2, key) + body
		}

		override fun open(sealed: ByteArray): ByteArray {
			require(sealed.size >= 3 && sealed[2] == key) { "tag mismatch" }
			return sealed.copyOfRange(3, sealed.size).map { (it.toInt() xor key.toInt()).toByte() }.toByteArray()
		}
	}

	private val legacy = MemorySettings()
	private val vaultFile = MemorySettings()
	private val cipher = FakeCipher()
	private val errors = mutableListOf<String>()

	private fun store() = SecretStore(legacy, SecretVault(vaultFile, cipher), onError = { msg, _ -> errors += msg })

	@Test
	fun plaintextUnderTheOldKeysIsSealedThenRemoved() {
		legacy.putString("password", "hunter2")
		legacy.putString("hubToken", "tok")
		legacy.putString("audioMuseToken", "am-tok")
		legacy.putString("username", "sandbox")

		val s = store()
		assertEquals("hunter2", s.navidromePassword)
		assertEquals("tok", s.hubToken)
		assertEquals("am-tok", s.audioMuseToken)

		for (key in listOf("password", "hubToken", "audioMuseToken")) {
			assertNull(legacy.getStringOrNull(key), "$key left the settings file")
			val stored = vaultFile.getStringOrNull(key)!!
			assertTrue(stored.startsWith(SecretStore.PREFIX), "$key is sealed")
		}
		assertFalse(vaultFile.getString("password", "").contains("hunter2"))
		assertEquals("sandbox", legacy.getString("username", ""), "nothing else moves")
		assertEquals(setOf("password", "hubToken", "audioMuseToken"), vaultFile.keys)
	}

	@Test
	fun theSettingsFileIsEmptiedAtLaunchNotAtEachSecretsFirstRead() {
		// A token nothing reads would otherwise stay in the file a device transfer carries.
		legacy.putString("audioMuseToken", "am-tok")
		legacy.putString("password", "hunter2")
		store()
		assertNull(legacy.getStringOrNull("audioMuseToken"))
		assertNull(legacy.getStringOrNull("password"))
		assertTrue(vaultFile.getString("audioMuseToken", "").startsWith(SecretStore.PREFIX))
		assertEquals(2, cipher.seals)
		// Nothing to move: a later launch seals nothing and opens nothing until asked.
		store()
		assertEquals(2, cipher.seals)
	}

	@Test
	fun aLaterLaunchReadsTheSealedValue() {
		legacy.putString("password", "hunter2")
		store().navidromePassword
		val again = store()
		assertEquals("hunter2", again.navidromePassword)
		assertEquals(1, cipher.seals, "sealed once, not on every launch")
	}

	@Test
	fun aMigrationThatStoppedBetweenItsStepsLosesNothingAndLeavesNoCopy() {
		// Sealed into the secrets file, then the process died before the old key was removed.
		legacy.putString("hubToken", "tok")
		store().hubToken
		legacy.putString("hubToken", "tok")

		assertEquals("tok", store().hubToken)
		assertNull(legacy.getStringOrNull("hubToken"))
	}

	@Test
	fun roundTrip() {
		val s = store()
		s.navidromePassword = "p@ss wörd ✓"
		s.hubToken = "t"
		assertEquals("p@ss wörd ✓", store().navidromePassword)
		assertEquals("t", store().hubToken)
		assertEquals("", store().audioMuseToken)
		assertNotEquals("p@ss wörd ✓", vaultFile.getString("password", ""))
	}

	@Test
	fun aPlaintextValueInTheSecretsFileIsReadAndSealedAgain() {
		vaultFile.putString("hubToken", "plain")
		assertEquals("plain", store().hubToken)
		assertTrue(vaultFile.getString("hubToken", "").startsWith(SecretStore.PREFIX))
		assertEquals("plain", store().hubToken)
	}

	@Test
	fun aValueTheKeyCannotOpenIsMissingAndCleared() {
		store().navidromePassword = "hunter2"
		cipher.key = 0x11 // the Keystore key did not come with the restore

		val s = store()
		assertEquals("", s.navidromePassword)
		assertNull(vaultFile.getStringOrNull("password"), "the bad value is removed")
		assertEquals(setOf(Secret.NavidromePassword), s.unreadable.value)
		assertTrue(errors.single().startsWith("NavidromePassword"))
		assertFalse(errors.single().contains("hunter2"), "no secret in a log line")
		// Typed again, it is sealed under the new key and no longer unreadable.
		s.navidromePassword = "hunter3"
		assertEquals(emptySet(), s.unreadable.value)
		assertEquals("hunter3", store().navidromePassword)
	}

	@Test
	fun corruptValuesAreMissingNeverTheirStoredForm() {
		val corrupt = listOf(
			SecretStore.PREFIX,
			SecretStore.PREFIX + "not base64!!",
			SecretStore.PREFIX + "AA==",
			SecretStore.PREFIX + "AQJa/w==", // opens to an invalid UTF-8 byte
		)
		for (value in corrupt) {
			vaultFile.putString("hubToken", value)
			val read = store().hubToken
			assertEquals("", read, value)
			assertNull(vaultFile.getStringOrNull("hubToken"), value)
		}
	}

	@Test
	fun aStoredFormUnderTheOldKeyIsNeverHandedOut() {
		legacy.putString("audioMuseToken", SecretStore.PREFIX + "AQJaPz8=")
		assertEquals("", store().audioMuseToken)
		assertNull(legacy.getStringOrNull("audioMuseToken"))
	}

	@Test
	fun noReadEverReturnsTheStoredForm() {
		legacy.putString("password", "hunter2")
		vaultFile.putString("hubToken", SecretStore.PREFIX + "garbage")
		vaultFile.putString("audioMuseToken", "plain")
		val s = store()
		for (secret in Secret.entries) assertFalse(s.get(secret).startsWith("enc:"), "$secret")
	}

	@Test
	fun aCorruptSealedCopyFallsBackToAPlaintextOneLeftByAnInterruptedMigration() {
		vaultFile.putString("password", SecretStore.PREFIX + "garbage")
		legacy.putString("password", "hunter2")
		val s = store()
		assertEquals("hunter2", s.navidromePassword)
		assertEquals(emptySet(), s.unreadable.value, "it was read after all")
		assertNull(legacy.getStringOrNull("password"))
		assertEquals("hunter2", store().navidromePassword)
	}

	@Test
	fun aSealThatFailsStoresNothingInPlaintextAndKeepsTheOldValueWhereItWas() {
		legacy.putString("password", "hunter2")
		cipher.failSeal = true
		val s = store()
		assertEquals("hunter2", s.navidromePassword, "the session still works")
		assertEquals("hunter2", legacy.getString("password", ""), "not removed: nothing sealed it")
		assertNull(vaultFile.getStringOrNull("password"))

		s.hubToken = "tok"
		assertEquals("tok", s.hubToken, "kept for this process")
		assertNull(vaultFile.getStringOrNull("hubToken"), "never written in plaintext")
		assertEquals("", store().hubToken)
	}

	@Test
	fun logoutClearsAllThree() {
		legacy.putString("password", "hunter2")
		val s = store()
		s.hubToken = "tok"
		s.audioMuseToken = "am"
		s.navidromePassword

		s.clearAll()
		for (secret in Secret.entries) assertEquals("", s.get(secret), "$secret")
		assertTrue(vaultFile.keys.isEmpty())
		for (key in listOf("password", "hubToken", "audioMuseToken")) assertNull(legacy.getStringOrNull(key))
		val again = store()
		for (secret in Secret.entries) assertEquals("", again.get(secret), "$secret after a restart")
	}

	@Test
	fun preferenceManagerTokensPassThrough() {
		legacy.putString("hubToken", "tok")
		val secrets = store()
		val prefs = PreferenceManager(legacy, secrets)
		assertEquals("tok", prefs.hubToken)
		prefs.audioMuseToken = "am"
		assertEquals("am", secrets.audioMuseToken)
		assertNull(legacy.getStringOrNull("hubToken"))
		assertNull(legacy.getStringOrNull("audioMuseToken"), "never a preference again")
	}

	@Test
	fun aPlainCipherStoresAsIsAndCannotOpenASealedValue() {
		val plainVault = MemorySettings()
		val s = SecretStore(legacy, SecretVault(plainVault, PlainSecretCipher()), onError = { _, _ -> })
		s.hubToken = "tok"
		assertEquals("tok", plainVault.getString("hubToken", ""))
		plainVault.putString("password", SecretStore.PREFIX + "AQJaPz8=")
		assertEquals("", s.navidromePassword)
	}
}
