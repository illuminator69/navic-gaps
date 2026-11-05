package paige.navic.domain.manager

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address

/**
 * Worbus P3 (design §6.4): this device's own private IPv4 address, read from the active network's
 * `LinkProperties`, only while that network is Wi-Fi or Ethernet. Null on mobile data, on a VPN-only
 * route, or without a private address (10/8, 172.16/12, 192.168/16): the sweep then does not run.
 */
class AndroidLocalSubnet(private val context: Context) : LocalSubnet {
	override fun ownPrivateIpv4(): String? = try {
		val cm = context.getSystemService(ConnectivityManager::class.java)
		val network = cm?.activeNetwork
		val caps = network?.let { cm.getNetworkCapabilities(it) }
		val local = caps != null && (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
			caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
		if (!local) null
		else cm.getLinkProperties(network)?.linkAddresses
			?.map { it.address }
			?.filterIsInstance<Inet4Address>()
			?.map { it.hostAddress.orEmpty() }
			?.firstOrNull { classifyHubAddress("ws://$it") == AddressClass.Private && !it.startsWith("127.") && !it.startsWith("169.254.") }
	} catch (e: Exception) {
		null
	}
}
