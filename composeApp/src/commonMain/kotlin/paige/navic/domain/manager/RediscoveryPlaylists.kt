package paige.navic.domain.manager

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * The rediscovery set: four server-side smart playlists that surface music
 * already in the library and rarely or never played.
 *
 * Nothing in this stack does that today — there is no never-played shelf, no
 * deep cuts, no stale favourites, and every "discovery" surface points outward
 * at things you do not own.
 *
 * **Server-side on purpose.** These are Navidrome native smart playlists
 * (`POST /api/playlist` with `rules` criteria), so Navidrome keeps them current
 * by itself and *every* Subsonic client sees them — this one, Feishin, and
 * anything else pointed at the same server. There is no polling, no
 * client-side computation and no second source of truth.
 *
 * They are opt-in and created once. Minting playlists in someone's library on
 * first launch would be a surprising thing for a music player to do, and the
 * creation is keyed on the name so running it twice is a no-op rather than a
 * duplicate.
 */
object RediscoveryPlaylists {

	/** Prefix on every playlist this creates, so a shelf can filter on it and a
	 *  user can see at a glance which playlists are ours. */
	const val PREFIX = "Rediscover: "

	data class Definition(
		val name: String,
		val description: String,
		val rules: JsonObject
	)

	/**
	 * `notInTheLast` takes days. 90 for "never played" is the deliberate part:
	 * without it the playlist fills with everything imported this morning, which
	 * is not rediscovery — it is the Recently Added list with extra steps.
	 */
	val definitions: List<Definition> = listOf(
		Definition(
			name = "${PREFIX}Never played",
			description = "In the library for months and never once played",
			rules = rules(
				sort = "random",
				limit = 500,
				conditions = listOf(
					numberIs("playcount", 0),
					dateNotInTheLast("dateadded", 90)
				)
			)
		),
		Definition(
			name = "${PREFIX}Loved but stale",
			description = "Favourites you haven't played in a year",
			rules = rules(
				sort = "lastplayed",
				limit = 500,
				conditions = listOf(
					boolIs("loved", true),
					dateNotInTheLast("lastplayed", 365)
				)
			)
		),
		Definition(
			name = "${PREFIX}Highly rated, long unplayed",
			description = "Rated 4 or 5, untouched for a year",
			rules = rules(
				sort = "rating",
				descending = true,
				limit = 500,
				conditions = listOf(
					numberGt("rating", 3),
					dateNotInTheLast("lastplayed", 365)
				)
			)
		),
		// "Deep cuts" as Navidrome's criteria can express it: tracks you have
		// never played that are not new arrivals. A true deep cut is a zero-play
		// track on an album you *otherwise* play, which needs a per-album join
		// Navidrome's rule grammar has no way to state — so this is the honest
		// approximation, kept distinct by favouring records you have rated.
		Definition(
			name = "${PREFIX}Deep cuts",
			description = "Unplayed tracks from records you thought enough of to rate",
			rules = rules(
				sort = "random",
				limit = 300,
				conditions = listOf(
					numberIs("playcount", 0),
					numberGt("rating", 0),
					dateNotInTheLast("dateadded", 30)
				)
			)
		)
	)

	private fun rules(
		conditions: List<JsonObject>,
		sort: String = "",
		descending: Boolean = false,
		limit: Int? = null
	): JsonObject = buildJsonObject {
		putJsonArray("all") {
			conditions.forEach { add(it) }
		}
		if (sort.isNotEmpty()) put("sort", sort)
		put("order", if (descending) "desc" else "asc")
		limit?.let { put("limit", it) }
	}

	private fun numberIs(field: String, value: Long) = buildJsonObject {
		putJsonObject("is") { put(field, value) }
	}

	private fun numberGt(field: String, value: Long) = buildJsonObject {
		putJsonObject("gt") { put(field, value) }
	}

	private fun boolIs(field: String, value: Boolean) = buildJsonObject {
		putJsonObject("is") { put(field, value) }
	}

	private fun dateNotInTheLast(field: String, days: Long) = buildJsonObject {
		putJsonObject("notInTheLast") { put(field, days) }
	}
}

/** Outcome of one [createRediscoveryPlaylists] run. */
data class RediscoveryResult(
	val created: Int,
	val skipped: Int,
	val failed: List<String>
)

/**
 * Create whichever of [RediscoveryPlaylists.definitions] the server does not
 * already have, by name.
 *
 * Idempotent by construction: an existing name is skipped rather than
 * recreated, so running this twice leaves one copy of each. [existingNames] is
 * the caller's current playlist list (Room's, which is Navidrome's).
 */
suspend fun createRediscoveryPlaylists(
	nativeApi: NativeApiManager,
	existingNames: Set<String>
): RediscoveryResult {
	var created = 0
	var skipped = 0
	val failed = mutableListOf<String>()
	RediscoveryPlaylists.definitions.forEach { definition ->
		if (definition.name in existingNames) {
			skipped++
			return@forEach
		}
		nativeApi.createSmartPlaylist(
			name = definition.name,
			comment = definition.description,
			isPublic = false,
			rules = definition.rules
		).onSuccess { created++ }
			.onFailure { failed += definition.name }
	}
	return RediscoveryResult(created = created, skipped = skipped, failed = failed)
}
