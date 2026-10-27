package paige.navic.ui.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Every cover's two seed colours ([CoverSeeds]) the app has worked out, kept across restarts and
 * read synchronously in composition, so a cover seen before is coloured in the first frame of the
 * page that wears it (I-043). It replaces an in-memory cache of whole `Palette`s that died with the
 * process: a cold start fetched and quantised the playing cover again, and the page sat on the
 * app's own scheme for those seconds before flipping to the wash.
 *
 * Keyed by what `rememberCoverColorScheme` keys by: a Navidrome cover id, or an absolute URL for
 * art with no id. Least recently used leaves at [cap]. A [put] schedules ONE write [writeDelayMs]
 * out (a later put inside the window joins it), so browsing a grid does not rewrite the preference
 * per tile. Main-thread only, like the caches it replaces: [scope] is a main-dispatcher scope.
 */
class CoverSeedStore(
	private val load: () -> String,
	private val save: (String) -> Unit,
	private val scope: CoroutineScope,
	private val cap: Int = 512,
	private val writeDelayMs: Long = 2_000
) {
	// Insertion-ordered (a common `mutableMapOf` is a LinkedHashMap), so the first key is the least
	// recently used one: a read takes its entry out and puts it back at the end. The JVM's
	// access-order constructor is not in common.
	private val entries: MutableMap<String, CoverSeeds> by lazy {
		mutableMapOf<String, CoverSeeds>().apply { CoverSeedCodec.decode(load()).forEach { (k, v) -> put(k, v) } }
	}
	private var pendingWrite: Job? = null

	operator fun get(key: String): CoverSeeds? {
		val seeds = entries.remove(key) ?: return null
		entries[key] = seeds
		return seeds
	}

	fun contains(key: String): Boolean = key in entries

	/** The playing cover's key: never the one a [put] over the cap evicts (audit L4a-08). */
	private var playingKey: String? = null

	/** Marks [key] as the playing cover (null: none), and refreshes its recency. */
	fun keepPlaying(key: String?) {
		playingKey = key
		key?.let { get(it) }
	}

	fun put(key: String, seeds: CoverSeeds) {
		entries.remove(key)
		entries[key] = seeds
		while (entries.size > cap) {
			val oldest = entries.keys.firstOrNull { it != playingKey } ?: break
			entries.remove(oldest)
		}
		if (pendingWrite?.isActive == true) return
		pendingWrite = scope.launch {
			delay(writeDelayMs)
			write()
		}
	}

	/** Writes the current contents now (and drops a write still waiting). */
	fun flush() {
		pendingWrite?.cancel()
		pendingWrite = null
		write()
	}

	private fun write() {
		save(CoverSeedCodec.encode(entries.toList()))
	}
}

/**
 * The store's persisted form: `{"v":<VERSION>,"e":[{"k":"<key>","d":<int>,"s":<int?>,"a":<int?>}]}`, oldest
 * entry first. Reading never throws: a blank, malformed or foreign blob is an empty store.
 */
internal object CoverSeedCodec {
	/**
	 * Bump whenever the rules in [deriveCoverSeeds] change: colours stored under the old rules
	 * are then discarded at the next start instead of being worn, and are worked out again.
	 */
	const val VERSION: Int = 2

	@Serializable
	private data class Blob(val v: Int, val e: List<Entry>)

	@Serializable
	private data class Entry(val k: String, val d: Int, val s: Int? = null, val a: Int? = null)

	private val json = Json { ignoreUnknownKeys = true }

	fun encode(entries: List<Pair<String, CoverSeeds>>): String =
		json.encodeToString(Blob.serializer(), Blob(VERSION, entries.map { (k, v) -> Entry(k, v.dominant, v.vivid, v.accent) }))

	fun decode(raw: String): List<Pair<String, CoverSeeds>> {
		if (raw.isBlank()) return emptyList()
		val blob = runCatching { json.decodeFromString(Blob.serializer(), raw) }.getOrNull() ?: return emptyList()
		if (blob.v != VERSION) return emptyList()
		return blob.e.map { it.k to CoverSeeds(dominant = it.d, vivid = it.s, accent = it.a) }
	}
}
