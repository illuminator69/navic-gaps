package paige.navic.ui.screens.playlist

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.kyant.capsule.ContinuousRoundedRectangle
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.koin.compose.koinInject
import paige.navic.di.LocalNavStack
import paige.navic.domain.manager.AlbumModeSmartPlaylists
import paige.navic.domain.manager.NativeApiManager
import paige.navic.domain.manager.PlaylistDownloadManager
import paige.navic.domain.manager.PlaylistDownloadPolicy
import paige.navic.domain.repositories.DbRepository
import paige.navic.icons.Icons
import paige.navic.icons.outlined.Add
import paige.navic.icons.outlined.Close
import paige.navic.ui.components.layouts.NestedTopBar
import paige.navic.ui.navigation.Screen
import paige.navic.ui.util.rememberLibraryTabBackground
import paige.navic.ui.components.common.Form
import paige.navic.ui.components.common.FormRow
import paige.navic.data.database.dao.PlaylistDao
import paige.navic.domain.manager.createRediscoveryPlaylists
import paige.navic.ui.components.common.FormTitle
import paige.navic.ui.screens.settings.components.SettingSelectionRow
import paige.navic.ui.screens.settings.components.SettingSwitchRow

// ---------------------------------------------------------------------------
// Rule model: a curated subset of Navidrome's criteria fields/operators.
// ---------------------------------------------------------------------------

private enum class FieldType { BOOL, DATE, NUMBER, STRING }

private data class FieldDef(val key: String, val label: String, val type: FieldType)

private val FIELDS = listOf(
	FieldDef("title", "Title", FieldType.STRING),
	FieldDef("artist", "Artist", FieldType.STRING),
	FieldDef("album", "Album", FieldType.STRING),
	FieldDef("albumartist", "Album artist", FieldType.STRING),
	FieldDef("genre", "Genre", FieldType.STRING),
	FieldDef("comment", "Comment", FieldType.STRING),
	FieldDef("filetype", "File type", FieldType.STRING),
	FieldDef("year", "Year", FieldType.NUMBER),
	FieldDef("rating", "Rating (0-5)", FieldType.NUMBER),
	FieldDef("playcount", "Play count", FieldType.NUMBER),
	FieldDef("duration", "Duration (sec)", FieldType.NUMBER),
	FieldDef("bpm", "BPM", FieldType.NUMBER),
	FieldDef("bitrate", "Bitrate", FieldType.NUMBER),
	FieldDef("loved", "Favorite", FieldType.BOOL),
	FieldDef("dateadded", "Date added", FieldType.DATE),
	FieldDef("lastplayed", "Last played", FieldType.DATE)
)

private data class OperatorDef(val key: String, val label: String)

private fun operatorsFor(type: FieldType): List<OperatorDef> = when (type) {
	FieldType.STRING -> listOf(
		OperatorDef("contains", "contains"),
		OperatorDef("notContains", "doesn't contain"),
		OperatorDef("is", "is"),
		OperatorDef("isNot", "is not"),
		OperatorDef("startsWith", "starts with"),
		OperatorDef("endsWith", "ends with")
	)

	FieldType.NUMBER -> listOf(
		OperatorDef("is", "is"),
		OperatorDef("gt", "greater than"),
		OperatorDef("lt", "less than")
	)

	FieldType.BOOL -> listOf(OperatorDef("is", "is"))

	FieldType.DATE -> listOf(
		OperatorDef("inTheLast", "in the last (days)"),
		OperatorDef("notInTheLast", "not in the last (days)"),
		OperatorDef("before", "before (YYYY-MM-DD)"),
		OperatorDef("after", "after (YYYY-MM-DD)")
	)
}

private class RuleState {
	var field by mutableStateOf(FIELDS[0])
	var operator by mutableStateOf(operatorsFor(FIELDS[0].type)[0])
	var value by mutableStateOf("")
	var boolValue by mutableStateOf(true)
}

private val SORT_OPTIONS = listOf(
	"" to "Default",
	"title" to "Title",
	"artist" to "Artist",
	"album" to "Album",
	"year" to "Year",
	"dateadded" to "Date added",
	"lastplayed" to "Last played",
	"playcount" to "Play count",
	"rating" to "Rating",
	"random" to "Random"
)

private fun buildRules(
	matchAll: Boolean,
	rules: List<RuleState>,
	sort: String,
	descending: Boolean,
	limit: Int?
): JsonObject = buildJsonObject {
	putJsonArray(if (matchAll) "all" else "any") {
		rules.forEach { rule ->
			addJsonObject {
				putJsonObject(rule.operator.key) {
					when (rule.field.type) {
						FieldType.BOOL -> put(rule.field.key, rule.boolValue)
						FieldType.NUMBER -> put(rule.field.key, rule.value.toLongOrNull() ?: 0L)
						FieldType.DATE ->
							if (rule.operator.key == "inTheLast" || rule.operator.key == "notInTheLast") {
								put(rule.field.key, rule.value.toLongOrNull() ?: 30L)
							} else {
								put(rule.field.key, rule.value)
							}

						FieldType.STRING -> put(rule.field.key, rule.value)
					}
				}
			}
		}
	}
	if (sort.isNotEmpty()) put("sort", sort)
	put("order", if (descending) "desc" else "asc")
	limit?.let { put("limit", it) }
}

/**
 * What [buildRules] wrote, read back into the editor's own state.
 *
 * The inverse did not exist, which is why a smart playlist could be created and
 * never changed: Navidrome holds the only copy of the criteria (nothing local has
 * a column for them), so editing one means parsing what the server returns.
 *
 * Deliberately lenient. Navidrome's criteria language is larger than this editor —
 * nested groups, operators with no row here — and a rule this build cannot
 * represent is **dropped from the form while the rest is kept**, rather than the
 * whole edit being refused. That is a real loss, so the caller warns when
 * [ParsedRules.lossy] is set; saving then rewrites only what was shown.
 */
private data class ParsedRules(
	val matchAll: Boolean,
	val rules: List<RuleState>,
	val sort: Pair<String, String>,
	val descending: Boolean,
	val limit: String,
	val lossy: Boolean
)

private fun parseRules(json: JsonObject): ParsedRules {
	val all = json["all"] as? JsonArray
	val any = json["any"] as? JsonArray
	val matchAll = any == null
	val clauses = (all ?: any) ?: JsonArray(emptyList())

	var lossy = false
	val parsed = mutableListOf<RuleState>()
	clauses.forEach { clause ->
		val obj = clause as? JsonObject
		if (obj == null || obj.size != 1) {
			lossy = true
			return@forEach
		}
		val (opKey, payload) = obj.entries.first()
		val fields = payload as? JsonObject
		if (fields == null || fields.size != 1) {
			// A nested `all`/`any` group, which this editor has no row for.
			lossy = true
			return@forEach
		}
		val (fieldKey, rawValue) = fields.entries.first()
		val field = FIELDS.firstOrNull { it.key == fieldKey }
		val operator = field?.let { f -> operatorsFor(f.type).firstOrNull { it.key == opKey } }
		if (field == null || operator == null) {
			lossy = true
			return@forEach
		}
		parsed += RuleState().apply {
			this.field = field
			this.operator = operator
			val primitive = rawValue as? JsonPrimitive
			if (field.type == FieldType.BOOL) {
				boolValue = primitive?.content?.equals("true", ignoreCase = true) ?: true
			} else {
				value = primitive?.content.orEmpty()
			}
		}
	}

	val sortKey = (json["sort"] as? JsonPrimitive)?.content.orEmpty()
	return ParsedRules(
		matchAll = matchAll,
		// Never an empty form: a playlist whose every rule was unrepresentable would
		// otherwise open with no rows and read as "this has no rules".
		rules = parsed.ifEmpty { listOf(RuleState()) },
		sort = SORT_OPTIONS.firstOrNull { it.first == sortKey } ?: SORT_OPTIONS[0],
		descending = (json["order"] as? JsonPrimitive)?.content == "desc",
		limit = (json["limit"] as? JsonPrimitive)?.content.orEmpty(),
		lossy = lossy
	)
}

/**
 * Turn the editor's Offline switch into a download policy.
 *
 * Permanent rather than rolling: the switch is a boolean, and "keep this offline"
 * with a silent cap on how much would be the wrong answer to it. Anything more
 * specific is what the auto-download dialog is for, and this deliberately does not
 * overwrite a policy already set there — only the off→on and on→off transitions
 * act, so opening the editor on a playlist with a rolling policy and saving does
 * not quietly flatten it to permanent.
 *
 * A blank id is the one case with nothing to do: Navidrome answered the create
 * without one, so there is no playlist to attach a policy to yet.
 */
private fun applyOfflinePolicy(
	manager: PlaylistDownloadManager,
	playlistId: String?,
	playlistName: String,
	offline: Boolean
) {
	val id = playlistId?.takeIf { it.isNotBlank() } ?: return
	val existing = manager.policies.value[id]
	when {
		offline && existing == null -> manager.setPolicy(
			PlaylistDownloadPolicy(
				playlistId = id,
				playlistName = playlistName,
				mode = PlaylistDownloadPolicy.MODE_PERMANENT
			)
		)

		!offline && existing != null -> manager.removePolicy(id, deleteDownloads = true)
	}
}

// ---------------------------------------------------------------------------

/**
 * A borderless input that reads as one more form row: no outline, its own
 * [surfaceContainerHigh][MaterialTheme.colorScheme] fill, rounded to match [FormRow].
 */
@Composable
internal fun FormTextField(
	value: String,
	onValueChange: (String) -> Unit,
	placeholder: String,
	modifier: Modifier = Modifier,
	keyboardType: KeyboardType = KeyboardType.Text
) {
	TextField(
		value = value,
		onValueChange = onValueChange,
		placeholder = { Text(placeholder) },
		singleLine = true,
		keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
		modifier = modifier.clip(ContinuousRoundedRectangle(5.dp)),
		colors = TextFieldDefaults.colors(
			focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
			unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
			focusedIndicatorColor = Color.Transparent,
			unfocusedIndicatorColor = Color.Transparent,
			disabledIndicatorColor = Color.Transparent,
			errorIndicatorColor = Color.Transparent
		)
	)
}

/**
 * Editor for Navidrome SERVER-SIDE smart playlists: rule rows are converted to
 * the criteria JSON the native API accepts; the server keeps the playlist
 * auto-updated and every Subsonic client sees it as a normal playlist.
 */
@Composable
fun SmartPlaylistEditorScreen(playlistId: String? = null) {
	val nativeApi = koinInject<NativeApiManager>()
	val dbRepository = koinInject<DbRepository>()
	val playlistDao = koinInject<PlaylistDao>()
	val playlistDownloadManager = koinInject<PlaylistDownloadManager>()
	val albumMode = koinInject<AlbumModeSmartPlaylists>()
	var creatingSet by remember { mutableStateOf(false) }
	val backStack = LocalNavStack.current
	val scope = rememberCoroutineScope()

	val editing = !playlistId.isNullOrBlank()
	var name by remember { mutableStateOf("") }
	var matchAll by remember { mutableStateOf(true) }
	val rules = remember { mutableStateListOf(RuleState()) }
	var sort by remember { mutableStateOf(SORT_OPTIONS[0]) }
	var descending by remember { mutableStateOf(false) }
	var limitText by remember { mutableStateOf("") }
	var isPublic by remember { mutableStateOf(false) }
	var saving by remember { mutableStateOf(false) }
	var error by remember { mutableStateOf<String?>(null) }
	// Keep the playlist offline. Reachable here at last because
	// `createSmartPlaylist` returns the new id — before that, the editor had no way
	// to name the playlist it had just made, so the policy had to be set afterwards
	// from a different screen entirely.
	var offline by remember { mutableStateOf(false) }
	var loading by remember { mutableStateOf(editing) }
	var lossy by remember { mutableStateOf(false) }
	// Whether the rules collect whole albums. Navidrome's criteria are track-scoped
	// and there is no album mode to ask it for, so this is a client-side expansion
	// and therefore a SNAPSHOT — see `AlbumModeSmartPlaylists`. Said plainly below
	// rather than left for the user to discover on the second week.
	var matchAlbums by remember { mutableStateOf(false) }

	// Load an existing playlist's rules. Navidrome holds the only copy — nothing
	// local has a column for them — so this is a network read, and a failure leaves
	// the form empty rather than silently offering to overwrite real criteria with
	// a blank rule.
	LaunchedEffect(playlistId) {
		val id = playlistId?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
		offline = playlistDownloadManager.policies.value.containsKey(id)
		name = playlistDao.getPlaylistById(id)?.playlist?.name.orEmpty()
		// An album-mode playlist is an ORDINARY playlist on the server — the
		// expansion is a snapshot and Navidrome refuses membership edits on a smart
		// one — so its recipe is only here. Checked first for that reason: asking
		// the server would correctly answer "no rules" and read as an error.
		val localRules = albumMode.rulesFor(id)
		if (localRules != null) {
			matchAlbums = true
			val parsed = parseRules(localRules)
			matchAll = parsed.matchAll
			rules.clear()
			rules.addAll(parsed.rules)
			sort = parsed.sort
			descending = parsed.descending
			limitText = parsed.limit
			lossy = parsed.lossy
			loading = false
			return@LaunchedEffect
		}
		nativeApi.fetchPlaylistRules(id)
			.onSuccess { stored ->
				if (stored == null) {
					error = "This playlist has no rules to edit."
				} else {
					val parsed = parseRules(stored)
					matchAll = parsed.matchAll
					rules.clear()
					rules.addAll(parsed.rules)
					sort = parsed.sort
					descending = parsed.descending
					limitText = parsed.limit
					lossy = parsed.lossy
				}
			}
			.onFailure { error = it.message ?: "Couldn't read this playlist's rules." }
		loading = false
	}

	Scaffold(
		topBar = {
			NestedTopBar({ Text(if (editing) "Edit smart playlist" else "New smart playlist") })
		}
	) { innerPadding ->
		Column(
			modifier = Modifier
				.padding(top = innerPadding.calculateTopPadding())
				.fillMaxSize()
				.background(rememberLibraryTabBackground())
				.verticalScroll(rememberScrollState())
				.padding(horizontal = 16.dp, vertical = 8.dp)
		) {
			// --- General ---------------------------------------------------
			FormTitle("General")
			Form {
				FormRow {
					FormTextField(
						value = name,
						onValueChange = { name = it },
						placeholder = "Playlist name",
						modifier = Modifier.fillMaxWidth()
					)
				}
				SettingSelectionRow(
					title = { Text("Match") },
					items = persistentListOf(true, false),
					label = { if (it) "All rules" else "Any rule" },
					selection = matchAll,
					onSelect = { matchAll = it }
				)
				// Offered on creation only. Switching an existing playlist between the
				// two would mean converting a live smart playlist into a snapshot or
				// back, and those are two different records on the server — so an
				// edit states which kind it is instead of offering to change it.
				if (editing) {
					FormRow {
						Text("Collect", modifier = Modifier.weight(1f))
						Text(
							if (matchAlbums) "Whole albums" else "Tracks",
							style = MaterialTheme.typography.bodyMedium,
							color = MaterialTheme.colorScheme.onSurfaceVariant
						)
					}
				} else {
					SettingSelectionRow(
						title = { Text("Collect") },
						items = persistentListOf(false, true),
						label = { if (it) "Whole albums" else "Tracks" },
						selection = matchAlbums,
						onSelect = { matchAlbums = it }
					)
				}
			}

			if (matchAlbums) {
				Text(
					"Navidrome's rules match tracks, so Whole albums is worked out " +
						"here: the rules are evaluated on the server, then every " +
						"matching track's full album is included. That makes it a " +
						"snapshot rather than a live smart playlist — it will not " +
						"update itself, and there is a Refresh in its menu.",
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
				)
			}

			// --- Rules -----------------------------------------------------
			FormTitle("Rules")
			rules.forEachIndexed { index, rule ->
				Form {
					FormRow {
						Text(
							"Rule ${index + 1}",
							style = MaterialTheme.typography.titleSmall,
							modifier = Modifier.weight(1f)
						)
						if (rules.size > 1) {
							IconButton(onClick = { rules.removeAt(index) }) {
								Icon(
									Icons.Outlined.Close,
									contentDescription = "Remove rule",
									tint = MaterialTheme.colorScheme.error,
									modifier = Modifier.size(20.dp)
								)
							}
						}
					}
					SettingSelectionRow(
						title = { Text("Field") },
						items = FIELDS.toImmutableList(),
						label = { it.label },
						selection = rule.field,
						onSelect = { picked ->
							rule.field = picked
							rule.operator = operatorsFor(picked.type)[0]
							rule.value = ""
						}
					)
					SettingSelectionRow(
						title = { Text("Condition") },
						items = operatorsFor(rule.field.type).toImmutableList(),
						label = { it.label },
						selection = rule.operator,
						onSelect = { rule.operator = it }
					)
					if (rule.field.type == FieldType.BOOL) {
						SettingSwitchRow(
							title = { Text("Value") },
							subtitle = { Text(if (rule.boolValue) "true" else "false") },
							value = rule.boolValue,
							onSetValue = { rule.boolValue = it }
						)
					} else {
						FormRow {
							FormTextField(
								value = rule.value,
								onValueChange = { rule.value = it },
								placeholder = when (rule.field.type) {
									FieldType.NUMBER -> "Number"
									FieldType.DATE ->
										if (rule.operator.key == "inTheLast" ||
											rule.operator.key == "notInTheLast"
										) "Days" else "YYYY-MM-DD"

									else -> "Value"
								},
								keyboardType = if (rule.field.type == FieldType.NUMBER)
									KeyboardType.Number else KeyboardType.Text,
								modifier = Modifier.fillMaxWidth()
							)
						}
					}
				}
			}

			OutlinedButton(
				onClick = { rules.add(RuleState()) },
				modifier = Modifier.fillMaxWidth()
			) {
				Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
				Spacer(Modifier.width(8.dp))
				Text("Add rule")
			}

			Spacer(Modifier.height(20.dp))

			// --- Sorting ---------------------------------------------------
			FormTitle("Sorting")
			Form {
				SettingSelectionRow(
					title = { Text("Sort by") },
					items = SORT_OPTIONS.toImmutableList(),
					label = { it.second },
					selection = sort,
					onSelect = { sort = it }
				)
				SettingSelectionRow(
					title = { Text("Order") },
					items = persistentListOf(false, true),
					label = { if (it) "Descending" else "Ascending" },
					selection = descending,
					onSelect = { descending = it }
				)
				FormRow {
					FormTextField(
						value = limitText,
						onValueChange = { limitText = it },
						placeholder = "Limit (optional)",
						keyboardType = KeyboardType.Number,
						modifier = Modifier.fillMaxWidth()
					)
				}
			}

			// --- Visibility ------------------------------------------------
			FormTitle("Visibility")
			Form {
				SettingSwitchRow(
					title = { Text("Public") },
					subtitle = { Text("Visible to everyone on the server") },
					value = isPublic,
					onSetValue = { isPublic = it }
				)
				SettingSwitchRow(
					title = { Text("Offline") },
					subtitle = { Text("Keep this playlist downloaded on this device") },
					value = offline,
					onSetValue = { offline = it }
				)
			}

			if (lossy) {
				Text(
					"Some of this playlist's rules are more complex than this editor " +
						"can show, and are not listed above. Saving will replace them " +
						"with what is shown.",
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.error,
					modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
				)
			}

			error?.let {
				Text(
					it,
					color = MaterialTheme.colorScheme.error,
					modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
				)
			}

			Button(
				enabled = !saving && !loading && name.isNotBlank() &&
					rules.all { it.field.type == FieldType.BOOL || it.value.isNotBlank() },
				modifier = Modifier.fillMaxWidth().height(52.dp),
				onClick = {
					saving = true
					error = null
					scope.launch {
						val criteria = buildRules(
							matchAll = matchAll,
							rules = rules,
							sort = sort.first,
							descending = descending,
							limit = limitText.toIntOrNull()
						)
						val result: Result<String> = when {
							// Album mode is not a smart playlist at all: the server
							// evaluates the rules, this expands the result to whole
							// albums, and what is stored is a plain playlist. Both
							// creating and re-saving run the same build, since
							// re-saving edited rules means re-running them.
							matchAlbums -> albumMode.build(
								name = name.trim(),
								comment = "Created with Navic",
								isPublic = isPublic,
								rules = criteria,
								existingId = playlistId
							)

							// An edit is a full PUT: Navidrome replaces the record
							// rather than patching it, so the name and visibility ride
							// along or they are blanked.
							editing -> nativeApi.updateSmartPlaylist(
								playlistId = playlistId!!,
								name = name.trim(),
								comment = "Created with Navic",
								isPublic = isPublic,
								rules = criteria
							).map { playlistId }

							else -> nativeApi.createSmartPlaylist(
								name = name.trim(),
								comment = "Created with Navic",
								isPublic = isPublic,
								rules = criteria
							)
						}
						saving = false
						result
							.onSuccess { savedId ->
								applyOfflinePolicy(
									manager = playlistDownloadManager,
									playlistId = savedId,
									playlistName = name.trim(),
									offline = offline
								)
								dbRepository.syncPlaylists()
								backStack.removeLastOrNull()
							}
							.onFailure {
								error = it.message
									?: if (editing) "Failed to save playlist"
									else "Failed to create playlist"
							}
					}
				}
			) {
				if (saving) CircularProgressIndicator(Modifier.height(20.dp))
				else Text(
					when {
						editing -> "Save changes"
						matchAlbums -> "Create album playlist"
						else -> "Create smart playlist"
					}
				)
			}

			// The rediscovery set: four ready-made smart playlists for music
			// already in the library and rarely or never played. Nothing in this
			// app surfaces that today — every "discovery" surface points outward
			// at records you do not own.
			//
			// Opt-in, and idempotent by name: minting playlists in someone's
			// library unasked would be a surprising thing for a player to do, and
			// pressing this twice leaves one copy of each, not two.
			Spacer(Modifier.height(16.dp))
			FormTitle("Rediscovery")
			Text(
				"Adds four smart playlists — never played, loved but stale, highly " +
					"rated and long unplayed, deep cuts. Navidrome keeps them current, " +
					"so every client sees them.",
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
				modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
			)
			Button(
				enabled = !creatingSet,
				modifier = Modifier.fillMaxWidth().height(52.dp),
				onClick = {
					creatingSet = true
					error = null
					scope.launch {
						val existing = playlistDao.getAllPlaylistsByName()
							.mapNotNull { it.playlist.name }
							.toSet()
						val result = createRediscoveryPlaylists(nativeApi, existing)
						dbRepository.syncPlaylists()
						creatingSet = false
						error = when {
							result.failed.isNotEmpty() ->
								"Could not create: " + result.failed.joinToString(", ")
							result.created == 0 ->
								"All ${result.skipped} rediscovery playlists already exist"
							else -> null
						}
						if (result.failed.isEmpty() && result.created > 0) {
							// `removeLastOrNull`, not `remove(Screen.SmartPlaylistEditor)`:
							// the key is a data class carrying an id now, so an
							// equality-based remove would miss whichever instance
							// is actually on the stack.
							backStack.removeLastOrNull()
						}
					}
				}
			) {
				if (creatingSet) CircularProgressIndicator(Modifier.height(20.dp))
				else Text("Add the rediscovery set")
			}
			Spacer(Modifier.height(24.dp))
		}
	}
}
