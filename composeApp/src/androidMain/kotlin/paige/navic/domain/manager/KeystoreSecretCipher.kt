package paige.navic.domain.manager

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.russhwolf.settings.SharedPreferencesSettings
import java.security.KeyStore
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256-GCM under a key that lives in the Android Keystore and cannot be exported (B-279,
 * adapted from upstream 4bd24206). A sealed value is the 12-byte IV, then the ciphertext and
 * its 128-bit tag. The key does not travel with a backup or a device transfer, which is why
 * the secrets file is excluded from both: on another device it could not be opened.
 */
class KeystoreSecretCipher : SecretCipher {
	override val encrypts = true

	private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }

	/** The key, made on the first seal. */
	@Synchronized
	private fun key(): SecretKey {
		(keyStore().getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
		val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
		generator.init(
			KeyGenParameterSpec.Builder(
				KEY_ALIAS,
				KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
			)
				.setKeySize(256)
				.setBlockModes(KeyProperties.BLOCK_MODE_GCM)
				.setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
				.build()
		)
		return generator.generateKey()
	}

	override fun seal(plain: ByteArray): ByteArray {
		val cipher = Cipher.getInstance(TRANSFORMATION)
		cipher.init(Cipher.ENCRYPT_MODE, key())
		val iv = cipher.iv
		check(iv.size == IV_BYTES) { "unexpected IV length ${iv.size}" }
		return iv + cipher.doFinal(plain)
	}

	/**
	 * [SecretUnrecoverableException] only on proof the value can never be opened here: too short
	 * to be one, no key under the alias (it did not come with a restore or a transfer; opening
	 * never makes one), or a tag that does not verify (another key sealed it, or it is corrupt).
	 * Every other failure (`KeyStoreException`, `ProviderException`, a binder error…) is thrown
	 * as it is, and the store keeps the value for a later read.
	 */
	override fun open(sealed: ByteArray): ByteArray {
		if (sealed.size <= IV_BYTES) throw SecretUnrecoverableException("sealed value too short")
		val key = synchronized(this) {
			val keyStore = keyStore()
			if (!keyStore.containsAlias(KEY_ALIAS)) throw SecretUnrecoverableException("no key on this device")
			keyStore.getKey(KEY_ALIAS, null) as? SecretKey
				?: throw IllegalStateException("the key entry is not a secret key")
		}
		val cipher = Cipher.getInstance(TRANSFORMATION)
		cipher.init(
			Cipher.DECRYPT_MODE,
			key,
			GCMParameterSpec(TAG_BITS, sealed, 0, IV_BYTES)
		)
		return try {
			cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES)
		} catch (e: AEADBadTagException) {
			throw SecretUnrecoverableException("the tag does not verify", e)
		}
	}

	private companion object {
		const val ANDROID_KEY_STORE = "AndroidKeyStore"
		const val KEY_ALIAS = "navic_secrets_v1"
		const val TRANSFORMATION = "AES/GCM/NoPadding"
		const val IV_BYTES = 12
		const val TAG_BITS = 128
	}
}

/** The secrets file's name. Excluded from every backup and transfer (`data_extraction_rules.xml`). */
internal const val SECRETS_FILE = "navic_secrets"

/**
 * The secrets file, written with `commit()`: a migration removes the old plaintext only after
 * the sealed value is on disk.
 */
fun androidSecretVault(context: Context): SecretVault = SecretVault(
	settings = SharedPreferencesSettings(
		context.getSharedPreferences(SECRETS_FILE, Context.MODE_PRIVATE),
		commit = true
	),
	cipher = KeystoreSecretCipher()
)
