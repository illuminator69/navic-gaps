package paige.navic.ui.screens.settings

/** What Storage's Danger zone can do. [confirms]: it deletes something that is not a cache, so it asks first. */
enum class DangerAction(val confirms: Boolean) {
	/** The image cache is rebuilt from the server as pages are opened. */
	ClearImageCache(false),

	/** Changes made offline that have not reached the server yet. They are lost. */
	ClearPendingActions(true),

	/** Every downloaded file on this device. */
	ClearDownloads(true),

	/** This device's copy of the library, pulled again from the server. */
	RebuildDatabase(true)
}

/**
 * A row in the Danger zone was tapped. An action that deletes something asks first: this returns it,
 * for the screen to put its confirmation up, and [run] is not called until the confirmation is. One
 * that does not (a cache) runs at once and returns null.
 */
fun requestDanger(action: DangerAction, run: (DangerAction) -> Unit): DangerAction? {
	if (action.confirms) return action
	run(action)
	return null
}
