package paige.navic.domain.models.settings

import navic.composeapp.generated.resources.Res
import navic.composeapp.generated.resources.option_explicit_playback_allowed
import navic.composeapp.generated.resources.option_explicit_playback_skip
import navic.composeapp.generated.resources.option_explicit_playback_skip_session
import org.jetbrains.compose.resources.StringResource

enum class ExplicitContentPlayback(val displayName: StringResource) {
	Allowed(Res.string.option_explicit_playback_allowed),
	Skip(Res.string.option_explicit_playback_skip),
	SkipForThisSession(Res.string.option_explicit_playback_skip_session)
}

/**
 * "Skip for this session" was never implemented (nothing ever reset it), so it only ever behaved as
 * Skip. It stays in the enum because it may be stored; every reader sees [ExplicitContentPlayback.Skip].
 */
fun ExplicitContentPlayback.normalised(): ExplicitContentPlayback =
	if (this == ExplicitContentPlayback.SkipForThisSession) ExplicitContentPlayback.Skip else this

/** Whether explicit songs are held back from playback and shown locked. */
fun ExplicitContentPlayback.skipsExplicit(): Boolean = normalised() != ExplicitContentPlayback.Allowed
