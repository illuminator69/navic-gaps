package paige.navic.domain.manager

/**
 * No-op: iOS has no foreground service, and no iOS feature in this fork is
 * implemented or verified. It exists so `commonMain` compiles for the target.
 */
actual class DownloadForegroundController {
	actual fun start() = Unit
	actual fun stop() = Unit
}
