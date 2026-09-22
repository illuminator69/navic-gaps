package paige.navic.domain.models.settings

/**
 * navi-connect: continuous autoplay (queue-end top-up) strategy.
 *
 * - [Off]: never top up.
 * - [Similar]: top up from the current track's similar songs (Subsonic
 *   `getSimilarSongs2`; served as sonic similarity when the AudioMuse plugin is
 *   installed). Tier 1 — works with no extra configuration.
 * - [Fingerprint]: top up from listening habits (AudioMuse Tier 2
 *   `sonic_fingerprint`).
 * - [Adaptive]: top up from the live Mood Flow centroid, where playing a track
 *   through is an ADD and skipping it a SUBTRACT (AudioMuse Tier 2
 *   `alchemy`), shaped by the selected `MoodCharacter`.
 *
 * Both Tier-2 modes are wired in `RadioManager.topUp` and are fail-soft: with
 * the core API unconfigured or unreachable they answer empty and autoplay falls
 * back to a local genre mix rather than stopping. See DESIGN-adaptive-audiomuse.md.
 *
 * Labels are hardcoded EN (consistent with the other navi-connect screens) to
 * avoid a string-resource regeneration step.
 */
enum class AutoplayMode(val label: String) {
	Off("Off"),
	Similar("Similar songs"),
	Fingerprint("Sonic Fingerprint"),
	Adaptive("Mood Flow")
}
