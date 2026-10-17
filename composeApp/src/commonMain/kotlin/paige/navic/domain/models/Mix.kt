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
 * **The hub stores and broadcasts these.** Each client regenerates locally through the
 * engine it has ([RadioManager] here, Feishin's `auto-dj` package), except that since
 * 2026-10-16 (PROTOCOL §18) the radio kinds play through the hub's station engine when it
 * has one: [MixKind.STATION], and `similar` / `fingerprint` / `adaptive` / `artist` with it.
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
	 * name for [MixKind.GENRE] (an older Feishin wrote the genre's id here and the name in
	 * [seedName]: [genreSeedNames]), and nothing at all for the seedless kinds, which
	 * seed from listening history rather than from a thing.
	 */
	val seedId: String = "",
	/** What to call the seed in the UI, so a recipe reads as a sentence offline. */
	val seedName: String = "",
	/**
	 * The old preset's name (`EchoMatch|SteadyVibes|TransitionMaestro`), which a client from
	 * before stations reads for ADAPTIVE. Written beside [character] (PROTOCOL §17.1).
	 */
	val moodCharacter: String = "",
	/**
	 * `close|balanced|explore`, a [paige.navic.domain.models.settings.StationCharacter]'s wire
	 * id: the character a STATION, ARTIST (or ADAPTIVE) recipe plays with. "" when the record
	 * has none, as a GENRE recipe never does.
	 */
	val character: String = "",
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
	/** AudioMuse `alchemy` steered by a character, seedless. With the station engine: Your mix. */
	const val ADAPTIVE = "adaptive"
	/** A genre, drawn locally from the library. */
	const val GENRE = "genre"
	/**
	 * More like one artist: `seedId` an artist id, `seedName` its name, and `character`. Played
	 * as an artist radio (B-150), exactly the artist page's: a station from up to five of their
	 * songs with the engine, Navidrome's similar songs without it. It used to shuffle the
	 * artist's own songs.
	 */
	const val ARTIST = "artist"
	/**
	 * A radio (PROTOCOL §17.1, §18): `seedId` a track, or empty for Your mix, and `character`.
	 * Played as a station; without the engine, as the seed's similar songs (or the fingerprint).
	 */
	const val STATION = "station"

	val ALL = listOf(SIMILAR, FINGERPRINT, ADAPTIVE, GENRE, ARTIST, STATION)

	fun fromWire(kind: String): String? = kind.takeIf { it in ALL }

	/** Whether this recipe needs a [Mix.seedId] to mean anything. */
	fun needsSeed(kind: String): Boolean = kind == SIMILAR || kind == ARTIST || kind == GENRE

	/** The kinds that play as a station when the hub's engine is there (PROTOCOL §17.1, §18). */
	val PLAYS_AS_STATION = setOf(STATION, SIMILAR, FINGERPRINT, ADAPTIVE, ARTIST)

	/** Whether a [kind] recipe plays as a station now: with the engine, all but `genre`. */
	fun playsAsStation(kind: String, engine: Boolean): Boolean = engine && kind in PLAYS_AS_STATION
}

/**
 * The names a `genre` recipe may hold its genre under, in the order to try them: `seedId`, which
 * is the name (what both clients write since B-150), then `seedName`, for an older
 * Feishin recipe whose `seedId` was the genre's Navidrome id. A genre is drawn by name.
 */
fun genreSeedNames(mix: Mix): List<String> =
	listOf(mix.seedId, mix.seedName).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
