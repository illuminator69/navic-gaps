package paige.navic.ui.components.layouts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.navigation3.runtime.NavKey
import kotlin.time.Duration
import kotlinx.collections.immutable.ImmutableList
import navic.composeapp.generated.resources.Res
import navic.composeapp.generated.resources.action_see_all
import navic.composeapp.generated.resources.count_plays
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import paige.navic.di.LocalNavStack
import paige.navic.ui.components.common.CoverArt
import paige.navic.util.toSummaryString

// TODO: get rid of this and use ArtGrid and ArtGridItem
@Composable
fun <T> ArtCarousel(
	title: String,
	items: ImmutableList<T>,
	destination: NavKey? = null,
	/** navi-connect: the line naming why a recommendation row is there, as `horizontalSection`'s. */
	because: String? = null,
	modifier: Modifier = Modifier,
	content: @Composable (item: T) -> Unit
) {
	val backStack = LocalNavStack.current

	if (items.isNotEmpty()) {
		Column(modifier) {
			Row(
				modifier = Modifier
					.fillMaxWidth()
					.padding(horizontal = 16.dp),
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.SpaceBetween
			) {
				Text(
					title,
					style = MaterialTheme.typography.titleMediumEmphasized,
					fontWeight = FontWeight(600),
					color = MaterialTheme.colorScheme.primary,
					modifier = Modifier.heightIn(min = 32.dp).padding(top = 12.dp)
				)
				if (destination != null) {
					Text(
						stringResource(Res.string.action_see_all),
						fontSize = 12.sp,
						color = MaterialTheme.colorScheme.primary,
						modifier = Modifier
							.heightIn(min = 32.dp).padding(top = 12.dp)
							.clickable(onClick = dropUnlessResumed {
								backStack.add(destination)
							})
					)
				}
			}
			if (!because.isNullOrBlank()) {
				Text(
					because,
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					maxLines = 2,
					overflow = TextOverflow.Ellipsis,
					modifier = Modifier.padding(horizontal = 16.dp)
				)
			}
			LazyRow(
				modifier = Modifier
					.fillMaxWidth()
					.padding(top = 16.dp, bottom = 16.dp),
				horizontalArrangement = Arrangement.spacedBy(12.dp),
				contentPadding = PaddingValues(horizontal = 16.dp)
			) {
				items(items) { item ->
					content(item)
				}
			}
		}
	}
}

@Composable
fun ArtCarouselItem(
	coverArtId: String?,
	title: String,
	subtitle: String? = null,
	playCount: Int? = null,
	duration: Duration? = null,
	contentDescription: String?,
	/** See `CoverArt.isArtist` — an art-less artist has no id to infer from. */
	isArtist: Boolean? = null,
	onSelect: () -> Unit = {},
	/**
	 * navi-connect: a cover drawn instead of `CoverArt`, for a card whose picture is not a
	 * library id's (lb-bot's art: `LbArtImage`). Given the card's interaction source, so the
	 * cover still ripples for a press anywhere on the card.
	 */
	cover: (@Composable (MutableInteractionSource) -> Unit)? = null,
	/** navi-connect: laid over the cover (a release's state, a one-tap acquire). */
	coverOverlay: (@Composable BoxScope.() -> Unit)? = null,
	/** navi-connect: for a row's item animation (`animateItem`). */
	modifier: Modifier = Modifier,
	onClick: () -> Unit = {}
) {
	val focusManager = LocalFocusManager.current
	val interactionSource = remember { MutableInteractionSource() }

	Column(
		modifier = modifier
			.width(150.dp)
			.combinedClickable(
				interactionSource = interactionSource,
				indication = null,
				onClick = {
					focusManager.clearFocus(true)
					onClick()
				},
				onLongClick = onSelect
			)
			.semantics {
				this.contentDescription = contentDescription ?: title
			}
	) {
		Box(Modifier.fillMaxWidth()) {
			if (cover != null) {
				cover(interactionSource)
			} else {
				CoverArt(
					coverArtId = coverArtId,
					contentDescription = contentDescription,
					interactionSource = interactionSource,
					isArtist = isArtist,
					modifier = Modifier.fillMaxWidth()
				)
			}
			coverOverlay?.invoke(this)
		}

		Text(
			text = title,
			style = MaterialTheme.typography.titleSmallEmphasized,
			modifier = Modifier.padding(top = 6.dp),
			maxLines = 2,
			overflow = TextOverflow.Ellipsis
		)

		subtitle?.let {
			Text(
				text = subtitle,
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis
			)
		}

		if (playCount != null && playCount > 0) {
			val playsText = pluralStringResource(Res.plurals.count_plays, playCount, playCount)
			val timeText = duration?.toSummaryString()

			Text(
				text = if (timeText != null) "$playsText • $timeText" else playsText,
				style = MaterialTheme.typography.labelSmall,
				color = MaterialTheme.colorScheme.primary,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis
			)
		}
	}
}
