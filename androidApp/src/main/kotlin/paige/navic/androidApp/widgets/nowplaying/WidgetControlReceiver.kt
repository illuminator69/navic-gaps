package paige.navic.androidApp.widgets.nowplaying

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import paige.navic.shared.PlaybackService

/**
 * Skip commands from the widgets, issued over a short-lived [MediaController].
 *
 * They deliberately do **not** go through `MediaButtonReceiver` the way play/pause does.
 * media3's receiver drops every key event that is not `PLAY`, `PLAY_PAUSE` or `HEADSETHOOK` on
 * API 26+ — it would have to start the service into the foreground to deliver one, which the
 * platform only forgives for a play command, so it logs *"Ignore key event that is not a `play`
 * command"* and returns. `KEYCODE_MEDIA_NEXT`/`PREVIOUS` sent that way therefore did nothing at
 * all, on any of the widgets, and silently: the click landed, the broadcast was sent, and it was
 * thrown away one process later.
 *
 * Binding the session instead needs no foreground start. Skipping only means anything when there
 * is already a session to skip within, so the service is running whenever this matters.
 */
class WidgetControlReceiver : BroadcastReceiver() {

	override fun onReceive(context: Context, intent: Intent) {
		val command = intent.getStringExtra(EXTRA_COMMAND) ?: return

		val pendingResult = goAsync()
		val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
		val future = MediaController.Builder(context.applicationContext, token).buildAsync()

		future.addListener({
			// A dead widget button had no diagnostic trail at all: the connection failure was
			// discarded by getOrNull(), and an unavailable command returned Unit. Both look
			// exactly like a click that never happened.
			val controller = runCatching { future.get() }.getOrElse { e ->
				Log.w(TAG, "no MediaController for widget \"$command\" — is playback running?", e)
				null
			}
			try {
				controller?.dispatch(command)
			} finally {
				controller?.release()
				runCatching { pendingResult.finish() }
			}
		}, ContextCompat.getMainExecutor(context))
	}

	/**
	 * `seekToNext`/`seekToPrevious` rather than the `...MediaItem` pair, so a widget skip behaves
	 * exactly like the notification's: shuffle and repeat order are honoured, and Previous
	 * restarts the current track instead of leaving it when you are past its opening seconds.
	 */
	private fun MediaController.dispatch(command: String) = when (command) {
		COMMAND_NEXT -> run(Player.COMMAND_SEEK_TO_NEXT, command) { seekToNext() }
		COMMAND_PREVIOUS -> run(Player.COMMAND_SEEK_TO_PREVIOUS, command) { seekToPrevious() }
		else -> Log.w(TAG, "unknown widget command \"$command\"")
	}

	/** Availability is a real answer, not a silent no-op: say which command was refused. */
	private inline fun MediaController.run(
		capability: Int,
		command: String,
		action: () -> Unit
	) {
		if (isCommandAvailable(capability)) action()
		else Log.w(TAG, "session does not currently allow widget \"$command\"")
	}

	companion object {
		private const val TAG = "WidgetControlReceiver"
		const val EXTRA_COMMAND = "command"
		const val COMMAND_NEXT = "next"
		const val COMMAND_PREVIOUS = "previous"
	}
}
