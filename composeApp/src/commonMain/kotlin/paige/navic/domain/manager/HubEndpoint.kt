package paige.navic.domain.manager

/**
 * The Worbus Server's HTTP base from an address: `ws://host:4790/connect` -> `http://host:4790`, the
 * path kept (Worbus P3: one normaliser, [normaliseHubAddress]). Null for a blank address or one that
 * does not normalise.
 */
fun hubHttpBaseOf(url: String): String? = httpBaseOfAddress(url)

/**
 * The hub's HTTP base for the managers that proxy through it.
 *
 * [LbBotManager], [PreviewManager], [StationManager] and [MoodManager] all need exactly this, so it
 * lives here rather than being written four times. It is deliberately NOT shared with
 * [AudioMuseManager], which keeps a direct-LAN fallback and therefore ignores the hub toggle,
 * whereas for these the hub is the only route and a user who switched it off should see no
 * traffic at all.
 *
 * Null means "not configured" — no hub, no credential ([HubAuth.bearer]), or the toggle off — and
 * every caller treats that as "this whole layer is absent", never as an error.
 */
internal fun hubHttpBase(auth: HubAuth): String? = auth.httpBase()

/**
 * Changes whenever the hub's route configuration does, so a `LaunchedEffect` keyed
 * on it re-probes instead of caching "unavailable" for the life of the composition.
 * Preferences here are plain delegated properties with no Flow behind them. A sign-in
 * (a secret where there was none, or another one) is such a change too.
 */
internal fun hubRouteSignature(preferenceManager: PreferenceManager, auth: HubAuth): String =
	// Worbus P3: the server, not the address it is reached at (design §6.5).
	"${preferenceManager.hubEnabled}|${perServerKey(preferenceManager)}|" +
		"${auth.bearer()?.hashCode() ?: 0}"
