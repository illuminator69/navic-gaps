package paige.navic.androidApp.widgets.nowplaying

import android.content.Context
import android.content.Intent

/** What the app last told the widgets is playing. */
internal data class NowPlaying(
	val isPlaying: Boolean,
	val title: String,
	val artist: String,
	val artUrl: String
)

/**
 * The last now-playing broadcast, kept outside any one widget's Glance state.
 *
 * Glance state is per widget id, and the app only ever broadcasts on a *change* — so a widget
 * added mid-song had no state and nothing to fill it: it read "Not Playing" until the next
 * play/pause happened to produce a broadcast. This is the answer for that first draw (see
 * [NowPlayingReceiver]), and it is written from the receiver rather than the player so the two
 * cannot drift: every value the widgets show passes through here.
 */
internal object NowPlayingSnapshot {

	private const val FILE = "now_playing_widget_snapshot"
	private const val KEY_HAS_VALUE = "has_value"
	private const val KEY_IS_PLAYING = "is_playing"
	private const val KEY_TITLE = "title"
	private const val KEY_ARTIST = "artist"
	private const val KEY_ART_URL = "art_url"

	fun of(intent: Intent) = NowPlaying(
		isPlaying = intent.getBooleanExtra("isPlaying", false),
		title = intent.getStringExtra("title") ?: "",
		artist = intent.getStringExtra("artist") ?: "",
		artUrl = intent.getStringExtra("artUrl") ?: ""
	)

	fun save(context: Context, state: NowPlaying) {
		prefs(context).edit()
			.putBoolean(KEY_HAS_VALUE, true)
			.putBoolean(KEY_IS_PLAYING, state.isPlaying)
			.putString(KEY_TITLE, state.title)
			.putString(KEY_ARTIST, state.artist)
			.putString(KEY_ART_URL, state.artUrl)
			.apply()
	}

	/** Null when the app has never announced a track — there is nothing to seed a widget with. */
	fun load(context: Context): NowPlaying? {
		val prefs = prefs(context)
		if (!prefs.getBoolean(KEY_HAS_VALUE, false)) return null
		return NowPlaying(
			isPlaying = prefs.getBoolean(KEY_IS_PLAYING, false),
			title = prefs.getString(KEY_TITLE, "").orEmpty(),
			artist = prefs.getString(KEY_ARTIST, "").orEmpty(),
			artUrl = prefs.getString(KEY_ART_URL, "").orEmpty()
		)
	}

	private fun prefs(context: Context) =
		context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
