package paige.navic.domain.manager

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import paige.navic.domain.models.Mix
import paige.navic.domain.models.MixKind
import paige.navic.ui.components.sheets.AvatarMenuEntry
import paige.navic.ui.components.sheets.AvatarMenuIds
import paige.navic.ui.components.sheets.avatarMenuItems
import paige.navic.ui.screens.artist.artistSheetOffersFillGaps
import paige.navic.ui.screens.collection.gapFillOffered
import paige.navic.ui.screens.home.mixTiles
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/*
 * Worbus P2, lane N3: admin and primary-only surfaces follow the signed-in user (design §6.5). A gated
 * control is absent, never disabled; an older hub (no `welcome.user`) is today's behaviour.
 */
class HubRolesTest {
	private val bob = HubUser("bob", isAdmin = false, isPrimary = false)
	private val alice = HubUser("alice", isAdmin = true, isPrimary = false)
	private val owner = HubUser("owner", isAdmin = true, isPrimary = true)

	private fun tools(roles: HubRoles, webUrl: String = "http://lb:8899"): List<String> =
		avatarMenuItems(lbConfigured = true, canShare = true, attentionCount = 3, webUrl = webUrl, downloadsOn = true, admin = roles.admin)
			.filterIsInstance<AvatarMenuEntry.Group>()
			.firstOrNull { it.id == AvatarMenuIds.LIBRARY_TOOLS }
			?.entries?.map { it.id }.orEmpty()

	private fun frame(text: String) = Json.parseToJsonElement(text).jsonObject

	@Test
	fun user_frame_moves_gates() {
		// welcome.user says alice is an admin: her admin actions are there.
		var roles = hubRolesOf(alice, signedIn = true)
		assertTrue(adminGated(true, roles))
		assertTrue(AvatarMenuIds.ATTENTION in tools(roles))
		// The hub demotes her while the socket is idle: `{"t":"user", …}` replaces the user in place.
		val demoted = hubUserFrameOf(frame("""{"t":"user","user":{"name":"alice","admin":false,"primary":false}}"""))
		assertEquals(HubUser("alice", false, false), demoted)
		roles = hubRolesOf(demoted, signedIn = true)
		assertFalse(adminGated(true, roles), "Fix album match and the like go")
		assertFalse(AvatarMenuIds.ATTENTION in tools(roles))
		assertEquals(LbDownloads.OFF, downloadsFor(roles, LbDownloads.ON))
		// And back: promoted, they return.
		roles = hubRolesOf(hubUserFrameOf(frame("""{"t":"user","user":{"name":"alice","admin":true,"primary":false}}""")), signedIn = true)
		assertTrue(AvatarMenuIds.ATTENTION in tools(roles))
		// A malformed frame changes nothing (the caller ignores a null).
		assertNull(hubUserFrameOf(frame("""{"t":"user","user":{"name":"alice"}}""")))
	}

	@Test
	fun non_admin_sees_no_admin_actions() {
		val roles = hubRolesOf(bob, signedIn = true)
		val menu = tools(roles)
		// Library tools: one assertion per gated row.
		assertFalse(AvatarMenuIds.ATTENTION in menu, "Needs attention")
		assertFalse(AvatarMenuIds.SOURCE_HISTORY in menu, "Source history")
		assertFalse(AvatarMenuIds.LIBRARY_MATCHES in menu, "Library Matches")
		assertFalse(AvatarMenuIds.DUPLICATES in menu, "Duplicates and trash")
		assertFalse(AvatarMenuIds.SOURCE_RULES in menu, "Source rules")
		assertFalse(AvatarMenuIds.LOGS in menu, "Logs")
		// The pages' own gates, each the flag its entry point reads.
		assertFalse(adminGated(true, roles), "Fix album match / Wrong edition? (albumOverridesSupported)")
		assertFalse(adminGated(true, roles), "Fix MusicBrainz match (artistOverridesSupported)")
		assertFalse(adminGated(true, roles), "Merge into one name (artistMergesSupported)")
		val mode = downloadsFor(roles, LbDownloads.ON)
		assertFalse(gapFillOffered(mode), "Fill gaps on the album page")
		assertFalse(artistSheetOffersFillGaps(hasGap = true, downloads = mode), "Fill gaps in the artist page's album sheet")
		assertFalse(mode.isOn, "Request this album, Retry, Cancel, Allow MP3: the downloads-off surfaces instead")
		assertFalse(attentionDotShown(badge = 3, preference = true, configured = true, admin = roles.admin), "the avatar's dot")
		// What a non-admin keeps.
		assertEquals(listOf(AvatarMenuIds.WISHLIST), menu, "the wishlist")
	}

	@Test
	fun non_primary_sees_no_listening_or_fingerprint() {
		for (user in listOf(bob, alice)) {
			val roles = hubRolesOf(user, signedIn = true)
			assertFalse(primaryGated(true, roles), "${user.name}: listening, Stats' tabs, the home's card")
			assertFalse(yourMixOffered(engine = true, roles = roles), "${user.name}: Your mix")
			assertTrue(mixTiles(emptyList(), yourMixOffered(true, roles)).none { it.yourMix })
			val mixes = listOf(
				Mix("mx_1", "fp", MixKind.FINGERPRINT),
				Mix("mx_2", "alchemy", MixKind.ADAPTIVE),
				Mix("mx_3", "mine", MixKind.STATION),
				Mix("mx_4", "radio", MixKind.STATION, seedId = "song-1"),
				Mix("mx_5", "rock", MixKind.GENRE, seedId = "Rock")
			)
			assertEquals(listOf("mx_4", "mx_5"), mixesFor(roles, mixes).map { it.id }, "${user.name}: fingerprint mixes")
			// Signed in, the fingerprint is never read straight from AudioMuse.
			assertFalse(fingerprintRouteAllowed(viaHub = false, signedIn = true))
		}
		val ownerRoles = hubRolesOf(owner, signedIn = true)
		assertTrue(primaryGated(true, ownerRoles))
		assertTrue(yourMixOffered(true, ownerRoles))
		assertEquals(5, mixesFor(ownerRoles, listOf(
			Mix("a", "", MixKind.FINGERPRINT), Mix("b", "", MixKind.ADAPTIVE), Mix("c", "", MixKind.STATION),
			Mix("d", "", MixKind.STATION, seedId = "x"), Mix("e", "", MixKind.GENRE)
		)).size)
		assertTrue(fingerprintRouteAllowed(viaHub = true, signedIn = true))
	}

	@Test
	fun signin_required_is_refused_state() {
		val effect = lbRefusalEffect(LbAccess.OPEN, 401, "signin_required")
		assertEquals(LbAccess.REFUSED, effect.access)
		assertTrue(effect.silent)
		assertFalse(lbLayerOpen(configured = true, access = effect.access), "lb-bot surfaces render nothing")
		// lbbot_too_old is unavailable, not absent.
		val old = lbRefusalEffect(LbAccess.OPEN, 503, "lbbot_too_old")
		assertEquals(LbAccess.TOO_OLD, old.access)
		assertTrue(lbLayerOpen(true, old.access))
		assertFalse(lbAvailableFor(available = true, access = old.access))
		// Any other refusal leaves the access as it was, and is shown as before.
		val other = lbRefusalEffect(LbAccess.OPEN, 401, "")
		assertEquals(LbAccess.OPEN, other.access)
		assertFalse(other.silent)
		assertEquals(LbAccess.OPEN, lbRefusalEffect(LbAccess.OPEN, 503, "busy").access)
	}

	@Test
	fun admin_only_hides_not_toasts() {
		val effect = lbRefusalEffect(LbAccess.OPEN, 403, "admin_only")
		assertTrue(effect.demoteAdmin, "the user is taken as no admin: the action's gate goes")
		assertTrue(effect.silent, "no toast, no error line")
		assertEquals(LbAccess.OPEN, effect.access, "lb-bot itself is still open to them")
		// What demotion does on screen: the actions are absent.
		val after = HubRoles.EVERYTHING.copy(admin = false)
		assertFalse(AvatarMenuIds.ATTENTION in tools(after))
		// primary_only is not admin_only.
		assertFalse(lbRefusalEffect(LbAccess.OPEN, 403, "primary_only").demoteAdmin)
	}

	@Test
	fun older_hub_without_user_is_as_today() {
		val roles = hubRolesOf(null, signedIn = false)
		assertEquals(HubRoles.EVERYTHING, roles)
		assertTrue(adminGated(true, roles))
		assertTrue(primaryGated(true, roles))
		assertEquals(LbDownloads.UNKNOWN, downloadsFor(roles, LbDownloads.UNKNOWN))
		assertTrue(yourMixOffered(true, roles))
		assertTrue(fingerprintRouteAllowed(viaHub = false, signedIn = false))
		// The menu is exactly the one a P1 client draws.
		assertEquals(
			avatarMenuItems(lbConfigured = true, canShare = true, attentionCount = 3, webUrl = "http://lb:8899", downloadsOn = true),
			avatarMenuItems(lbConfigured = true, canShare = true, attentionCount = 3, webUrl = "http://lb:8899", downloadsOn = true, admin = roles.admin)
		)
		// A signed-in device before its welcome shows nothing gated.
		assertEquals(HubRoles.NOTHING_YET, hubRolesOf(null, signedIn = true))
	}
}
