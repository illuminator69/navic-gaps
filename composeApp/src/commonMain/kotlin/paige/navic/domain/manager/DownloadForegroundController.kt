package paige.navic.domain.manager

/**
 * Keeps the process alive while transfers are in flight.
 *
 * **The bug this exists for.** [DownloadManager]'s scope is a process-scoped
 * `SupervisorJob` on a Koin singleton, and nothing in this app has ever cancelled
 * it — there is no `ProcessLifecycleOwner`, no lifecycle observer, no `onStop`. So
 * "downloads stop when you leave the app" was never a cancellation bug. It was the
 * OS: with no foreground service the process drops to *cached* the moment the last
 * Activity stops, and Android 12+'s freezer suspends its threads. The transfers
 * stall, and on the next launch `reconcileInterruptedDownloads` finds rows claiming
 * to be active with no coroutine behind them and parks them `FAILED / "Interrupted"`
 * — which is exactly what a user downloading a large playlist sees.
 *
 * `setOngoing(true)` on the progress notification confers no process priority
 * whatsoever; only `startForeground` does.
 *
 * An `expect` rather than something in `DownloadManager`, because that class is
 * `commonMain` and a foreground service is an Android concept. iOS gets a no-op:
 * `commonMain` only has to *compile* there.
 */
expect class DownloadForegroundController {
	/**
	 * Called when the queue goes from empty to non-empty. Must be idempotent — the
	 * counters that drive it move on every claimed job.
	 */
	fun start()

	/** Called when the queue drains, or is cancelled wholesale. Also idempotent. */
	fun stop()
}
