package paige.navic.ui.screens.settings

/**
 * What Storage's Danger zone can do. [confirms]: it deletes something that is not a cache, so it asks first.
 * [needsServer]: it wipes what only the server can put back, so it must not start offline.
 */
enum class DangerAction(val confirms: Boolean, val needsServer: Boolean = false) {
	/** The image cache is rebuilt from the server as pages are opened. */
	ClearImageCache(false),

	/** Changes made offline that have not reached the server yet. They are lost. */
	ClearPendingActions(true),

	/** Every downloaded file on this device. */
	ClearDownloads(true),

	/** This device's copy of the library, pulled again from the server. */
	RebuildDatabase(true, needsServer = true)
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

/**
 * Whether a confirmed [action] may still run. The row is greyed while offline, but the dialog stays up
 * while the connection can drop, and a rebuild that wipes the library with no sync possible leaves it
 * empty. So the confirm checks again; offline, nothing is deleted.
 */
fun mayRunNow(action: DangerAction, online: Boolean): Boolean = online || !action.needsServer
