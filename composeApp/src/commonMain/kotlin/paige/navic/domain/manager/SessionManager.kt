package paige.navic.domain.manager

import com.russhwolf.settings.Settings
import com.russhwolf.settings.set
import dev.zt64.subsonic.api.model.Role
import dev.zt64.subsonic.api.model.User
import dev.zt64.subsonic.client.SubsonicAuth
import dev.zt64.subsonic.client.SubsonicClient
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import paige.navic.util.Logger
import paige.navic.util.installSubsonicResponseRepair
import okio.ByteString.Companion.encodeUtf8
import kotlin.random.Random

class SessionManager(
	private val settings: Settings,
	private val preferenceManager: PreferenceManager
) {
	private val _isLoggedIn = MutableStateFlow(false)
	val isLoggedIn: StateFlow<Boolean> = _isLoggedIn.asStateFlow()

	private var currentUser: User? = null
	private val mutex = Mutex()
	private val scope = CoroutineScope(Dispatchers.IO)

	var api: SubsonicClient = createClient(
		instanceUrl = settings.getString("instanceUrl", ""),
		username = settings.getString("username", ""),
		password = settings.getString("password", ""),
	)
		private set

	init {
		_isLoggedIn.value = settings.getStringOrNull("username") != null
		if (_isLoggedIn.value) getCachedUser()
	}

	private fun createClient(
		instanceUrl: String,
		username: String,
		password: String,
	) = SubsonicClient.Companion(
		baseUrl = instanceUrl,
		auth = SubsonicAuth.Token(
			username = username,
			password = password,
		),
		client = "Navic",
		clientConfig = {
			// Before anything parses: one album without cover art used to fail every library sync.
			installSubsonicResponseRepair()

			install(UserAgent) {
				agent = "Navic"
			}

			// OkHttp's default 10s read timeout aborts library syncs when the
			// server is momentarily slow (e.g. mid-scan behind a tunnel).
			install(HttpTimeout) {
				connectTimeoutMillis = 15_000
				requestTimeoutMillis = 120_000
				socketTimeoutMillis = 60_000
			}

			val customHeaders = preferenceManager.customHeadersMap()
			if (customHeaders.isNotEmpty()) {
				defaultRequest {
					customHeaders.forEach { (key, value) -> header(key, value) }
				}
			}
		}
	)

	suspend fun login(
		instanceUrl: String,
		username: String,
		password: String
	) {
		val client = createClient(instanceUrl, username, password)

		try {
			client.ping()
			fetchCurrentUser(username, client)
		} catch (e: Exception) {
			// TODO: custom exception instead of the generic "Exception"
			throw Exception(
				"Failed to connect to the instance. Please check your credentials and try again.",
				e
			)
		}

		settings["instanceUrl"] = instanceUrl
		settings["username"] = username
		settings["password"] = password

		api = client
		_isLoggedIn.value = true
	}

	fun logout() {
		settings["username"] = null
		settings["password"] = null
		_isLoggedIn.value = false
		currentUser = null
	}

	fun refreshClient() {
		api = createClient(
			instanceUrl = settings.getString("instanceUrl", ""),
			username = settings.getString("username", ""),
			password = settings.getString("password", ""),
		)
	}

	/**
	 * [size] overrides the user's cover-art quality for callers that want a specific pixel size.
	 * It is a PARAMETER rather than something a caller appends, because the URL this returns
	 * already carries a `size`: `"$url&size=128"` produced `…&size=4096&size=128`, and Subsonic
	 * reads the first, so the palette extractor silently downloaded a 4096px image for every
	 * cover it quantised.
	 */
	/**
	 * A cover's URL from its Navidrome id — **or the URL itself, unchanged, when the
	 * "id" already is one.**
	 *
	 * That passthrough is what makes an external track's artwork work everywhere at
	 * once. A preview (`ext:`) has no Navidrome cover id and carries an absolute
	 * image URL in `coverArtId` instead; signing it as a Subsonic request would
	 * produce a URL to this server for a cover it has never heard of. Every cover
	 * surface funnels through here — `CoverArt`, the media-notification artwork, the
	 * palette fetch behind the colour engine — so one rule covers all of them and
	 * none of them needs to know previews exist.
	 *
	 * [size] is deliberately dropped for an absolute URL: it is a Subsonic parameter,
	 * and appending it to somebody else's URL is the `&size=4096&size=128` bug the
	 * colour engine already paid for once.
	 */
	fun getCoverArtUrl(coverArtId: String, size: Int? = null): String =
		if (coverArtId.startsWith("http://") || coverArtId.startsWith("https://")) {
			coverArtId
		} else {
			api.getCoverArtUrl(
				coverArtId,
				auth = true,
				size = "${size ?: preferenceManager.coverArtQuality.value}"
			)
		}

	/**
	 * ALBUM artists via Subsonic `getArtists` (the canonical album-artist list,
	 * matching Feishin's "Album Artists"). search3 returns every track/featured
	 * artist too (a 7k mess), and the bundled library's getArtists deserializer
	 * silently drops index groups — so this is a raw, lenient parse of all
	 * `index[].artist[]` entries.
	 */
	suspend fun fetchAlbumArtists(): List<RawArtist> {
		val base = settings.getString("instanceUrl", "").trimEnd('/')
		val username = settings.getString("username", "")
		val password = settings.getString("password", "")

		val salt = Random.nextBytes(12)
			.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
		val token = "$password$salt".encodeUtf8().md5().hex()

		val httpClient = HttpClient {
			install(ContentNegotiation) {
				json(Json {
					ignoreUnknownKeys = true
					isLenient = true
					coerceInputValues = true
				})
			}
			install(UserAgent) { agent = "Navic" }
		}

		return try {
			val envelope: SubsonicArtistsEnvelope =
				httpClient.get("$base/rest/getArtists.view") {
					preferenceManager.customHeadersMap().forEach { (key, value) ->
						header(key, value)
					}
					parameter("u", username)
					parameter("t", token)
					parameter("s", salt)
					parameter("v", "1.16.1")
					parameter("c", "Navic")
					parameter("f", "json")
				}.body()
			envelope.response.artists?.index?.flatMap { it.artist } ?: emptyList()
		} finally {
			httpClient.close()
		}
	}

	/**
	 * Subsonic `getArtistInfo2` — biography, Last.fm link and similar artists, for
	 * an **ID3** artist id.
	 *
	 * Hand-rolled rather than taken from the bundled `dev.zt64.subsonic` client,
	 * for two reasons that both bite silently:
	 *
	 *  - That client's `getArtistInfo` is the *non-ID3* endpoint, and it was being
	 *    called with ID3 ids. Navidrome answers, so nothing errors — it just
	 *    answers about the wrong artist, or about nothing.
	 *  - Its `getArtistInfoID3` is not a fix: it issues `getArtistInfo` too (the
	 *    endpoint name is hard-coded in `SubsonicApiImpl`), so switching to it
	 *    changes the call site and nothing else.
	 *
	 * The ID3 endpoint also returns full `similarArtist[]` entries rather than
	 * bare ids, which is what has limited Navic's similar artists to the ones that
	 * happen to be in the library.
	 *
	 * Lenient parse throughout: `biography` is HTML from whichever agent Navidrome
	 * has configured, and any field may be absent.
	 */
	suspend fun fetchArtistInfo2(artistId: String, maxSimilar: Int = 20): ArtistInfo2Dto? {
		val base = settings.getString("instanceUrl", "").trimEnd('/')
		val username = settings.getString("username", "")
		val password = settings.getString("password", "")

		val salt = Random.nextBytes(12)
			.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
		val token = "$password$salt".encodeUtf8().md5().hex()

		val httpClient = HttpClient {
			install(ContentNegotiation) {
				json(Json {
					ignoreUnknownKeys = true
					isLenient = true
					coerceInputValues = true
				})
			}
			install(UserAgent) { agent = "Navic" }
		}

		return try {
			val envelope: SubsonicArtistInfo2Envelope =
				httpClient.get("$base/rest/getArtistInfo2.view") {
					preferenceManager.customHeadersMap().forEach { (key, value) ->
						header(key, value)
					}
					parameter("u", username)
					parameter("t", token)
					parameter("s", salt)
					parameter("v", "1.16.1")
					parameter("c", "Navic")
					parameter("f", "json")
					parameter("id", artistId)
					parameter("count", maxSimilar)
				}.body()
			envelope.response.artistInfo2
		} finally {
			httpClient.close()
		}
	}

	/**
	 * OpenSubsonic `getSimilarSongs2` — ids of songs similar to a given
	 * song/album/artist id. Vanilla Navidrome serves a heuristic mix; with the
	 * AudioMuse-AI plugin installed the SAME endpoint returns sonic
	 * similarity, so this is also the AudioMuse integration point.
	 */
	suspend fun fetchSimilarSongIds(id: String, count: Int = 50): List<String> {
		val base = settings.getString("instanceUrl", "").trimEnd('/')
		val username = settings.getString("username", "")
		val password = settings.getString("password", "")

		val salt = Random.nextBytes(12)
			.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
		val token = "$password$salt".encodeUtf8().md5().hex()

		val httpClient = HttpClient {
			install(ContentNegotiation) {
				json(Json {
					ignoreUnknownKeys = true
					isLenient = true
					coerceInputValues = true
				})
			}
			install(UserAgent) { agent = "Navic" }
		}

		return try {
			val envelope: SubsonicSimilarEnvelope =
				httpClient.get("$base/rest/getSimilarSongs2.view") {
					preferenceManager.customHeadersMap().forEach { (key, value) ->
						header(key, value)
					}
					parameter("u", username)
					parameter("t", token)
					parameter("s", salt)
					parameter("v", "1.16.1")
					parameter("c", "Navic")
					parameter("f", "json")
					parameter("id", id)
					parameter("count", count)
				}.body()
			envelope.response.similarSongs2?.song?.map { it.id } ?: emptyList()
		} finally {
			httpClient.close()
		}
	}

	/**
	 * OpenSubsonic `getOpenSubsonicExtensions` — the names of the extensions the
	 * server advertises. Navidrome lists `sonicSimilarity` ONLY when a sonic
	 * similarity plugin (AudioMuse) is loaded, so this is the capability probe
	 * for [fetchSonicSimilarTrackIds] / [findSonicPathIds].
	 */
	suspend fun fetchOpenSubsonicExtensions(): List<String> {
		val base = settings.getString("instanceUrl", "").trimEnd('/')
		val username = settings.getString("username", "")
		val password = settings.getString("password", "")

		val salt = Random.nextBytes(12)
			.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
		val token = "$password$salt".encodeUtf8().md5().hex()

		val httpClient = HttpClient {
			install(ContentNegotiation) {
				json(Json {
					ignoreUnknownKeys = true
					isLenient = true
					coerceInputValues = true
				})
			}
			install(UserAgent) { agent = "Navic" }
		}

		return try {
			val envelope: SubsonicExtensionsEnvelope =
				httpClient.get("$base/rest/getOpenSubsonicExtensions.view") {
					preferenceManager.customHeadersMap().forEach { (key, value) ->
						header(key, value)
					}
					parameter("u", username)
					parameter("t", token)
					parameter("s", salt)
					parameter("v", "1.16.1")
					parameter("c", "Navic")
					parameter("f", "json")
				}.body()
			envelope.response.openSubsonicExtensions.map { it.name }
		} finally {
			httpClient.close()
		}
	}

	/**
	 * OpenSubsonic `getSonicSimilarTracks` — ids of sonically similar tracks,
	 * ordered by similarity. Unlike `getSimilarSongs2` (which Navidrome can serve
	 * heuristically via agents), this ALWAYS routes through the sonic plugin, so
	 * it's guaranteed AudioMuse when present. The server 404s when no plugin is
	 * loaded; guard with [fetchOpenSubsonicExtensions].
	 */
	suspend fun fetchSonicSimilarTrackIds(id: String, count: Int = 50): List<String> {
		val base = settings.getString("instanceUrl", "").trimEnd('/')
		val username = settings.getString("username", "")
		val password = settings.getString("password", "")

		val salt = Random.nextBytes(12)
			.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
		val token = "$password$salt".encodeUtf8().md5().hex()

		val httpClient = HttpClient {
			install(ContentNegotiation) {
				json(Json {
					ignoreUnknownKeys = true
					isLenient = true
					coerceInputValues = true
				})
			}
			install(UserAgent) { agent = "Navic" }
		}

		return try {
			val envelope: SubsonicSonicMatchEnvelope =
				httpClient.get("$base/rest/getSonicSimilarTracks.view") {
					preferenceManager.customHeadersMap().forEach { (key, value) ->
						header(key, value)
					}
					parameter("u", username)
					parameter("t", token)
					parameter("s", salt)
					parameter("v", "1.16.1")
					parameter("c", "Navic")
					parameter("f", "json")
					parameter("id", id)
					parameter("count", count)
				}.body()
			envelope.response.sonicMatch.map { it.entry.id }
		} finally {
			httpClient.close()
		}
	}

	/**
	 * OpenSubsonic `findSonicPath` — a sonic "journey": the ordered tracks that
	 * bridge [startId] and [endId] in audio-feature space (AudioMuse plugin).
	 * 404s without the plugin; guard with [fetchOpenSubsonicExtensions].
	 */
	suspend fun findSonicPathIds(startId: String, endId: String, count: Int = 25): List<String> {
		val base = settings.getString("instanceUrl", "").trimEnd('/')
		val username = settings.getString("username", "")
		val password = settings.getString("password", "")

		val salt = Random.nextBytes(12)
			.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
		val token = "$password$salt".encodeUtf8().md5().hex()

		val httpClient = HttpClient {
			install(ContentNegotiation) {
				json(Json {
					ignoreUnknownKeys = true
					isLenient = true
					coerceInputValues = true
				})
			}
			install(UserAgent) { agent = "Navic" }
		}

		return try {
			val envelope: SubsonicSonicMatchEnvelope =
				httpClient.get("$base/rest/findSonicPath.view") {
					preferenceManager.customHeadersMap().forEach { (key, value) ->
						header(key, value)
					}
					parameter("u", username)
					parameter("t", token)
					parameter("s", salt)
					parameter("v", "1.16.1")
					parameter("c", "Navic")
					parameter("f", "json")
					parameter("startSongId", startId)
					parameter("endSongId", endId)
					parameter("count", count)
				}.body()
			envelope.response.sonicMatch.map { it.entry.id }
		} finally {
			httpClient.close()
		}
	}

	/**
	 * Fetch ALL artists by paging Subsonic `search3` with an empty query.
	 *
	 * Why not the library's `getArtists()`: that endpoint returns nested
	 * index-groups (`<index><artist/>...`), and the bundled
	 * `subsonic-client` (beta) drops whole index groups while deserializing
	 * that shape — which is why only ~450 of several thousand artists showed
	 * up. `search3` returns a flat, pageable `artist` array (like `getAlbums`,
	 * which already works), so it sidesteps the broken path and returns the
	 * full set. Auth is the standard salted-token scheme; no credentials are
	 * placed in the URL beyond the one-way token.
	 */
	suspend fun fetchAllArtists(pageSize: Int = 500): List<RawArtist> {
		val base = settings.getString("instanceUrl", "").trimEnd('/')
		val username = settings.getString("username", "")
		val password = settings.getString("password", "")

		val salt = Random.nextBytes(12)
			.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
		val token = "$password$salt".encodeUtf8().md5().hex()
		val customHeaders = preferenceManager.customHeadersMap()

		val httpClient = HttpClient {
			install(ContentNegotiation) {
				json(Json {
					ignoreUnknownKeys = true
					isLenient = true
					coerceInputValues = true
				})
			}
			install(UserAgent) { agent = "Navic" }
		}

		return try {
			val all = mutableListOf<RawArtist>()
			var offset = 0
			while (true) {
				val envelope: SubsonicSearchEnvelope =
					httpClient.get("$base/rest/search3.view") {
						customHeaders.forEach { (key, value) -> header(key, value) }
						parameter("u", username)
						parameter("t", token)
						parameter("s", salt)
						parameter("v", "1.16.1")
						parameter("c", "Navic")
						parameter("f", "json")
						parameter("query", "")
						parameter("artistCount", pageSize)
						parameter("artistOffset", offset)
						parameter("albumCount", 0)
						parameter("songCount", 0)
					}.body()

				val batch = envelope.response.searchResult3?.artist ?: emptyList()
				all += batch
				if (batch.size < pageSize) break
				offset += pageSize
			}
			all
		} finally {
			httpClient.close()
		}
	}

	private suspend fun fetchCurrentUser(
		username: String = settings.getString("username", ""),
		client: SubsonicClient = api
	): User? {
		mutex.withLock {
			if (username.isNotBlank()) {
				currentUser = client.getUser(username)
				return currentUser
			}
		}

		// TODO: custom exception instead of the generic "Exception"
		throw Exception("Failed to get current user because the username is blank")
	}

	fun getCachedUser(): User? {
		if (currentUser != null) {
			return currentUser
		}
		scope.launch {
			try {
				fetchCurrentUser()
			} catch (e: Exception) {
				Logger.e("SessionManager", "Failed to fetch current user info", e)
			}
		}
		return currentUser
	}
}

fun User.hasRole(role: Role): Boolean {
	return this.roles.contains(role)
}

fun User.canShare(): Boolean {
	return this.hasRole(Role.SHARE)
}

fun SessionManager.canUserShare(): Boolean {
	return this.getCachedUser()?.canShare() ?: false
}

@Serializable
data class SubsonicSearchEnvelope(
	@SerialName("subsonic-response") val response: SubsonicSearchBody = SubsonicSearchBody()
)

@Serializable
data class SubsonicSearchBody(
	val status: String = "ok",
	val searchResult3: SearchResult3Dto? = null
)

@Serializable
data class SearchResult3Dto(
	val artist: List<RawArtist> = emptyList()
)

@Serializable
data class RawArtist(
	val id: String,
	val name: String = "",
	val albumCount: Int = 0,
	val coverArt: String? = null,
	val artistImageUrl: String? = null,
	val starred: String? = null,
	val userRating: Int? = null,
	val sortName: String? = null,
	val musicBrainzId: String? = null
)

@Serializable
data class SubsonicArtistInfo2Envelope(
	@SerialName("subsonic-response") val response: SubsonicArtistInfo2Body = SubsonicArtistInfo2Body()
)

@Serializable
data class SubsonicArtistInfo2Body(
	val status: String = "ok",
	val artistInfo2: ArtistInfo2Dto? = null
)

@Serializable
data class ArtistInfo2Dto(
	/** HTML, from whichever metadata agent Navidrome has configured. Strip it
	 *  before rendering — see `paige.navic.ui.util.stripHtml`. */
	val biography: String = "",
	val musicBrainzId: String = "",
	val lastFmUrl: String = "",
	val smallImageUrl: String = "",
	val mediumImageUrl: String = "",
	val largeImageUrl: String = "",
	/** Full entries, unlike the non-ID3 endpoint's bare ids — an artist with no
	 *  `id` is one the library does not have, which is useful rather than noise. */
	val similarArtist: List<SimilarArtistDto> = emptyList()
)

@Serializable
data class SimilarArtistDto(
	val id: String = "",
	val name: String = "",
	val albumCount: Int = 0,
	val musicBrainzId: String = ""
)

@Serializable
data class SubsonicSimilarEnvelope(
	@SerialName("subsonic-response") val response: SubsonicSimilarBody = SubsonicSimilarBody()
)

@Serializable
data class SubsonicSimilarBody(
	val status: String = "ok",
	val similarSongs2: SimilarSongs2Dto? = null
)

@Serializable
data class SimilarSongs2Dto(
	val song: List<RawSongId> = emptyList()
)

@Serializable
data class RawSongId(
	val id: String
)

@Serializable
data class SubsonicExtensionsEnvelope(
	@SerialName("subsonic-response") val response: SubsonicExtensionsBody = SubsonicExtensionsBody()
)

@Serializable
data class SubsonicExtensionsBody(
	val status: String = "ok",
	val openSubsonicExtensions: List<OpenSubsonicExtensionDto> = emptyList()
)

@Serializable
data class OpenSubsonicExtensionDto(
	val name: String = "",
	val versions: List<Int> = emptyList()
)

@Serializable
data class SubsonicSonicMatchEnvelope(
	@SerialName("subsonic-response") val response: SubsonicSonicMatchBody = SubsonicSonicMatchBody()
)

@Serializable
data class SubsonicSonicMatchBody(
	val status: String = "ok",
	val sonicMatch: List<SonicMatchDto> = emptyList()
)

@Serializable
data class SonicMatchDto(
	val entry: RawSongId = RawSongId(id = ""),
	val similarity: Double = 0.0
)

@Serializable
data class SubsonicArtistsEnvelope(
	@SerialName("subsonic-response") val response: SubsonicArtistsBody = SubsonicArtistsBody()
)

@Serializable
data class SubsonicArtistsBody(
	val status: String = "ok",
	val artists: ArtistsIndexDto? = null
)

@Serializable
data class ArtistsIndexDto(
	val index: List<ArtistIndexGroupDto> = emptyList()
)

@Serializable
data class ArtistIndexGroupDto(
	val name: String = "",
	val artist: List<RawArtist> = emptyList()
)
