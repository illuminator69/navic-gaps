package paige.navic.domain.manager

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/*
 * B-279: the password and the two tokens are read through SecretStore and nothing else. A reader
 * that goes to the old preference keys gets nothing (they are migrated away) or, worse, would
 * re-create the plaintext; a reader of the secrets file would send the sealed `enc:v1:` form to
 * a server. Source-level, because neither shows until a device has the data.
 */
class SecretAccessGuardTest {
	private val sources: List<File> = listOf("src/commonMain", "src/androidMain", "src/iosMain")
		.map(::File)
		.flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }

	private val keys = "(password|hubToken|audioMuseToken)"

	/** Settings calls on an old key, an indexed get or set on one, or a preference delegate named after one. */
	private val legacyAccess = listOf(
		Regex("""\b(getString|getStringOrNull|putString|remove|hasKey|get|set|getOrNull)(<[^>]*>)?\(\s*"$keys""""),
		Regex("""\[\s*"$keys"\s*]"""),
		Regex("""\b(var|val)\s+$keys\s+by\s+preference\b"""),
		Regex("""\bpreference\(\s*"$keys""""),
		// A named argument: `getString(key = "password", …)`, `preference(key = "hubToken")`.
		Regex("""\bkey\s*=\s*"$keys""""),
		// Where the store keeps a secret is the store's alone (`Secret.key` is private to it).
		Regex("""\bSecret\.\w+\.key\b"""),
	)

	private fun rel(f: File) = f.invariantSeparatorsPath

	@Test
	fun theSourcesAreFound() {
		assertTrue(sources.size > 300, "found ${sources.size} files: run from composeApp/")
		assertTrue(sources.any { it.name == "SecretStore.kt" })
	}

	@Test
	fun noFileButTheStoreReadsTheOldKeys() {
		val offenders = sources
			.filter { it.name != "SecretStore.kt" }
			.flatMap { f ->
				f.readLines().mapIndexedNotNull { i, line ->
					if (legacyAccess.any { it.containsMatchIn(line) }) "${rel(f)}:${i + 1}: ${line.trim()}" else null
				}
			}
		assertEquals(emptyList(), offenders, "read the secret through SecretStore")
	}

	@Test
	fun theSecretsFileIsOpenedInOnePlaceOnly() {
		val allowed = setOf("KeystoreSecretCipher.kt", "PlatformModule.ios.kt")
		val offenders = sources
			.filter { it.name !in allowed }
			.filter { "navic_secrets" in it.readText() }
			.map(::rel)
		assertEquals(emptyList(), offenders)
	}

	@Test
	fun theVaultIsReadOnlyByTheStore() {
		// SecretVault's settings are `internal`, i.e. visible to the whole module: only the store
		// and the places that build a vault may name it.
		val allowed = setOf("SecretStore.kt", "KeystoreSecretCipher.kt", "PlatformModule.android.kt", "PlatformModule.ios.kt")
		val offenders = sources
			.filter { it.name !in allowed }
			.filter { Regex("""\bSecretVault\b|\.cipher\.open\(""").containsMatchIn(it.readText()) }
			.map(::rel)
		assertEquals(emptyList(), offenders)
	}

	@Test
	fun theGuardCatchesTheShapesItIsFor() {
		val bad = listOf(
			"""val password = settings.getString("password", "")""",
			"""settings["password"] = password""",
			"""settings.remove("hubToken")""",
			"""var hubToken by preference("")""",
			"""var audioMuseToken by preference("")""",
			"""val x = settings.get<String>("audioMuseToken")""",
			"""settings.getString(key = "password", defaultValue = "")""",
			"""var hubToken by preference(key = "hubToken", default = "")""",
			"""vault.settings.remove(Secret.HubToken.key)""",
		)
		for (line in bad) assertTrue(legacyAccess.any { it.containsMatchIn(line) }, line)
		val fine = listOf(
			"""put("password", secrets.navidromePassword)""",
			"""put("token", preferenceManager.hubToken)""",
			"""parameter("navidrome_password", secrets.navidromePassword)""",
			"""val key = "passwordState"""",
			"""Secret.HubToken.name""",
		)
		for (line in fine) assertTrue(legacyAccess.none { it.containsMatchIn(line) }, line)
	}

	@Test
	fun noSecretIsKeptInSavedState() {
		// A `rememberSaveable` value goes into the saved-state Bundle, which outlives the screen.
		val saved = Regex("""rememberSaveable[^\n]*\b(hubToken|audioMuseToken|navidromePassword)\b""")
		val offenders = sources.flatMap { f ->
			f.readLines().mapIndexedNotNull { i, line ->
				if (saved.containsMatchIn(line)) "${rel(f)}:${i + 1}: ${line.trim()}" else null
			}
		}
		assertEquals(emptyList(), offenders)
		assertTrue(saved.containsMatchIn("""var token by rememberSaveable { mutableStateOf(preferenceManager.hubToken) }"""))
	}

	@Test
	fun theSecretsFileTravelsNowhere() {
		val transfer = File("../androidApp/src/main/res/xml/data_extraction_rules.xml").readText()
		val backup = File("../androidApp/src/main/res/xml/backup_rules.xml").readText()
		val exclude = Regex("""<exclude\s+domain="sharedpref"\s+path="navic_secrets\.xml"\s*/>""")
		val cloud = transfer.substringAfter("<cloud-backup>").substringBefore("</cloud-backup>")
		val device = transfer.substringAfter("<device-transfer>").substringBefore("</device-transfer>")
		assertTrue(exclude.containsMatchIn(cloud), "cloud backup")
		assertTrue(exclude.containsMatchIn(device), "device transfer")
		assertTrue(exclude.containsMatchIn(backup), "full backup (API 30 and below)")
	}
}
