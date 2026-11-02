package paige.navic.domain.manager

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/*
 * Worbus P2, lane N1: every call to the Worbus Server authenticates through HubAuth.bearer() /
 * HubAuth.helloCredential() and nothing else. A site that read the legacy token directly would keep
 * sending it after a sign-in, i.e. act as the server's primary user whoever is signed in.
 * Source-level, because neither shows until a device holds both credentials.
 */
class HubBearerGuardTest {
	private val sources: List<File> = listOf("src/commonMain", "src/androidMain", "src/iosMain")
		.map(::File)
		.flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }

	private fun file(name: String) = sources.single { it.name == name }

	/** Naming either stored credential: `x.hubToken`, `secrets.hubDeviceSecret`, `var hubToken`. */
	private val credentialRead = Regex("""\b(hubToken|hubDeviceSecret)\b""")

	@Test
	fun noFileButTheStoreAndHubAuthNamesAHubCredential() {
		val allowed = setOf("SecretStore.kt", "HubAuth.kt")
		val offenders = sources.filter { it.name !in allowed }.flatMap { f ->
			f.readLines().mapIndexedNotNull { i, line ->
				if (credentialRead.containsMatchIn(line)) "${f.invariantSeparatorsPath}:${i + 1}: ${line.trim()}" else null
			}
		}
		assertEquals(emptyList(), offenders, "read the Worbus Server credential through HubAuth")
	}

	@Test
	fun everyBearerSiteReadsHubAuth() {
		// The sites the brief lists, by file, and how many times each must read the accessor.
		val sites = mapOf(
			"LbBotManager.kt" to 5,
			"PreviewManager.kt" to 2,
			"StationManager.kt" to 1,
			"MoodManager.kt" to 3,
			"AudioMuseManager.kt" to 1,
		)
		for ((name, n) in sites) {
			val text = file(name).readText()
			val reads = Regex("""hubAuth\.bearer\(\)""").findAll(text).count()
			assertTrue(reads >= n, "$name reads hubAuth.bearer() $reads times, expected at least $n")
		}
		// Every hub-route `Bearer` header in them interpolates the accessor (or a value built from it).
		val bearerLine = Regex(""""Bearer \$\{?([^"}]+)""")
		val fine = setOf("hubAuth.bearer().orEmpty()", "ep.token", "token")
		for (name in sites.keys) {
			for (m in bearerLine.findAll(file(name).readText())) {
				assertTrue(m.groupValues[1] in fine, "$name: Bearer ${m.groupValues[1]}")
			}
		}
		// The hello (main socket and cast bridges) sends HubAuth's credential.
		assertTrue("hubAuth.helloCredential()" in file("HubManager.kt").readText())
		assertTrue("putHelloCredential" in file("HubManager.kt").readText())
		assertTrue("hubAuth.helloCredential()" in file("CastBridgeManager.kt").readText())
		assertTrue("putHelloCredential" in file("CastDeviceBridge.kt").readText())
	}

	@Test
	fun theGuardCatchesTheShapesItIsFor() {
		for (line in listOf(
			"""header("Authorization", "Bearer ${'$'}{preferenceManager.hubToken}")""",
			"""put("token", preferenceManager.hubToken)""",
			"""val s = secrets.hubDeviceSecret""",
		)) assertTrue(credentialRead.containsMatchIn(line), line)
		for (line in listOf("""hubAuth.bearer()""", """val hubTokenSet = 1""", """Secret.HubToken""")) {
			assertTrue(!credentialRead.containsMatchIn(line), line)
		}
	}
}
