package paige.navic

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/*
 * B-181 (audit 2026-10-22, W-01 and L2-01): there is one MainActivity, and a link it was handed is
 * handled once.
 *
 * `MainActivity` was `standard`, and the widgets, the fill notification and a share into the app
 * all name it, so each of them started a SECOND instance on top of the running one: a new Home over
 * the old `[Home, Search]`, and Back then walked both (`[Home, Search, Home]`, B-180). And
 * `onCreate` handled `intent` unconditionally, so a rotation or a restore after process death
 * replayed the link that had already opened its page.
 *
 * Neither is visible to a unit test without a device, and the manifest and the activity are
 * outside the module the rules live in. So this reads the source, like `WashedBrowsingDriftTest`:
 * JVM-only (androidHostTest, java.io), run from composeApp/. The behaviour itself was watched on the
 * AVD (`dumpsys activity` after a share, see the note in CLAUDE.md §7).
 */
class SingleActivityLaunchTest {
	private val manifest = File("../androidApp/src/main/AndroidManifest.xml")
	private val activity = File("../androidApp/src/main/kotlin/paige/navic/androidApp/MainActivity.kt")

	private fun text(file: File): String {
		assertTrue(file.isFile, "${file.absolutePath} not found (host tests run from composeApp/)")
		return file.readText()
	}

	/** The `<activity android:name=".MainActivity" …>` start tag, attributes included. */
	private fun mainActivityTag(): String {
		val xml = text(manifest)
		val start = Regex("""<activity\s+android:name="\.MainActivity"""").find(xml)?.range?.first
			?: error("MainActivity's <activity> tag not found in the manifest")
		val end = xml.indexOf(">", start)
		assertTrue(end > start, "MainActivity's <activity> tag is not closed")
		return xml.substring(start, end)
	}

	@Test
	fun mainActivityIsTheOnlyInstanceInItsTask() {
		val tag = mainActivityTag()
		assertTrue(
			"""android:launchMode="singleTask"""" in tag,
			"MainActivity must be singleTask: a widget, notification or share intent that names it otherwise " +
				"starts a second instance on top of the running one. Tag was:\n$tag"
		)
	}

	/** `onCreate`'s body, up to the next member. */
	private fun onCreateBody(): String {
		val src = text(activity)
		val start = src.indexOf("override fun onCreate(")
		assertTrue(start >= 0, "onCreate not found in MainActivity.kt")
		val end = src.indexOf("\n\t/**", start).takeIf { it > start } ?: src.length
		return src.substring(start, end)
	}

	@Test
	fun theLaunchIntentIsHandledOnlyOnAFreshCreate() {
		val body = onCreateBody()
		val calls = Regex("""handleDeepLink\(""").findAll(body).count()
		assertEquals(1, calls, "onCreate should hand the intent to handleDeepLink exactly once")
		assertTrue(
			Regex("""if \(savedInstanceState == null\)\s*\{?\s*handleDeepLink\(intent\)""").containsMatchIn(body),
			"onCreate must handle the intent only when savedInstanceState == null: a rotation or a restore " +
				"after process death re-enters onCreate with the same intent, and the link it carried was " +
				"already handled. onCreate was:\n$body"
		)
	}

	@Test
	fun aNewIntentIsStillHandled() {
		val src = text(activity)
		val start = src.indexOf("override fun onNewIntent(")
		assertTrue(start >= 0, "onNewIntent not found in MainActivity.kt")
		val body = src.substring(start, src.indexOf("\n\t}\n", start))
		assertTrue("handleDeepLink(intent)" in body, "onNewIntent must still hand the intent to handleDeepLink")
	}

	/**
	 * Audit L2-13: predictive Back (and the back-invoked callbacks Navigation 3 registers) relied on
	 * targetSdk 37's default, which a device on API 33 to 35 does not share. The manifest says it.
	 */
	@Test
	fun theApplicationOptsIntoPredictiveBack() {
		val xml = text(manifest)
		val start = xml.indexOf("<application")
		assertTrue(start >= 0, "<application> not found in the manifest")
		val tag = xml.substring(start, xml.indexOf(">", start))
		assertTrue(
			"""android:enableOnBackInvokedCallback="true"""" in tag,
			"<application> must declare android:enableOnBackInvokedCallback=\"true\". Tag was:\n$tag"
		)
	}
}
