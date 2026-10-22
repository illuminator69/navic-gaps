package paige.navic.androidApp

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import paige.navic.App
import paige.navic.domain.manager.HubManager
import paige.navic.domain.manager.LbBotManager
import paige.navic.ui.navigation.AppDeepLink
import paige.navic.domain.manager.PermissionManager

/** The least time between two steps while a volume button is held down. */
private const val HELD_VOLUME_STEP_MS = 150L

class MainActivity : ComponentActivity() {

	private val lbBot: LbBotManager by inject()
	private val permissionManager: PermissionManager by inject()
	private val hubManager: HubManager by inject()

	private var volumePressTaken = false
	private var volumePressAtMs = 0L

	/**
	 * While another device plays, a volume button pressed in Navic is that device's, and Navic
	 * draws the level itself (`RemoteVolumeHud`). Taken here, before the system sees it, so that
	 * what happens does not depend on whether this Android version draws a slider for a remote
	 * session: some do and some do not. A sheet or a dialog is a window of its own and does not
	 * pass through here; there the session, declared remote while Navic is on screen, gets the
	 * press (`VolumeKeys.kt`).
	 */
	override fun dispatchKeyEvent(event: KeyEvent): Boolean {
		val up = when (event.keyCode) {
			KeyEvent.KEYCODE_VOLUME_UP -> true
			KeyEvent.KEYCODE_VOLUME_DOWN -> false
			else -> return super.dispatchKeyEvent(event)
		}
		if (event.action != KeyEvent.ACTION_DOWN) {
			// The release of a press that was taken is taken with it.
			val taken = volumePressTaken
			volumePressTaken = false
			return taken || super.dispatchKeyEvent(event)
		}
		val now = SystemClock.uptimeMillis()
		// A held button repeats every 50 ms; a step each time would cross the range in a second.
		if (event.repeatCount > 0 && volumePressTaken && now - volumePressAtMs < HELD_VOLUME_STEP_MS) return true
		if (!hubManager.pressRemoteVolume(up)) return super.dispatchKeyEvent(event)
		volumePressTaken = true
		volumePressAtMs = now
		return true
	}

	private val requestNotifications =
		registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		enableEdgeToEdge()
		// Only on a fresh create (B-181): a rotation, or a restore after process death, re-enters here
		// with the intent the activity was last given, and the link it carried already opened its page.
		if (savedInstanceState == null) handleDeepLink(intent)
		observeFills()
		setContent { App() }
	}

	/**
	 * Post a notification when a fill settles, and ask for the permission to do so the
	 * first time one is started.
	 *
	 * Lazy on purpose: a permission prompt at launch, for a feature most sessions never
	 * touch, is the kind that gets denied reflexively — and lb-bot is off entirely unless
	 * a hub is configured. Asking at the moment the user starts a download makes the
	 * prompt legible, and a denial costs nothing: the snackbar in `App.kt` still fires,
	 * and the Download Center still lists every fill.
	 *
	 * `repeatOnLifecycle(STARTED)` rather than a process-wide collector, because that is when
	 * there is a UI to own the notification permission prompt. What it must NOT do is make
	 * delivery conditional on being started: `fillEvents` has no replay, so a fill that settled
	 * while the app was backgrounded emitted into nothing and the ledger recorded a completion
	 * whose promised notification never arrived. So the flow is the fast path only —
	 * `deliverPendingNotifications` is the one that actually owes and settles the debt, and it is
	 * driven both on every foreground entry and on each live event. It marks a row notified only
	 * once the notifier has accepted it, so this stays exactly-once across process death too.
	 */
	private fun observeFills() {
		val notifier = FillNotifier(applicationContext)
		// Delivered from the manager's own `settle`, so a fill that settles while the
		// process is alive but this activity is stopped is announced then and there.
		// In the foreground the snackbar in `App.kt` is the announcement, and the
		// system notification is deliberately skipped — the same rule Feishin uses —
		// so the user is not told twice. The row is still marked delivered.
		lbBot.notificationSink = { event ->
			if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) notifier.notify(event)
		}
		lifecycleScope.launch {
			repeatOnLifecycle(Lifecycle.State.STARTED) {
				// Anything that settled while the process was dead, first.
				lbBot.deliverPendingNotifications { notifier.notify(it) }
				launch {
					lbBot.ledger.collect { entries ->
						if (entries.any { it.isRunning }) askForNotifications()
					}
				}
			}
		}
	}

	private var askedForNotifications = false

	private fun askForNotifications() {
		if (askedForNotifications) return
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
		val granted = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
			PackageManager.PERMISSION_GRANTED
		// One ask per process. Android itself only shows the dialog twice ever, and after
		// that the call is a silent no — re-firing it on every fill would be pointless.
		askedForNotifications = true
		if (!granted) requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
	}

	/**
	 * Reached when a Quick Picks tile is tapped, a link is shared in, a widget or the fill
	 * notification is tapped while the activity is already running. The activity is `singleTask`
	 * (B-181), so every one of them arrives here instead of starting a second instance.
	 */
	override fun onNewIntent(intent: Intent) {
		super.onNewIntent(intent)
		setIntent(intent)
		handleDeepLink(intent)
	}

	private fun handleDeepLink(intent: Intent?) {
		if (intent == null) return
		// A link shared in from another app. Handled here and not in one of the two
		// `activity-alias` entries: those exist only to swap the launcher icon, one of
		// them is `enabled="false"`, and a filter on them would therefore be either
		// dead or a duplicate share target in the system sheet.
		//
		// Both `EXTRA_TEXT` and the intent's own data are checked because apps
		// disagree about which they use — a browser's "Share link" sends text, while
		// "Open with" sends a URI.
		if (intent.action == Intent.ACTION_SEND && intent.type?.startsWith("text/") == true) {
			val shared = intent.getStringExtra(Intent.EXTRA_TEXT)
				?: intent.data?.toString()
			shared?.takeIf { it.isNotBlank() }?.let(AppDeepLink::requestSharedText)
			return
		}

		val uri: Uri = intent.data ?: return
		if (uri.scheme != DEEP_LINK_SCHEME) return
		when (uri.host) {
			DEEP_LINK_ALBUM -> uri.lastPathSegment
				?.takeIf { it.isNotBlank() }
				?.let(AppDeepLink::requestAlbum)
		}
	}

	private companion object {
		const val DEEP_LINK_SCHEME = "navic"
		const val DEEP_LINK_ALBUM = "album"
	}
}
