package paige.navic.ui.components.sheets

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import navic.composeapp.generated.resources.Res
import navic.composeapp.generated.resources.action_allow_mp3
import navic.composeapp.generated.resources.action_download_from_source
import navic.composeapp.generated.resources.action_show_files
import navic.composeapp.generated.resources.action_find_sources
import navic.composeapp.generated.resources.action_cancel_download
import navic.composeapp.generated.resources.info_already_downloading
import navic.composeapp.generated.resources.info_hub_needs_restart
import navic.composeapp.generated.resources.info_pending_sync_detail
import navic.composeapp.generated.resources.info_mp3_would_help
import navic.composeapp.generated.resources.info_no_sources
import navic.composeapp.generated.resources.info_searching_sources
import navic.composeapp.generated.resources.info_source_complete
import navic.composeapp.generated.resources.info_source_failover
import navic.composeapp.generated.resources.info_source_matches_album
import navic.composeapp.generated.resources.info_source_partial
import navic.composeapp.generated.resources.info_source_recommended
import navic.composeapp.generated.resources.info_source_wrong_album
import navic.composeapp.generated.resources.label_edition
import navic.composeapp.generated.resources.label_quality_preference
import navic.composeapp.generated.resources.label_version
import navic.composeapp.generated.resources.title_missing_album
import navic.composeapp.generated.resources.title_sources
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import paige.navic.domain.manager.LbBotManager
import paige.navic.ui.components.common.failureHeadline
import paige.navic.ui.components.common.formatBytes
import paige.navic.ui.components.common.resolve
import paige.navic.ui.components.common.runningHeadline
import paige.navic.domain.manager.LbFillStatus
import paige.navic.domain.manager.LbGapSource
import paige.navic.domain.manager.LbRelease
import paige.navic.domain.manager.LbReleaseDetail
import paige.navic.domain.manager.LbResolvedEdition
import paige.navic.domain.manager.LbTracklist
import paige.navic.ui.components.common.RemoteCoverArt

/**
 * A release the library doesn't have: which pressing to fetch, from whom, and how
 * the fetch is going.
 *
 * **Downloading is a two-step, on purpose.** The first version fired lb-bot's
 * top-ranked pick straight off, and for a self-titled album — where every peer
 * folder's name looks plausible — it fetched the wrong record, with no way to
 * tell before or after. So the sources are searched first and shown with the
 * evidence needed to judge them: coverage matched against the canonical
 * MusicBrainz tracklist (not a file count), an explicit "is this even the right
 * album" verdict, and the format actually on offer. The top-ranked source is
 * listed first, so confirming is one extra tap rather than a research project.
 *
 * The quality dropdown stays, but it is a *ranking* term upstream, not a filter —
 * hence "prefer", never "only". Seeing the real format in the source row is what
 * actually answers "what am I about to get".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MissingAlbumSheet(
	release: LbRelease,
	onDismissRequest: () -> Unit,
	/**
	 * Status only: the release-group's detail, its tracklist and whatever the fill
	 * ledger says, with no way to start another download.
	 *
	 * This is how a `pendingSync` tile becomes tappable — lb-bot has flipped its
	 * index row to `present` and Navidrome has not indexed the files yet, so there
	 * is no album to open but there is very much something to say. Offering "get
	 * this album" for one that just landed would be the wrong answer to the tap.
	 */
	readOnly: Boolean = false
) {
	val lbBot = koinInject<LbBotManager>()
	val scope = rememberCoroutineScope()

	var detail by remember(release.rgid) { mutableStateOf<LbReleaseDetail?>(null) }
	var loadingDetail by remember(release.rgid) { mutableStateOf(true) }
	var variantIndex by remember(release.rgid) { mutableStateOf(0) }
	var editionMbid by remember(release.rgid) { mutableStateOf("") }
	var tracklist by remember(release.rgid) { mutableStateOf<LbTracklist?>(null) }
	var quality by remember { mutableStateOf(lbBot.preferredQuality) }
	var qualityMenuOpen by remember { mutableStateOf(false) }

	var sources by remember(release.rgid) { mutableStateOf<List<LbGapSource>?>(null) }
	var searching by remember(release.rgid) { mutableStateOf(false) }
	var starting by remember(release.rgid) { mutableStateOf(false) }
	var alreadyRunning by remember(release.rgid) { mutableStateOf(false) }
	var error by remember(release.rgid) { mutableStateOf<LbBotManager.LbError?>(null) }

	val fills by lbBot.fills.collectAsState()
	val status = fills[release.rgid]

	// Stale-while-revalidate (`LbBotManager.cachedGet`): a release-group seen before paints its
	// editions from Room at once, and a revalidated answer replaces them only if it differs.
	LaunchedEffect(release.rgid) {
		loadingDetail = true
		lbBot.albumReleases(release.rgid).collect { answer ->
			detail = answer
			loadingDetail = false
			val variants = answer?.releases.orEmpty()
			// Only the first answer picks the edition: a revalidation must not move a pick
			// the user has already made (or that the tracklist below is already following).
			if (editionMbid.isBlank()) {
				variantIndex = 0
				editionMbid = variants.firstOrNull()?.releaseMbid.orEmpty()
				return@collect
			}
			// But the variant is held by *position*, and a revalidated list need not be in the
			// same order, or the same length. Left alone, `chosenEdition()` paired the kept
			// edition with whatever variant now sat at the old index — the wrong title and track
			// count sent to `albumSources` and `download`, silently. So the index follows the
			// edition. An edition the new answer no longer lists at all is a pick that no longer
			// exists: fall back to the first variant, as the first answer did, rather than send
			// an edition next to a variant it does not belong to.
			val found = variants.indexOfFirst { variant ->
				variant.releaseMbid == editionMbid ||
					variant.editions.any { it.releaseMbid == editionMbid }
			}
			if (found >= 0) {
				variantIndex = found
			} else {
				variantIndex = 0
				editionMbid = variants.firstOrNull()?.releaseMbid.orEmpty()
			}
		}
	}

	// The tracklist is per *edition*: two pressings of one release-group can differ,
	// which is the whole reason the picker exists.
	LaunchedEffect(editionMbid) {
		if (editionMbid.isBlank()) return@LaunchedEffect
		tracklist = null
		lbBot.tracklist(editionMbid).collect { tracklist = it }
	}

	// The pressing the pickers above have already settled on. Sending it stops lb-bot re-resolving
	// the release-group to "official, earliest" — which both ignored the user's choice silently and
	// dragged the five-minute MusicBrainz failure cooldown into a button press.
	fun chosenEdition(): LbResolvedEdition? {
		val mbid = editionMbid.ifBlank { return null }
		val variant = detail?.releases?.getOrNull(variantIndex)
		return LbResolvedEdition(
			releaseMbid = mbid,
			artist = detail?.artist.orEmpty(),
			title = variant?.title?.ifBlank { null } ?: release.title,
			// The loaded tracklist is for this exact edition; the variant's count is for the
			// variant, so it is the fallback rather than the source of truth.
			totalTracks = tracklist?.tracks?.size?.takeIf { it > 0 } ?: variant?.trackCount ?: 0
		)
	}

	fun findSources() {
		scope.launch {
			searching = true
			error = null
			when (val result = lbBot.albumSources(release.rgid, chosenEdition())) {
				is LbBotManager.LbResult.Ok -> sources = result.value.sources
				is LbBotManager.LbResult.Failed -> error = result.error
			}
			searching = false
		}
	}

	fun download(source: LbGapSource?) {
		scope.launch {
			starting = true
			error = null
			// Not fast even with a source chosen: lb-bot resolves the release-group
			// against MusicBrainz before answering.
			when (val result = lbBot.download(release.rgid, quality, source, chosenEdition())) {
				is LbBotManager.LbResult.Failed -> error = result.error
				is LbBotManager.LbResult.Ok -> {
					alreadyRunning = result.value.existing
					if (result.value.ok && result.value.releaseMbid.isNotBlank()) {
						// Artist and title are carried into the watch, not looked up
						// later: the downloads view may be opened days afterwards, on a
						// screen with no artist page behind it, and by then lb-bot's own
						// (in-memory, capped) ledger may have forgotten the fill entirely.
						lbBot.startAlbumFill(
							rgid = release.rgid,
							releaseMbid = result.value.releaseMbid,
							quality = quality,
							artist = detail?.artist.orEmpty(),
							album = release.title,
							// Completes the edition snapshot the watch keeps, so Retry can
							// re-request THIS pressing instead of letting lb-bot re-resolve.
							totalTracks = chosenEdition()?.totalTracks ?: 0,
							source = source
						)
					}
				}
			}
			starting = false
		}
	}

	ModalBottomSheet(onDismissRequest = onDismissRequest) {
		Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
			Row(verticalAlignment = Alignment.CenterVertically) {
				RemoteCoverArt(
					url = LbBotManager.caaCoverUrl(release.rgid),
					modifier = Modifier.size(64.dp)
				)
				Column(Modifier.padding(start = 12.dp)) {
					Text(
						release.title,
						style = MaterialTheme.typography.titleMedium,
						maxLines = 2,
						overflow = TextOverflow.Ellipsis
					)
					Text(
						listOfNotNull(
							detail?.artist?.takeIf { it.isNotBlank() },
							release.year.takeIf { it.isNotBlank() },
							stringResource(Res.string.title_missing_album)
						).joinToString(" • "),
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant
					)
				}
			}

			Spacer(Modifier.height(16.dp))

			if (loadingDetail) {
				Box(Modifier.fillMaxWidth().height(72.dp), Alignment.Center) {
					CircularProgressIndicator(Modifier.size(24.dp))
				}
			} else {
				val variants = detail?.releases.orEmpty()
				// A variant changes the tracklist (Original / Remaster / Deluxe); an
				// edition is the same tracklist on different media. Only offer either
				// picker when there is actually a choice to make.
				if (variants.size > 1) {
					SheetLabel(stringResource(Res.string.label_version))
					ChipRow(
						labels = variants.map { variant ->
							listOfNotNull(
								variant.disambiguation.takeIf { it.isNotBlank() }
									?: variant.title.takeIf { it.isNotBlank() },
								variant.year.takeIf { it.isNotBlank() }
							).joinToString(" • ").ifBlank { "—" }
						},
						selectedIndex = variantIndex,
						onSelect = { index ->
							variantIndex = index
							editionMbid = variants[index].editions.firstOrNull()?.releaseMbid
								?: variants[index].releaseMbid
						}
					)
				}
				val editions = variants.getOrNull(variantIndex)?.editions.orEmpty()
				if (editions.size > 1) {
					SheetLabel(stringResource(Res.string.label_edition))
					ChipRow(
						labels = editions.map { edition ->
							listOfNotNull(
								edition.label.takeIf { it.isNotBlank() },
								edition.format.takeIf { it.isNotBlank() },
								edition.year.takeIf { it.isNotBlank() }
							).joinToString(" • ").ifBlank { "—" }
						},
						selectedIndex = editions.indexOfFirst { it.releaseMbid == editionMbid },
						onSelect = { index -> editionMbid = editions[index].releaseMbid }
					)
				}

				Spacer(Modifier.height(8.dp))
				Tracklist(tracklist)
			}

			Spacer(Modifier.height(16.dp))
			HorizontalDivider()
			Spacer(Modifier.height(8.dp))

			Row(
				Modifier.fillMaxWidth(),
				horizontalArrangement = Arrangement.SpaceBetween,
				verticalAlignment = Alignment.CenterVertically
			) {
				Text(
					stringResource(Res.string.label_quality_preference),
					style = MaterialTheme.typography.bodyMedium
				)
				Box {
					TextButton(onClick = { qualityMenuOpen = true }) {
						// A stored value lb-bot has since dropped from its enum must not
						// crash the sheet — fall back to the "default" entry.
						Text(
							LbBotManager.QUALITY_OPTIONS.firstOrNull { it.first == quality }
								?.second ?: LbBotManager.QUALITY_OPTIONS.first().second
						)
					}
					DropdownMenu(qualityMenuOpen, onDismissRequest = { qualityMenuOpen = false }) {
						LbBotManager.QUALITY_OPTIONS.forEach { (value, label) ->
							DropdownMenuItem(
								text = { Text(label) },
								onClick = {
									quality = value
									// Sticky: someone who wants CD-quality FLAC wants it
									// every time, and re-picking per album is a chore
									// nobody does twice.
									lbBot.preferredQuality = value
									qualityMenuOpen = false
								}
							)
						}
					}
				}
			}

			FillProgress(
				status,
				// The source picker is disabled while a fill runs, so without this a stuck
				// or crawling download left the sheet with nothing to press at all.
				onCancel = { scope.launch { lbBot.cancelFill(release.rgid) } }
			)
			LbErrorLine(error)

			if (status != null && status.mp3WouldHelp && status.groupId.isNotBlank()) {
				Text(
					stringResource(Res.string.info_mp3_would_help),
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant
				)
				TextButton(onClick = { scope.launch { lbBot.allowMp3(status.groupId) } }) {
					Text(stringResource(Res.string.action_allow_mp3))
				}
			}

			Spacer(Modifier.height(8.dp))

			if (readOnly) {
				Text(
					stringResource(Res.string.info_pending_sync_detail),
					style = MaterialTheme.typography.bodyMedium,
					color = MaterialTheme.colorScheme.onSurfaceVariant
				)
				return@Column
			}

			val fillActive = status?.state in ACTIVE_STATES
			when {
				// This hub does not proxy the source picker. A client ships
				// independently of the hub and the hub is a long-running process, so
				// "my client is newer than the hub" is a permanent condition — and
				// without this check an unknown route is an ordinary 404 that no HTTP
				// client raises as an error, surfacing as a button that does nothing.
				// That cost a full round of feedback here, where the real cause was an
				// un-restarted hub. An *empty* route list is an older hub advertising
				// none at all: assume supported.
				!lbBot.supportsSourcePicker -> Text(
					stringResource(Res.string.info_hub_needs_restart),
					style = MaterialTheme.typography.bodyMedium,
					color = MaterialTheme.colorScheme.error
				)

				// Step 1: nothing searched yet.
				sources == null -> Button(
					onClick = { findSources() },
					enabled = !searching && !fillActive,
					modifier = Modifier.fillMaxWidth()
				) {
					if (searching) {
						CircularProgressIndicator(Modifier.size(18.dp))
						Text(
							stringResource(Res.string.info_searching_sources),
							modifier = Modifier.padding(start = 12.dp)
						)
					} else {
						Text(stringResource(Res.string.action_find_sources))
					}
				}

				sources.orEmpty().isEmpty() -> Text(
					stringResource(Res.string.info_no_sources),
					style = MaterialTheme.typography.bodyMedium,
					color = MaterialTheme.colorScheme.onSurfaceVariant
				)

				// Step 2: pick one. This is the whole point of the sheet.
				else -> {
					SheetLabel(stringResource(Res.string.title_sources))
					SheetCaption(stringResource(Res.string.info_source_failover))
					AlbumSources(
						sources = sources.orEmpty(),
						enabled = !starting && !fillActive,
						onPick = { download(it) }
					)
				}
			}

			// Also how a fill started from another client surfaces: this device's own
			// watch map knows nothing about it, but lb-bot does.
			if (alreadyRunning) {
				Text(
					stringResource(Res.string.info_already_downloading),
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					modifier = Modifier.padding(top = 6.dp)
				)
			}
		}
	}
}

/**
 * The ranked candidates.
 *
 * Every row answers three questions in order of how badly getting them wrong
 * hurts: is this the right *album*, does it have all the *tracks*, and is it the
 * right *quality*. The album verdict comes first because it is the only one whose
 * failure is silent — a partial download is obvious afterwards, the wrong record
 * is not.
 */
@Composable
internal fun AlbumSources(
	sources: List<LbGapSource>,
	enabled: Boolean,
	onPick: (LbGapSource) -> Unit
) {
	// One source open at a time: the file lists are long, and the point is to
	// compare candidates, not to scroll past all of them at once.
	var expanded by remember { mutableStateOf(-1) }

	LazyColumn(Modifier.heightIn(max = 340.dp)) {
		itemsIndexed(sources) { index, source ->
			Column(
				Modifier
					.fillMaxWidth()
					.clickable { expanded = if (expanded == index) -1 else index }
					.padding(vertical = 8.dp)
			) {
				SourceHeadline(source.peer) {
					if (source.recommended) {
						Text(
							stringResource(Res.string.info_source_recommended),
							style = MaterialTheme.typography.labelSmall,
							color = MaterialTheme.colorScheme.primary
						)
					}
				}
				// The verdict that would have caught the self-titled album.
				Text(
					if (source.albumMatchOk) stringResource(Res.string.info_source_matches_album)
					else stringResource(Res.string.info_source_wrong_album),
					style = MaterialTheme.typography.labelMedium,
					fontWeight = FontWeight.Medium,
					color = if (source.albumMatchOk) MaterialTheme.colorScheme.primary
					else MaterialTheme.colorScheme.error
				)
				Text(
					coverageText(source),
					style = MaterialTheme.typography.bodySmall,
					color = if (source.coverageFull) MaterialTheme.colorScheme.onSurface
					else MaterialTheme.colorScheme.onSurfaceVariant
				)
				SourceMetaLine(
					listOf(
						source.format,
						source.bitrate,
						source.size,
						source.folder.substringAfterLast('\\').substringAfterLast('/')
					)
				)
				if (source.flags.isNotEmpty()) {
					Text(
						source.flags.joinToString(" • "),
						style = MaterialTheme.typography.labelSmall,
						color = MaterialTheme.colorScheme.error
					)
				}
				// The listing rides along with this route on purpose — it is a
				// one-shot read, and these rows are the evidence.
				if (expanded == index) {
					SourceFileList(source.files, source.filesTruncated)
				} else if (source.files.isNotEmpty()) {
					Text(
						stringResource(Res.string.action_show_files, source.files.size),
						style = MaterialTheme.typography.labelSmall,
						color = MaterialTheme.colorScheme.primary
					)
				}
				TextButton(onClick = { onPick(source) }, enabled = enabled) {
					Text(stringResource(Res.string.action_download_from_source))
				}
			}
			HorizontalDivider()
		}
	}
}

/**
 * "All 12 tracks" / "9 of 12 tracks".
 *
 * lb-bot's own `coverage` string is used when it has one — the album picker sends
 * a pre-formatted label — and the counts are the fallback. Both come from pairing
 * the folder against the canonical tracklist, never from counting files.
 */
@Composable
private fun coverageText(source: LbGapSource): String {
	val have = source.coverageDetail.haveTracks
	val total = source.coverageDetail.totalTracks
	return when {
		total > 0 && have >= total -> stringResource(Res.string.info_source_complete, total)
		total > 0 -> stringResource(Res.string.info_source_partial, have, total)
		else -> source.coverage
	}
}

@Composable
private fun Tracklist(tracklist: LbTracklist?) {
	if (tracklist == null) {
		Box(Modifier.fillMaxWidth().height(48.dp), Alignment.Center) {
			CircularProgressIndicator(Modifier.size(20.dp))
		}
		return
	}
	LazyColumn(Modifier.heightIn(max = 180.dp)) {
		items(tracklist.tracks) { track ->
			Row(
				Modifier.fillMaxWidth().padding(vertical = 3.dp),
				verticalAlignment = Alignment.CenterVertically
			) {
				Text(
					"${track.position}",
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					modifier = Modifier.size(width = 28.dp, height = 18.dp)
				)
				Text(
					track.title,
					style = MaterialTheme.typography.bodyMedium,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
					// `presenceKnown == false` means the library holds none of this
					// album, which is the normal case here — every track reads as
					// absent, and that is not an error state.
					color = if (tracklist.presenceKnown && track.present)
						MaterialTheme.colorScheme.onSurface
					else MaterialTheme.colorScheme.onSurfaceVariant
				)
			}
		}
	}
}

/**
 * A fill's progress, in lb-bot's own words where it has any.
 *
 * `unknown` renders as nothing at all: it is the resting state of every album
 * nobody has asked for, and showing it as a state would put an error-shaped
 * message on every album the user merely looked at.
 */
@Composable
internal fun FillProgress(status: LbFillStatus?, onCancel: (() -> Unit)? = null) {
	if (status == null || status.state == "unknown") return
	// The same words the Download Center uses (PROTOCOL §15.2): the sheet used to
	// carry a second set of labels for the same states.
	val label = when (status.state) {
		"failed" -> stringResource(failureHeadline(status.failureKind))
		else -> runningHeadline(status.state, status.done, status.total, status.verifyGaveUp).resolve()
	}
	Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
		Text(label, style = MaterialTheme.typography.bodyMedium)
		// lb-bot's own sentence for a failure, shown verbatim — it knows why and we
		// don't, and paraphrasing it would only lose detail.
		if (status.reason.isNotBlank()) {
			Text(
				status.reason,
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant
			)
		}
		if (status.state == "downloading" && status.bytesTotal > 0L) {
			Text(
				"${formatBytes(status.bytesDone)} / ${formatBytes(status.bytesTotal)}" +
					if (status.speedBps > 0L) " · ${(status.speedBps / (1024.0 * 1024.0) * 10).toLong() / 10.0} MB/s" else "",
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant
			)
		}
		if (status.state in ACTIVE_STATES) {
			Spacer(Modifier.height(6.dp))
			if (status.total > 0 || status.state == "placing" || status.state == "placed") {
				LinearProgressIndicator(
					progress = { status.percent / 100f },
					modifier = Modifier.fillMaxWidth()
				)
			} else {
				LinearProgressIndicator(Modifier.fillMaxWidth())
			}
		}
		// The server's own Cancel rule: searching, queued or downloading, or a failed
		// fill whose automatic retry is still pending. Never on `placing`.
		if (onCancel != null && status.canCancel) {
			TextButton(onClick = onCancel) {
				Text(stringResource(Res.string.action_cancel_download))
			}
		}
	}
}

private val ACTIVE_STATES =
	setOf("searching", "queued", "downloading", "placing", "placed")
