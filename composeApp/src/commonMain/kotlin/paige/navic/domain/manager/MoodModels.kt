package paige.navic.domain.manager

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlinx.serialization.Serializable
import paige.navic.data.database.entities.SavedQueueEntity
import kotlin.random.Random

/*
 * navi-connect P7: Home's moods, as plain functions (`MoodModelsTest`). The catalogue is the hub's
 * (PROTOCOL §18.5, `GET /sonic/mood/catalogue`); the draw is Navic's, because the library's durations
 * and what it holds are here and not on the hub. "Mood" already names three older things in this
 * tree (the `MoodCharacter` presets, now `StationCharacter`; the free-text Mood search; AudioMuse's
 * Mood Flow): nothing here touches them, and every name in this file says `Mood…` + a noun of its own.
 */

/** One Home tile of a mood: a single CLAP prompt on the hub. [hue] is two hues for its art. */
@Immutable
@Serializable
data class MoodVariant(val id: String, val title: String, val hue: List<Int> = emptyList())

/**
 * One mood of the catalogue. [group] is "home" (a chip on Home, four [variants]) or "extra" (the
 * queue's chips and the radio's filter only). [maxSeconds] is the owner's length rule for a Home mix,
 * null = no limit; [depth] how far into a tile's list a mix draws from.
 */
@Immutable
@Serializable
data class MoodEntry(
	val id: String,
	val label: String,
	val group: String = MOOD_GROUP_HOME,
	val filter: Boolean = false,
	val maxSeconds: Int? = null,
	val depth: Int = MOOD_DEPTH_DEFAULT,
	val variants: List<MoodVariant> = emptyList()
)

/**
 * The hub's catalogue. [configured]: the hub has AudioMuse; [available]: AudioMuse answers and its
 * CLAP index is loaded; [ready]: the merged lists the queue's chips need are built (a tile needs none).
 */
@Immutable
@Serializable
data class MoodCatalogue(
	val configured: Boolean = false,
	val available: Boolean = false,
	val ready: Boolean = false,
	val version: String = "",
	val moods: List<MoodEntry> = emptyList()
) {
	companion object {
		/** No hub, an older hub, or nothing read yet: no chip anywhere. */
		val NONE = MoodCatalogue()
	}
}

const val MOOD_GROUP_HOME = "home"
const val MOOD_GROUP_EXTRA = "extra"
const val MOOD_DEPTH_DEFAULT = 300

/** Home's chips: the hub's order, its home group only. */
fun MoodCatalogue.homeMoods(): List<MoodEntry> = moods.filter { it.group == MOOD_GROUP_HOME }

/** Whether Home draws its chip row: AudioMuse is there and the catalogue has a Home mood. */
fun MoodCatalogue.chipsVisible(): Boolean = available && homeMoods().isNotEmpty()

fun MoodCatalogue.find(id: String): MoodEntry? = moods.firstOrNull { it.id == id }

// --------------------------------------------------------------------------------------------- //
// The draw
// --------------------------------------------------------------------------------------------- //

/** A song of the library as the draw needs it: its length and who it is by (Navidrome's artist id). */
data class OwnedSong(val id: String, val durationSec: Int, val artistKey: String)

/** A mood mix is about this many songs (the tile's "~50 songs"; the page says the real number). */
const val MOOD_MIX_SIZE = 50

/** Fewer songs than this in the library for a tile is Short, never a short mix presented as a mix. */
const val MOOD_MIX_MIN = 20

/** No artist twice within this many songs of a mix, where the songs drawn allow it. */
const val MOOD_ARTIST_GAP = 4

/**
 * The songs of the library a tile can draw from: its first [depth] ids that the library holds,
 * within [maxSeconds] (inclusive; null = any length), each once, in the hub's rank order.
 */
fun moodEligible(ids: List<String>, owned: Map<String, OwnedSong>, maxSeconds: Int?, depth: Int): List<OwnedSong> {
	val seen = HashSet<String>()
	return ids.asSequence()
		.take(depth)
		.filter { seen.add(it) }
		.mapNotNull { owned[it] }
		.filter { maxSeconds == null || it.durationSec <= maxSeconds }
		.toList()
}

/**
 * A mood mix (P7 call 2: every opening is a new draw): [size] songs drawn uniformly without
 * replacement from [moodEligible], then ordered so no artist comes back within [MOOD_ARTIST_GAP]
 * songs where the draw allows it (the artist with the most songs left goes first among those
 * allowed, which never strands one artist at the end when a spacing exists). Fewer eligible songs
 * than [size] gives all of them, never padding.
 */
fun drawMix(
	ids: List<String>,
	owned: Map<String, OwnedSong>,
	maxSeconds: Int?,
	depth: Int,
	random: Random,
	size: Int = MOOD_MIX_SIZE
): List<String> {
	val drawn = moodEligible(ids, owned, maxSeconds, depth).shuffled(random).take(size)
	return spaceArtists(drawn, MOOD_ARTIST_GAP).map { it.id }
}

internal fun spaceArtists(songs: List<OwnedSong>, gap: Int): List<OwnedSong> {
	val left = songs.toMutableList()
	val out = ArrayList<OwnedSong>(songs.size)
	while (left.isNotEmpty()) {
		val recent = out.takeLast(gap - 1).map { it.artistKey }.filter { it.isNotEmpty() }.toSet()
		val counts = left.groupingBy { it.artistKey }.eachCount()
		val allowed = left.filter { it.artistKey.isEmpty() || it.artistKey !in recent }
		val next = allowed.maxByOrNull { if (it.artistKey.isEmpty()) 0 else counts.getValue(it.artistKey) }
			?: left.first()
		left.remove(next)
		out.add(next)
	}
	return out
}

/** What a mood mix page shows. */
sealed interface MixState {
	data object Loading : MixState
	data class Ready(val ids: List<String>) : MixState
	/** The library holds [found] of the tile's songs, under [MOOD_MIX_MIN]: said, with Retry. */
	data class Short(val found: Int) : MixState
	/** The hub could not search (AudioMuse down, CLAP unloaded, the hub unreachable): Retry. */
	data object Unavailable : MixState
}

/** The page's state from its read: [drawn] null while it runs, [eligible] the songs it could draw from. */
fun mixState(drawn: List<String>?, eligible: Int, failed: Boolean): MixState = when {
	failed -> MixState.Unavailable
	drawn == null -> MixState.Loading
	eligible < MOOD_MIX_MIN -> MixState.Short(eligible)
	else -> MixState.Ready(drawn)
}

// --------------------------------------------------------------------------------------------- //
// Tile art, the saved queues, Home's selection
// --------------------------------------------------------------------------------------------- //

/** A tile's art: a gradient between two colours a white title reads on at 4.5:1 or better. */
data class TileColors(val top: Color, val bottom: Color)

/** The contrast a white title keeps on a tile's colours (WCAG AA for normal text). */
const val TILE_TITLE_CONTRAST = 4.5f

/**
 * A tile's two colours from its hub [MoodVariant.hue] pair, or, with none, a pair derived from its
 * id (stable, different for different ids). Each is the hue at a fixed saturation, made darker until
 * white reads on it at [TILE_TITLE_CONTRAST] with a margin: artwork, not theme, and the same on
 * every theme.
 */
fun tileColors(variant: MoodVariant): TileColors {
	val (a, b) = if (variant.hue.size >= 2) variant.hue[0] to variant.hue[1]
	else {
		val h = idHue(variant.id)
		h to (h + 40) % 360
	}
	return TileColors(top = darkEnough(a, 0.62f, 0.46f), bottom = darkEnough(b, 0.58f, 0.36f))
}

private fun idHue(id: String): Int {
	var h = 0x811C9DC5.toInt()
	for (c in id) {
		h = h xor c.code
		h *= 0x01000193
	}
	return ((h % 360) + 360) % 360
}

private fun darkEnough(hue: Int, saturation: Float, lightness: Float): Color {
	var l = lightness
	val h = (((hue % 360) + 360) % 360).toFloat()
	while (true) {
		val c = Color.hsl(h, saturation, l)
		if (whiteContrast(c) >= TILE_TITLE_CONTRAST + 0.2f || l <= 0.05f) return c
		l -= 0.01f
	}
}

fun whiteContrast(c: Color): Float = 1.05f / (c.luminance() + 0.05f)

/**
 * "Saved queues from these mixes": a queue a mood tile played, matched on the name the tile saved it
 * under (`GeneratedQueue.ofMoodTile`, which `SavedQueueRepository` stores as `sourceName`). A queue
 * the user renamed ([SavedQueueEntity.name] set) is theirs now and drops out (P7 call 10); it stays in
 * Continue listening. Newest first, at most [limit].
 */
fun queuesFromMood(queues: List<SavedQueueEntity>, mood: MoodEntry, limit: Int = 10): List<SavedQueueEntity> {
	val titles = mood.variants.map { it.title }.toSet()
	return queues.asSequence()
		.filter { it.name == null && it.sourceName != null && it.sourceName in titles }
		.sortedByDescending { it.updatedAt }
		.take(limit)
		.toList()
}

/** Home's selected mood chip. Not remembered across a restart (P7 call 9). */
data class HomeMoodSelection(val moodId: String? = null)

/** Tapping the selected chip clears it; any other selects it. */
fun HomeMoodSelection.tap(id: String): HomeMoodSelection =
	if (moodId == id) HomeMoodSelection(null) else HomeMoodSelection(id)

fun HomeMoodSelection.clear(): HomeMoodSelection = HomeMoodSelection(null)
