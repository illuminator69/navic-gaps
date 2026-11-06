package paige.navic.domain.manager

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import paige.navic.di.synchronized
import paige.navic.util.Logger
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** The secrets this app keeps (B-279; the device secret since Worbus P2). */
enum class Secret {
	NavidromePassword,
	HubToken,
	AudioMuseToken,
	/** The Worbus Server's per-device credential (`/hub/signin`'s `deviceSecret`). */
	HubDeviceSecret
}

/**
 * The key a secret had in the settings file before B-279, and has in the secrets file. Private to
 * this file: nothing but [SecretStore] may name where a secret is stored (`SecretAccessGuardTest`).
 */
private val Secret.key: String
	get() = when (this) {
		Secret.NavidromePassword -> "password"
		Secret.HubToken -> "hubToken"
		Secret.AudioMuseToken -> "audioMuseToken"
		Secret.HubDeviceSecret -> "hubDeviceSecret"
	}

/**
 * A stored value that can never be opened on this device: the key that sealed it is gone, it was
 * sealed under another key, or it is not a sealed value at all. Removing it loses nothing.
 * Anything else [SecretCipher.open] throws is a fault of the moment (the Keystore busy or
 * restarting, a binder error) and the stored value is kept for a later read.
 */
class SecretUnrecoverableException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Seals and opens a secret's bytes. Android: AES-256-GCM under a non-exportable Android
 * Keystore key, the output IV then ciphertext and tag (`KeystoreSecretCipher`).
 * [PlainSecretCipher] does not encrypt; only tests use it.
 */
interface SecretCipher {
	/** False when [seal] does not encrypt: values are then stored as they are. */
	val encrypts: Boolean

	fun seal(plain: ByteArray): ByteArray

	/**
	 * Throws [SecretUnrecoverableException] when [sealed] can never be opened here (the key is gone
	 * or is not the one that sealed it, the value is malformed), and anything else for a fault that
	 * may pass.
	 */
	fun open(sealed: ByteArray): ByteArray
}

/** Stores secrets as they are, NOT encrypted. Tests only. */
class PlainSecretCipher : SecretCipher {
	override val encrypts = false
	override fun seal(plain: ByteArray) = plain
	override fun open(sealed: ByteArray) = sealed
}

/**
 * The platform half of [SecretStore]: the secrets file, which holds nothing but the three
 * secrets, and the cipher. Registered in both platform modules. Nothing but [SecretStore] may
 * read [settings] (`SecretAccessGuardTest`).
 */
class SecretVault(internal val settings: Settings, internal val cipher: SecretCipher)

/**
 * The one way to the Navidrome password, the hub token, the AudioMuse token and the Worbus Server's
 * device secret (B-279, adapted
 * from upstream 4bd24206). They used to be plaintext in the settings file, which device
 * transfer carries; they are now in their own file ([SecretVault]), each value
 * `enc:v1:<base64(iv | ciphertext)>`, and that file is in no backup and no transfer.
 *
 * - **Read once, then from memory.** The first read of a secret opens it; later reads are the
 *   kept value. A read never returns the stored form: what cannot be opened is "" (missing).
 * - **Migration when the store is made** (at launch, as PreferenceManager needs it). A plaintext
 *   value under the old key in the settings file is sealed into the secrets file and only then
 *   removed from the settings file, so a crash in between loses nothing: the next launch finds
 *   the sealed one and removes the leftover.
 *   A plaintext value found in the secrets file is sealed again.
 * - **Only what can never be opened is dropped** (the Keystore key did not come with a restore or a
 *   transfer, a corrupt value: [SecretUnrecoverableException], bad base64, bytes that are not
 *   UTF-8): the stored value is removed and the secret reads as never set.
 * - **A fault of the moment keeps the stored value** (any other exception from the cipher): the
 *   read is "" this once, nothing is removed and nothing is kept in memory, so the next read tries
 *   again.
 * - Either way the secret is listed in [unreadable] until a read or a set succeeds, for the login
 *   screen to say why it is asking again.
 * - **A seal that fails stores nothing.** The value is kept in memory for this process only,
 *   never written in plaintext; a migration that cannot seal leaves the old value where it was.
 *
 * Reads are Compose state like the preferences they replace: a set recomposes its readers.
 */
@OptIn(ExperimentalEncodingApi::class)
class SecretStore(
	private val legacy: Settings,
	private val vault: SecretVault,
	private val onError: (String, Throwable?) -> Unit = { msg, tr -> Logger.e("SecretStore", msg, tr) }
) {
	private val lock = Any()
	private val loaded = HashMap<Secret, String>()
	private var changes by mutableIntStateOf(0)

	private val _unreadable = MutableStateFlow<Set<Secret>>(emptySet())

	/** Secrets that were stored but could not be read on this device, since this launch. */
	val unreadable: StateFlow<Set<Secret>> = _unreadable.asStateFlow()

	init {
		// What the settings file still holds is moved now, not at that secret's first read: the
		// settings file travels in a device transfer, and a token nothing reads (AudioMuse behind
		// the hub) would otherwise stay there in plaintext. Once per secret per install; later
		// launches only look for the key.
		for (secret in Secret.entries) if (legacy.hasKey(secret.key)) get(secret)
	}

	var navidromePassword: String
		get() = get(Secret.NavidromePassword)
		set(value) = set(Secret.NavidromePassword, value)

	var hubToken: String
		get() = get(Secret.HubToken)
		set(value) = set(Secret.HubToken, value)

	var audioMuseToken: String
		get() = get(Secret.AudioMuseToken)
		set(value) = set(Secret.AudioMuseToken, value)

	/** Worbus P2: read only through [HubAuth] (`HubBearerGuardTest`). */
	var hubDeviceSecret: String
		get() = get(Secret.HubDeviceSecret)
		set(value) = set(Secret.HubDeviceSecret, value)

	fun get(secret: Secret): String {
		changes // read, so a set recomposes whoever read this
		return synchronized(lock) {
			loaded[secret] ?: load(secret)?.also { loaded[secret] = it } ?: ""
		}
	}

	/** "" removes the secret. */
	fun set(secret: Secret, value: String) {
		synchronized(lock) {
			if (!writeVault(secret, value)) vault.settings.remove(secret.key)
			legacy.remove(secret.key)
			loaded[secret] = value
		}
		_unreadable.update { it - secret }
		changes++
	}

	/** The secret's value, or null for a miss that a later read should try again (not kept). */
	private fun load(secret: Secret): String? {
		var lost = false
		val stored = vault.settings.getStringOrNull(secret.key)
		if (stored != null) {
			when (val opened = openStored(secret, stored)) {
				is Opened.Value -> {
					if (vault.cipher.encrypts && !stored.startsWith(PREFIX)) writeVault(secret, opened.plain)
					// A migration that stopped between its two steps left the old copy behind.
					legacy.remove(secret.key)
					_unreadable.update { it - secret }
					return opened.plain
				}
				Opened.NotNow -> {
					_unreadable.update { it + secret }
					return null
				}
				Opened.Never -> {
					vault.settings.remove(secret.key)
					lost = true
				}
			}
		}
		val old = legacy.getStringOrNull(secret.key)
		val result = when {
			old == null -> ""
			// Never hand out a stored form, whoever wrote it.
			old.isEmpty() || old.startsWith(PREFIX) -> {
				legacy.remove(secret.key)
				""
			}
			else -> {
				if (writeVault(secret, old)) legacy.remove(secret.key)
				old
			}
		}
		if (lost && result.isEmpty()) _unreadable.update { it + secret }
		return result
	}

	private sealed interface Opened {
		class Value(val plain: String) : Opened
		/** Can never be opened here: the stored value is removed. */
		data object Never : Opened
		/** Could not be opened this time: the stored value is kept. */
		data object NotNow : Opened
	}

	private fun openStored(secret: Secret, stored: String): Opened {
		if (!stored.startsWith(PREFIX)) return Opened.Value(stored)
		if (!vault.cipher.encrypts) return Opened.Never
		val bytes = try {
			Base64.decode(stored.removePrefix(PREFIX))
		} catch (e: IllegalArgumentException) {
			onError("${secret.name} is not a sealed value; it is dropped", e)
			return Opened.Never
		}
		val plain = try {
			vault.cipher.open(bytes)
		} catch (e: SecretUnrecoverableException) {
			onError("${secret.name} can never be opened on this device; it is dropped", e)
			return Opened.Never
		} catch (e: Exception) {
			onError("${secret.name} could not be opened this time; it is kept for the next read", e)
			return Opened.NotNow
		}
		return try {
			Opened.Value(plain.decodeToString(throwOnInvalidSequence = true))
		} catch (e: CharacterCodingException) {
			onError("${secret.name} did not open to text; it is dropped", e)
			Opened.Never
		}
	}

	/** True when [value] is stored sealed (or removed, for ""). */
	private fun writeVault(secret: Secret, value: String): Boolean {
		if (value.isEmpty()) {
			vault.settings.remove(secret.key)
			return true
		}
		val cipher = vault.cipher
		if (!cipher.encrypts) {
			vault.settings.putString(secret.key, value)
			return true
		}
		val sealed = try {
			PREFIX + Base64.encode(cipher.seal(value.encodeToByteArray()))
		} catch (e: Exception) {
			onError("${secret.name} could not be sealed; it is not stored", e)
			return false
		}
		vault.settings.putString(secret.key, sealed)
		return true
	}

	companion object {
		/** The stored form's prefix. Nothing but a stored value starts with it. */
		const val PREFIX = "enc:v1:"
	}
}
