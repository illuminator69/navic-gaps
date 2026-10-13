package paige.navic.domain.manager

/**
 * Do the phone's volume buttons belong to the device that is playing, right now?
 *
 * While another device plays, the media session can be declared remote, and Android then sends
 * the buttons to it from anywhere, in every other app. Whether it also draws a slider for that
 * depends on the Android build: the phone this was reported on drew none, the API 37 emulator
 * drew a cast row. So turning the phone down while reading something else moved a speaker in
 * another room, and on that phone nothing said so.
 *
 * So the session is remote only where the buttons can mean nothing else: with Navic on screen,
 * which then draws the level itself (`RemoteVolumeHud`), and with the phone locked, where there
 * is no other app in front and no local sound to turn down. Everywhere else the session is
 * declared local, and the buttons move the phone's own volume with the system's slider.
 */
fun volumeKeysGoRemote(appOnScreen: Boolean, phoneLocked: Boolean): Boolean =
	appOnScreen || phoneLocked
