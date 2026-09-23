package paige.navic.ui.screens.mixes

import androidx.compose.runtime.Composable
import navic.composeapp.generated.resources.Res
import navic.composeapp.generated.resources.mix_kind_adaptive
import navic.composeapp.generated.resources.mix_kind_artist
import navic.composeapp.generated.resources.mix_kind_fingerprint
import navic.composeapp.generated.resources.mix_kind_genre
import navic.composeapp.generated.resources.mix_kind_similar
import navic.composeapp.generated.resources.mix_kind_unknown
import navic.composeapp.generated.resources.mix_seeded_by
import org.jetbrains.compose.resources.stringResource
import paige.navic.domain.models.Mix
import paige.navic.domain.models.MixKind

/**
 * Display rules for mixes, shared by the Mixed for You screen and the Discover row —
 * the same arrangement `SavedQueueFormat.kt` has, and for the same reason: one
 * record, several surfaces, and it must read identically on all of them.
 *
 * Feishin renders the same hub-side list, so these labels are kept deliberately in
 * step with its own. There is no shared build; see `DiscoverRows.kt`.
 */

/** What kind of recipe this is, named for a person rather than for the wire. */
@Composable
fun mixKindName(kind: String): String = when (kind) {
	MixKind.SIMILAR -> stringResource(Res.string.mix_kind_similar)
	MixKind.FINGERPRINT -> stringResource(Res.string.mix_kind_fingerprint)
	MixKind.ADAPTIVE -> stringResource(Res.string.mix_kind_adaptive)
	MixKind.GENRE -> stringResource(Res.string.mix_kind_genre)
	MixKind.ARTIST -> stringResource(Res.string.mix_kind_artist)
	// Not the raw value, unlike `queueKindLabel`: a saved queue's unknown kind is
	// cosmetic, but an unknown mix kind is one this build cannot regenerate, and the
	// label is where the user finds that out before tapping it.
	else -> stringResource(Res.string.mix_kind_unknown)
}

/**
 * The recipe as one line: the kind, plus what it is seeded from when that is a
 * thing the user named.
 *
 * The seedless kinds genuinely have nothing to add — Sonic Fingerprint is built
 * from listening history and Mood Flow from a steering character — so they render
 * the kind alone rather than an empty "from".
 */
@Composable
fun mixKindLabel(mix: Mix): String {
	val kind = mixKindName(mix.kind)
	val seed = mix.seedName.ifBlank { mix.seedId }
	return if (MixKind.needsSeed(mix.kind) && seed.isNotBlank()) {
		stringResource(Res.string.mix_seeded_by, kind, seed)
	} else {
		kind
	}
}
