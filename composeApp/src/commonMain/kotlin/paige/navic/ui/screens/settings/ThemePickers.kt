package paige.navic.ui.screens.settings

/** How the Theme mode and Theme pickers stand while "Colours from artwork" is [dynamicTheming]. */
data class ThemePickers(
	/** Whether they can be changed. */
	val enabled: Boolean,
	/** Whether the one-line note ("Pages with album art take their colours from it") is drawn. */
	val noteShown: Boolean
)

/**
 * Artwork colours decide pages that have album art; the stored mode and theme still decide every
 * page without (settings, dialogs, a browsing page with nothing playing). So they are never greyed:
 * they are enabled, and the note says where artwork wins.
 */
fun themePickers(dynamicTheming: Boolean): ThemePickers =
	ThemePickers(enabled = true, noteShown = dynamicTheming)
