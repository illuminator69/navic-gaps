package paige.navic.ui.components.sheets

import paige.navic.di.PlatformType

/**
 * Whether this build may ask GitHub for a newer version. The check, the launch sheet and About's
 * switch all read this one answer.
 */
fun updateChecksOffered(platformType: PlatformType, fdroid: Boolean, debugBuild: Boolean): Boolean =
	platformType == PlatformType.Android && !fdroid && debugBuild

/**
 * The version check, without its HTTP client so a test can count the calls. [offered] is
 * [updateChecksOffered] for this build.
 */
class UpdateChecker(
	private val offered: Boolean,
	private val fetchLatest: suspend () -> GitHubRelease
) {
	/** The release to offer, or null: nothing newer, the check failed, or this build does not check. */
	suspend fun check(currentVersion: String): GitHubRelease? {
		if (!offered) return null
		val release = fetchLatest()
		val remoteVersion = release.tag
			.filter { it.isDigit() }
			.toIntOrNull() ?: return null
		val localVersion = currentVersion
			.filter { it.isDigit() }
			.toIntOrNull() ?: return null
		return if (remoteVersion > localVersion || "$remoteVersion".length != "$localVersion".length)
			release
		else null
	}
}
