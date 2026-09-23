package paige.navic.ui.screens.mixes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import org.koin.compose.koinInject
import paige.navic.domain.manager.PreferenceManager
import paige.navic.domain.models.Mix
import paige.navic.domain.models.MixKind
import paige.navic.icons.Icons
import paige.navic.icons.outlined.Artist
import paige.navic.icons.outlined.Genre
import paige.navic.icons.outlined.History
import paige.navic.icons.outlined.Note
import paige.navic.icons.outlined.PlaylistPlay
import paige.navic.icons.outlined.Speed
import paige.navic.ui.components.common.CoverArt

/**
 * The face of a mix, in the one place both surfaces read it from.
 *
 * A mix had no face at all until now: `Mix.coverArtId` existed on the model and in
 * the hub record, nothing ever wrote it, and so both the list row and the Discover
 * tile always took `CoverArt`'s art-less branch — a plain themed square. A shelf of
 * those is unreadable, which is exactly the complaint.
 *
 * Two cases, and the split is the whole design:
 *
 * - **A stamped cover** is the SEED's artwork, and it is drawn as artwork. The seed
 *   is a fixed part of the recipe, so it stays true on a second play. (It is
 *   emphatically *not* the last regenerated queue's cover, which would be a claim
 *   about what plays next that a recipe cannot make.)
 * - **No cover** — the two seedless kinds, and any recipe saved before this landed —
 *   gets a generated tile keyed on the mix's `kind` and `id`. That is deliberately
 *   *not* fake album art: it is an icon naming the kind over a gradient that only
 *   varies so two fingerprint mixes can be told apart at a glance. It says "this is
 *   a fingerprint mix", never "this is what will play".
 *
 * Feishin's `mixes-row.tsx` declines to generate anything, on the grounds that a
 * generated cover "would be a lie about what is inside". That objection is right
 * about invented *album art* and does not reach an icon; recorded as a deliberate
 * divergence rather than a drift.
 *
 * One composable rather than two call sites, because a fix applied to the row and
 * not to the tile is how the two come to disagree.
 */
@Composable
fun MixArtwork(
	mix: Mix,
	modifier: Modifier = Modifier,
	onClick: (() -> Unit)? = null
) {
	val cover = mix.coverArtId.ifBlank { null }
	if (cover != null) {
		CoverArt(
			modifier = modifier,
			coverArtId = cover,
			contentDescription = mix.name,
			onClick = onClick,
			// Always a record, never a person: an artist mix is seeded from an
			// artist but the stamped id is that artist's own `ar-` cover, and
			// letting the inference make it a circle in a row of squares is the
			// trap `CoverArt.isArtist` documents.
			isArtist = false
		)
		return
	}

	val preferenceManager = koinInject<PreferenceManager>()
	val scheme = MaterialTheme.colorScheme
	// Three container roles rather than free-floating hues: the page is washed in
	// the current artwork's colours (CLAUDE.md §6) and a tile painting its own
	// invented colour sits on that as a slab. Picking between the scheme's own
	// roles keeps every tile in the page's tonal register while still separating
	// one mix from the next.
	val (top, glyph) = when (kotlin.math.abs(mix.id.hashCode()) % 3) {
		0 -> scheme.primaryContainer to scheme.onPrimaryContainer
		1 -> scheme.secondaryContainer to scheme.onSecondaryContainer
		else -> scheme.tertiaryContainer to scheme.onTertiaryContainer
	}

	Box(
		modifier = modifier
			.aspectRatio(1f)
			.clip(preferenceManager.coverArtShape.shape)
			.background(Brush.linearGradient(listOf(top, scheme.surfaceContainerHighest)))
			.then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
		contentAlignment = Alignment.Center
	) {
		Icon(
			imageVector = mixKindIcon(mix.kind),
			contentDescription = mix.name,
			// The gradient runs from a container role, so its own `on` colour is
			// the one guaranteed to stay legible against it in both themes. Taken
			// from the same branch that chose the role rather than matched back by
			// value — two roles can resolve to the same Color under a washed
			// scheme, and an equality lookup would then pick the wrong `on`.
			tint = glyph,
			modifier = Modifier.fillMaxSize().padding(24.dp)
		)
	}
}

/** The kind, as a glyph. Kept beside `mixKindName` so the two never disagree. */
private fun mixKindIcon(kind: String): ImageVector = when (kind) {
	MixKind.SIMILAR -> Icons.Outlined.Note
	MixKind.FINGERPRINT -> Icons.Outlined.History
	MixKind.ADAPTIVE -> Icons.Outlined.Speed
	MixKind.GENRE -> Icons.Outlined.Genre
	MixKind.ARTIST -> Icons.Outlined.Artist
	// A kind this build cannot regenerate still gets a face; the row's label is
	// where it says so, and a blank tile would read as a loading one.
	else -> Icons.Outlined.PlaylistPlay
}
