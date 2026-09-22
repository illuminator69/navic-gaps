package paige.navic.ui.screens.discover

/**
 * The Discover row catalogue.
 *
 * **This table is duplicated in Feishin, on purpose, and the ids are the
 * contract.** There is no shared build between the two repos and no codegen, so
 * "shared catalogue" can only ever mean "written twice and kept in sync". The
 * precedent is [paige.navic.domain.models.settings.MoodCharacter], and it is a
 * cautionary one: the same three presets live here and in Feishin's
 * `audio-muse-source.ts` with matching numbers but different ids and labels, and
 * the two have already drifted — Feishin's auto-DJ escalates temperature per
 * pass and this client's `topUp` does not. Writing the ids down in one place
 * (`navi-connect/CLAUDE.md` §6) is the only thing standing between this table
 * and the same fate.
 *
 * Feishin's copy is `renderer/features/discover/discover-rows.ts`.
 */
enum class DiscoverRowId(val wireId: String) {
	FRESH("fresh"),
	SIMILAR_ARTISTS("similar-artists"),
	LISTENBRAINZ("listenbrainz"),
	REDISCOVERY("rediscovery"),
	MOOD("mood");
}

/**
 * The prefix ListenBrainz's own playlists arrive under.
 *
 * Unlike [paige.navic.domain.manager.RediscoveryPlaylists.PREFIX] this app does
 * **not** mint these — the `listenbrainz-daily-playlist` Navidrome plugin does —
 * so the prefix is an observation rather than a contract this side can enforce.
 * Confirmed against the live server on 2026-09-23: "ListenBrainz Daily Jams",
 * "ListenBrainz Weekly Jams", "ListenBrainz Weekly Exploration". A rename
 * upstream makes the row go quiet rather than wrong, which is the right failure.
 * Also written down in `navi-connect/CLAUDE.md` §6 beside the row ids.
 */
const val LISTENBRAINZ_PLAYLIST_PREFIX = "ListenBrainz "

/**
 * What a row needs before it has anything to say.
 *
 * Three availability idioms already coexist in this tree — `RadioManager`'s
 * login-scoped StateFlow, `AudioMuseManager.clapAvailability()`'s reasoned enum,
 * and `LbBotManager.ensureAvailability()`'s TTL-cached boolean — which is how
 * every surface ended up deciding for itself what "absent" looks like. The
 * Discover screen asks one question per row through one viewmodel instead.
 */
sealed interface DiscoverCapability {
	/** An lb-bot route, gated on the hub advertising it in `/lb/status.routes`. */
	data class LbBot(val route: String) : DiscoverCapability
	/** AudioMuse's CLAP index. */
	data object Clap : DiscoverCapability
	/** Navidrome alone — always answerable. */
	data object Library : DiscoverCapability
}

data class DiscoverRow(
	val id: DiscoverRowId,
	val capability: DiscoverCapability
)

/**
 * Render order. Leverage first: what is new, then who you are missing, then what
 * was picked for you, then what you already own and forgot, then a way to ask a
 * question of your own.
 *
 * A "stations" row is deliberately absent rather than disabled. Persistent named
 * stations were scoped and deferred to a hub-side implementation next to saved
 * queues, and a placeholder here would be a row that can never render. Note also
 * that "station" is already taken in this app's vocabulary by
 * [paige.navic.ui.navigation.Screen.RadioList], which is Subsonic internet
 * radio — whoever builds it should pick a different word deliberately.
 */
val DISCOVER_ROWS: List<DiscoverRow> = listOf(
	DiscoverRow(DiscoverRowId.FRESH, DiscoverCapability.LbBot("GET /lb/fresh-releases")),
	DiscoverRow(DiscoverRowId.SIMILAR_ARTISTS, DiscoverCapability.LbBot("GET /lb/artist/similar")),
	// Navidrome only: the plugin writes these as ordinary server-side playlists,
	// so the row is a name filter costing no route, no probe and no lb-bot.
	DiscoverRow(DiscoverRowId.LISTENBRAINZ, DiscoverCapability.Library),
	DiscoverRow(DiscoverRowId.REDISCOVERY, DiscoverCapability.Library),
	DiscoverRow(DiscoverRowId.MOOD, DiscoverCapability.Clap)
)
