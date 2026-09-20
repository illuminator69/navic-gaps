package paige.navic.di

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

interface PlatformContext {
	val name: String
	val appVersion: String
	val colorScheme: ColorScheme?
	val sizeClass: WindowSizeClass
	val platformType: PlatformType

	// Upstream dropped both from this interface after alpha41 with no replacement. Kept:
	// clickSound() backs every tap sound in the app, and checkLocalNetworkPermission() is
	// the Android 17 ACCESS_LOCAL_NETWORK prompt the hub and cast discovery need.
	fun checkLocalNetworkPermission()
	fun clickSound()
}

@Composable
fun PlatformContext.isLandscape() = remember(sizeClass) {
	sizeClass.widthSizeClass >= WindowWidthSizeClass.Medium
}

enum class PlatformType {
	Android,
	IOS
}

/**
 * The running platform, outside composition. [PlatformContext.platformType] is only reachable from
 * a `@Composable`; plain managers (the downloader picking a transcode container, say) need it too.
 */
expect val currentPlatformType: PlatformType

@Composable
expect fun rememberPlatformContext(): PlatformContext

/**
 * While composed, sets the status-bar icons to contrast [isDarkBackground] (light
 * icons over a dark page, dark icons over a light page) and restores the previous
 * appearance on dispose. Used by the art-themed detail screens, whose ambient
 * background follows the cover art's brightness regardless of app theme. No-op on iOS.
 */
@Composable
expect fun ForceSystemBars(isDarkBackground: Boolean)

expect fun <T> synchronized(lock: Any, block: () -> T): T
