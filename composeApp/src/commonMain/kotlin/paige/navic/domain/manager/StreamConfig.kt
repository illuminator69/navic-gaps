package paige.navic.domain.manager

import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Everything that decides which URL a queued song resolves to: the downloads, the network, and the
 * stream quality settings. The player re-points its queue each time this changes, so a setting
 * takes effect without a queue change. The first element is the downloaded-songs map; the rest are
 * only there to be compared.
 */
fun streamConfigChanges(
	preferenceManager: PreferenceManager,
	downloadedSongs: Flow<Map<String, String>>,
	isCellular: Flow<Boolean>,
	isOnline: Flow<Boolean>
): Flow<List<Any?>> = combine(
	downloadedSongs,
	isCellular,
	snapshotFlow { preferenceManager.streamingQualityWifi },
	snapshotFlow { preferenceManager.streamingQualityCellular },
	snapshotFlow { preferenceManager.isAdvancedTranscodingActive },
	snapshotFlow { preferenceManager.customMaxBitrateWifi },
	snapshotFlow { preferenceManager.customMaxBitrateCellular },
	// The custom container too: it used to be left out, so a format picked in Settings waited for
	// the next queue change before the playing queue asked the server for it.
	snapshotFlow { preferenceManager.customFormatWifi },
	snapshotFlow { preferenceManager.customFormatCellular },
	// So flipping the toggle (or moving on/off cellular) re-points the queue at the
	// downloads or back at the server without needing a re-queue.
	snapshotFlow { preferenceManager.preferDownloadsOnCellular },
	isOnline
) { it.toList() }.distinctUntilChanged()
