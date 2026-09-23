package paige.navic.domain.manager

/**
 * The hub's HTTP base, derived from the WebSocket address the user configured.
 *
 * `ws://host:4790/connect` -> `http://host:4790`. Two managers proxy through the
 * hub — [LbBotManager] and [PreviewManager] — and both need exactly this, so it
 * lives here rather than being written twice. It is deliberately NOT shared with
 * [AudioMuseManager], which follows a different rule: that one keeps a direct-LAN
 * fallback and therefore ignores the hub toggle, whereas for these two the hub is
 * the only route and a user who switched it off should see no traffic at all.
 *
 * Null means "not configured" — no hub, no token, or the toggle off — and every
 * caller treats that as "this whole layer is absent", never as an error.
 */
internal fun hubHttpBase(preferenceManager: PreferenceManager): String? {
	if (!preferenceManager.hubEnabled) return null
	val raw = preferenceManager.hubUrl.trim()
	if (raw.isBlank() || preferenceManager.hubToken.isBlank()) return null
	val http = when {
		raw.startsWith("wss://") -> "https://" + raw.removePrefix("wss://")
		raw.startsWith("ws://") -> "http://" + raw.removePrefix("ws://")
		raw.startsWith("http://") || raw.startsWith("https://") -> raw
		else -> "http://$raw"
	}
	return http.trimEnd('/').removeSuffix("/connect").trimEnd('/')
}

/**
 * Changes whenever the hub's route configuration does, so a `LaunchedEffect` keyed
 * on it re-probes instead of caching "unavailable" for the life of the composition.
 * Preferences here are plain delegated properties with no Flow behind them.
 */
internal fun hubRouteSignature(preferenceManager: PreferenceManager): String =
	"${preferenceManager.hubEnabled}|${preferenceManager.hubUrl}|" +
		"${preferenceManager.hubToken.isNotBlank()}"
