package paige.navic.ui.screens.queue

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import navic.composeapp.generated.resources.Res
import navic.composeapp.generated.resources.queue_mood_all
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import paige.navic.domain.manager.ForSongAnswer
import paige.navic.domain.manager.MoodEntry
import paige.navic.domain.manager.MoodManager
import paige.navic.domain.manager.find
import paige.navic.domain.manager.moodChipsFor
import paige.navic.icons.Icons
import paige.navic.icons.outlined.Check

/**
 * The moods the queue offers for the playing song (P7 Part B): the hub's for it ([moodChipsFor]),
 * with the selected mood kept in the row even when the song is not one of its own (so it can be
 * seen and undone). Asked when the queue is shown and when the playing song changes while it is;
 * the answer is kept for the session, so a song heard again asks nothing.
 */
@Composable
fun rememberQueueMoods(songId: String?, selected: String?, offered: Boolean): List<MoodEntry> {
	val moodManager = koinInject<MoodManager>()
	val catalogue by moodManager.catalogue.collectAsState()
	var answer by remember { mutableStateOf<ForSongAnswer?>(null) }
	LaunchedEffect(songId, offered) {
		answer = null
		if (offered && songId != null) answer = moodManager.forSong(songId)
	}
	if (!offered) return emptyList()
	val forSong = moodChipsFor(answer, catalogue)
	val kept = selected?.let { catalogue.find(it) }?.takeIf { s -> forSong.none { it.id == s.id } }
	return listOfNotNull(kept) + forSong
}

/**
 * One sideways row: All and the moods ([showMoods]), a thin divider, then [options] (the characters,
 * or Off and the characters), the selected chip of each group filled. A chip's check eases in and
 * out as `PageChips`' do. Without moods it is the characters alone, with no gap.
 */
@Composable
fun <T> MoodAndCharacterChips(
	showMoods: Boolean,
	moods: List<MoodEntry>,
	selectedMood: String?,
	onMood: (String?) -> Unit,
	options: List<T>,
	selected: T,
	label: @Composable (T) -> String,
	onSelect: (T) -> Unit,
	modifier: Modifier = Modifier,
	contentPadding: PaddingValues = PaddingValues(horizontal = 24.dp)
) {
	Row(
		modifier = modifier
			.fillMaxWidth()
			.horizontalScroll(rememberScrollState())
			.padding(contentPadding),
		horizontalArrangement = Arrangement.spacedBy(8.dp),
		verticalAlignment = Alignment.CenterVertically
	) {
		if (showMoods) {
			QueueChip(stringResource(Res.string.queue_mood_all), selectedMood == null) { onMood(null) }
			moods.forEach { mood ->
				QueueChip(mood.label, mood.id == selectedMood) { onMood(mood.id) }
			}
			VerticalDivider(Modifier.height(24.dp).padding(horizontal = 2.dp))
		}
		options.forEach { option ->
			QueueChip(label(option), option == selected) { onSelect(option) }
		}
	}
}

@Composable
private fun QueueChip(text: String, isSelected: Boolean, onClick: () -> Unit) {
	FilterChip(
		modifier = Modifier.animateContentSize(
			if (isSelected) MaterialTheme.motionScheme.fastSpatialSpec()
			else MaterialTheme.motionScheme.defaultEffectsSpec()
		),
		selected = isSelected,
		onClick = onClick,
		label = { Text(text, maxLines = 1) },
		shape = MaterialTheme.shapes.small,
		leadingIcon = if (isSelected) {
			{ Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
		} else null
	)
}
