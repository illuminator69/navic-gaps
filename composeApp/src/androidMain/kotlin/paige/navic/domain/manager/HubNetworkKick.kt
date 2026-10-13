package paige.navic.domain.manager

import android.annotation.SuppressLint
import android.content.Context
import android.net.Network
import paige.navic.util.Logger
import android.net.ConnectivityManager as AndroidConnectivityManager

/**
 * Tells the hub link the moment Android has a default network again, so it dials at once
 * instead of sitting out a backoff of up to 30 s ([HubManager.kickReconnect]).
 *
 * Deliberately NOT built on the app's own [ConnectivityManager]. That one answers "may the app
 * use the network": it folds in the user's offline mode and waits for a validated network. The
 * question here is only "did the route to the hub just change", whatever the offline mode says,
 * and a hub on the LAN is reachable over a network Android has not validated.
 *
 * `onAvailable` on the default-network callback fires when a network becomes the default: the
 * first one after none, and each switch between Wi-Fi and mobile. It also fires once on
 * registration; that one finds the link up or already dialling, and at most turns a first
 * retry's one-second wait into none.
 *
 * Only the main hub socket is kicked. The cast bridges keep their own reconnect rules.
 */
class HubNetworkKick(context: Context, private val hubManager: HubManager) {
	private val connectivity =
		context.getSystemService(Context.CONNECTIVITY_SERVICE) as AndroidConnectivityManager

	private val callback = object : AndroidConnectivityManager.NetworkCallback() {
		override fun onAvailable(network: Network) {
			hubManager.kickReconnect("network available")
		}
	}

	// ACCESS_NETWORK_STATE is declared in androidApp's manifest; this module has none.
	@SuppressLint("MissingPermission")
	fun start() {
		try {
			connectivity.registerDefaultNetworkCallback(callback)
		} catch (e: Exception) {
			// The system caps callbacks per app. Without this one the link still reconnects,
			// on its backoff, as it did before.
			Logger.w("HubNetworkKick", "no default-network callback", e)
		}
	}
}
