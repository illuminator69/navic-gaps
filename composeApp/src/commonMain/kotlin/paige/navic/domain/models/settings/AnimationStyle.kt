package paige.navic.domain.models.settings

import navic.composeapp.generated.resources.Res
import navic.composeapp.generated.resources.option_animation_style_expressive
import navic.composeapp.generated.resources.option_animation_style_quick
import navic.composeapp.generated.resources.option_animation_style_standard
import org.jetbrains.compose.resources.StringResource

// Stored by ordinal (BasePreferenceManager.getEnum): append only, never reorder.
enum class AnimationStyle(val displayName: StringResource) {
	Expressive(Res.string.option_animation_style_expressive),
	Standard(Res.string.option_animation_style_standard),
	// navi-connect I-042: shorter, lower-travel page, cover-flight and sheet motion (PageMotion.kt).
	Quick(Res.string.option_animation_style_quick)
}
