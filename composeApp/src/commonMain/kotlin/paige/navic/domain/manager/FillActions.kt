package paige.navic.domain.manager

/**
 * What a fill's buttons do (`FillButton`), written once for every surface that offers them: the
 * Downloads page's rows and the album page's request box (P4). They used to be the Downloads view
 * model's own, and a second copy in the box would be a second place for the rules below to drift.
 *
 * Each returns lb-bot's answer and announces nothing: what a refusal looks like is the caller's
 * (a snackbar on Downloads, a line in the box). None of them is tied to a screen, and a caller that
 * may be popped before the answer arrives runs them to completion itself.
 */
class FillActions(private val lbBot: LbBotManager) {
	/**
	 * Re-issue a fill. Never automatic — lb-bot walks its whole ranked source list before
	 * reporting failure, so an unattended retry re-runs the identical search; the user
	 * asking again is the new information.
	 */
	suspend fun retry(key: String): LbBotManager.LbResult<LbOk> = lbBot.retry(key)

	/** Retry with the peer that failed or crawled ruled out. */
	suspend fun retryAnotherSource(key: String): LbBotManager.LbResult<LbOk> = lbBot.retryAnotherSource(key)

	suspend fun cancel(key: String): LbBotManager.LbResult<LbOk> = lbBot.cancelFill(key)

	/**
	 * Widen this one album's search to include mp3, then try again. Offered when lb-bot
	 * said the search rejected mp3s and would otherwise have found something.
	 *
	 * With a review group the opt-in is set on the group and the retry follows — only
	 * if the opt-in took; it used to retry regardless, under the very format policy that
	 * had just rejected everything. Without one, the retry itself carries `allowMp3`.
	 *
	 * Null when lb-bot refused the opt-in, so nothing was retried.
	 */
	suspend fun allowMp3AndRetry(entry: LbFillEntry): LbBotManager.LbResult<LbOk>? {
		if (entry.groupId.isNotBlank()) {
			val allowed = lbBot.allowMp3(entry.groupId, allow = true)
			if (allowed !is LbBotManager.LbResult.Ok || !allowed.value.ok) return null
			return lbBot.retry(entry.key)
		}
		return lbBot.retryAllowMp3(entry.key)
	}

	/**
	 * Keep wanting a release nobody was sharing.
	 *
	 * The ONLY action offered on a `no_source` failure, and the reason the row is
	 * not simply a dead end: a plain retry re-runs an identical ranked search against
	 * identical peers and fails identically, which is exactly why lb-bot marks it
	 * `retryable: false`. The retry that can work is a slow periodic one, and the
	 * wishlist is where it is registered.
	 *
	 * Dismisses the ledger row on success, because leaving both would show one
	 * release in two lists saying opposite things about whether it is still wanted.
	 */
	suspend fun addToWishlist(entry: LbFillEntry): Boolean {
		if (lbBot.wishlistAdd(entry.rgid, entry.artist, entry.album) == null) return false
		lbBot.dismiss(entry.key)
		return true
	}

	/** Forget one finished row. Only ever offered on a settled fill. */
	fun dismiss(key: String) = lbBot.dismiss(key)
}
