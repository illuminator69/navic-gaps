package paige.navic.di

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.view.SoundEffectConstants
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.expressiveLightColorScheme
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.app.ActivityCompat.requestPermissions
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import org.koin.compose.koinInject
import paige.navic.domain.manager.PreferenceManager
import paige.navic.domain.models.settings.ThemeMode
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
@Composable
actual fun rememberPlatformContext(): PlatformContext {
	val view = LocalView.current
	val context = LocalContext.current
	val inDarkTheme = isSystemInDarkTheme()
	val preferenceManager = koinInject<PreferenceManager>()
	val isDark = remember(preferenceManager.themeMode) {
		when (preferenceManager.themeMode) {
			ThemeMode.System -> inDarkTheme
			ThemeMode.Dark -> true
			ThemeMode.Light -> false
		}
	}
	val activity = LocalActivity.current!!
	val sizeClass = calculateWindowSizeClass(activity)
	// The app theme's brightness is the DEFAULT for the status-bar icons, not the last word.
	// This used to assign `isAppearanceLightStatusBars` directly — and from a `SideEffect`, which
	// runs after EVERY recomposition of this composable, at the app root. So it silently undid
	// whatever [ForceSystemBars] had set for the page you were actually looking at: a dark
	// cover-themed page under a light app theme showed black status icons on a near-black bar,
	// and only until the next root recomposition if it ever looked right at all. Both now go
	// through [SystemBars], which keeps the page's override on top of this default.
	SideEffect {
		activity.window?.let { window ->
			SystemBars.setAppDefault(WindowCompat.getInsetsController(window, view), isDark)
		}
	}
	return remember(isDark, sizeClass) {
		object : PlatformContext {
			// TODO: remove this and usages of it as compose will do it by default in alpha03
			override fun clickSound() {
				view.playSoundEffect(SoundEffectConstants.CLICK)
			}

			override fun checkLocalNetworkPermission() {
				if (Build.VERSION.SDK_INT < 37) return

				val hasPermission = context.checkSelfPermission(
					Manifest.permission.ACCESS_LOCAL_NETWORK
				) == PackageManager.PERMISSION_GRANTED
				if (hasPermission) return

				// The hosting Activity, not `view.context`: in Compose that context is usually a
				// ContextThemeWrapper, and casting it blind used to risk an NPE — now that this
				// runs on every launch rather than only on a login tap, that would be a crash.
				requestPermissions(
					activity,
					arrayOf(Manifest.permission.ACCESS_LOCAL_NETWORK),
					500
				)
			}

			override val platformType = PlatformType.Android
			override val name = "Android ${Build.VERSION.SDK_INT}"
			override val appVersion: String =
				context.packageManager
					.getPackageInfo(context.packageName, 0)
					.versionName.toString()
			// A `val`, NOT a `get()`. Building this resolves ~49 Android system colour
			// resources and returns a fresh ColorScheme — and ColorScheme has identity
			// equality, so as a getter every read handed MaterialExpressiveTheme a
			// different instance and invalidated every descendant reading
			// MaterialTheme.colorScheme. NavicTheme reads it on each recomposition, so
			// that was the whole app, on every navigation. The enclosing object is
			// already remembered on [isDark], which is this value's only input, so
			// computing it once here is exactly as correct and vastly cheaper.
			override val colorScheme: ColorScheme =
				if (Build.VERSION.SDK_INT >= 31)
					if (isDark)
						if (preferenceManager.amoled)
							dynamicDarkColorScheme(context).copy(
								surface = Color.Black,
								onSurface = Color.White,
								background = Color.Black,
								onBackground = Color.White
							)
						else dynamicDarkColorScheme(context)
					else dynamicLightColorScheme(context)
				else
					if (isDark)
						darkColorScheme()
					else expressiveLightColorScheme()
			override val sizeClass = sizeClass
		}
	}
}

actual fun <T> synchronized(lock: Any, block: () -> T): T = kotlin.synchronized(lock, block)

/**
 * Who decides the status-bar icon colour.
 *
 * There are two callers with different lifetimes and neither can simply own the window flag.
 * [rememberPlatformContext]'s `SideEffect` re-asserts the app theme's brightness after every root
 * recomposition; [ForceSystemBars] asserts a page's own, from a `DisposableEffect` that only
 * re-runs when its argument changes. Written directly, the first always wins eventually.
 *
 * So the page's value is an OVERRIDE over the app default, and the overrides are a stack rather
 * than a single slot: during a push both the old screen's and the new screen's effects are alive,
 * and restoring "the previous value" on dispose — what this used to do — handed back a value the
 * other screen had already replaced. Keyed by token, a screen removes only its own entry and the
 * one still standing is the one still on screen.
 */
private object SystemBars {
	private var controller: WindowInsetsControllerCompat? = null
	private var appDefaultIsDark: Boolean = false
	private val overrides = mutableListOf<Pair<Any, Boolean>>()

	fun setAppDefault(controller: WindowInsetsControllerCompat, isDark: Boolean) {
		this.controller = controller
		appDefaultIsDark = isDark
		apply()
	}

	fun push(controller: WindowInsetsControllerCompat?, token: Any, isDark: Boolean) {
		controller?.let { this.controller = it }
		overrides.removeAll { it.first === token }
		overrides.add(token to isDark)
		apply()
	}

	fun pop(token: Any) {
		overrides.removeAll { it.first === token }
		apply()
	}

	private fun apply() {
		// Light (white) icons over a dark background, dark icons over a light one.
		val isDark = overrides.lastOrNull()?.second ?: appDefaultIsDark
		controller?.isAppearanceLightStatusBars = !isDark
	}
}

@Composable
actual fun ForceSystemBars(isDarkBackground: Boolean) {
	val view = LocalView.current
	val token = remember { Any() }
	DisposableEffect(isDarkBackground, token) {
		val controller = (view.context as? Activity)?.window?.let {
			WindowCompat.getInsetsController(it, view)
		}
		SystemBars.push(controller, token, isDarkBackground)
		onDispose { SystemBars.pop(token) }
	}
}

actual val currentPlatformType: PlatformType = PlatformType.Android
