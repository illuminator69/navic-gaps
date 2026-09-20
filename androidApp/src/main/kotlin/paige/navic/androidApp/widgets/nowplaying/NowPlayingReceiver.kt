package paige.navic.androidApp.widgets.nowplaying

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.state.updateAppWidgetState
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Base receiver class which widgets' receivers will inherit from. Used with `NowPlayingWidget`
 */
open class NowPlayingReceiver(
	private val widgetClass: Class<out NowPlayingWidget>
) : GlanceAppWidgetReceiver() {

	override val glanceAppWidget: GlanceAppWidget by lazy {
		widgetClass.getDeclaredConstructor().newInstance()
	}

	override fun onReceive(context: Context, intent: Intent) {
		super.onReceive(context, intent)

		// Only OUR broadcast carries now-playing extras. Every other intent that reaches a widget
		// receiver — APPWIDGET_UPDATE above all, which the system and Glance both send — has
		// none, and writing their absent extras blanked the state: the widget went back to
		// "Not Playing"/"No Artist" mid-song, whenever something merely asked it to redraw.
		//
		// An APPWIDGET_UPDATE is still worth answering, from the stored snapshot rather than from
		// the intent: it is what a freshly placed widget gets, and that widget's own Glance state
		// is empty until the app next happens to broadcast a *change* — which is why a widget
		// added mid-song read "Not Playing" until playback was paused and resumed. Reading a
		// stored value keeps the rule above intact: absent extras are still never written, and a
		// snapshot that does not exist yet writes nothing at all.
		val state = when (intent.action) {
			"${context.packageName}.NOW_PLAYING_UPDATED" ->
				NowPlayingSnapshot.of(intent).also { NowPlayingSnapshot.save(context, it) }

			AppWidgetManager.ACTION_APPWIDGET_UPDATE -> NowPlayingSnapshot.load(context) ?: return

			else -> return
		}

		val pendingResult = goAsync()

		// A SupervisorJob scope owned by the class, not a fresh MainScope per broadcast: an
		// unmanaged scope is never cancelled, so a redraw that wedges leaves a coroutine (and
		// its Glance state write) alive with nothing able to stop it. goAsync() still bounds
		// how long the process is kept up; this bounds what is left behind if it runs out.
		widgetScope.launch {
			try {
				val glanceIds = GlanceAppWidgetManager(context).getGlanceIds(widgetClass)
				glanceIds.forEach { id ->
					updateAppWidgetState(context, id) { prefs ->
						prefs[NowPlayingKeys.isPlaying] = state.isPlaying
						prefs[NowPlayingKeys.titleKey] = state.title
						prefs[NowPlayingKeys.artistKey] = state.artist
						prefs[NowPlayingKeys.artUrlKey] = state.artUrl
					}
					glanceAppWidget.update(context, id)
				}
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				// A widget that quietly stops updating is otherwise indistinguishable from one
				// nothing is broadcasting to. Say which it was.
				Log.w(TAG, "widget update failed for ${widgetClass.simpleName}", e)
			} finally {
				runCatching { pendingResult.finish() }
			}
		}
	}

	private companion object {
		private const val TAG = "NowPlayingReceiver"

		/**
		 * Shared by every widget receiver instance; the system creates a new receiver object per
		 * broadcast, so a per-instance scope would be the same unmanaged leak by another name.
		 * SupervisorJob so one failed widget update cannot take the next one down with it.
		 */
		private val widgetScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
	}
}
