package paige.navic.domain.manager

import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec
import paige.navic.domain.manager.base.BasePreferenceManager
import paige.navic.domain.models.settings.AnimationStyle
import paige.navic.domain.models.settings.AppIconVariant
import paige.navic.domain.models.settings.BottomBarCollapseMode
import paige.navic.domain.models.settings.BottomBarVisibilityMode
import paige.navic.domain.models.settings.CoverArtQuality
import paige.navic.domain.models.settings.CoverArtShape
import paige.navic.domain.models.settings.CoverArtTapAction
import paige.navic.domain.models.settings.ExplicitContentPlayback
import paige.navic.domain.models.settings.FontOption
import paige.navic.domain.models.settings.GridSize
import paige.navic.domain.models.settings.ListViewMode
import paige.navic.domain.models.settings.MarqueeSpeed
import paige.navic.domain.models.settings.MiniPlayerProgressStyle
import paige.navic.domain.models.settings.MiniPlayerStyle
import paige.navic.domain.models.settings.NowPlayingBackgroundStyle
import paige.navic.domain.models.settings.NowPlayingSliderStyle
import paige.navic.domain.models.settings.OfflineMode
import paige.navic.domain.models.settings.QueueInfoType
import paige.navic.domain.models.settings.ReplayGainMode
import paige.navic.domain.models.settings.StationCharacter
import paige.navic.domain.models.settings.StreamingQuality
import paige.navic.domain.models.settings.Theme
import paige.navic.domain.models.settings.ThemeMode
import paige.navic.domain.models.settings.ToolbarPosition
import com.russhwolf.settings.Settings as KmpSettings

class PreferenceManager(
	settings: KmpSettings
) : BasePreferenceManager(settings) {
    var queueInfoType by (preference(QueueInfoType.Full))
    var appIconVariant by preference(AppIconVariant.Default)
	var font by preference(FontOption.GoogleSans)
	var fontPath by preference("")
	var animationStyle by preference(AnimationStyle.Expressive)
	var nowPlayingBackgroundStyle by preference(NowPlayingBackgroundStyle.Dynamic)
	// navi-connect: real backdrop blur (Haze) for the Mood Flow frosted UI.
	// Experimental (alpha dependency) → off by default.
	var expressiveBlur by preference(false)
	var swipeToSkip by preference(true)
	var hideIfIdle by preference(false)
	var enablePredictiveBackAnimations by preference(true)
	var gridSize by preference(GridSize.TwoByTwo)
	var coverArtShape by preference(CoverArtShape.Soft)
	var artistImageShape by preference(CoverArtShape.Soft)
	var coverArtQuality by preference(CoverArtQuality.High)
	var artGridItemSize by preference(150f)
	var marqueeSpeed by preference(MarqueeSpeed.Slow)
	var alphabeticalScroll by preference(false)
	var enableRatings by preference(true)
	var lyricsAutoscroll by preference(true)
	var lyricsBeatByBeat by preference(true)
	var lyricsFakeBeatByBeat by preference(false)
	var lyricsKeepAlive by preference(true)
	var lyricsBlur by preference(false)
	var lyricsBrightInactive by preference(false)
	var enableScrobbling by preference(true)
	var scrobblePercentage by preference(.5f)
	var minDurationToScrobble by preference(30f)
	var replayGainMode by preference(ReplayGainMode.Off)
	var rgAmpGain by preference(0f)
	var ampGain by preference(0f)
	var gaplessPlayback by preference(true)
	var audioOffload by preference(false)

	// TODO: better names and strings for these transcoding settings
	var streamingQualityWifi by preference(StreamingQuality.Lossless)
	var streamingQualityCellular by preference(StreamingQuality.Lossless)
	var isAdvancedTranscodingActive by preference(false)
	var customMaxBitrateWifi by preference(0)
	var customMaxBitrateCellular by preference(0)
	// On a metered connection, play the downloaded copy or stream the server's original?
	// Default true (play the download) — that's the point of downloading, and it costs no data.
	// Turn it off when the downloads are space-saving transcodes but you'd rather hear the
	// original away from Wi-Fi. On an unmetered connection a download is always preferred.
	var preferDownloadsOnCellular by preference(true)
	// Downloads are kept, so they get their OWN quality rather than inheriting whatever the
	// current network's streaming setting happens to be. Original by default: an offline copy you
	// keep is the last place to silently save bandwidth.
	//
	// Bitrate and container are separate, and use the same value model as a per-playlist download
	// policy (PlaylistDownloadDialog): bitrate 0 = the server's original file (so FLAC stays FLAC),
	// otherwise kbps; format "" = original container, else an explicit transcode target. The old
	// [downloadQuality] tier couldn't express "320 kbps mp3" at all — its tiers stopped at 192 and
	// always forced opus on Android.
	var downloadBitrate by preference(0)
	var downloadFormat by preference("")
	// Legacy: superseded by downloadBitrate/downloadFormat, read once by [migrateDownloadQuality]
	// so an existing choice carries over instead of silently resetting to Original.
	var downloadQuality by preference(StreamingQuality.Lossless)
	var customMaxBitrateDownload by preference(0)
	private var downloadQualityMigrated by preference(false)

	/**
	 * Carry a pre-existing download-quality tier over to the bitrate/format pair. Runs once; a user
	 * who never changed the default (Lossless → 0/"") is unaffected either way.
	 */
	private fun migrateDownloadQuality() {
		if (downloadQualityMigrated) return
		downloadQualityMigrated = true
		val legacy = downloadQuality
		if (legacy == StreamingQuality.Lossless) return
		val custom = customMaxBitrateDownload
		downloadBitrate = if (custom > 0) custom else legacy.bitrateAndroid
		downloadFormat = legacy.containerAndroid.orEmpty()
	}

	init {
		migrateDownloadQuality()
	}
	// Download constraints (Symfonium-style). Wi-Fi-only pauses transfers on a metered/cellular
	// connection; charging-only pauses them off charger. Max-concurrency caps simultaneous
	// transfers (the download semaphore's permit count); coerced into [1, 10] where read.
	var downloadWifiOnly by preference(false)
	var downloadChargingOnly by preference(false)
	var downloadMaxConcurrency by preference(4)
	var customFormatWifi by preference("")
	var customFormatCellular by preference("")

	var downloadQualityWifi by preference(StreamingQuality.Lossless)
	var downloadQualityCellular by preference(StreamingQuality.Lossless)
	var isAdvancedDownloadTranscodingActive by preference(false)
	var customDownloadMaxBitrateWifi by preference(0)
	var customDownloadMaxBitrateCellular by preference(0)
	var customDownloadFormatWifi by preference("")
	var customDownloadFormatCellular by preference("")

	var nowPlayingToolbarPosition by preference(ToolbarPosition.Bottom)
	var nowPlayingSongInfo by preference(true)
	var nowPlayingSliderStyle by preference(NowPlayingSliderStyle.Squiggly)
	var nowPlayingCoverArtAction by preference(CoverArtTapAction.ShowLyrics)
	var customHeaders by preference("")
	var checkForUpdates by preference(true)
	var explicitContentPlayback by preference(ExplicitContentPlayback.Allowed)
	var autoFillQueue by preference(false)
	var shushQueueDuplicateDialog by preference(false)

	// navigation bar settings
	var bottomBarCollapseMode by preference(BottomBarCollapseMode.OnScroll)
	var bottomBarVisibilityMode by preference(BottomBarVisibilityMode.AllScreens)
	// upstream list/grid view mode, per library tab
	var albumListViewMode by preference(ListViewMode.Grid)
	var artistListViewMode by preference(ListViewMode.Grid)
	var playlistListViewMode by preference(ListViewMode.Grid)
	var songListViewMode by preference(ListViewMode.List)
	var miniPlayerStyle by preference(MiniPlayerStyle.Detached)
	var miniPlayerProgressStyle by preference(MiniPlayerProgressStyle.Seekable)

	// theme related settings
	var theme by preference(Theme.Dynamic)
	var themeMode by preference(ThemeMode.System)
	var amoled by preference(false)
	// ON by default: the fork has always themed from cover art, and this pref had no readers at all
	// until it was rewired (see `coverThemingEnabled`), so defaulting false would un-theme every
	// existing install on update.
	var dynamicTheming by preference(true)
	var paletteStyle by preference(PaletteStyle.TonalSpot)
	var paletteSpec by preference(ColorSpec.SpecVersion.SPEC_2025)
	var paletteAccentH by preference(0f)

	// sync related settings
	var lastFullSyncTime by preference(0L)

	// navi-connect autoplay (I-040): On or Off. With the hub's station engine the queue continues as
	// a station (PROTOCOL §18); without it, as Auto DJ (`AutoDj.kt`). It was a four-way
	// `AutoplayMode` stored by ordinal at "autoplayMode", read once by [migrateAutoplay].
	var autoplayOn by preference(false)
	private var autoplayMigrated by preference(false)

	// The character every new station starts with (`StationCharacter`). The key stays
	// "moodCharacter": the three take the old presets' ordinals one for one, so a stored choice
	// keeps its meaning. Append-only from here (see the enum).
	var stationCharacter by preference("moodCharacter", StationCharacter.Default)

	// Auto DJ, the autoplay used when the hub has no station engine: a port of upstream Feishin's
	// (`AutoDj.kt`), with its defaults. Songs added per top-up, whether to stop at Navidrome's
	// similar songs when they found something, whether a song already queued may be added again,
	// and how few songs left after the playing one start a top-up.
	var autoDjItemCount by preference(5)
	var autoDjOnlySimilar by preference(false)
	var autoDjAllowDuplicates by preference(false)
	var autoDjTiming by preference(1)

	// What built the current queue, when a station did (`StationProvenance`, JSON), so a restart
	// does not turn a radio into a queue nobody continues. Written by StationManager alone.
	var stationProvenance by preference("")
	// navi-connect I-043: every cover's two seed colours, so a page is coloured in its first frame
	// (`CoverSeedStore`, JSON from `CoverSeedCodec`). Written by that store alone.
	var coverSeeds by preference("")
	// navi-connect I-043: the playing cover's id, so a cold start wears it until the player has
	// restored (`washCoverArtId`). Written by `ColourArrivalEffects` alone.
	var lastNowPlayingCoverArtId by preference("")
	// The saved-queue id of a queue Auto DJ has topped up, for the queue sheet's line.
	var autoDjQueueId by preference("")
	// The hub's last character catalogue (`GET /sonic/station/characters`), JSON, so the labels
	// and descriptions are the hub's on a cold start too. "" until a hub has answered.
	var stationCatalogue by preference("")

	/**
	 * Carry the old four-way autoplay mode over to [autoplayOn], once. Read as a raw int: the enum
	 * it was stored from is gone, and reading it through one with fewer entries would have thrown
	 * on any install that had Fingerprint (2) or Mood Flow (3) selected. Off (0) stays off; Similar,
	 * Sonic Fingerprint and Mood Flow (1, 2, 3) all become on, since each of them kept the queue
	 * going and a station now does that for all three. The old key is removed afterwards.
	 */
	private fun migrateAutoplay() {
		if (autoplayMigrated) return
		val legacy = settings.getIntOrNull(LEGACY_AUTOPLAY_MODE_KEY)
		if (legacy != null) autoplayOn = legacy != 0
		settings.remove(LEGACY_AUTOPLAY_MODE_KEY)
		autoplayMigrated = true
	}

	init {
		migrateAutoplay()
	}

	// navi-connect Tier 2: AudioMuse-AI core API (direct HTTP, Bearer token).
	// Unlocks Sonic Fingerprint autoplay (+ later: adaptive/chat/mood search).
	var audioMuseUrl by preference("")
	var audioMuseToken by preference("")

	// navi-connect hub (Spotify-Connect-style remote control)
	var hubEnabled by preference(false)
	var hubUrl by preference("")
	var hubToken by preference("")
	var hubDeviceName by preference("Navic")
	var hubDeviceId by preference("")
	// Comma-joined device ids the user manually hid from the device picker.
	var hubHiddenDeviceIds by preference("")
	// Saved-queue deletions we haven't been able to tell the hub about yet, as "id:millis"
	// pairs joined by commas. Deleting history while offline used to be silently undone: the
	// row went from Room, then the next reconnect adopted the hub's list — which still had it.
	// Mirrors hub.py's `deleted_saved_queues`. See SavedQueueRepository.
	var deletedSavedQueueIds by preference("")

	// navi-connect lb-bot (library-gap intelligence, hub-proxied only).
	//
	// Which copy of an album to prefer when several are on offer — one of lb-bot's
	// QUALITY_PREFERENCES, or "" for "whatever lb-bot's own Source preference says".
	// Sticky across albums and restarts: a user who wants CD-quality FLAC wants it
	// every time, and re-picking it per album is the kind of chore nobody does twice.
	var lbBotQuality by preference("")

	// In-flight fills, as a JSON object keyed by rgid (album downloads) or review
	// group id (gap fills). Persisted rather than held in memory because a fill takes
	// minutes and the app is routinely killed inside that window — see LbBotManager.
	var lbBotWatches by preference("{}")

	// Fresh-releases filters. Persisted because they are decisions about how someone
	// reads that page — "only my artists, last 30 days, albums" — not per-visit state.
	// Stored as the enum's own name (see FreshViewModel), read back defensively so a
	// value written by a build that knew a name this one doesn't falls back rather
	// than throwing on startup.
	var freshDays by preference(30)
	var freshScope by preference("YOURS")
	var freshSort by preference("DATE")
	var freshType by preference("ALL")

	// these values are bitmasks of `DomainFilter`
	var albumFilters by preference(0)
	var songFilters by preference(0)
	var artistFilters by preference(0)
	var playlistFilters by preference(0)

	fun customHeadersMap(): Map<String, String> = buildMap {
		for (line in customHeaders.lines()) {
			val parts = line.split(":", limit = 2)
			if (parts.size < 2) continue

			val rawKey = parts[0]
			val rawValue = parts[1]

			val key = rawKey.trim()
			val value = rawValue.trim()
			if (key.isNotEmpty() && value.isNotEmpty()) put(key, value)
		}
	}

	var offlineMode by preference(OfflineMode.Auto)

	/**
	 * Which Deezer genre the two Discover browse rows are narrowed to.
	 *
	 * A Deezer genre id as a string, `"0"` being the global chart. Stored as an
	 * opaque id rather than an index: the list comes from lb-bot and Deezer has
	 * changed it before, so a position would quietly re-point to another genre.
	 */
	var deezerGenre by preference("0")

	/**
	 * When the library last changed as far as this device heard — a hub `library` frame, a fill
	 * of ours landing, or a (re)connect that may have missed some. Epoch millis.
	 *
	 * A watermark for `LbBotManager.cachedGet`: every cached lb-bot answer that carries ownership
	 * (`owned`, `releaseOwned`, a per-track `present`) and was fetched at or before this is stale,
	 * whatever its age. A preference rather than an `UPDATE` over the cache table because it is
	 * one synchronous write from the frame thread, so a screen re-reading on the same bump can
	 * never beat it, and it survives the process — which the in-memory copy does not.
	 */
	var lbCacheLibraryStaleAt by preference(0L)

	/**
	 * Whether the hub's last lb-bot advert listed the listening-analytics routes (I-006). Kept so
	 * the listening history is there on a cold start with no hub in reach: the screen renders its
	 * cached summary offline, and an advert that never arrives must not hide the way into it.
	 * Written by `LbBotManager` on every advert, so a hub or lb-bot that loses the routes
	 * withdraws the entry on the next connect.
	 */
	var lbListeningAdvertised by preference(false)

	/**
	 * The mediaId of the now-playing entry Navidrome last confirmed for this device's
	 * `reportPlayback`, or "" when the last confirmed report closed it. Persisted so a force stop
	 * does not leave the phone in `getNowPlaying`: the next playback service closes it with a
	 * `stopped` for this exact id (Q-042, `PlaybackReportLedger`).
	 */
	var playbackReportOpenId by preference("")

	private companion object {
		/** Where the four-way autoplay mode was stored, by ordinal, before I-040. */
		const val LEGACY_AUTOPLAY_MODE_KEY = "autoplayMode"
	}
}
