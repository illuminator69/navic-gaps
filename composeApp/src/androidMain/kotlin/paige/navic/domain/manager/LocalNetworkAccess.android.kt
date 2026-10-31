package paige.navic.domain.manager

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * Android 17's `ACCESS_LOCAL_NETWORK`, read from the system on every call (B-108): the user can
 * change it in the app's settings while the app runs, and nothing tells the app.
 */
class AndroidLocalNetworkAccess(private val context: Context) : LocalNetworkAccess {
	override val sdk: Int = Build.VERSION.SDK_INT

	override fun granted(): Boolean =
		sdk < LOCAL_NETWORK_PERMISSION_SDK || context.checkSelfPermission(
			Manifest.permission.ACCESS_LOCAL_NETWORK
		) == PackageManager.PERMISSION_GRANTED
}
