package paige.navic.ui.navigation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A screen the app was asked to open from outside the composition — today, an album tapped on the
 * Quick Picks home-screen widget.
 *
 * It is a flow rather than a start-destination argument because the request can arrive at any
 * point in the process's life: `App()` may not be composed yet (cold start), or may have been
 * composed for minutes already (the widget re-entering a running activity through `onNewIntent`).
 * The composition drains it once it is up and the user is signed in.
 */
object AppDeepLink {

	private val _pending = MutableStateFlow<Screen?>(null)
	val pending: StateFlow<Screen?> = _pending.asStateFlow()

	/**
	 * Ask for [albumId]'s detail page.
	 *
	 * Callers name an album rather than a [Screen] on purpose: the platform entry points that
	 * raise these (`MainActivity`) sit outside the module that owns navigation, and would
	 * otherwise have to pull in navigation3 just to spell the destination.
	 */
	fun requestAlbum(albumId: String) {
		_pending.value = Screen.CollectionDetail(albumId, "")
	}

	fun consume() {
		_pending.value = null
	}

	/**
	 * Text shared into the app from elsewhere — a streaming URL from another app's
	 * share sheet.
	 *
	 * Text rather than a [Screen], because unlike [requestAlbum] this cannot name a
	 * destination: where it goes depends on what lb-bot makes of the URL, which is a
	 * round trip away. So the platform entry point hands over the raw string and the
	 * composition does the resolving — which keeps `MainActivity` free of both
	 * navigation3 and lb-bot, exactly as this object exists to do.
	 *
	 * Its own flow rather than sharing [pending] so the two drains stay independent:
	 * an album tile tapped on the widget while a shared link is still resolving must
	 * not cancel it, and vice versa.
	 */
	private val _sharedText = MutableStateFlow<String?>(null)
	val sharedText: StateFlow<String?> = _sharedText.asStateFlow()

	fun requestSharedText(text: String) {
		if (text.isNotBlank()) _sharedText.value = text.trim()
	}

	fun consumeSharedText() {
		_sharedText.value = null
	}
}
