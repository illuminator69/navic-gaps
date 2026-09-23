package paige.navic.domain.manager

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import paige.navic.domain.models.DomainExplicitStatus
import paige.navic.domain.models.DomainSong
import paige.navic.util.Logger

/**
 * Previews: hearing a track the library does not have.
 *
 * A **preview** is one track resolved by the sidecar from an external provider
 * (yt-dlp today) and handed back as an ordinary queue track carrying its own
 * absolute `streamUrl`. That shape is the whole design: the hub's saved-queue
 * sanitiser already whitelists `id, title, artist, album, durationMs, imageUrl,
 * streamUrl, mime`, so an `ext:` track survives publishing, saved-queue sync,
 * transfer and Continue Listening with **no protocol work at all**.
 *
 * **The hub does not stream.** It proxies only the control plane — `/preview/resolve`
 * and `/preview/status`, ordinary buffered JSON — and the audio comes straight from
 * the sidecar over plain HTTP with Range support. The reasoning lives in
 * navi-connect's `CLAUDE.md`; the short version is that the hub's HTTP proxy
 * terminates in a single buffered `bytes` body with a library-computed
 * `Content-Length` and a handshake deadline, all three of which a long media body
 * breaks.
 *
 * HUB ONLY, exactly like [LbBotManager] and for the same reason: the sidecar has no
 * authentication of its own beyond the capability signature on a stream URL, so the
 * hub's route whitelist is the gate. No sidecar address is ever stored on the device.
 *
 * FAIL-SOFT everywhere. No hub, no `PREVIEW_URL` upstream, an unreachable sidecar and
 * "this track has no preview" all resolve to *nothing happens* — never an error
 * dialog, never a row that looks broken.
 */
class PreviewManager(
	private val preferenceManager: PreferenceManager
) {
	private val json = Json {
		ignoreUnknownKeys = true
		isLenient = true
		coerceInputValues = true
		encodeDefaults = false
	}

	private val client by lazy {
		HttpClient {
			install(ContentNegotiation) { json(json) }
			install(UserAgent) { agent = "Navic" }
			// A resolve runs a provider search upstream, which is slow the first time
			// and instant afterwards (an `ext:` id is stable, so the sidecar caches
			// it). Generous, but bounded: this is a user-initiated tap, and the
			// alternative to giving up is a row that spins forever.
			install(HttpTimeout) {
				connectTimeoutMillis = 8_000
				requestTimeoutMillis = 30_000
				socketTimeoutMillis = 30_000
			}
		}
	}

	private fun hubBase(): String? = hubHttpBase(preferenceManager)

	/** Re-probe key for a `LaunchedEffect`; see [LbBotManager.routeSignature]. */
	val routeSignature: String
		get() = hubRouteSignature(preferenceManager)

	// ----- availability ------------------------------------------------------ //

	private val _status = MutableStateFlow(PreviewStatus())
	val status: StateFlow<PreviewStatus> = _status.asStateFlow()
	private var _statusAt = 0L

	/**
	 * Whether previews are usable at all, cached for the process rather than per
	 * composable — same reasoning as [LbBotManager.ensureAvailability], and the same
	 * TTL. Returns the answer because every caller deciding whether to offer a
	 * preview control needs it.
	 */
	suspend fun ensureAvailability(): Boolean {
		if (_statusAt != 0L && nowMs() - _statusAt < AVAILABILITY_TTL_MS) {
			return _status.value.usable
		}
		return probe()
	}

	suspend fun probe(): Boolean {
		val base = hubBase()
		if (base == null) {
			_status.value = PreviewStatus()
			_statusAt = 0L          // a non-answer is not worth caching
			return false
		}
		return try {
			val response = client.get("$base/preview/status") {
				header("Authorization", "Bearer ${preferenceManager.hubToken}")
			}
			if (!response.status.isSuccess()) {
				Logger.w("PreviewManager", "/preview/status -> HTTP ${response.status.value}")
				_status.value = PreviewStatus()
				_statusAt = 0L
				return false
			}
			val probe = response.body<PreviewStatus>()
			_status.value = probe
			_statusAt = nowMs()
			probe.usable
		} catch (e: Exception) {
			Logger.e("PreviewManager", "/preview/status failed", e)
			_status.value = PreviewStatus()
			_statusAt = 0L
			false
		}
	}

	/**
	 * Whether a preview in the queue may be handed to a Chromecast.
	 *
	 * False when the hub has no `PREVIEW_PUBLIC_URL`, in which case the stream URL is
	 * a LAN address the speaker cannot fetch. Decided here rather than discovered at
	 * the speaker: a cast that "works" and plays silence is the worst of the three
	 * possible outcomes, so the transfer is refused with a reason instead.
	 */
	val previewCastable: Boolean
		get() = _status.value.previewCastable

	// ----- resolve ----------------------------------------------------------- //

	/**
	 * A playable preview for [artist] / [title], or null.
	 *
	 * Null covers every kind of absence identically — not configured, unreachable,
	 * and the sidecar's own `{}` meaning "I could not find this one". That last is a
	 * legitimate 200 answer and never an error, the same rule lb-bot's `strict=False`
	 * metadata chain follows.
	 *
	 * [durationMs] is passed when known so the sidecar can reject a match of the
	 * wrong length — the classic way a search for a song returns an hour-long mix of
	 * it.
	 */
	suspend fun resolve(
		artist: String,
		title: String,
		album: String = "",
		durationMs: Long = 0L
	): PreviewTrack? {
		if (title.isBlank() && artist.isBlank()) return null
		val base = hubBase() ?: return null
		return try {
			val response = client.get("$base/preview/resolve") {
				header("Authorization", "Bearer ${preferenceManager.hubToken}")
				if (artist.isNotBlank()) parameter("artist", artist)
				if (title.isNotBlank()) parameter("title", title)
				if (album.isNotBlank()) parameter("album", album)
				if (durationMs > 0L) parameter("durationMs", durationMs.toString())
			}
			if (!response.status.isSuccess()) {
				Logger.w("PreviewManager", "/preview/resolve -> HTTP ${response.status.value}")
				return null
			}
			val track = response.body<PreviewTrack>()
			// `{}` decodes to an all-defaults object, which is what "no preview found"
			// looks like on the wire. Without a stream URL there is nothing to play,
			// so treat the two as one.
			if (track.id.isBlank() || track.streamUrl.isBlank()) null else track
		} catch (e: Exception) {
			Logger.e("PreviewManager", "/preview/resolve failed", e)
			null
		}
	}

	private fun nowMs(): Long = Clock.System.now().toEpochMilliseconds()

	companion object {
		/**
		 * The id namespace for an external, non-library track: `ext:<provider>:<id>`.
		 *
		 * Extends the `mb:` convention lb-bot already uses for artists and albums.
		 * **Nothing in this app may mint one** — only the sidecar does, and the format
		 * is frozen in navi-connect's `PROTOCOL.md`.
		 *
		 * Note the colon. Upstream already has an unrelated `ext-` prefix (see
		 * `TextUtils.buildSongInfoString`) for Subsonic's own "external" songs; the
		 * two namespaces do not collide, and neither one should be tested for with a
		 * bare `startsWith("ext")`.
		 */
		const val EXT_PREFIX = "ext:"

		fun isPreviewId(id: String): Boolean = id.startsWith(EXT_PREFIX)

		/** Matches [LbBotManager]'s, so the two layers go stale together. */
		private const val AVAILABILITY_TTL_MS = 60_000L
	}
}

/**
 * `/preview/status`, mirroring `/lb/status`'s shape so the two read the same way.
 *
 * [previewCastable] is the one field with no lb-bot counterpart: it says whether the
 * hub is minting publicly reachable stream URLs, which is a deployment fact the
 * clients cannot work out for themselves.
 */
@Serializable
data class PreviewStatus(
	val configured: Boolean = false,
	val upstreamReachable: Boolean = false,
	val previewCastable: Boolean = false
) {
	val usable: Boolean get() = configured && upstreamReachable
}

/**
 * One resolved preview — deliberately the shape of a hub queue track, field for
 * field, because that is what both clients and the hub already pass around.
 */
@Serializable
data class PreviewTrack(
	val id: String = "",
	val title: String = "",
	val artist: String = "",
	val album: String = "",
	val durationMs: Long = 0L,
	val imageUrl: String = "",
	/**
	 * Absolute, signed and time-limited: the hub rewrites the sidecar's bare answer
	 * with `exp` + an HMAC `sig` on the way out, so the shared secret never leaves the
	 * two servers. Treat it as opaque and never rebuild it.
	 */
	val streamUrl: String = "",
	val mime: String = "",
	val provider: String = "",
	val confidence: Double = 0.0
)

/**
 * A preview as the player's own model.
 *
 * Two fields are carrying something other than their usual meaning, and both follow
 * a path this app already has:
 * - [DomainSong.filePath] holds the stream URL, exactly as it does for a `radio_`
 *   track. `MediaPlayer.toMediaItem` is the single place that reads it.
 * - [DomainSong.coverArtId] holds an absolute image URL rather than a Navidrome id.
 *   `SessionManager.getCoverArtUrl` passes an absolute URL straight through, which
 *   makes every cover surface in the app — tiles, the notification, the palette
 *   fetch — work on a preview with no per-site special case.
 *
 * [DomainSong.isExternal] is true, so the existing "this is not in your library"
 * annotation in `buildSongInfoString` labels it without new UI.
 */
fun PreviewTrack.toDomainSong(): DomainSong = DomainSong(
	id = id,
	title = title,
	artistName = artist,
	artistId = "",
	albumTitle = album.ifBlank { null },
	albumId = null,
	parentId = null,
	comment = null,
	trackNumber = null,
	discNumber = null,
	isrc = emptyList(),
	year = null,
	genre = null,
	genres = emptyList(),
	moods = emptyList(),
	duration = durationMs.milliseconds,
	bpm = null,
	contributors = emptyList(),
	playCount = 0,
	userRating = null,
	averageRating = null,
	bitRate = null,
	bitDepth = null,
	sampleRate = null,
	audioChannelCount = null,
	replayGain = null,
	fileSize = 0,
	fileExtension = "",
	mimeType = mime.ifBlank { null },
	filePath = streamUrl,
	starredAt = null,
	coverArtId = imageUrl.ifBlank { null },
	musicBrainzId = null,
	explicitStatus = DomainExplicitStatus.Unknown,
	artists = emptyList(),
	albumArtists = emptyList(),
	isExternal = true
)
