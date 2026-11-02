package paige.navic.domain.manager

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/*
 * Worbus P2, lane N's review fixes: the wiring no host test can run (LbBotManager, HubManager and the
 * cast bridges are not constructible here), held at the source. Each rule's behaviour is a pure
 * function tested in HubAuthTest / HubRolesTest / HubLogoutTest; this keeps the managers calling them.
 */
class LaneNReviewGuardTest {
	private val sources: List<File> = listOf("src/commonMain", "src/androidMain")
		.map(::File)
		.flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }

	private fun text(name: String) = sources.single { it.name == name }.readText()

	/** A member function's text, from its `fun` to the closing brace at its own (one-tab) indent. */
	private fun member(file: String, signature: String): String {
		val all = text(file)
		val start = all.indexOf(signature)
		assertTrue(start >= 0, "$file: $signature")
		val end = all.indexOf("\n\t}\n", start)
		return all.substring(start, end)
	}

	/**
	 * Review I-2: the five boxes and sheets the review names draw an lb-bot error only through
	 * [paige.navic.ui.components.sheets.LbErrorLine], which draws nothing for a hide-type refusal; and
	 * the error-to-words function has a branch for the refusals ("lb-bot isn't set up." is only for a
	 * hub that has none).
	 */
	@Test
	fun the_five_surfaces_draw_refusals_through_the_one_line() {
		val surfaces = listOf("GapBox.kt", "RequestBox.kt", "MbidMatchSheet.kt", "ArtistMergeBlock.kt", "AlbumMatchSheet.kt")
		for (name in surfaces) {
			val src = text(name)
			assertTrue("LbErrorLine(" in src, "$name draws its errors with LbErrorLine")
			assertTrue("lbErrorText(" !in src, "$name: no error words outside LbErrorLine")
		}
		val common = text("LbBotCommon.kt")
		val line = common.substring(common.indexOf("fun LbErrorLine("))
		assertTrue(Regex("""if \(error == null \|\| !lbErrorDrawn\(error\)\) return""").containsMatchIn(line), "LbErrorLine draws nothing for a hidden refusal")
		val words = common.substring(common.indexOf("fun lbErrorText("), common.indexOf("fun aboutRefreshMessage("))
		assertTrue("is LbBotManager.LbError.AccessRefused ->" in words, "lbErrorText has the refusals' branch")
		assertTrue("error_lbbot_unavailable_to_account" in words, "too old says its one sentence")
	}

	/** Review I-2: every reader of a non-2xx answer returns the refusal as itself. */
	@Test
	fun every_failure_reader_returns_access_refused() {
		for (sig in listOf(
			"private fun failureFor(",
			"private fun overrideFailureFor(",
			"private fun metaFailureFor(",
			"private inline fun <T> albumAnswer(",
			"private inline fun <T> mergeAnswer("
		)) {
			val body = member("LbBotManager.kt", sig)
			assertTrue(listOf("LbError.AccessRefused", "accessRefusedOf(", "lbAccessError(").any { it in body }, "$sig returns AccessRefused")
		}
	}

	/** Review I-1: a 2xx answer clears too-old in every reader; the `lb` advert's identity reaches it. */
	@Test
	fun success_and_identity_reach_the_access_state() {
		for (sig in listOf(
			"private suspend inline fun <reified T> getJson(",
			"private suspend inline fun <reified B, reified T> postJson(",
			"private suspend fun getText(",
			"private fun rawAnswer("
		)) {
			assertTrue("noteAccessAnswered(" in member("LbBotManager.kt", sig), "$sig notes a 2xx")
		}
		assertTrue("lbAccessAfterAdvert(" in member("LbBotManager.kt", "fun applyHubAdvert("))
		assertTrue("identity = advert.identity" in member("HubManager.kt", "private fun applyLbAdvert("))
	}

	/** Review M-1: logout forgets the credentials before anything is launched. */
	@Test
	fun logout_forgets_the_credentials_synchronously() {
		val leave = member("HubManager.kt", "fun leaveForLogout(")
		val begin = leave.indexOf("beginLeavingWorbusServer(")
		val launch = leave.indexOf("scope.launch")
		assertTrue(begin in 0 until launch, "beginLeavingWorbusServer runs before scope.launch")
		// Review M-7: and the fill watches go with it.
		assertTrue("lbBotManager.forgetWatches()" in leave)
	}

	/** Review M-2: HubAuth's 401 callback reaches the socket. */
	@Test
	fun a_hub_401_kicks_the_socket() {
		assertTrue(Regex("""hubAuth\.onUnauthorized = \{ kickReconnect\(""").containsMatchIn(text("HubManager.kt")))
		assertTrue("hubAuth.noteHttpUnauthorized()" in member("LbBotManager.kt", "private fun noteAccessRefusal("))
	}

	/** Review M-5: the cast bridges rebuild when the credential changes, not at the next 30 s tick. */
	@Test
	fun cast_bridges_follow_the_credential() {
		val start = member("CastBridgeManager.kt", "fun start(")
		assertTrue("hubAuth.credentialGeneration" in start, "CastBridgeManager.start collects the credential generation")
	}

	/** Review M-6: every mutable field of HubAuth is read across threads (the socket's, the UI's). */
	@Test
	fun hub_auth_fields_are_volatile() {
		val lines = text("HubAuth.kt").lines()
		val unguarded = lines.withIndex().filter { (i, l) ->
			l.trimStart().startsWith("private var ") && !lines[i - 1].trim().startsWith("@kotlin.concurrent.Volatile")
		}.map { it.value.trim() }
		assertEquals(emptyList(), unguarded)
	}

	/** Review M-10: the admin route is never posted for a non-admin, whoever calls it. */
	@Test
	fun index_release_is_admin_only_at_the_source() {
		val body = member("LbBotManager.kt", "suspend fun indexRelease(")
		val gate = body.indexOf("_roles.value.admin")
		assertTrue(gate in 0 until body.indexOf("postJson("), "indexRelease checks admin before posting")
		assertTrue("externalIndexAdd(" in member("ExternalAlbumViewModel.kt", "private suspend fun refreshIndexRow("))
	}

	/** Review M-12: the direct fingerprint asks whether the device belongs to a Worbus Server. */
	@Test
	fun direct_fingerprint_reads_the_bound_question() {
		assertTrue("fingerprintRouteAllowed(ep.viaHub, hubAuth.boundToWorbusServer)" in text("AudioMuseManager.kt"))
	}

	/** Review M-4: the Server page tells a token link from a signed-in one. */
	@Test
	fun the_server_page_knows_a_token_link() {
		assertTrue("linkedByToken = linkedByToken" in text("ServerScreen.kt"))
	}
}
