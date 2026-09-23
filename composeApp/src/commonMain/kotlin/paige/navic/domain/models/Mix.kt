package paige.navic.domain.models

import androidx.compose.runtime.Immutable

/**
 * "Mixed for You": a **recipe**, not a result.
 *
 * This is the distinction the whole feature turns on, and it is the one thing the
 * app could not express before. A [SavedQueueEntity] stores a *result* — a frozen
 * tracklist with a `sourceKind`, so re-opening it replays exactly what played last
 * time. `RadioManager` takes `{mode, seedId, moodCharacter, count}` as arguments and
 * drops them on the floor. Nothing persisted the arguments, so nothing could ever
 * regenerate.
 *
 * A mix persists precisely those arguments. Playing it runs the local engine again,
 * which is why a second play gives a different tracklist — and why the hub can hold
 * one for every client without ever knowing what a song is.
 *
 * **The hub stores and broadcasts these; it never generates.** Each client
 * regenerates locally through the engine it already has ([RadioManager] here,
 * Feishin's `auto-dj` package). That is what keeps the hub audio-free and
 * AudioMuse-free, which is a standing rule of this stack rather than an accident of
 * this feature.
 *
 * **The word is deliberate.** "Station" is taken by [Screen.RadioList] — Subsonic
 * internet radio, with its own entity, DAO and dialog — and "radio" by
 * [SavedQueueSource.RADIO] / `RadioManager.startRadio`, which is the *ephemeral*
 * similarity mix. Navidrome's own "Instant Mix" is ephemeral too, which is exactly
 * what these are not. User-facing string: "Mixed for You"; the noun in code is
 * `mix`.
 */
@Immutable
data class Mix(
	/** `mx_<ms>_<hex>`, minted by the hub. Never minted here. */
	val id: String,
	val name: String,
	val kind: String,
	/**
	 * What the recipe is seeded from, in the seed kind's own vocabulary: a Navidrome
	 * song / album / artist id for [MixKind.SIMILAR] and [MixKind.ARTIST], a genre
	 * name for [MixKind.GENRE], and nothing at all for the two AudioMuse modes, which
	 * seed from listening history rather than from a thing.
	 */
	val seedId: String = "",
	/** What to call the seed in the UI, so a recipe reads as a sentence offline. */
	val seedName: String = "",
	/** One of [paige.navic.domain.models.settings.MoodCharacter]'s names, for ADAPTIVE. */
	val moodCharacter: String = "",
	val count: Int = 50,
	val coverArtId: String = "",
	val createdAt: Long = 0L,
	val updatedAt: Long = 0L,
	val lastPlayedAt: Long = 0L
)

/**
 * The five recipes, matching the hub's `kind` vocabulary and Feishin's.
 *
 * String-valued rather than an enum on the wire, for the same reason
 * [SavedQueueSource] is: a newer client's kind must round-trip through an older one
 * unchanged rather than being coerced to a default. [fromWire] answers null for a
 * kind this build does not know, and the UI then shows the mix without offering to
 * play it — which is honest, and better than regenerating the wrong thing.
 */
object MixKind {
	/** Similar to a seed track/album/artist — Tier 1, works on vanilla Navidrome. */
	const val SIMILAR = "similar"
	/** AudioMuse `sonic_fingerprint`: built from listening habits, seedless. */
	const val FINGERPRINT = "fingerprint"
	/** AudioMuse `alchemy` steered by a [MoodCharacter], seedless. */
	const val ADAPTIVE = "adaptive"
	/** A genre, drawn locally from the library. */
	const val GENRE = "genre"
	/** One artist's own catalogue, shuffled. */
	const val ARTIST = "artist"

	val ALL = listOf(SIMILAR, FINGERPRINT, ADAPTIVE, GENRE, ARTIST)

	fun fromWire(kind: String): String? = kind.takeIf { it in ALL }

	/** Whether this recipe needs a [Mix.seedId] to mean anything. */
	fun needsSeed(kind: String): Boolean = kind == SIMILAR || kind == ARTIST || kind == GENRE
}
