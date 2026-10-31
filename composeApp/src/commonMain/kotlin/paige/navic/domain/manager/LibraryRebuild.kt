package paige.navic.domain.manager

/**
 * Settings' Rebuild database: wipe this device's copy of the library and pull it again. The pull
 * starts only once the wipe has finished (it used to be started beside it, so it could read a
 * library the wipe was still emptying), and the periodic cycle is stopped before the wipe so a pull
 * in flight cannot put rows back into it.
 */
suspend fun rebuildLibrary(stopSync: () -> Unit, wipe: suspend () -> Unit, sync: () -> Unit) {
	stopSync()
	wipe()
	sync()
}
