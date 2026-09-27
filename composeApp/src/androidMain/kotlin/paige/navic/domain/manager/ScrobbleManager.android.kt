package paige.navic.domain.manager

import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import kotlinx.coroutines.CoroutineScope

class AndroidScrobbleManager(
	private val player: Player,
	scope: CoroutineScope,
	connectivityManager: ConnectivityManager,
	syncManager: SyncManager,
	sessionManager: SessionManager,
	preferenceManager: PreferenceManager,
	hubManager: HubManager
) : Player.Listener {

	private val playerSource = object : ScrobblePlayerSource {
		override val currentPosition: Long get() = player.currentPosition
		override val duration: Long get() = player.duration
		override val isPlaying: Boolean get() = player.isPlaying
	}

	private val scrobbleManager =
		ScrobbleManager(
			playerSource,
			connectivityManager,
			syncManager,
			sessionManager,
			scope,
			preferenceManager
		)

	// OpenSubsonic `reportPlayback`, off the same listener as the scrobbles: this is the LOCAL
	// ExoPlayer, never the RemoteSessionPlayer facade, so it only ever describes this device.
	private val playbackReporter =
		PlaybackReporter(sessionManager, preferenceManager, connectivityManager, hubManager, scope)

	private fun snapshot() = PlaybackSnapshot(
		mediaId = player.currentMediaItem?.mediaId,
		isPlaying = player.isPlaying,
		playWhenReady = player.playWhenReady,
		stopped = player.playbackState == Player.STATE_ENDED ||
			player.playbackState == Player.STATE_IDLE,
		positionMs = player.currentPosition,
		playbackRate = player.playbackParameters.speed
	)

	init {
		player.addListener(this)
	}

	override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
		scrobbleManager.onMediaChanged(mediaItem?.mediaId)
		playbackReporter.onMediaChanged(snapshot())
	}

	override fun onPositionDiscontinuity(
		oldPosition: Player.PositionInfo,
		newPosition: Player.PositionInfo,
		reason: Int
	) {
		val isSeekOrRepeat = reason == Player.DISCONTINUITY_REASON_SEEK ||
			reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION

		val jumpedToStart = newPosition.positionMs < 5000L
		val wasFurtherAlong = oldPosition.positionMs > 10000L

		if (isSeekOrRepeat && jumpedToStart && wasFurtherAlong) {
			scrobbleManager.onMediaChanged(player.currentMediaItem?.mediaId)
		}

		// A seek to another item arrives as onMediaItemTransition; only a seek within the item
		// is a new position for the one already reported.
		if (reason == Player.DISCONTINUITY_REASON_SEEK &&
			oldPosition.mediaItemIndex == newPosition.mediaItemIndex
		) {
			playbackReporter.onSeek(::snapshot)
		}
	}

	override fun onIsPlayingChanged(isPlaying: Boolean) {
		scrobbleManager.onPlayStateChanged(isPlaying)
		playbackReporter.onPlayingChanged(snapshot())
	}

	override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
		playbackReporter.onRateChanged(snapshot())
	}

	fun release() {
		player.removeListener(this)
		playbackReporter.release()
	}
}
