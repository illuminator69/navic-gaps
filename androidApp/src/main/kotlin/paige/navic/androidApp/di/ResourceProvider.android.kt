package paige.navic.androidApp.di

import paige.navic.di.ResourceProvider

class AndroidResourceProvider(
	override val appIconDefault: Int = paige.navic.androidApp.R.mipmap.ic_launcher,
	override val appIconInverted: Int = paige.navic.androidApp.R.mipmap.ic_launcher_inverted,
	override val icNavic: Int = paige.navic.androidApp.R.drawable.ic_navic,
	override val animPause: Int = paige.navic.androidApp.R.drawable.anim_pause
) : ResourceProvider
