# navic-gaps — agent reference

**This file is the source of truth for working inside this tree.** `README.md` is the front page
and stays brief. The wider stack — the hub, the wire protocol, the desktop client, lb-bot — is
documented in **[navi-connect](https://github.com/illuminator69/navi-connect)**; anything about how
two clients agree on something belongs there, and anything about *this* app belongs here.

Keep it in sync when architecture changes. Sections 2 and 6 are the ones that repay reading before
you touch anything: they record failures that the compiler cannot see and that cost a full
build-and-drive round each to find.

---

## 1. What this is

A fork of **[ssalggnikool/Navic](https://github.com/ssalggnikool/Navic)** — a Kotlin
Multiplatform / Compose Multiplatform (Open)Subsonic client — that turns it into a client for a
shared playback session, a Chromecast bridge, an AudioMuse-AI front end and an lb-bot front end.

| | |
|---|---|
| Branch | **`navi-connect`** (not `master`) |
| Upstream base | ssalggnikool/Navic **`v1.0.0-alpha59`** |
| Divergence | ~264 files, +33.6k / −3.5k |
| Modules | `:androidApp` (the application), `:composeApp` (the shared KMP library), `iosApp/` |
| Licence | GPL-3.0, inherited |

**Android is the only platform that is built, run or tested.** `commonMain` must still *compile*
for iOS — so a new `expect` needs an `actual` in `iosMain` and a Koin registration in
`PlatformModule.ios.kt` — but no iOS feature is implemented or verified.

### The fork's surface, in one table

| Area | Entry points |
|---|---|
| Shared session | `domain/manager/HubManager.kt` (act helpers, `resolveQueue`, remote mirror), `androidMain/.../shared/RemoteSessionPlayer.kt` (a media3 `SimpleBasePlayer` facade so the notification and Bluetooth keys drive the *remote* session); the hub socket's HttpClient comes from `HubSocketClientFactory` (§7), and what this device is and its media volume from `HubLocalDevice` (§4) |
| Player | `shared/MediaPlayer.kt` (blended `uiState` + raw `localUiState`), `androidMain/.../shared/MediaPlayer.android.kt`, `shared/AroundPlayingEdit.kt` (a hub `queueChanged` applied around the playing song, `AroundPlayingEditTest`) |
| Chromecast | `androidMain/.../domain/manager/cast/*` — `CastDiscovery` (`NsdManager`), a hand-rolled castv2 client (`CastChannel`/`CastProtocol`/`CastPayloads`), `CastDeviceBridge`, `CastBridgeManager` (reconnect safety net: `BridgeLiveness.kt`, §4). No Cast SDK, no Play Services. Scrobbling in `domain/manager/CastScrobbler.kt` |
| Radio and autoplay (stations, I-040) | `domain/manager/RadioManager.kt` (entry points, the autoplay driver, likes, mixes), `domain/manager/StationManager.kt` (the hub's `/sonic/station/...`, availability, the persisted record of what built the queue), `domain/manager/StationModels.kt` (wire shapes and every rule the driver and the queue sheet apply, `StationDriverTest`), `domain/manager/AutoDj.kt` (the no-hub fallback, a port of upstream Feishin's, `AutoDjPlannerTest`), `domain/manager/AutoplayChips.kt` (the queue's autoplay chips, `AutoplayChipsTest`), `domain/models/settings/StationCharacter.kt`, the chip row and `ui/screens/queue/components/QueueGeneratorLine.kt` in the queue sheet (§4) |
| AudioMuse-AI | `domain/manager/AudioMuseManager.kt` (CLAP mood search; the fingerprint and alchemy lists the old mix kinds play from without the station engine) |
| lb-bot | `domain/manager/LbBotManager.kt` (the whole surface plus the persisted watch map; `applyAlbumStatus` / `applyGapSummary` are the ONE writer each, fed by the `/lb/fills` poll and the hub's `fill` frame alike), `ui/components/common/FillVocabulary.kt` (the acquisition vocabulary, PROTOCOL §15.2 — mirrored in Feishin's `fill-vocabulary.ts`), `ui/components/sheets/LbBotCommon.kt`, the album page's gaps box (`ui/screens/collection/components/GapBox.kt`, which retired `GapFillSheet`; its stage is `GapStage.kt`), `domain/manager/FillActions.kt` (what a fill's buttons do, shared by Downloads and the request box), `domain/manager/AlbumDownloadAll.kt` (the album picker's "more files in this folder" and "Download all", §5), `domain/manager/ArtistMbidOverride.kt` + `ui/components/sheets/MbidMatchSheet.kt` (which MusicBrainz artist a library artist is, §5), `domain/manager/AlbumOverride.kt` + `ui/components/sheets/AlbumMatchSheet.kt` ("Fix album match": which MusicBrainz release an owned album is; the sheet is composed by `AlbumMatchHost()` in `App.kt`, §5), `domain/manager/ArtistMerge.kt` + `ui/components/sheets/ArtistMergeBlock.kt` ("Merge into one name": two spellings of one artist, a block of `MbidMatchSheet`, §5), `domain/manager/LbJobPoll.kt` (the one poll rule both background jobs are followed by), `ui/screens/artist/components/{DiscographyShelf,DiscographyBrowser}.kt`, `ui/screens/fresh/*`, `ui/screens/external/*` |
| Listening history (Stats' tabs) | `domain/manager/Listening.kt` (the wire shapes and every rule the screen applies — `ListeningTest`), `ui/screens/listening/*` (`ListeningStats`, hosted by upstream's `StatisticsScreen`), the `listening*` calls in `LbBotManager`; the Fresh scope "Artists you play" is `freshScoped` in `FreshViewModel.kt` (§5) |
| Library Matches, pending actions | `ui/screens/matches/*` (`LibraryMatchesScreen` + its view model: the page that was the "lb-bot data" settings section, §5), `domain/manager/LibraryMatches.kt` (its sections and what a row draws — `LibraryMatchesTest`), `ui/components/common/LbArtImage.kt`, `domain/manager/LbBotData.kt` (every sentence these surfaces say, as tested functions — `LbBotDataTest`, and `OverrideListsTest` for the album-override and name-merge lists), `ui/screens/settings/PendingActionsScreen.kt` + its view model, `LbIndexSync.lastRun`, `LbBotManager.refreshArtistMeta` / `refreshAlbumMeta` (§5) |
| Discover | `ui/screens/discover/*` — `DiscoverRows.kt` (the row catalogue, **duplicated in Feishin**; see below), `DiscoverScreen.kt`, `viewmodels/DiscoverViewModel.kt` |
| Saved queues | `domain/repositories/SavedQueueRepository.kt`, `ui/screens/savedqueues/*` |
| Downloads | `domain/manager/{DownloadManager,PlaylistDownloadManager,DownloadForegroundController}.kt`, `androidMain/.../shared/DownloadService.kt`, `ui/screens/downloads/*` (`DownloadsScreen`, this device's downloads only since P6, and `PasteLinkSheet`; it was Settings' Download Center plus a Wishlist page, I-039; lb-bot's fills and `WishlistSection` are Source history's chips now, and `Downloads(Fills|Wishlist)` opens Source history). What holds downloads back (Wi-Fi only, charging only, how many at once) is in Settings › Data & storage |
| Mixed for You | `domain/models/Mix.kt`, `ui/screens/mixes/*` (`MixListScreen`, `MixDetailScreen`, `MixArtwork`, `MixFormat`), `RadioManager.generate`/`regenerate`/`currentRecipe` |
| Custom radio (P5) | `ui/screens/radio/custom/*`: `CustomRadioScreen` (two steps, `PickArtistsStep` and `TuneStep`), `CustomRadioViewModel` (the picks, the character, the variety, the debounced count, Done), every rule pure in `CustomRadioModel.kt` (`radioTitle`, `CountState`, `CountKey`, `reduceCount`, `canFinish`, `customRadioRecipe`; `CustomRadioModelTest`); the seed rules and the hub's count answer in `StationModels.kt` (`seedsPerArtist`, `interleaveSeeds`, `customRadioPlan`, `parseStationCount`; `CustomRadioPlumbingTest`); `StationManager.count`; the saved snackbar in `NewSheetHost` through `ShellController.customRadioDone` (§4) |
| Mix page (P5 round 2) | `ui/screens/mixes/MixDetailScreen.kt` over `MixDetailViewModel` (`MixPageState`); `RadioManager.buildMix` / `playMixBuild` with `MixBuild` (`domain/manager/MixBuild.kt`); `Screen.MixDetail(mixId)`, `YOUR_MIX_ID` for Your mix (§4) |
| Native Navidrome API | `domain/manager/{NativeApiManager,AlbumModeSmartPlaylists}.kt` — smart playlists (create, **read rules, update**), album-mode expansion, and "Appears on" (Subsonic's `getArtist` is album-artist only) |
| Hidden windows (performance, I-041) | `ui/navigation/SheetCover.kt` (which full-screen sheets cover everything under them, reported by `NowPlayingScene` / `BottomSheetScene`; `rememberCoveredBySheet()` / `heldWhileCoveredBySheet()` for what sits under one, `SheetCoverTest`), read by `BlendBackground`'s caller in `NowPlayingScreen`, the player's wave (`ProgressBar.kt`, `DurationsRow.kt`), `Waveform.kt` and the mini player (§7) |
| Colour engine | `ui/util/CoverColorScheme.kt`, `ui/components/common/CoverAmbientBackground.kt` (`BrowsingAmbient`; and `AppWash`, which draws the wash once behind `NavDisplay`, §6 rule 9), `ui/util/AmbientColorHolder.kt`, `ui/components/common/BlendBackground.kt`, `ui/components/common/blur/ExpressiveBlur.kt`; what a cover is reduced to and where it is kept (I-043): `ui/util/CoverSeeds.kt` (`deriveCoverSeeds`), `ui/util/CoverSeedStore.kt`, `ui/util/CoverSeedPrefetcher.kt`, `ui/ColourArrivalEffects.kt` (composed once in `App.kt`); the wash's numbers: `ui/util/WashTuning.kt` (§6) |
| Shell and Home (P1) | `ui/navigation/ShellTab.kt` (the bar's four fixed buttons and the ONE tap rule the phone's bar and the tablet's rail share: `isSelected`, `shellTap`, `onShellTabTapped`; `ShellTabTest`), `domain/manager/ShellController.kt` (which of the New sheet and the avatar menu is open; a Koin single), `ui/components/sheets/AvatarMenu.kt` (`avatarMenuItems` as plain data, `AvatarMenuItemsTest`, and `AvatarMenuDrawer`, composed once around the page and the rail in `App.kt`), `ui/components/sheets/NewSheet.kt` (`NewSheetHost()`, composed beside `AlbumMatchHost()`), `ui/components/common/AvatarButton.kt` + `domain/manager/NeedsAttention.kt` (the header avatar and its dot), `ui/screens/home/*` (`HomeScreen`, `HomeViewModel`, `HomeModels.kt` the pure decisions, `HomeShelf.kt`, `ContinueListeningBox.kt`; `HomeModelsTest`), and the arrival kit `ui/util/{Arrival,Fold,TintedPlaceholder}.kt` (§7) |
| Player and its queue (P2) | `ui/screens/nowPlaying/NowPlayingScreen.kt` chooses: a portrait phone (under 600 dp, taller than wide; `stagedPlayerFits`) gets `ui/screens/nowPlaying/staged/StagedNowPlaying.kt`, the player with the queue as a layer inside it (`StagedQueueLayer.kt`); landscape and wider windows keep the old player with its toolbar and the `Screen.Queue` sheet. The rules are pure: `QueueDetents.kt` (peek / half / full, anchors, release, handle, Back, the compaction curves; `QueueDetentsTest`), `QueueGrouping.kt` (rows by who added them, the peek row, Playing from; `QueueGroupingTest`), `SheetScroll.kt` (`SheetScrollTest`). The queue's content is written once, `QueueContent(QueueLayout)` in `ui/screens/queue/QueueScreen.kt`, hosted by the sheet (`QueueScreen`) and by the layer. The autoplay switch is `autoplayToggleChip` over the chip rules (`AutoplayChips.kt`, `RadioManager.onAutoplayToggle`). Settings has no "Toolbar position" row any more; `nowPlayingToolbarPosition` stays and the landscape player and the lyrics sheet still read it (§4) |
| Search (P3, "Find") | `ui/screens/search/SearchScreen.kt` draws; every decision is pure: `SearchModel.kt` (`LibraryHits`, `mergeHits`, `capped`, `Lookup`, `searchPhase`, `offlineHits`, `browseTiles`, `externalRows`, `ownedArtistJoinsLibrary`; `SearchModelTest`) and `SearchState.kt` (`SearchUi`, `reduce`, which drops an answer for any query but the current one; `SearchViewModelReducerTest`). The rows: `search/components/{SearchRows,ExternalSection,BrowseTiles}.kt`. Typo tolerance: `domain/search/FuzzyMatch.kt` (`FuzzyMatchTest`), `LibraryFuzzyIndex.kt` (over Room), `MusicBrainzQuery.kt` (`mbFuzzyQuery`, `mbAlbumQuery`; `MusicBrainzQueryTest`); ownership by MBID: `domain/search/ArtistOwnership.kt` + `LbBotManager.ownedArtistIdsByMbid` (`ArtistOwnershipTest`). The browse cards' Deezer pages: `ui/screens/browse/*` (`Screen.Charts`, `Screen.EditorsPicks`), a tile's target from `domain/manager/BrowseResolver.kt` (shared with Discover). Discover's reasoned stack: `ui/screens/discover/DiscoverStack.kt` (`DiscoverStackTest`). The paste sheet's three states: `ui/screens/downloads/PasteLinkSheet.kt`, opened straight from Search through `ShellController.openAddFromLink` (§5, §7) |
| Artist and requests (P4) | The album page's request box, `ui/screens/external/components/RequestBox.kt` (which replaced the missing-album sheet; its sheet-to-stage table is at its top) with `SourceCards.kt`; which stage it shows is pure, `ui/screens/external/RequestStage.kt` (`requestStage`, `requestSteps`, `preselectedSource`, `requestActions`; `RequestStageTest`), and its state (edition, search, tap in flight, the echo of "Download all") lives in `ExternalAlbumViewModel` (`RequestUi`). The unowned artist page: `ArtistPageState.kt` (`ArtistPageStateTest`), `components/ArtistHero.kt`, the scan on arrival in `ExternalArtistViewModel`. The discography as rows: `components/ReleaseRows.kt`, each row's pill from `ui/screens/artist/ReleaseState.kt` (`ReleaseStateTest`). Pills: `ui/components/common/StatePills.kt` (tones, `StatePillsTest`) and `StatePill.kt`. Fill gaps on the owned album page: the gaps box, `ui/screens/collection/components/GapBox.kt` (its sheet-to-box table at the top), stage from `ui/screens/collection/GapStage.kt` (`gapStage`, `GapStageTest`), state `GapBoxUi` and `AlbumGap` in `CollectionDetailViewModel` (§7). Round 2 (the owner's mockups): the owned artist page's Popular releases and the discography page behind chips, `ui/screens/artist/{ArtistDiscographyScreen,DiscographyFilter,DiscographyOpen}.kt` (`DiscographyFilterTest`) and `components/DiscographyBrowser.kt`, which the unowned artist page draws inline |
| Library (P5) | `ui/screens/library/LibraryScreen.kt` (the header, the chips, the sort row, one body under a 220 ms cross-fade), Recents in `LibraryRecents.kt` over `LibraryViewModel` (light Room reads: `AlbumDao.getRecentlyPlayedAlbumRows`, `PlaylistDao.getPlaylistRows`, `SongDao.countStarred`; the hub's mixes; saved queues), its order pure in `LibraryModel.kt` (`recentsEntries`, `artistRecentAt`, `LibraryChip`; `LibraryModelTest`). The chips host the list pages' bodies: `AlbumListBody`, `ArtistListBody`, `PlaylistListBody`, `SongListBody`, `RadioListBody`, `MixListBody`, each beside its page with `xListViewModel` and `XSortAction` (§7) |
| Library tools (P6) | lb-bot's operator half, in the avatar menu's Library tools group. Every rule pure: `domain/manager/Attention.kt` (`/lb/attention`'s answer and how one read is taken, `toFixCount`, `attentionBadge`, `folderTitle` / `folderDetail`, `attentionPage`, `shouldRefreshAttention`, `fillFrameForcesAttention`; `AttentionTest`) and `SourceHistory.kt` (`/lb/activity`, `mergeHistory`, `historyGroups`; `SourceHistoryTest`); the count and its reads in `LbBotManager` (`attention`, `attentionBadge`, `refreshAttention`, `activity()`). Pages: `ui/screens/attention/*` (`Screen.Attention`), `ui/screens/sourcehistory/*` (`Screen.SourceHistory(section)`); the fill row both draw, `ui/components/common/FillRows.kt` (moved out of `DownloadsScreen`). Statistics' Find sources: `unownedRowAction` (`Listening.kt`), `Screen.ExternalAlbum.findSources`, `arrivalSearchShouldStart` (§7) |
| Playback reporting | `domain/manager/PlaybackReporter.kt` (OpenSubsonic `reportPlayback`, §4). **Two upstream files carry its wiring, and a merge must keep the fork's lines in both.** `androidMain/.../domain/manager/ScrobbleManager.android.kt` is upstream's `AndroidScrobbleManager` listener; it holds the `playbackReporter` field and `snapshot()`, the reporter call in each listener callback, the `nowPlayingReported` hook assignment and the `isRemoteActive` takeover collector in `init`, and `playbackReporter.release(snapshot())` in `release()` (B-009, B-027). Taking upstream's copy silently drops all reporting, the takeover close and the teardown `stopped`. `commonMain/.../ScrobbleManager.kt` holds the `nowPlayingReported` hook and its guard in `scrobbleNowPlaying` (B-027); lose them and the legacy now-playing ping comes back, resetting the entry's position to 0. `PlaybackReportLedger` (same file) persists the mediaId Navidrome confirmed, so the next start closes what a force stop left open (Q-042, §4) |

Two Room databases. **`CacheDatabase` is at 23** and is `fallbackToDestructiveMigration(true)` — it
is a cache, and saved queues re-reconcile from the hub. **`DownloadDatabase` (at 4) holds real user
data** and must never be treated that way. 23 added the lb-bot index mirror (§5) through a
hand-written `MIGRATION_CACHE_22_23`, so an upgrade keeps the cached library rather than wiping it.

---

## 2. Merging upstream — the playbook

```bash
git fetch upstream --tags
git merge v1.0.0-alphaNN
```

That is genuinely the whole workflow. The fork was a squashed snapshot with no shared history until
September 2026; reconstructing it onto its true base (`v1.0.0-alpha40`) is what makes a plain merge
resolve per hunk instead of declaring every file a conflict. The reconstruction was one-way — don't
redo it, and don't rebase the fork onto a new base "to tidy up".

Scale, for calibration: alpha40→58 was 247 conflicted files / 1,039 hunks. alpha58→59, the first
ordinary merge, was **32 files / ~75 hunks** and took one pass with no checkpoints.

### The rules, each of which was paid for

1. **Never splice two rewrites.** On a hunk where both sides rewrote the same block, taking "both"
   produces code that compiles into the wrong shape. Take **the fork's file whole, then re-apply
   upstream's specific change by hand** — or, where upstream rewrote a file the fork barely
   touched, the reverse. Pick a skeleton; don't interleave.
2. **No regex sweeps over a conflicted tree.** Four self-inflicted breakages came from scripted
   rewrites that were right in the general case (an `import kotlinx.coroutines.flow.map` added
   where `.map` was a collection op; a `_backing`-field sweep that hit upstream's `field =` idiom;
   an `album.name → album.displayName` regex that also caught `it.name` on `DomainSongArtist`).
   Per-site, or not at all.
3. **After it compiles, diff the registrations against both parents.** This is the dominant failure
   class and the compiler sees none of it:

   | Mechanism | Resolves by | How a loss surfaces |
   |---|---|---|
   | Koin (`PlatformModule.{android,ios}.kt`, `ViewModelModule.kt`, `ManagerModule.kt`, `RepositoryModule.kt`) | type, at runtime | `NoDefinitionFoundException` on launch |
   | navigation3 (`entry<Screen…>` in `App.kt`, ~46 of them) | key, at runtime | the screen throws when navigated to — possibly only from one card, deep in the app |
   | `staticCompositionLocalOf { error(…) }` (`di/Locals.kt`) | read site | throws only when read, e.g. `LocalSheetState used outside of a sheet` on opening the player |

   The `LocalSheetState` one recurs because the fork keeps its own `NowPlayingScene` /
   `BottomSheetScene` (they carry the cover ambient and the `OverlayScene` structure) while
   `NowPlayingScreen` and `LyricsScreen` come from upstream and read the local: two consumers, zero
   providers. Every throwing local must end the merge with exactly one provider.

   Fork-only Koin bindings are the easiest to lose, because upstream's modules never had them.
   `HubSocketClientFactory` (B-045) and `HubLocalDevice` (I-032, §4) are each registered in
   **both** `PlatformModule.android.kt` and `PlatformModule.ios.kt` and consumed by `HubManager`'s
   last two `get()`s in `ManagerModule.kt`; a merge that drops a registration compiles, and
   `HubManager` is `createdAtStart`, so the app dies at launch with `NoDefinitionFoundException`.
   `StationManager` (I-040) is the same kind of loss in `ManagerModule.kt`: `RadioManager`, also
   `createdAtStart`, takes it and `SongRepository` as its last two `get()`s.

   **A fourth mechanism fails with no error at all: an optional argument at a call site.** A
   nullable lambda that defaults to `null` is an entry point that exists only where a caller
   passes it, and a merge that takes upstream's call drops it silently — the row or link is
   simply not there. The ones this fork depends on:

   | Argument | Passed at | Lost, it hides |
   |---|---|---|
   | `onFixAlbumMatch` (`CollectionSheet`) | `ArtistDetailScreen` (the album sheet it opens), and `CollectionDetailScreen` → `CollectionDetailScreenTopBar` (`collection/components/TopBar.kt`) → `CollectionSheet` | "Fix album match" (I-022) |
   | `onWrongEdition` (`GapBox`) | `CollectionDetailScreen` | "Wrong edition?" in the gaps box's note (I-022) |
   | `onStartRadio` (`CollectionSheet`) | `collection/components/TopBar.kt` (albums only), `ArtistDetailScreen`'s album sheet, `album/components/{GridItem,ListItem}.kt` | an album's "Start radio" (I-040) |

   `MbidMatchSheet.ndArtistId` ("Merge into one name", I-023) was a third and is a **required**
   parameter for exactly this reason: losing it is a compile error now. Prefer that for a new
   one wherever every caller has the value.
4. **Build `:androidApp:assembleRelease` and verify the signature. Every time.** See §3 — upstream
   owns that line and keeps rewriting it, debug signs correctly either way, and an unsigned APK
   reads on the phone as a vague "couldn't update".
5. **Check Room versions on both sides before trusting the build.** If upstream bumped
   `CacheDatabase`, go **above both** — at alpha59 both parents claimed 21 with *different*
   schemas, so shipping 21 would have matched an installed database against the wrong one. Resolve
   the conflicted `N.json` to upstream's (so N means what upstream shipped), regenerate the new
   one, and assert it carries both parents' changes. Confirm `DownloadDatabase` is untouched.
   **The fork's own tables make this sharper since 23.** `CacheDatabase` 23 is fork-only: the four
   lb-bot mirror tables (`lb_index_artist`, `lb_index_release`, `lb_index_meta`,
   `lb_response_cache`, in `entities/LbIndexEntities.kt`, DAO `LbIndexDao`) and their two indices
   (`lb_index_artist.ndArtistId`, `lb_index_release.rgid` — the latter added before 23 ever
   shipped, since the `(artistKey, rgid)` key cannot serve `WHERE rgid = ?`) arrive through the
   hand-written `MIGRATION_CACHE_22_23` in `DownloadMigrations.kt`, registered in **both**
   `PlatformModule.android.kt` and `PlatformModule.ios.kt`. When upstream ships its own 22→23 (or
   anything ≥ 23), renumber upstream's change **above the fork's** — never slot it in underneath and
   never let two 23s coexist; that is the alpha59 "two 21s" trap again. The fork's migration then
   has to be re-chained: a new `MIGRATION_CACHE_23_24` (or higher) that applies upstream's DDL on
   top of the fork's 23, registered in both platform modules, with its SQL checked against the
   regenerated `N.json`'s `createSql` by script rather than by eye (that is how 22→23 was
   verified). A migration that is merged away does not fail the build — the upgrade silently falls
   back to destructive and wipes the cached library, and the mirror re-pulls from zero.
6. **Translations** (`values-*/strings.xml`): upstream's wholesale, re-add the fork's keys, then
   grep for duplicate `name=` attributes — a duplicate breaks
   `convertXmlValueResourcesForCommonMain` with an error that doesn't name the key.
7. **`.github/`, `app-repo.json`, `Gemfile*`, `.gitignore`, `libs.versions.toml`,
   `settings.gradle.kts`, `fastlane/`: upstream's.** Release plumbing and dependency wiring.
8. **Check every new upstream screen against the fork's ambient** (§6). Upstream writes screens
   against its own flat theme; a new one needs checking both that it lets the ambient through and
   that its own colour choices survive a scheme it was never designed for. At alpha59 one screen
   had two independent faults of exactly this kind.
9. **Grep for the replaced engines after a merge.** `rememberColorSchemeFromCoverArt` /
   `rememberDominantColorState` are what this fork replaced; they came back in through a new
   upstream screen and rendered black-on-black. They legitimately remain in `SongSheet`,
   `MoreButton`, `SongDetailSheet` and `SongDetailScreen` (sheets over a fixed-brightness backdrop),
   so a grep needs reading, not a blind sweep.
10. **Prefer `merged()` over a `NavbarConfig.VERSION` bump** when upstream adds a `NavbarTab.Id`
    (§7).
11. **Sample the region that should change.** More below, but: a verification measuring the wrong
    band will happily confirm a fix that did nothing.
12. **Smoke-test on the emulator, then install the release build on the phone.** They catch
    different things — the emulator found three runtime crashes, the phone found the unsigned APK.
13. **Do not `git push --tags`.** `upstream` brings ~75 of ssalggnikool's release tags; pushing
    them makes the fork look as though it cut those releases.
14. **`NavDisplay` keeps BOTH entry decorators**, upstream's list verbatim:
    `entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator(),
    rememberViewModelStoreNavEntryDecorator())` in `App.kt`. Upstream added it at alpha42
    (`bbda3447`), and not one of the fork's merges carried it — the alpha52, alpha58 and alpha59
    trees all lack it, because `App.kt` is taken fork-whole (below) and this was a structural
    change nobody re-applied. **Nothing failed**: `NavDisplay`'s default is the saveable-state decorator alone, so everything kept
    working while every ViewModel ever created lived in the activity's store for the whole process
    — every artist page ever opened stayed alive and re-read on every library bump. It was found by
    an audit, not a symptom. After a merge touching `App.kt`, grep for
    `rememberViewModelStoreNavEntryDecorator`. With it in place a ViewModel dies when its entry is
    popped — and **a tab switch replaces the tab page above Home** (`BottomBar` / `SideBar`; since P6 the
    stack is `[Home]` or `[Home, tab]`, it used to be cleared), so every root tab but Home loses its
    ViewModel on every switch unless it opts out through
    `koinViewModel(viewModelStoreOwner = koinInject<PersistentViewModelStoreOwner>())`. The
    decorator and the opt-outs are ONE change upstream (`bbda3447`); restoring one without the
    other is how this fork briefly re-fetched Discover and Fresh over the network on every tab
    visit. The opt-out sites, all of which must survive a merge:

    | Site | Opts out | Source |
    |---|---|---|
    | `AlbumListScreen`, `ArtistListScreen`, `GenreListScreen`, `PlaylistListScreen`, `RadioListScreen`, `SearchScreen` | when `!nested` | upstream |
    | `SongListScreen` | when `!nested` (on the fork's `artistId` key) | upstream |
    | `StarredScreen` (Liked) | never since P5 round 2: its list is read on each opening | fork |
    | `LibraryScreen`'s `LibraryViewModel`, and each chip's body's view model (`albumListViewModel(false, …)` and its siblings, `DownloadCenterViewModel`) | always | fork (P5): a chip switch keeps each list's scroll, sort and filters |
    | `LyricsScreen` | always, keyed on the song | upstream |
    | `FreshScreen`, `DiscoverScreen`, `MixListScreen` | when `!nested` | fork — their `init` loads over the network |
| `HomeScreen` | always | fork (P1) — a root tab with no `nested` form; it reads on every visit (`revalidate`) |
| `HomeScreen`'s `SavedQueuesViewModel` | always | fork (P1), as `LibraryScreen`'s |
    | `LibraryScreen`'s `SavedQueuesViewModel` | always | fork |

    Grep `PersistentViewModelStoreOwner` against both parents after a merge; a fork-only root tab
    added later needs its own row here. Anything else that must outlive its screen belongs in a
    process-scoped manager, never in a ViewModel on a popped entry. A ViewModel that must start
    such work itself shields it instead: `DownloadCenterViewModel` (the Downloads page's, a pushed
    page, not a tab) runs its cancel / retry / another-source / allow-mp3 / wishlist POSTs through
    `launchToCompletion`, i.e. `withContext(NonCancellable)` inside `viewModelScope` — before that,
    a Cancel tapped just before backing out was cancelled with the pane and the download kept
    running. A new lb-bot action on that screen goes through the same helper.

⚠️ `dev.zt64.subsonic:subsonic-client` resolves at `1.0.0-SNAPSHOT` from a GitHub raw maven repo. A
snapshot is not reproducible: if a build suddenly fails on a symbol that used to exist, suspect
that before suspecting the merge.

### Files that are always the hard ones

- **`shared/MediaPlayer.android.kt`** — the fork's largest delta over an area upstream rewrites
  often. Upstream has **no** competing implementation for any fork part here (there is no upstream
  cast code at all), so every problem in this file is a re-threading error, not two designs
  colliding. It was 27 hunks at alpha58 and only 4 at alpha59 — and the alpha59 ones auto-merged
  *around* the fork's code, which is the real trap: **a clean auto-merge is not a correct one.** The
  `hubManager.isRemoteActive` collector that swaps `session.player` between ExoPlayer and
  `RemoteSessionPlayer` was the last surviving reader of `mediaSession` after upstream renamed it
  to `mediaLibrarySession`, and nothing but reading it caught that.
- **`App.kt`** — every nav entry, the `Washed` wrapper on the root tabs, the composition locals,
  the `NavDisplay` entry decorators (rule 14), and `AlbumMatchHost()` under the theme (§5,
  I-022: drop it and "Fix album match" opens nothing, with no error), and `ColourArrivalEffects()`
  beside it (§6 rule 8: drop it and cold starts go neutral again, with no error). P1 added two more fork lines the same way: `AvatarMenuDrawer { … }` around the page and the rail, and `NewSheetHost()` beside `AlbumMatchHost()` (§7: drop either and the avatar or the New button opens nothing, with no error). Round 2 added a third: `AppWash( … ) { NavDisplay( … ) }`, the playing cover's wash behind the pages (§6 rule 9: drop it and a page change flashes the colours and a back gesture shows a white bar, again with no error; the blur source and the window-insets modifier are on the `AppWash`, not on `NavDisplay`). Take the fork's whole and
  re-apply upstream's structural changes by hand.
- **`PreferenceManager.kt`**, **`SessionManager.kt`**, **`DownloadManager.kt`**,
  **`DbRepository.kt`**, **`SongDao.kt`** — union merges where both sides append. Usually textual
  collisions only; read them as unions rather than as choices.
- **`strings.xml`** — 237 fork keys against upstream's, historically with zero name collisions.

---

## 3. Building, signing, driving

```bash
./gradlew :androidApp:assembleRelease     # ~2 min → androidApp/build/outputs/apk/release/Navic.apk
./gradlew :androidApp:assembleDebug       # for correctness work
./gradlew :composeApp:testAndroidHostTest # JVM host tests, no device/emulator
```

There is no `:composeApp:compileDebugKotlinAndroid` task. Gradle provisions its own JDK 21
toolchain — don't set `JAVA_HOME`. **Judge smoothness on release only**; debug Compose is
dramatically choppier and will mislead you.

**Host tests** run pure logic on the JVM, no device needed: `composeApp/src/commonTest` for
rule functions (`LbIndexPlannerTest`, `PlaybackReportTrackerTest`) and `composeApp/src/androidHostTest`
for the ones that read the source tree with `java.io` (`WashedBrowsingDriftTest`) — one task,
`testAndroidHostTest`, runs both. Filter to one class while iterating with
`./gradlew :composeApp:testAndroidHostTest --tests '<FQCN>'`, and run the whole task once before
committing.

### RAM: this box cannot run two 8 GB daemons

`gradle.properties` used to ship `-Xmx8g -Xms8g` on **both** `org.gradle.jvmargs` and
`kotlin.daemon.jvmargs`. `-Xms` is *pre-allocated*, so the two daemons reserved 16 GB between them
before compiling a line — on a 15.3 GB workstation. That took the whole machine down on
2026-09-22, mid-build.

They are `-Xmx4g` and `-Xmx3g` now, with **no `-Xms`**. Do not reintroduce `-Xms`, and do not raise
the ceilings without the RAM to back them. The capped config is also *faster* — a full
`assembleRelease` including R8 runs in ~2 min rather than ~6, because the machine stops swapping.

While working here:

- **Stop the daemons when you are done**: `./gradlew --stop`. They are 4 GB and 3 GB of resident
  memory doing nothing, and a session that builds repeatedly leaves both alive between builds.
- **Don't run a build and the emulator at once**, and don't run two builds at once.
- `free -m` before a build if anything else heavy is running. Under ~5 GB available, stop the
  daemons first.
- If you need to cap harder without touching the tracked file:
  `./gradlew --no-daemon -Dorg.gradle.jvmargs="-Xmx5g" -Pkotlin.compiler.execution.strategy=in-process …`
  — one JVM, nothing resident afterwards.

### The signing trap

With `SIGNING_*` unset the release build falls back to the **debug** keystore, and every installed
copy of this fork trusts that one key. Two ways it goes wrong, both of which read on the phone as
an unexplained failure to update:

- **A new machine silently generates a fresh `~/.android/debug.keystore`.** The phone then refuses
  the APK with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`. Back the keystore up; the private key cannot
  be recovered from an installed APK, and the app's preferences file (the one holding the Navidrome
  password and the hub / AudioMuse tokens) is excluded from Android backup on purpose, so an
  uninstall really does cost the login.
- **A merge eats the fallback.** Upstream writes
  `signingConfig = signingConfigs.findByName("release")?.takeIf { it.storeFile != null }`, which is
  **null** when `SIGNING_*` is unset — their CI always sets it, so upstream never sees an unsigned
  output. The fork's line is
  `signingConfig = signingConfigs.getByName(if (hasReleaseSigning) "release" else "debug")`, and
  the tell that a merge took upstream's is `hasReleaseSigning` computed two lines above and never
  read. The result is an APK with **no signature at all**
  (`apksigner verify` → `DOES NOT VERIFY — Missing META-INF/MANIFEST.MF`).

```bash
$ANDROID_HOME/build-tools/36.0.0/apksigner verify --print-certs \
    androidApp/build/outputs/apk/release/Navic.apk
keytool -list -v -keystore ~/.android/debug.keystore -storepass android | grep SHA-256
```

Debug builds sign correctly either way, which is exactly why an emulator round misses this.

### Driving it

A change here can be *looked at* rather than reasoned about, and that loop is how most of the
colour work was done: edit → `assembleDebug` → `adb install -r` → `screencap` → measure the pixels
→ edit again.

```bash
emulator -avd <avd> &            # -no-window for headless; screencap works either way
adb wait-for-device && adb shell getprop sys.boot_completed
adb install -r androidApp/build/outputs/apk/debug/Navic.apk
adb shell monkey -p paige.navic.debug -c android.intent.category.LAUNCHER 1

adb exec-out screencap -p > /tmp/shot.png          # read it back and sample it
adb exec-out uiautomator dump /dev/tty             # real element bounds
adb logcat -d --pid=$(adb shell pidof paige.navic.debug)
adb shell "cmd uimode night yes"                   # dark mode without touching settings
adb shell wm size 1600x2560                        # cross the Medium breakpoint for the SideBar
adb shell wm size reset
adb shell run-as paige.navic.debug ls cache/       # debug builds only: Coil cache, Room DBs
```

- **A logged-in emulator joins the hub.** Pressing play there moves playback on whatever device is
  really active. The release build is a different `applicationId` and has no session.
- **Screenshots are evidence, not proof.** Sample the PNG (hue, lightness, contrast ratio) rather
  than eyeballing it — that caught colour regressions that looked fine, twice. And **sample the
  region that is supposed to change**: one "verification" measured the bottom 5% of the screen,
  where the mini-player gradient is the same colour on both shots, and so confirmed a fix that had
  done literally nothing.
- `uiautomator dump` gives real bounds. Tap coordinates guessed off a screenshot land on the wrong
  tab as soon as the nav bar's pill resizes.
- Shut down afterwards: `adb emu kill`, then `./gradlew --stop` — the daemons hold 7 GB
  between them and the emulator is another 3 GB; leaving both up is how the box ran out.

**Measuring motion, place and audio (the sandbox VM's AVD, I-039).** A screenshot shows a state at
rest; the phone's complaints were about what happens between states. These read it:

- **Frames:** `adb exec-out screenrecord --output-format=frames --size 336x748 --time-limit 4 - >
  f.bin` gives raw RGB frames, one per screen change, each after a 20-byte header (`<IIIII`: ?,
  width, height, stride, bytes per pixel). In-between frames prove an animation; a single odd frame
  is a glitch. Follow an element by matching its own pixels from frame to frame.
- **Transitions on debug:** the first frame of a pushed page outlasts the 450 ms SharedXAxis, so no
  in-between frame is drawn in either orientation. Record with `settings put global
  animator_duration_scale 10`, then set it back to 1.
- **Audio:** loop `dumpsys media.audio_flinger` on the device and read the app's active track: id,
  Server frames (hex), Underruns, Flushed. Continuous playback is about 44 frames/ms at 44.1 kHz.
  The AVD has no audio offload, so a clean reading there does not clear the phone (B-133).
- **Hub directives:** the umbrella's `hub/tools/fake_receiver.py --echo` as the playing device prints
  LOAD, "queue changed (N)", jump. Its clock runs past a song's end, so a transfer back from it fails.
- **Gestures:** a snackbar shows about 1.5 s after its action and goes after about 6, and a dump
  takes 1 to 2 s, so tap it by coordinates in the same `adb shell` line. A queue row's swipe-to-dismiss
  needs `input motionevent DOWN / MOVE… / UP`.
- **Library internals:** there are no sources jars; unzip the transformed jar under
  `~/.gradle/caches/*/transforms/` and `javap -p -c` it.

---

## 4. The player, and what "remote" means here

The app is simultaneously a controller and a receiver of a hub session. `shared/MediaPlayer.kt`
exposes a **blended `uiState`** — the local ExoPlayer state when playing locally, the mirrored
session when not — alongside a raw `localUiState`. `MediaPlayer.android.kt` runs a
`MediaLibraryService` (since alpha59, for Android Auto's browse tree) whose `mediaLibrarySession`
has its `player` swapped between the real ExoPlayer and `RemoteSessionPlayer` as
`hubManager.isRemoteActive` changes. That swap is the single most fragile line in the tree.

Rules that are easy to violate and produce "it looks right and the wrong thing plays":

- **media3 flushes its listeners INLINE.** `removeMediaItem` / `moveMediaItem` / `addMediaItems`
  re-enter `onTimelineChanged` → `updatePlaybackState()` synchronously, which reads the
  already-mutated player index against the not-yet-mutated `_uiState.queue` — and then the
  mutator's own arithmetic applies the same shift a second time. Deleting a queue item above the
  playing track landed on the wrong song. Every queue mutation runs inside `withQueueMutation { }`
  (which makes `updatePlaybackState` a no-op) and re-derives once afterwards — **except** when the
  queue is now empty, because an empty timeline reports index 0 and would clobber the
  `currentIndex == -1` sentinel every enqueue path reads.
- **Undo reverses the one edit, and never reloads (B-133).** `performQueueUndo` makes the single
  insert or move that reverses a swipe or a drag (`queueUndoEdit`, `QueueUndoTest`): locally
  `insertQueueItem` / `moveQueueItem`; through the hub `move`, or `enqueue` at the end then `move`
  into place (the hub inserts only next or at the end), each a `queueChanged` that Feishin and Navic
  apply around the playing song. Loading the snapshot re-prepares the playing item (a second of
  silence); rebuilding around it still replaces the next item, which a player in audio offload is
  already decoding; and a whole queue sent to the hub is a `setQueue`, a `load` on the receiver.
  Anything that is not one edit (a clear, the playing song removed) still loads the snapshot.
- **On a portrait phone the queue is a layer inside the player, not a sheet (P2).** One
  `AnchoredDraggableState` with three places: peek (a strip naming the next song in play order,
  shorter with a "Casting to" line above it while another device plays), half (the player compacts
  above it and keeps title, progress and controls; the half line is 52 % of the height or 26 dp
  under the measured compact block, and a screen too short for it has no half), full (a mini header
  over the queue). A release goes to `releaseTarget` (0.15 s projection, nearest place); Back and
  the handle move one place; the list and the layer share a scroll by `layerShare` (the Material
  sheet's rule; what is left of a downward scroll at peek stays, so it never drags the player down).
  The player's pieces are placed once at rest and moved to their compact places by the layer's
  position in placement layers (`StagedPlayerLayout`), so nothing is re-measured while it moves.
  The header and the mini header take turns (`headerAlpha` / `miniHeaderAlpha`), never both half
  there. Settled at full the layer reports itself to `SheetCover` as depth + 1, so the player's
  blur and wave hold as they did under the queue window. `QueueContent(QueueLayout.Layer)`: the
  Autoplay switch and Save in its header, the character chips (dimmed at 0.4 while autoplay is off,
  still a tap that turns it on with that character), the off note (only for a queue a station is
  bound to), and at full only the Up next / Related switch, the count with Clear and the generator
  line. Rows are grouped by who added them (`queueListItems`: Autoplay = in the bound station's
  served list; "Queued by you" only beside an autoplay group; "Up next" otherwise); a dragged row
  moves through a frozen copy of the list, past headers, and the mirror moves only when it passes a
  row. **Outside a drag the list groups by the player's own index**, not by the uid-tracked playing
  row: that follows a track change a frame late, and the scroll to "Now playing" landed on the song
  just played (LazyColumn then kept that row's key at the top; seen on the AVD). The layer is as tall
  as at full and slides, so what is pinned to its bottom (the undo snackbar) is lifted by the part
  off screen (`QueueLayout.Layer.hiddenBelowPx`). Navigating out from the layer leaves the player by
  `NowPlayingSheetController.requestHide`. A rotation drops to the old player; the place is not
  carried. **The phone's first notes (2026-10-19):** the queue header's left says what it plays
  from and what built it (`QueueGeneratorLine`, said once when it repeats the name); "Playing from"
  reads the hub session's record id while another device plays (the mirrored player state carries
  none); no "Casting to" line, the device pill is inverted instead (an offscreen layer, its content
  drawn `BlendMode.DstOut`); the title row's star is the RATING (`StagedRating.kt`: opens left into
  five, open when rated, a tap on the set star clears it); the Save pill is the playing song's:
  favourites (a heart, filled while it is one; `starSong`) or `AddToPlaylistSheet` (editable
  playlists, + adds and becomes a bin, the sheet stays open, New playlist bottom left puts the new
  one first, Done bottom right). Saving the queue stays in the queue's header. The mini header is
  composed only from where it starts to show: a disabled Material button still takes the touches
  behind it, and the clear one ate the header's more button's taps. **Drags on the player
  (2026-10-20):** the player's area is a vertical `scrollable` that consumes nothing under
  `PlayerAreaScrollConnection`, so every drag there is nested scroll: pre-scroll reaches the sheet
  first (it takes nothing while fully open), then the connection, which moves the queue: up opens it
  (`playerReleaseTarget`: a flick stops at half), down brings it back while it is up and swallows the
  rest so the player is never taken; a downward drag that starts at peek is left alone and reaches the
  sheet's post-scroll, which closes the player as before. The Save menu is NOT a popup window (a window
  cannot blur the player): the pill GROWS into it (`SaveMorph.kt`, 2026-10-20), a shape drawn in the
  player's layout over a Haze blur of it (`hazeSource` on the player's area, whatever the Expressive
  blur setting), driven by one progress on `rememberFoldMotion`; the real pill steps aside while the
  shape is anything but closed, and the edge and sheen grow in with it so closed it IS the pill.
- **The remote mirror's base is cached by tracks and index; everything else is read per frame.**
  `HubManager.refreshMirror` rebuilds the resolved queue only when the track ids or the index change,
  and `pushRemoteProgress` lays the frame's play state, progress, shuffle and repeat over it. Shuffle
  and repeat used to live in the base, so a tap on either during remote playback showed nothing until
  the next song. A new per-session field goes in `pushRemoteProgress`, not the base.
- **A queue reorder must not look like a track change.** Two places read the playing track by
  *index*, and a reorder moves the index without moving the music. `ArtworkPager` draws from a
  snapshot swapped only once the pager has been repositioned, and snaps rather than animates when
  the song at the new index is the one already showing. `QueueScreen` highlights by the row's
  stable uid, not by `currentIndex` — the mirror reorders during the drag and commits on release.
- **Never hand the notification a `RemoteTrack.imageUrl`.** It was minted by whichever device
  published the queue, from *its* base URL and *its* salted token, so this device may simply be
  unable to fetch it — a blank cover in the shade for the whole of remote playback. Mint locally
  from the song id. `setArtworkUri(sessionManager.getCoverArtUrl(coverArtId))` satisfies this;
  `ExoPlayerCoilBitmapLoader` then fetches it in the service process.
- **Widgets have the matching trap:** `broadcastNowPlaying` must be driven from the **merged**
  `uiState`, not the local player's, which is paused and static while remote is active.
- **`builder.setMimeType(mimeType)`** in `MediaPlayer.android.kt` is load-bearing for the Cast
  `MediaItemConverter`. (There is **no** `SafeMediaItemConverter` class in this tree, despite older
  docs naming one.)
- **Who the volume buttons belong to during remote playback is decided, not left to the
  session (`VolumeKeys.kt`).** A session declared remote takes the buttons from every app, and
  whether the system then draws a slider depends on the Android build: the user's phone drew
  none, the API 37 emulator drew a cast row. So `RemoteSessionPlayer` declares
  `PLAYBACK_TYPE_REMOTE` only while Navic is on screen or the phone is locked
  (`AppPresence`, `volumeKeysGoRemote`) and `PLAYBACK_TYPE_LOCAL` otherwise, where the buttons
  move the phone's own volume with the system's slider. On screen, `MainActivity.dispatchKeyEvent`
  takes the press before the system sees it (`HubManager.pressRemoteVolume`), and
  `RemoteVolumeHud` (composed once in `App.kt`, a fork line) draws the level; a sheet or dialog
  is a window of its own and reaches the session instead. `AppPresence`'s receiver is
  **exported**: "unlocked" (`ACTION_USER_PRESENT`) is sent by the system UI's uid, and a
  not-exported receiver never heard it, which left the phone "locked" after an unlock.
- **The output-device chip** in the shade reads "This phone" during remote playback and cannot be
  fixed by media3 alone: `DeviceInfo.routingControllerId` would have to name a real `MediaRouter2`
  routing session, i.e. a `MediaRoute2ProviderService` publishing the hub's devices. Deliberately
  not built.
- **`reportPlayback` describes the LOCAL player only.** `domain/manager/PlaybackReporter.kt` is
  driven off the same listener on the local ExoPlayer as the scrobbles (`AndroidScrobbleManager`),
  so the mirrored remote session is never reported from here. It sends nothing unless the server
  advertises `playbackReport`. It closes the phone's entry with one `stopped` when
  `isRemoteActive` flips: the takeover closes it itself, because the swap's `local.pause()` fires
  no event when local was already paused. It also closes it when `PlaybackService` is destroyed,
  from a process-lifetime scope, bounded to 2 s and best-effort, and that `stopped` supersedes
  anything still queued. Always `ignoreScrobble=true`: `ScrobbleManager` stays the only scrobbler.
  **While the server is known to support it and the reporter is not suppressed (another hub
  device active, scrobbling off), `ScrobbleManager`'s legacy now-playing ping
  (`scrobble(submission=false)`) is skipped.** Navidrome 0.64 implements that ping as a
  `reportPlayback` `playing` at position 0 under the same player key, and forwards NowPlaying to
  Last.fm / ListenBrainz for either call, so the ping only reset the entry's position. The call
  is hand-rolled in `SessionManager`, because the bundled client's sends `state=PLAYING` (enum
  `toString()`) and Navidrome only accepts lower case. Android only; iOS does not report.
- **A force stop's now-playing entry is closed on the next start (Q-042).** The tracker's open id
  lives in memory, so `PlaybackReportLedger` (in `PlaybackReporter.kt`) persists the mediaId of
  every report Navidrome *confirmed* (`reportPlayback`'s Boolean) in
  `PreferenceManager.playbackReportOpenId`, cleared by a confirmed `stopped` for that id. A new
  reporter queues a `stopped` for **that exact id** before its consumer starts, because Navidrome
  ignores a `stopped` whose mediaId differs from the entry. A dropped or refused send keeps the id
  for the next start. Watched closing a force-stopped entry on the phone, 2026-10-02.
- **The persisted player state is read once, and null is an answer (Q-049).**
  `PlayerStateRepository.savedState()` is one read of the DataStore: nothing persisted (a fresh
  install) and a state that no longer decodes both come back null, and `restoreState` moves on.
  Upstream's shape — a `StateFlow` that starts null, read with `filterNotNull().firstOrNull()` —
  waits forever in exactly those two cases, and `primeIndex` and `observeAndSaveState` run after
  `restoreState` in the same coroutine. So a fresh install never wrote `player_state` at all, on
  its first run or any later one (no queue after a restart, ever), and an undecodable state was
  never replaced; both watched on the emulator, 2026-10-12. `stateToRestore` restores into an
  empty player only, never over a queue started since the process came up. Upstream's
  `PlayerStateRepository.state` flow is gone here: check `restoreState` after a merge that
  touches either file.
- **Android Auto knows nothing about the hub.** Its `resolveStreamUrl` builds a *local* Navidrome
  URL, so browsing or playing from Auto during a remote session may start local playback. Untested
  as of alpha59.
- **Q-040 (releasing the in-process `MediaController` on a swipe, so the service can stop) was
  built and reverted 2026-10-02** and is kept on the local branch `q040-wip`: it needs
  `onPlaybackResumption` first, or a media button after a swipe reaches an empty service (likely
  `ForegroundServiceDidNotStartInTimeException`).

- **A value this device commanded is pinned until the hub answers for it (I-031).** With
  `actAck` in `welcome.features` every `act` carries a `cid` (`HubManager.withCid`; the hub
  answers each with an `ack`, written after the frames the act caused), and play state, a seek
  target and a volume level are shown at once and held until the ack of the **newest** act on
  that value, 2.5 s without one, or a `welcome`. The rules are pure functions in `ActPins.kt`
  (`ActPinsTest`). The mirror's own fields hold what is SHOWN and each pin keeps what the hub
  last said underneath, so every reader of `remoteSession` is covered unchanged; `applySession`
  lays a snapshot **under** the pins (`underPins`) instead of replacing the mirror, because a
  `session` frame may have left the hub before the act arrived. The play button sends `play` or
  `pause`, never the hub-side `playpause`, since a toggle applied to a state the user was not
  looking at undoes the tap. A refusal arrives in the ack only (`ok:false`) and goes through
  `onHubError`, the `error` frame's own path. Against a hub without `actAck` nothing carries a
  `cid` and the timed holds of `CommandHold.kt` stand in. Feishin's `act-pins.ts` is the twin.

- **A device's row says what the device is and how loud it is, and the phone's volume is its
  media volume (I-032, I-033, B-114).** `hello` carries `kind`: `phone` or `tablet` by the
  600 dp line, and a cast bridge reads `group` / `tv` / `speaker` off the `ca` bits of the TXT
  record (`CastTraits.kt`). The speaker's name goes out bare: the 📺 prefix is gone, the row's
  icon comes from `kind`, and nothing may tell a cast row by its name (the id is `cast-<id>`,
  the platform `chromecast`). `do:setVolume` sets `AudioManager.STREAM_MUSIC` through
  `HubLocalDevice` (`AndroidHubLocalDevice`) and puts the player's gain back to 1. It used to
  set that gain, which nothing showed, nothing reset, and which stayed in force for local
  playback afterwards, while the hardware buttons moved a different quantity. The level the
  system holds is read from the system and reported with `deviceState {volume}`: after
  `welcome`, after a directive even when nothing moved (a phone has 15 to 25 steps, so the row
  told 37 comes to say 40), and on every change whoever made it, at most 4 a second. A bridge
  reports its receiver's level the same way, from RECEIVER_STATUS, while it holds a cast
  connection. The picker's order, its status words and "paused" for an active device that is
  not playing are pure functions in `DeviceRows.kt` (`DeviceRowsTest`), read by the picker, the
  navi-connect settings list and the line over the now-playing title. iOS registers
  `PlainHubLocalDevice`: nothing reported, the gain as before.

- **A device that is not playing can be turned down, and the timed sleep timer is the
  session's (I-033, I-034, B-113).** `hello` carries the `idleVolume` cap on Android
  (`HubLocalDevice`): `do:setVolume` is applied whenever it arrives, carries no `seq`, and
  neither arms the hub-driven window nor counts as a directive to echo. With `volumeTarget`
  in `welcome.features` (`HubManager.hubOffers`) the picker gives a slider to every row that
  `rowVolumeOffered` allows — online, responding, has the cap, not the playing device, not this
  one — committed on release as `act:volume {level, target}`; the level is pinned per device id
  (`VolumePins` in `ActPins.kt`) until that act's ack. With `sleepTimer` in the features the
  TIMED mode of `SleepTimerManager` sends `act:sleepTimer {minutes}` and arms nothing here;
  `session.sleepTimer` is mirrored as `HubManager.sessionSleepTimer`, counted down on this
  device's clock from the moment its frame arrived, and shown on every device
  (`shownSleepMode`). The song-count and end-of-queue modes stay local. The rules are pure
  functions in `SessionSleepTimer.kt` (`SessionSleepTimerTest`), the guard among them: the
  playing device pauses itself 5 s after the timer's end if no frame has cleared it, which is
  what stops the music when the hub link has dropped. The cast bridge does not take
  `idleVolume`: a speaker it holds no connection to gets no row slider from this client.

The cast bridge's own rules — self-exclusion on reconnect, join-don't-launch, release by pausing
rather than stopping — are protocol-level and live in navi-connect's `CLAUDE.md` and `PROTOCOL.md`,
because both clients must agree on them.

Its reconnect behaviour is app-local (B-045). `CastDeviceBridge.runLoop` survives a
`CancellationException` that is not its own — `ensureActive()` still ends it on `destroy()`, and
`start()` never restarts a loop that ended, which is how a bridge sat on "connecting…" for the life
of the app. The backoff resets on `welcome`. And `CastBridgeManager`'s reconcile tears down and
re-claims a bridge whose loop has ended, or that has been `!connected` for over 2 min **while this
device's own hub link was up** (`bridgeIsStale` in `BridgeLiveness.kt`): the clock does not run
during a hub outage, when no bridge can get in and a teardown would close a cast channel that is
still advancing the speaker's queue. It logs one line when it fires.

**Cast discovery waits for the local-network permission on Android 17 (B-099).** An app that
calls `NsdManager.discoverServices` there without `ACCESS_LOCAL_NETWORK` is not refused: the
system raises its own "Choose a device to connect" chooser over the app. `CastBridgeManager` is
`createdAtStart`, so a fresh install opened on that chooser before the first screen had asked
for the permission, and with the permission declined the re-query raised it again once a minute.
`followDiscoveryPermission` starts and stops `CastDiscovery` on `castDiscoveryAllowed`
(`BridgeLiveness.kt`), looked at every 5 s; the permission itself is still asked for by
`checkLocalNetworkPermission` in `App.kt`.

### Radio and autoplay are the hub's stations (I-040)

With `station` in `welcome.features` and the probe (`GET /sonic/station/characters`) saying
available (`StationManager.available`; navi-connect PROTOCOL §18), a radio is a **station**: a
track's (`startRadio`), an album's (`startAlbumRadio`: up to five of its tracks spread over it),
an artist's (`startArtistRadio`: up to five of their songs, the most played one per album first;
never an artist id to a track endpoint, which the old artist radio sent to
`getSonicSimilarTracks`), or Your mix (seedless). The queue is the seed songs and the first batch.
Rules, each with a reason:

- **The queue names its station on every publish.** This device republishes its whole queue as a
  `setQueue` on every edit, an autoplay top-up included, and a `setQueue` without `stationId`
  unbinds the session. So `StationManager` records the station BEFORE the queue is loaded, and
  `HubManager.stationIdForPublish` (set by `StationManager`) puts the bound station's id on both
  publish paths. A queue's station is `boundStation`: the record when it is this queue's
  (`StationProvenance.queueId` = `PlayerUiState.savedQueueId`; a new album has another id, which
  is what ends a radio without anything clearing it), else, while this device plays the session,
  `session.stationId` with the cursor from `welcome.station` / the `station` frames (a queue a
  transfer brought here continues its station). The record is persisted
  (`PreferenceManager.stationProvenance`), so a restart keeps a radio a radio.
- **Autoplay is the station's driver, local player only** (`RadioManager.observeAutoplay` over
  `autoplayStep`). Fewer than 2 songs left **in play order** (`MediaPlayerViewModel.playOrder`,
  published by `MediaPlayer.android.kt` from the timeline's shuffle order on the controller's
  thread; null on iOS, which has no shuffle mode) asks `next {after: seq, count: 5}`: a short
  lookahead, so a skip (which the hub reads from this device's reports) steers the next batch
  within a few songs. A queue no station built (an album, a journey) asks `start` with its last
  1–3 songs, `origin: "continue"` and `bindSession`; a 409 `session_bound` continues the session's
  station instead, and `bound: false` continues it from here unbound (its id then stays off the
  publish, and the bind is not tried again). One request at a time; an answer for a queue that has
  since been replaced is dropped. `exhausted` stops and the queue sheet says so (the Explore chip widens it);
  `partial` is asked again at the next change, and at the latest 15 s later; `servedElsewhere`
  takes the cursor only and waits 8 s for the other device's batch (as does a `station` frame that
  moves the cursor while nothing was asked, and a 409), so two devices at the end add one batch; a 404 `station_unknown` starts again once, as the same
  station (`stationRestart`, below).
- **Autoplay is the queue's chips, and Off stops everything (I-041).** Under the queue's header,
  one sideways-scrolling `PageChips` row: Off, Close, Balanced, Explore with the engine (the
  catalogue's words), Off and Auto DJ without it (`AutoplayChips.kt`; Feishin's
  `station-chips.ts` is the twin). The selected chip is a bound station's own state (Off iff it is
  `stopped`, else its character), else the preference. **Off**: autoplay off, `signal stop` to the
  bound station (the hub then answers `next` with no tracks and `stopped`, for every device, and
  says `stopped` in its frames), and the songs that station added that are still to come are taken
  out. **A character**: autoplay on with it; a bound station that is stopped or on another
  character gets `signal character`, its songs still to come are swapped for a fresh batch at once,
  and the top-up decision runs at once, **paused or not** (`AutoplayLook.chipTap`; Feishin's
  B-144). Starting any radio turns autoplay back on.
  **Who edits (S-035).** With `stationEdits` in `welcome.features` and the queue being the
  session's bound station (`chipEditsOnHub`: this device playing it, or controlling the device
  that does), the tap is ONE request, `signal {kind, value?, apply: true}`, and the hub edits the
  queue: it reaches the playing device as `queueChanged` and every other device in the session
  broadcast. Before that each device edited only the songs it had fetched itself, and the playing
  device did nothing about another device's tap (B-143). Otherwise (an unbound station, an older
  hub) this device edits, by the hub's own cut: from the song after the playing one, or from the
  one after that with under 20 s left of the playing song (`chipEditKeepsNext`), when the next may
  already be loading for a gapless start. The next song used to be kept always, so a radio's second
  song outlived every chip (B-145). Which songs are the station's is then `StationProvenance.served`
  (what this device saw it add); a song the user put in is never taken. The edits are in place and
  never a reload: locally `MediaPlayerViewModel.editUpcoming` (media-item removes and adds inside
  `withQueueMutation`, never the playing item); as a controller, `remove` acts highest index first
  and one `enqueue`, never a `setQueue` (a `load` on the playing device). A `queueChanged` is
  applied the same way (`aroundPlayingEdit`): only the run that changed, around the playing item,
  so a cut that keeps the next song leaves its media item alone; its queue's signature is the one
  the publisher already holds, so this device does not republish it. Chip work and the driver's
  top-ups share one lock (`stationOps`). The old now-playing switch and character button are gone,
  and `ControlsRow` is upstream's again.
- **A top-up's songs are in before the driver looks again** (`appendToQueueAndWait`): an append
  still on its way read as "fewer than two left", and a continuation's `start` was followed at once
  by a `next`, ten songs for one top-up (watched on the VM, 2026-10-17). The trigger key also names
  the station, so a restart is a new trigger only once its songs are in. **The driver reads the
  player as it is when it looks**, never the value `combine` handed it: values queued while a step
  ran could predate that step's songs, and after a restart a `start` was still followed by a
  `next` (watched again, after the awaited append).
- **A station the hub lost comes back as itself** (B-139, `stationRestart`). A station another
  device started and this one continued has no seed here (frames carry none; only `welcome.station`
  does). Its 404 used to start a NEW continuation of the queue, named after the playing song and in
  this device's preference: watched on the VM, "Take a Picture radio · Close" became "Desert Island
  Disk radio · Explore" at a top-up, as the phone's became "I Don't Wanna Lose radio · Balanced".
  It is now restarted in its own character, seeded from the queue's last songs under its own name
  (the hub names a station "<seed name> radio", so that name goes back as the seed's; "Your mix"
  as a Your mix seed). Nothing here names a station's character from this device's preference: a
  session station without a frame, and the record a 409 builds, name none until a frame does.
  Another device's station met here while this device plays the session becomes the record at
  once (`adoptedStationRecord`, in the driver's look): a hub that forgets it also stops naming it in
  the session, and the queue would otherwise have no station left at its next top-up (watched:
  the album queue's line vanished after the scratch hub restarted without its stations file).
- **Failures are read as Feishin reads them** (`stationFailure`, `stationFailureAction`). A
  fallback (503, a `{fallback: true}` body, no `station` feature, a probe that said unavailable,
  the hub disconnected, a bare 404) is Auto DJ for that top-up (with autoplay on) and stops asking
  the engine for a minute. A transient failure (a timeout or connection error with the hub socket
  up, any other 5xx, 429, 408) is asked again up to 3 times, 8 s apart, for the same trigger, then
  waits for the next one: never Auto DJ, whose random steps are what stations keep out of a radio,
  and never "unavailable". 400 `bad_seed` / 404 `seed_unknown` wait for the next trigger. An entry
  point that gets no station plays Navidrome's similar songs at once.
- **Without the engine, Auto DJ** (`AutoDj.kt`): upstream Feishin's song strategy, similar songs
  → genre → album artist → library, "only similar", "allow duplicates", 5 per top-up when fewer
  than 1 is left. Radio without it is Navidrome's similar songs as before (`getSimilarSongs2` for
  an album or artist id), and only those. Nothing else tops a queue up with random songs.
- **A star steers the bound station** (`signal like|unlike`, from `SongRepository.starChanges`):
  stars go to Navidrome, which the hub never hears from. A character change is `signal character`.
- **What the queue sheet says comes from the station**, never from the autoplay setting
  (`queueGenerator`, `QueueGeneratorLine`): "Cinnamon Girl radio · Balanced", "· via Navidrome"
  for `engine: tier1`, "Auto DJ", "Nothing left that fits", "… · Off" for a stopped station.
  A queue generated here without a station (`playMix`: a genre mix, any mix or radio without
  the engine, a mood search, a journey) is said by the name it was saved under ("Rock",
  "Cinnamon Girl radio", "Search: rain"), with "· via AudioMuse" only where AudioMuse chose the
  songs: a mood search, a journey, the fingerprint and alchemy lists (`GeneratedQueue`,
  `mixBuiltByAudioMuse`). Navidrome's similar songs and a genre's random draw are credited to
  nobody. The mini player's AudioMuse colour follows the same credit (`wearsAudioMuseColour`).
  It used to say "AudioMuse mix" over every one of them (`QueueGeneratorLineTest`).
- **A custom radio is an `artist` mix of several artists (P5).** New › Custom radio (and the Mixes
  page's +) opens `Screen.CustomRadio`: Pick (the library's artists, most played first by their albums'
  play counts, `ArtistDao.getArtistsMostPlayed`; up to `CUSTOM_RADIO_MAX_ARTISTS` 8), then Tune (Artist
  variety, the character as three dials, the count, Done). The recipe is `kind: "artist"` with every
  artist in `Mix.seedIds` and the first in `seedId` (PROTOCOL §17.1), so an older client plays the first
  artist; `actSaveMix` writes `seedIds` and `variety` only when there are any. It plays as a `tracks`
  station of `seedsPerArtist` (12 shared, one to five each) of each held artist's `artistSeedSongs`,
  dealt in turn (`customRadioPlan`; one artist is exactly `artistRadioPlan`), and without the engine as
  their seeds then their similar songs, dealt in turn. **Variety and the count are drawn only when the
  probe lists them** (`StationCatalogue.varieties`, `.count`; `takesVariety`); `start` sends `variety`
  only then, and a hub without them shows no control and "Count unavailable". The count is per artist
  set and character (`CountKey`), asked 250 ms after a change, never for a variety tap, and an answer
  for any other key is dropped (`reduceCount`). Done is disabled only by `Known(0)` and `Unknown`
  (`canFinish`). Done mints the id (the hub takes a client-minted one), saves and waits up to 6 s for the
  `mixes` broadcast to hold it; **it does not play** (P5 round 2, the owner's note: it was reached from
  New, so starting it surprised): the stack becomes the Library on Mixes with the mix's page on top, and
  `NewSheetHost` shows "Saved under Mixes", whose Undo deletes the mix (its page then leaves). A save the
  hub never confirms stays on Tune with a snackbar, to try again. Leaving the
  flow saves nothing. **The words are Familiar / Blend / Discover** (`StationCharacter.label`, the
  bundled catalogue); a catalogue that still says Close / Balanced / Explore (an older hub, or one stored
  before) reads as the new words in `StationCatalogue.infoFor`; ids, legacy names and ordinals did not
  move. The old new-mix form (`MixFormSheet`, `MixFormKind`) is gone: a Genre mix can no longer be made,
  and an existing one still plays.
- **Preferences.** Autoplay is a Boolean (`autoplayOn`); the four-way `AutoplayMode` it replaces
  was read once as a raw int at "autoplayMode" (0 off, 1–3 on) and removed, behind
  `autoplayMigrated` (`AutoplayPrefMigrationTest`). `StationCharacter` (Close, Balanced, Explore)
  keeps the key "moodCharacter" and the old presets' ordinals, and is **append-only**.
  `BasePreferenceManager.getEnum` reads an ordinal it does not know as the default: it used to
  throw from the delegate while `PreferenceManager` was constructed, i.e. at launch.

---

## 5. Data and sync

- **A parent row with cascading children is upserted, never `REPLACE`d.** `REPLACE` deletes the
  row before inserting it, and the delete cascades: `PlaylistDao.insertPlaylists` used to empty
  every playlist's `PlaylistSongCrossRef` rows on every full sync, until the playlist phase refilled
  them minutes later (or not at all, when the sync failed in between). It is `@Upsert` now.
  **Upstream's copy is `@Insert(REPLACE)`**, so check this line after a merge that touches
  `PlaylistDao.kt`.
- **Don't overwrite a track's artist with its album's.** The library sync used to, which discarded
  the full "A feat. B" credit Navidrome sends and made featured artists invisible app-wide. The
  album artist is a *fallback* for a track that names none.
- **Only a complete AND stable pull prunes Room (B-037).** The album list is offset-paged while
  Navidrome may be editing it: an album removed or renamed behind the walk shifts the next one
  back across the page boundary, unread, and the prune deleted it. `listAllAlbums`
  (`domain/repositories/AlbumListing.kt`), shared by `syncLibrarySongs` and `syncChangedAlbums`,
  overlaps pages 50 on 500 and dedupes by id, and calls the listing `stable` only when
  `getScanStatus` answers before and after with no scan and an unchanged count. An unstable or
  partial pull still upserts, but `LibrarySync.complete` is false and nothing is pruned. A rename
  that moves an album behind the walk mid-scan is still missed by that sync; the bracket is what
  keeps it from being pruned.
- **An artist list that lists nobody does not prune Room (B-069).** `getArtists` is parsed
  leniently (`SessionManager.fetchAlbumArtists`), so a Subsonic error envelope reads as an empty
  list, and `syncArtists` used to prune against it: one failed answer emptied the artist tab until
  the next pull that worked. `artistListMayReplace` (`domain/repositories/ArtistListing.kt`)
  believes an empty answer only when Room holds no artists either (a new server, the first sync).
  Otherwise `DbRepository.replaceArtists`, shared by `syncArtists` and `resyncArtist`, throws: the
  full pull stops at the artist phase and is reported as failed (the log line with its cause, the
  "sync failed" banner, `lastFullSyncTime` not stamped, so the next cycle pulls again), and the
  artist tab's refresh shows its error over the list it had. A library really emptied on the
  server reads the same way and keeps its artists until the account is logged out.
- **A song's artists come from the server; only the LINKS are filtered.** `SongEntity.artists` is
  the OpenSubsonic `artists[]` array, so names and ids are authoritative — which retired the fork's
  old split-on-"feat." heuristic entirely. What survives is `util/SongCredits.kt`:
  `artistCreditsText()` renders off the **original** credit string rather than re-joining the names,
  so "feat." and "&" appear exactly as the tag wrote them (upstream's `appendArtists` re-joins with
  a literal `", "`, which reads as a rewrite of the credit).
  **But an authoritative id is not a page that exists.** This fork syncs only ALBUM artists into
  Room (`DbRepository.fetchAlbumArtists`), so a featured track artist has no row and
  `ArtistDetailScreen` correctly refuses the id — linking every credited name produced a tappable
  name landing on "Something went wrong". `linkableArtists()` filters against
  `ArtistDao.getArtistsByIds` first; unlinked names render as plain text. The old heuristic had
  this property *by accident* (a name resolving to nothing was never linked), it was load-bearing,
  and it was written down nowhere — hence this paragraph.
- **A marquee and per-word tap targets don't mix.** An auto-scrolling line slides the name out from
  under the finger, so `MarqueeText` takes `manualScroll`, used by the now-playing credits line
  only.
- **"Appears on" needs Navidrome's native API.** Subsonic's `getArtist` is album-artist only; the
  native `GET /api/album?artist_id=` is a participation filter that also matches track artists.
  Fail-soft: offline or an old server renders no section rather than an error.
- **lb-bot rows are never filtered on `status == 'missing'`.** A completed fill flips the row to
  `present` while the local library cache still has no album for it, so filtering on `missing`
  makes an album vanish *because* the download succeeded. The discography shelf is built
  **Navidrome-first** for the same family of reason: starting from lb-bot's list would hide albums
  the user owns but whose Navidrome record lb-bot's matcher couldn't claim.
- **The artist page reads lb-bot's discography from a local mirror, not the network.**
  `LbIndexSync` (`domain/manager/`) keeps the `lb_index_*` tables converged with lb-bot's change
  feed — a pull on every hub `welcome`, every `index` frame and every 15-minute sync cycle, one in
  flight, backoff on 503. `ArtistDetailViewModel` reads the artist's mirrored rows and builds the
  typed shelf **before** the page goes to `Success`, so the shelf is in the first frame and the
  legacy carousel is never shown-then-swapped; it then observes the mirror as a Flow. The old
  `ensureAvailability` → `GET /lb/artist/discography` waterfall survives only as the fallback for
  an artist the mirror does not hold yet. `ExternalArtistViewModel`, `ExternalAlbumViewModel` and
  `CollectionDetailViewModel` (the rgid behind an owned album's About) read the same mirror first.
  Library bumps reach the artist page as `LbBotManager.libraryBumps`, filtered to the page's own
  `ndArtistId` / albums / release-groups; the bare `libraryRevision` is for pages with nothing to
  filter on. The discography-scan poll (`awaitArtistScan`) is a 15 s fallback raced against the
  mirror Flow, because the scan record — the only way to learn a scan *failed* — is not index
  state. Only `LbIndexSync` writes the mirror, and only from the feed.
- **lb-bot's other reads are stale-while-revalidate over `lb_response_cache`, through one
  `LbBotManager.cachedGet`.** Meta artist/album and album releases/tracklist (30 d), Fresh and
  "Fans also like" (10 min), the Deezer chart/editorial (1 h) and genres (24 h) return a `Flow`:
  the cached body first, whatever its age, then a request only if it is stale. Key = route +
  sorted non-blank params; a failure or an empty/`found:false` answer never overwrites a body.
  An **ownership-bearing** answer (every Fresh/Discover row, a tracklist asked for WITH presence)
  is also stale once the library changes: `markLibraryStale()` sets a persisted watermark from
  `onLibraryChanged` and from every hub `welcome` (frames missed while the socket was down), and
  such a body fetched at or before it revalidates on its next read. Bodies are stamped with when
  the request LEFT, so one in flight across a landing still counts as stale. Never cached: fill
  and acquisition state (`album/status`, `/lb/fills`, `/lb/gap`, sources), the discography (the
  mirror has it), wishlist, lookups, anything that POSTs. Discover paints its Room rows and every
  cached lb-bot row before the probes, and Discover/Fresh call `revalidate()` on every visit,
  because as root tabs their ViewModels outlive the visit.
- **A library artist's MusicBrainz id is `effectiveArtistMbid`, never `artist.musicBrainzId`
  (I-019).** Navidrome's tag is wrong for a mis-tagged artist and absent for an untagged one, who
  then could not be indexed at all. The user's pick is an **override**, stored by lb-bot and shared
  by every client (`GET /lb/overrides`, `POST /lb/artist/override`, `…/override/remove`); the rules
  are the pure functions in `ArtistMbidOverride.kt` (`ArtistMbidOverrideTest`). `LbBotManager`
  holds the device's copy, `artistOverrides`: restored from `lb_response_cache` at start, re-read
  on every hub advert, every `index` frame and after a write here. A failed read keeps the map,
  and a stale one is harmless — the tag is sent and lb-bot swaps to the override on its own side.
  Reads and writes share `overridesLock`: a refresh in flight must not publish its older answer
  over a write, and the write's re-read must not join that request. A write applies its own
  result to the map before re-reading, because the re-read may fail and the write did not.
  `ArtistDetailViewModel` resolves the id when the artist loads and again whenever the map moves
  (a pick here, or one made on another client): `mbidChanged` re-keys the mirror observer, drops
  the About, re-reads the shelf, and ignores mirror rows still filed under the id it just left —
  the mirror lookup's Navidrome-id fallback finds exactly that row until the next pull.
  **The page never starts a scan after a write.** lb-bot does, and answers its `task_id`, which
  the page waits on like any other scan; a blank `task_id` is lb-bot deciding not to
  (`overrideScanToFollow`) — the picked MBID's discography is already another library artist's
  row, which a scan from this artist would rewrite against the wrong albums, or a remove left
  nothing to resolve — and the page then shows what lb-bot holds under the new id, no spinner.
  **The Rescan button follows the same rule** (`rescanStart`): `POST /lb/artist/discography` can
  answer `{"task_id": "", "covered": true}` and start nothing when another library artist holds
  this artist's discography. A blank id is never followed — it matched no scan record and spun
  until the scan timeout — and the shelf says the discography is shared in one plain line
  (`DiscographyUi.scanShared`), not in the error colour. The map is re-read at once on a hub
  advert and after a write here, but an `index` or a `library` frame only asks for a read **3 s
  out, once per burst** (`requestArtistOverridesRefreshSoon`, decided in one place:
  `frameRereadsOverrideLists`, checked at the top of `HubManager.handleFrame`): a bulk index
  build sends a frame every couple of seconds, and each read is an upstream one. The `library`
  frame is there for the lists that ride the same body (I-022 / I-023, below): it is lb-bot's
  notice that Navidrome has indexed what a retag, a merge or an undo wrote. A write that lb-bot answers 200 for is done only
  when the answer says `ok` (`confirmedWrite`, the listening sync too). The
  surfaces (the artist sheet's "Fix MusicBrainz match"; the shelf's "Choose MusicBrainz artist"
  where there is no id to scan under) show only while `artistOverridesSupported`: the hub
  advertises `POST /lb/artist/override` and lb-bot has not answered 404 for it. A **bare** 404 —
  and a 405 on the POST, since lb-bot's SPA fallback is a GET-only catch-all — is an lb-bot older
  than the hub and hides them; when it answers a tap, a snackbar says so in plain words, never the
  raw status text. A 404 **with a `code`** (`unknown_mbid`,
  `no_override`) and a 502 with `retryable` (MusicBrainz or Navidrome's artist list unavailable,
  on a set and on a remove alike) are lb-bot's own refusals and are shown in its words
  (`overrideWriteError`; the general `failureFor` reads them as "hub too old" and "busy"). A new
  consumer of a library artist's MBID that reaches an lb-bot call or a MusicBrainz link goes
  through `LbBotManager.effectiveArtistMbid(ndId, tag)`; ids that came FROM lb-bot (a Fresh row,
  a lookup hit, a mirrored artist row, the external pages' route key) are already the right ones.
- **The listening history shows lb-bot's numbers and computes none (I-006).** It was a page of its
  own, My Listening, until I-039 made it upstream's Stats page: `StatisticsScreen` hosts
  `ListeningStats` and its tabs wherever lb-bot holds a history (`listeningOnStats`: every phase
  but off and older, which leave Stats its own Navidrome figures), Overview in Stats' layout
  (its cards, the starred-songs card kept, top artists as its ranking rows through `RankingItem`,
  albums and tracks as its carousels). Read "the page" or "My Listening" below as that half of
  Stats. **The library home's Stats card follows the history too** (Q-103, your ruling):
  `ListeningCardViewModel` reads the summary Stats opens on (`LISTENING_CARD_PERIOD`, 30 days)
  through the same cache, and while lb-bot holds a history (`listeningHistoryHeld`: an answer
  with a play in the period or the heat map) the card's three pages are its top artist, album
  and track, their plays and their share of the period's plays. It has no status read (never
  cached, and the home is visited constantly), so "a history" is read off the summary; otherwise
  the card is upstream's, on Navidrome's play counts. lb-bot mirrors the user's
  ListenBrainz listens and serves the aggregates; every figure on `ListeningStats` is a field of
  its answer, and every decision about those answers is a pure function in `Listening.kt`. Three
  reads of three kinds: the **status** (`/lb/listening/status`, never cached) decides what the page
  is at all — `ListeningPhase`: off, importing, ready, the last sync failed, lb-bot unreachable, or
  an lb-bot **older** than the routes (a 404, said in one quiet sentence, not as an error) — and
  is the only thing polled, every 5 s, **only while importing and only while the screen is
  started** (`repeatOnLifecycle`); the **summary** is `cachedGet` (5 min, ownership-stale like
  Fresh), so the last body for a period and zone paints first and offline; the **unowned** list
  and **recent plays** are plain reads. `tz` is the device's IANA zone on every summary request,
  because lb-bot's container runs in UTC.
  - **A summary read mid-import is partial, and its age does not say so.** `importFinished` is the
    cue to re-read the summary and the unowned list when the phase leaves "importing";
    `summaryNeedsRefresh` (the body's `generatedAt` older than the status's `lastSyncAt`) catches
    the same body across a relaunch, when there is no phase change left to see. A current hub
    passes the summary straight through, so that re-read is lb-bot's own answer. An **older**
    hub kept its own 60 s copy, which a sync landing on lb-bot did not flush, and could hand the
    re-read the body it meant to replace: the one more read 65 s later is the fallback for that
    hub only, and is not reached against a current one.
  - **Nothing is not an answer.** A summary with no `period` (`isAnswer`) is never stored or
    painted — `{}` decodes to zeros, and zeros read as an empty history — and a status with no
    `state` reads as unreachable. No cached summary and none from the network is a failure line
    with a Retry, never a blank page and never the empty-history wording.
  - **The gate is strict and remembered.** `LbBotManager.listeningSupported` is true only when the
    advert **lists** `GET /lb/listening/summary` — an empty advert is not "capable" here, unlike
    `advertisesRoute` — and is seeded from `PreferenceManager.lbListeningAdvertised`, the last
    advert's verdict, so the entry exists on a cold offline start and the cached page is reachable.
  - **Rows link out and never download.** `albumTarget` / `artistTarget` take the ids Room holds:
    lb-bot's `ndAlbumId` / `ndArtistId` are authoritative but an id is not a page that exists
    (above). What the library holds opens its page; what it does not opens `Screen.ExternalAlbum` /
    `Screen.ExternalArtist`, where the reviewed acquire flow already lives. **`owned` is "the
    library holds it"** — lb-bot's index, or Navidrome's own album list — wider than Fresh's
    `releaseOwned`, and it can come with no `ndAlbumId` (a filled album still syncing): an owned
    top album with no page here is plain text, never a link to the acquire page. A recent-plays row's
    lazy-list key carries its index (`recentPlayKey`): two identical scrobbles are two rows, and a
    duplicate key is a crash.
  - **Fresh's "Artists you play"** is the rows with `artistPlays > 0`, most played first
    (`freshScoped`). **The chip is offered only when some row has plays**
    (`freshPlayedOffered`). A current lb-bot always sends `artistPlays`, and sends 0 on every
    row while its listens mirror is off or importing; an older one sends no field. Neither is
    "none of the artists you play released anything", so an empty scope is never offered. A
    stored choice of that scope falls back to "Your artists" without being overwritten. The sort
    chips are hidden in that scope, since its order is its own.
  - **A list that could not be read says why** (`listeningListFailure`): lb-bot's own sentence
    for a 503 or a 400, the "older lb-bot" line with no Retry for a 404, and the generic line
    only when nothing was said. The first page of recent plays runs under its own job, not as
    the load's child: the list does not depend on the period, and a period change that
    cancelled the load used to cancel it halfway and leave its spinner on.
  - `listeningSync()` (`POST /lb/listening/sync`) is called by My Listening's own "Sync now"
    (`ListeningViewModel.syncNow`), and by nothing else. A 405 from it is an lb-bot that
    predates the route. The button and the status line moved here from the settings (I-026):
    the button does and reports the listening half alone, is absent where a sync cannot be
    asked for (`listeningSyncOffered`: off, older, nothing heard yet), and **the status is said
    once** — the plain line only while the mirror is simply ready
    (`listeningStatusLineShown`), every other state being the banner's.
  - **The page is four tabs** (I-026; `ListeningTab`, the same four as Feishin's): Overview
    (tiles, rankings, genres), Activity (heat map, plays per day, plays by hour), History
    (recent plays) and Not in library. One period for the page; its picker is drawn where it
    changes something (`listeningTabShowsPeriod`: not on History, and on Activity **below** the
    heat map). **The heat map does not follow the picker**: lb-bot sends the same sixteen weeks
    with every period's summary, and the caption names the range drawn (`heatmapSpan`). **Its
    grid is whole calendar weeks, Monday first** (`heatmapGrid`): it starts on the first Monday
    inside the data — up to six leading days are not drawn; a span that began on a Sunday used
    to leave one lone square — and ends with the current week, whose days not yet reached are
    placeholders (null cells, drawn faintly), so it is always a clean rectangle. Days are
    placed by date, not by list position. **It is drawn from its own state**
    (`ListeningUi.heatmap`, `keptHeatmap`), not from the summary: a period change clears the
    summary, and the map — with the picker under it — must not blink out. A square has a
    largest size (`heatmapCellSize`), so a history of a week or two is a small grid at the
    start of the row. The plays-per-day chart is calendar days with no gaps (`daySeries`) and
    has its own axis of dates (`dayAxisLabels`), never the hours chart's. The plays-per-day chart is capped by lb-bot at the
    newest ninety days of a period, and says so for a year or all time
    (`listeningDailyCapped`). Albums and tracks are horizontally scrollable rows of cards, full
    width; artists are Stats' ranking rows (I-039); **genres are a ranking read down the screen**
    (rank, name, bar, plays — `genreBarFraction`), not a sideways row. **A History row's long
    press opens the app's own menu** — `SongSheet` for the song when it can be told apart,
    else `CollectionSheet` for its album, else `ArtistSheet`, else nothing. A recent play
    carries no Navidrome song id, so the song is looked for among its album's songs when the
    menu is asked for: by recording id, else by title with case and punctuation aside, and
    **only when exactly one fits** (`historyMenu`) — never a menu acting on a guessed song. **Pictures come from ids the rows carry** (`LbArt`):
    the library's own cover or artist image where Room holds the id, the Cover Art Archive's
    front for an album it does not, the placeholder otherwise (`albumCardArt`,
    `unownedCardArt`, `libraryArtistArt`); `ListeningUi.albumArt` / `artistArt` are two Room
    queries for the page, and their keys are still what makes a row a link.
- **The data-management surfaces say only what something said (I-020).** Three surfaces, one
  rule: a sentence is a claim about lb-bot, and it is only made from a fact lb-bot, the hub or
  Room actually reported. The sentences are pure functions in `LbBotData.kt`.
  - **"Library Matches"** (`LibraryMatchesScreen`, `Screen.LibraryMatches`, I-026) is where the
    "lb-bot data" section of the navi-connect settings went: a page of its own, reached from
    the library home's top buttons (a sixth, offered wherever the lb-bot layer is on —
    `libraryMatchesOffered`, i.e. `isConfigured`, **not** `artistOverridesSupported`: that is
    false on a cold start before the hub has spoken and for an lb-bot older than the override
    routes, and the page is where those cases and the copy loaded last are shown; the override
    routes gate its writes only), washed like
    the fork's other pages. Four sections switched with chips, each with its count — To check,
    Artists, Albums, Merged names — opening on To check when it has rows, else on Artists; a
    list lb-bot does not send is no section (`matchesSections`, `matchesSectionToShow`). Rows
    carry the artist's image or the album's cover from ids they already have (`LbArtImage` over
    `CoverArt` / `RemoteCoverArt`; `libraryArtistArt`, `matchAlbumArt`): an album whose id is
    not known yet shows the cover lb-bot was given, one gone from the library the placeholder,
    and a row is a link only when Room holds its id (`heldInLibrary`). The index mirror is a
    line at the foot with its own **Refresh** — the mirror's pull plus a re-read of the page's
    lists, reporting only what the mirror did. The listening history's status and "Sync now"
    are My Listening's. Everything below about what the lists say is unchanged; read "panel"
    as this page. Formerly (`LbBotDataSection`; plain English like its
    host screen): the override list (artist, MBID, "Navidrome said: …", Remove), the albums
    lb-bot retagged and the artist names it merged, each with Undo (I-022 / I-023, below), the
    index mirror's numbers, the listening status in words, and one "Sync now". `panelData` decides what
    a list is — reading (only while a request is in flight), unreachable with nothing loaded,
    the copy loaded last, or current — from `LbBotManager.artistOverridesLoaded` /
    `artistOverridesReadFailed`; an empty map alone cannot tell "no overrides" from "never read".
    "Reading" is a COUNT of the panel's reads that are out, plus "due" until the first one has
    started (`panelReading`): the panel opening and "Sync now" each read, and a single flag was
    cleared by whichever came back first.
    **"This hub is older" and "does not offer" are only said of a hub that answered**
    (`LbBotManager.hubHeard`: an advert, or an answer to `/lb/status`, in this process). Until
    then `artistOverridesSupported` is false and `listeningSupported` is a verdict remembered
    from an earlier run, and neither is this hub refusing anything — so on a cold start with the
    hub out of reach the panel asks it once (`hearHub`), says "Asking lb-bot…" while it does, and
    then "not reachable" (or shows the cached copy as the last one loaded), never "older".
    The section reads when it appears and again whenever the layer under it changes
    (`LaunchedEffect(routeSignature, hubSaysNoLbBot)`), **not from the view model's `init`**: the
    hub is set up on that very screen, and a view model created before that read nothing and
    then sat on "Asking lb-bot…" with nothing asking. "Save & reconnect" is a third key
    (`saves`): the settings are plain preferences, so a save changes no Compose state, and with
    the hub out of reach nothing else on the screen moved — the section went on saying "Set the
    hub up above" for a hub just set up. With no layer at all it says which half is
    missing (`lbLayerLine`): no hub, or a hub that is set up and says it has no lb-bot.
  - **"Sync now" reports what happened, not what a timeout suggests.** The mirror's line is
    `LbIndexSync.lastRun`: running, done on which cursor and how many artists changed, skipped
    and why, failed and with what. **It is a record, never an input** — nothing in `LbIndexSync`
    reads it, and syncing, retrying and backing off are exactly as they were; the notes are
    assignments beside the returns they describe, **with no suspension point of their own**
    (the cursor is the last applied page's `newCursor`, not a read after the pass). "Up to
    date" is only said of a pull that finished and changed nothing — and artists a drift
    repair deleted count as a change, though no page mentioned them.
    **The last run is a verdict on an earlier pull, so after a tap it is not shown as the
    tap's answer.** The panel notes `LbIndexRun.serial` at the tap; until a pass newer than
    that has started the line says the pull has been asked for, and after a few seconds with
    nothing running, that none has started (`indexRunLineAfterTap`). A pass already running
    at the tap is said as running. The button is disabled, and says "Syncing…", while the
    sync it started is at work.
    The listening half reports the sync request's own answer and then the status it leads to
    (`listeningSyncOutcome`): the request returns at once, so "synced" needs `lastSyncAt` to
    have moved **past a baseline read immediately before the request** — never past whatever
    the panel last showed, which would credit a background sync that finished in between to
    the tap. With no baseline (that read failed) nothing is called finished, or failed; the
    first status read since stands in for the reads that follow (`listeningSyncBaseline`). An
    `error` that was already there is not this sync's, and a 405 is "this lb-bot has no
    listening history yet", a fact.
  - **A write made from the panel follows lb-bot's scan decision** (`overrideWriteLine` over
    `overrideScanToFollow`), exactly as the artist page does. Nothing there starts a scan. A
    set and a remove are worded apart: a remove that failed is "Not removed", not "Not saved".
  - **"Refresh About"** (the artist and album sheets) is `refreshArtistMeta` / `refreshAlbumMeta`:
    one request with `refresh=1`, which lb-bot and the hub both step past their cached copy for.
    **A refresh never destroys a good About** (`aboutRefreshOutcome`, weighed against what the
    page shows). Only an answer with content (`found`) replaces what is shown, and only such
    an answer is stored (`aboutRefreshStored`) — a sparse `found: false`, written under the
    ordinary key, replaced a good About for the cache's whole month, and lb-bot has answered
    `found: false` for a MusicBrainz or Wikipedia blip as readily as for "nothing written".
    Over an About with content, `found: false` says "lb-bot found nothing this time. What is
    shown is unchanged."; with nothing on screen it is shown as "nothing written". An answer
    equal to what is shown says so, not "refreshed". A failure says which it was and leaves
    the About alone: "could not reach lb-bot" (the hub's own 502 / 504 included), an error
    with its status and lb-bot's text (a 502 `retryable` is lb-bot's, kept by
    `metaRefreshError` where the general mapping drops it), or "returned nothing" for a 200
    that is not an About (`aboutBodyOrNull`). It is deliberately not `cachedGet` with
    `REFRESH`, which can say none of that.
  - **The pending-actions browser** (`Screen.Settings.PendingActions`, opened from Data &
    Storage's row) is `SyncActionDao.observePendingActions()`: each queued action in words, its
    item's title out of Room or else its id, and when it was queued. A per-row remove and
    `SyncManager.processQueue` do not disturb each other — the drain reads the queue once and
    deletes each row by id after sending it — but an action the drain has already read may
    still be sent in the moment after it is removed, and the screen says so in a line, since a
    row vanishing reads as "cancelled". A remove that could not be written says that too. The
    queue re-emits on every row a drain deletes, so a title is looked up once per id
    (`pendingTitlesToLookUp`), not once per emission.
- **"Matches to check" ride the overrides body (I-021).** lb-bot lists the library artists
  whose MusicBrainz identity is worth a look — no tag in Navidrome, and its own name-search guess
  matched none of their albums (`unmatched`) or found nobody (`unresolved`) — as `toCheck` on
  `GET /lb/overrides`, so it is restored, re-read and persisted with the override map
  (`LbBotManager.matchesToCheck`) and costs no request. **Absent is not empty**: the field is
  nullable, an lb-bot that predates it sends none and the panel shows no section; an empty list
  shows "Nothing to check" (`matchesSectionShown`, `MatchesToCheck.kt`, `MatchesToCheckTest`).
  `matchesToShow` also drops an artist the map already has an override for, so a set takes the
  row out at once. The copy says "worth a look, not necessarily wrong" — an untagged artist with
  a non-Latin name is often listed although the guess is right. Every row says how many albums
  the artist has (`matchDetail`): inside the sentence for a guess, after "No MusicBrainz artist
  found" for none — and a count of 0 is a field that did not arrive, never "0 albums". "Keep this
  match" is an ordinary set of the guessed MBID; "Choose…" is the artist page's own picker,
  opened with no id in force.
- **"Fix album match" retags an owned album to a MusicBrainz release, and lb-bot holds all of
  it (I-022).** The user picks which release an album is; lb-bot rewrites the files' tags (release
  id, disc and track numbers, titles, date — tags only), keeps the previous ones for an undo, and
  can save a Cover Art Archive image as the cover. Six routes under `/lb/album/` (`override` GET
  and POST, `override/preview`, `override/remove`, `covers`, `cover`). **Nothing is stored on the
  device**: `AlbumMatchSheet` reads the album's state when it opens, a job in flight is followed in
  memory, and the retagged album comes back through the Room sync.
  - **Every rule is a pure function in `AlbumOverride.kt`** (`AlbumOverrideTest`,
    `AlbumOverridePollTest`, `AlbumJobTest`), and the sheet only asks and draws. Each route's answer is read by a
    function of the HTTP status and the body text (`albumStateAnswer`, `albumPreviewAnswer`,
    `albumApplyAnswer`, …), through `LbBotManager.rawGet` / `rawPost`, so a refusal keeps its
    `code`: `LbError.Rejected.code` is filled on these routes and "" on every other. A bare 404 or a
    405 is an lb-bot older than the hub (`RouteUnknown`: `albumOverridesSupported` goes false and
    the entry points hide until the next advert; the sheet says so in one muted sentence); a 404
    **with** a code, a 409 `busy` / `in_progress` and a 502 with `retryable` are lb-bot's own
    answers and are shown in its words. A **409 `poor_match` with both numbers is not a failure but
    a question** (`LbAlbumApply.PoorMatch`): the sheet names both and resends with `force`. A 200
    that is not the promised body is never read as one — `{}` would decode to "untagged, no
    tracks", "0 files change" or "started".
  - **The job is followed by the override row's id, and it is the manager's, not the sheet's.**
    An apply and a remove answer at once and work in the background, and **the retag changes the
    album's Navidrome id**. `LbBotManager.followAlbumJob` polls `GET /lb/album/override?id=`
    every 2 s on the poll routes' 12 s timeout, in its own scope, and publishes the job in
    `albumJobs` (by row id; in memory): closing the sheet stops nothing. `albumPollStep` ends
    it — `applied` / `failed` for an apply, the row being gone (404 `no_override`) for a remove,
    a row standing again after it was seen `removing` is a remove that did not finish, and a
    failed poll is not an answer. **"Seen `removing`" starts from the POST's own `state`**
    (`albumPollStart`'s `postState`; the row's state when a running job is picked up): lb-bot
    flips the row before it answers and can reach `failed` before the first poll, and that
    first `failed` would otherwise read as the state the job started from, for ever. **The
    give-up clock measures silence, not length** (`lbJobStep`): three minutes without the row's
    `state` or `done` moving, restarted by every movement, so a long job that advances is never
    given up on; the first answer is not a movement. A job that timed out is not settled later —
    the sheet says lb-bot is still working and that Library Matches shows the result.
    A job found running when the sheet opens is followed again (or joined), and a sheet opened
    on an id that has since died finds its job by the album id it was started for. **A second apply
    or remove for an album whose job is still followed is refused in the manager, unsent**
    (`albumJobInWay`). The three writes run in the manager's scope too.
  - **The wait for Navidrome reads as a wait.** lb-bot holds a row `applying` / `removing`
    (`merging` / `undoing`) after its last write until Navidrome has scanned, up to a minute,
    and **`done` counts successes only**. So "the writes are over" is one helper for every
    surface — `lbWritesOver`: `total > 0 && done + failures >= total` while the state is still
    an active one (`albumWaitingForIndex`, `mergeWaitingForIndex`) — and the sheet, the merge
    block and both panel rows then say the tags are written and lb-bot is waiting, with no
    "N of N" and no determinate bar. `done >= total` is the wrong test: one failed file keeps
    it below for the whole minute.
  - **lb-bot's reasons are on screen wherever Undo is.** A `failures` entry is a file — or the
    cover: "Cover image" is one too — so results count *problems* apart from files ("wrote 12
    of 12 files", then "1 problem:" above the list; no matching on the title). The reasons are
    listed for an apply that landed with some or did not finish **and for a remove that did
    not finish** (`albumJobFailures`; the standing row block; the panel's `albumRowProblems`):
    lb-bot's undo is two presses when a file has gone missing, and only its reason says "undo
    again to restore it if it is back, or to give up on it". A `failed` row does not say
    whether an apply or an undo left it so; the panel's sentence is true of both.
  - **When a job ends, three things follow from the job alone — never from whether a sheet is
    open** (`AlbumJobTest`). (1) **The library is settled first** (`settleAlbumJob`): the album
    is pulled under its new id (`syncAlbumsById`; `CollectionDetail` loads from Room and
    refuses an id it does not hold), and only then is the job published as over
    (`AlbumJobState.after`). (2) **The dead id is forgotten** (`albumDeadId`): only where the
    contract says the id changed — `applied` with a non-empty, different `albumId`, or the
    finished undo of an override that had changed the release — and only as an empty shell.
    `DbRepository.forgetAlbumIfEmpty` deletes the album row when no song is filed under it any
    more, at once or after a later sync (a few minutes), because that is what tells the cases
    apart: the retag kept the songs' ids and the pull moved them over (remove it), files
    lb-bot left untouched still make the old id an album on the server (keep it), or the songs
    got new ids and the rows left behind are what a completed download is keyed by (keep it;
    the full pull prunes it as before). The check and the delete are ONE statement
    (`AlbumDao.deleteAlbumIfEmpty`), so a sync that files a song under the id in between cannot
    lose the album. An undo started from the job block passes the ROW's `albumId` as the id it
    kills — "" on a `failed` row, and then nothing is forgotten — never the id the sheet was
    opened on, which is the one the undo brings the album back to. (3) **A page showing the dead album leaves it**
    (`albumLeave`, decided on ids: the id the job was started for against the id the page
    shows): `CollectionDetailScreen` observes `albumJobs` and replaces its own back-stack entry
    with the album's new page, else the artist's, and re-reads in place when the id survived
    (the undo of a cover-only change included). A page that was open as the job ended
    leaves whatever Room still holds — "open" is when the page was OPENED, kept with its
    back-stack entry (`rememberSaveable`), because a page that sat under another one is not
    composed while the job ends and a plain `remember` made it "opened later" on return; one
    really opened afterwards leaves only once Room has dropped
    its id (`CollectionDetailViewModel.missing`) — a queue still naming the dead id is followed
    to the album, while an old id Room still holds is an album in its own right and opens.
    Nothing else moves. `CollectionDetailViewModel.refreshCollection` catches the repository's
    throw for an id Room no longer holds, which used to take the app down.
  - **The sheet lives above the pages.** `AlbumMatchHost()` is composed once in `App.kt` on
    `LbBotManager.albumMatch`, because the page of a retagged album is replaced when lb-bot
    finishes and a sheet composed inside it would take what lb-bot wrote with it. The host also
    says, in a snackbar, what came of a job that ends with no sheet open on it — an album job,
    and an artist name merge (I-023) no block is showing. **That one line
    in `App.kt` is fork code a merge can drop silently**: both entry points would then open
    nothing. A `poor_match` confirmation is tied to the release it was answered for
    (`forceAllowed`). A download is a `DownloadEntity` keyed by **song** id in the other
    database, which no sync prunes: if Navidrome gave the retagged songs new ids, the files
    stay on disk under the old ones and the album under its new id reads as not downloaded.
  - **Entry points**, both gated on `albumOverridesSupported` and `isConfigured`: the "Fix album
    match" row of `CollectionSheet` (passed by the album page's top bar and by the artist page's
    album sheet — the other long-press sheets do not host it) and "Wrong edition?" in
    the album page's gaps box (`GapBox`). With no `rgid` from lb-bot the release group is found the way Discover
    resolves a browse row (`albumLookupQuery` fielded, `releaseGroupFor` validated, declining is
    fine) and otherwise only a pasted release id is offered. Covers for a release that is not
    one of the listed editions — a pasted id — are asked by `release_mbid` alone
    (`albumCoversRgid`): it may be another group's, and lb-bot derives the group itself. **The
    cover read is keyed on what is asked** (`albumCoversQuery`): with nothing picked, the covers
    of the release the album is on, as soon as the album and its group are known — so the
    picker is there when the sheet opens, with "Set cover" for a cover chosen without touching
    the edition; with a release picked, that release's covers once its preview has landed. It
    used to be gated on a preview, which only a pick makes. One question, one read: a picked
    release waits for the edition list to settle (whether it is listed is part of its
    question), and coming back to a question already answered keeps its answer
    (`albumCoversReadNeeded`). A 409
    `files_unreachable` (lb-bot cannot see the album's real files) carries a `total` like
    `poor_match` does and is never read as that question: it is shown in lb-bot's words, which
    name the remedy, on preview, apply, cover, remove and both merge writes. Every sentence states what lb-bot
    answered; nothing says whether a favourite, a rating or a play count survives a retag.
    Feishin's `album-override.ts` is the hand-mirrored twin.
- **"Merge into one name" joins two spellings of one artist, and lb-bot holds all of it
  (I-023).** Navidrome keys artists by NAME, so "Belle & Sebastian" and "Belle and Sebastian"
  are two artists even with one MusicBrainz id. lb-bot retags one spelling's files to the other's
  name and keeps the previous tags for an undo. Four routes under `/lb/artist/merge` (`preview`,
  the POST, the GET by `id`, `undo`), gated on `artistMergesSupported`
  (`advertisesRoute("POST /lb/artist/merge")`; a bare 404 / a 405 from any of them **hides the
  block** until the next advert — no "older lb-bot" line: a control inside a page hides, and the
  preview that finds out fires without a tap). The rules are pure functions in `ArtistMerge.kt`
  (`ArtistMergeTest`); the answers are read exactly as the album routes' are (`albumBody`,
  `albumRefused`).
  - **`GET /lb/overrides` carries three more lists, and absent is not empty.** `albums` (every
    album override), `merges` (every merge that can still be undone) and `siblings` (groups of
    library artists whose effective MBID is equal) are each **null** in `LbOverrides` when lb-bot
    left the field out — an older one, or one that could not work it out — and `[]` when there
    is nothing. `LbBotManager.albumOverrideRows` / `artistMerges` / `artistSiblings` keep the
    difference, and so does the persisted copy: `lb_response_cache` stores the response text
    itself, decoded again at the next start. **All three StateFlows are declared above the
    manager's `init` block**, whose restore coroutine writes them; one declared below it can
    still be null when that coroutine runs.
  - **`nd_id` is the entry whose name is KEPT, `from_nd_id` the spelling that is rewritten**
    (`planMerge`): keeping the other spelling's name swaps the pair, and it is then the artist
    the sheet is open on whose files are rewritten. The block (`ArtistMergeBlock`, in
    `MbidMatchSheet` for a library artist) offers "Merge with" only when the group has three or
    more spellings, defaults to keeping the one with the most albums (`mergeDefaultKeep`), and
    **merges only what lb-bot has counted**: the button is enabled by the `preview` answer for
    exactly that pair, whose `into.name` is the name shown as kept.
  - **A collaboration credit is not a spelling.** A `siblings` entry may carry `collab`
    ("JPEGMAFIA × Danny Brown" beside "JPEGMAFIA"; absent = false). `mergeOthers` never offers
    one, and offers nothing when the artist's OWN entry is one — so a group that is an artist
    plus its credits (61 of the live library's 114 groups, when measured) shows no block,
    where lb-bot would only refuse with 409 `collaboration`.
  - **The job is the manager's, followed by the one rule the album job uses.** `LbJobPoll.kt`
    holds it (`lbJobStep` over an `LbJobSpec`: which state is "working", which ends it well or
    badly, whether the row being gone is the finish); `AlbumOverride.kt` and `ArtistMerge.kt`
    supply the vocabulary. `followMergeJob` polls `GET /lb/artist/merge?id=` every 2 s, until
    the row has not moved for 3 minutes, in the manager's scope and publishes `mergeJobs`; a
    merge ends on `merged` /
    `failed`, an undo on 404 `no_merge`. A second merge or undo touching either artist while one
    is followed is refused there, unsent (`mergeJobInWay`). The POST's own `state` and the
    silence clock are the album job's (above). A merge lb-bot is already working on is picked
    up by either artist's block (`mergeToPickUp`: the row names both ids; an lb-bot older than
    `from.ndArtistId` names the rewritten spelling by name alone, and then only the kept
    artist's block can). Not picked up: a row still in `mergeJobs` — a poll that gave up is not
    restarted while its "still working" is on screen, though once dismissed it may be — and a
    merge this device already followed **to rest** (`mergesFinished`, by `id@setAt`:
    `mergeFinishedKey`), never again in the process. The list can go on saying `merging` for a
    merge that has ended, and picking that up showed a finished merge twice.
  - **The result must survive its own cause** (`mergeBlockView`). A merge that worked leaves one
    spelling, and `siblings` only lists groups of two or more — the group that offered the merge
    disappears *because it worked*. So while a job concerns the artist the block shows the job
    (progress, outcome, lb-bot's reasons, Undo), with the names taken from the job — lb-bot's
    row once it has answered, the names it was started with until then — never from `siblings`.
    Undo is offered for a merge at rest **and for an undo that did not finish**
    (`mergeJobCanUndo`), with its reasons listed (`mergeJobFailures`): lb-bot expects that one
    pressed again.
    **An ended job stays in `mergeJobs` until the user taps "Done"** (`dismissMergeJob`); it is
    in memory only.
  - **When a job ends, the library is settled first, then pages move** (as for an album).
    `settleMergeJob` → `DbRepository.resyncArtist`: the albums Room files under the artist whose
    files were rewritten (`mergeResyncArtistId`: the rewritten spelling after a merge, the kept
    artist after an undo) are read again, and so is the artist list. **The dead artist row is
    not a "forget when empty" guess**: the artist list is read by the full pull and the artist
    tab's refresh only, so a merged-away spelling would stay listed for up to an hour — but a
    merge that could not write every file leaves the old spelling a real artist, and only the
    server knows. So it is one `getArtists` and the same prune the full pull does, **which drops
    nobody when the answer lists nobody** (`fetchAlbumArtists` reads a Subsonic error envelope as
    an empty list; the guard is `artistListMayReplace`, the full pull's too since B-069). Still
    listed after a merge that landed (Navidrome mid-scan): one more look 45 s later, then it is
    the full pull's. Albums Navidrome re-keyed in the process (no MusicBrainz album id) are not
    named by lb-bot's row and stay listed under their old id until that pull. A blank id from
    `mergeResyncArtistId` is not null: a merge picked up from the row of an lb-bot older than
    `from.ndArtistId` has no id for the rewritten spelling, and reads the artist list alone.
    **A merge that finished on another device is settled too**, from the list: after every
    successful read of the overrides body, each `merged` row that names `from.ndArtistId`, that
    this device neither follows nor followed to rest (`mergesToResync`) and whose artist Room
    still holds is re-read with the same `resyncArtist`, once per `id@setAt` per process
    (`LbBotManager.resyncMergedElsewhere`). Without it the merged-away spelling stayed listed
    here until the next full pull: a `library` frame re-reads albums, never the artist list.
    Then `ArtistDetailScreen` observes `mergeJobs`: a page showing the rewritten spelling when
    a merge LANDS replaces its own back-stack entry with the kept artist's (`mergeLeave`, on
    ids; an undo moves nobody). A page OPENED after the ending is left alone — the old spelling
    can still be an artist — and "opened" is kept with the back-stack entry (`rememberSaveable`),
    so a page that sat under another one while the merge ran still leaves when it is next shown.
  - **Who says how it ended** (`mergeAnnounced`): the block, when one that will still be there
    is on screen (`LbBotManager.mergeBlocksOn`); otherwise `AlbumMatchHost` in a snackbar. A
    block on the rewritten spelling's page does not count once the merge has landed — that page
    is about to be replaced, and the sheet with it.
  - **The data panel lists both, each with Undo** (`OverrideListsTest`). A section exists only
    when lb-bot sent its list, something was loaded, and the hub carries the route its Undo goes
    through (`overrideListShown`); an empty list says there are none. A row's sentence is made of
    the row's own numbers and a state this build has never heard of is not put into words
    (`albumRowDetail`, `mergeRowDetail`); under it, for a row at rest, lb-bot's reasons one per
    line (`albumRowProblems`, `mergeRowProblems`, the first ten and a count of the rest). Undo goes through the same manager calls as the sheets,
    so the job is followed and the library settled the same way; the line under the lists is a
    function of that job (`undoLine`) — "lb-bot is putting … back" only while it is followed,
    then how it ended, and nothing once nobody follows it.
    Feishin's `artist-merge.ts` is the hand-mirrored twin.
- **A failed lb-bot poll is not an answer.** Collapsing it into the default `unknown` state makes
  it indistinguishable from "nothing is filling this". A failed poll goes through `noteError` — the
  row keeps its state and the Downloads page says "Can't reach lb-bot — last checked Ns ago" after
  two missed ticks; a real `unknown` is bounded patience on the clock (30 s grace after the tap, two
  minutes continuous before `gave_up`). The poll is one `GET /lb/fills` for every due row, 5 s
  backing off to 10/20 s while the payload is unchanged, and 30 s while the hub socket is up and
  `fill` frames are arriving (`HubManager` sets `hubConnected`). Expiry is a settle measured from
  the row's last progress, never a delete. A terminal status stays in `_fills` so a sheet shows the
  outcome and re-enables its picker; the map used to keep the last live state forever.
- **A `/lb/fills` gap summary carries no source rows.** It is the gap view with `sources`
  dropped (only `sourcesTotal` / `sourcesFoundAt`), and `applyGapSummary` used to publish it
  whole — every poll tick blanked the gap picker (then `GapFillSheet`, now `GapBox`), which then read `picking` with nothing
  to pick and told the user lb-bot needed a decision. It keeps the rows it holds while
  `sourcesFoundAt` is unchanged, and re-reads the full `/lb/gap` once when a summary reports
  sources it has never read. A sourceless `picking` is three cases (PROTOCOL §15): tracks reading
  `downloaded` await a manual match in lb-bot's workspace (the box's `Attention` says so and offers "Open in
  lb-bot"), a `noSourceReason` is shown as lb-bot wrote it, and anything else says "search again"; and
  "Open in lb-bot" opens `<webUrl>/#/gaps/<groupId>` when the hub advertises `webUrl`.
- **A multi-disc gap numbers its rows by disc (B-071).** lb-bot's gap rows (`tracks[]` of
  `GET /lb/gap`, `LbGapTrack`) carry `disc` since 2026-10-10 — an integer, 0 for "not known", no
  field at all from an older lb-bot — because `position` starts again on every disc and a
  two-disc gap read 1, 2, 1, 2. `gapShowsDisc` is true when some row has `disc > 1`; then
  `gapPositionLabel` writes a row with `disc >= 1` as `<disc>-<position>` ("2-1", no padding)
  and leaves a `disc == 0` row its bare position. Otherwise every row is as it was. Rows are
  never re-sorted. Both live in `FillVocabulary.kt` (`GapPositionLabelTest`) and are Feishin's
  rule too, mirrored by hand. `MissingTracks` in `GapBox.kt` is the only place a gap row's
  number is printed; its column is 40 dp instead of 28 when discs show ("10-12" measures 27 dp
  in the bundled font). The canonical tracklist of `ExternalAlbumScreen` is another shape
  (`LbTrack`, no `disc`) and prints the bare position.
- **A whole-album download asks a folder for the release's tracks; "Download all M files" is
  the explicit other (B-084).** lb-bot says, on each source row of `GET /lb/album/sources`, how
  many of the folder's seen audio files a plain download would leave out (`extraFiles`).
  **Absent is not 0**: an unknown tracklist, an older lb-bot and every gap-fill row send none,
  and the row is then exactly what it was. With a count, `SourceCards` (the album page's request
  box, P4) adds one quiet line and a second, muted text button to the CHOSEN card ("Request this
  album" is the box's own button under the cards) (`sourceExtras`, `AlbumDownloadAll.kt`, `AlbumDownloadAllTest`); nothing is worked out
  on the device from `files` or `fileCount` but M in that button's label. The second action is
  the same request plus `allFiles: true` — sent only when asked and only with a picked peer
  (`albumDownloadRequest`; lb-bot answers 400 without one). **The answer's echo decides what
  is said, never the tap** (`allFilesNote`): an older hub drops the key without a word, so an
  echo that is false or absent reads "the album's tracks only", an answer that started nothing
  says nothing, and `existing` keeps the box's "Already downloading" alone. The line sits
  with the request's progress in the box (its Active and Done stages). Retry,
  "Try another source" and the one-tap acquire never send `allFiles`: it is consent for one
  folder, once. The fill view's `leftOut` (files of the current source's folder that were not
  asked for) is said where a status line already existed and nowhere new — the Download
  Center's row and the request box (`describeFill`'s sublines), while the album downloads and once it is in
  (`leftOutPart`, `fillDetailLine`, `FillLeftOutTest`); it rides the persisted watch, so a
  finished row keeps it. **On the fill view, absent is "this view does not say", not 0**
  (`LbFillStatus.leftOut` is nullable): the hub's `fill` frame is rebuilt field by field from
  a list with no `leftOut`, and one arrives on every step of a download while the poll is
  backed off to 30 s — read as 0 it would take the count off the line between polls. The one
  writer keeps the last said value across a silent view (`fillKeepingLeftOut`), except across
  a new search or a view naming another peer, where nothing is shown until a full view says.
  Only the album picker reads `extraFiles`: the gaps box's cards have no "Download all". Feishin's
  `album-download.ts` is the hand-mirrored twin.
- **A cancel is `cancelled`, not a retryable failure.** `canRetry` is false on a cancelled row —
  a cancel is restarted from the album page — and Cancel is offered from the server's own
  `cancellable` field, never on `placing`/`placed`. `cancelFill` answers "too late" when lb-bot
  says placement has begun, and applies lb-bot's status instead of settling the row as cancelled.
- **Android backup excludes the preferences file** — the one holding the Navidrome password, hub
  token and AudioMuse token. A restored install asks for the server details again, on purpose.
- **Release builds no longer trust user-installed CAs** (they moved to `debug-overrides`, since the
  base config applied them to every HTTPS destination the app touches). Plain-HTTP LAN servers are
  unaffected; a self-signed HTTPS server needs its certificate in the system store.

- **Search finds through typos on both halves, and ownership is by MBID (P3).** Navidrome's `search3` matches
  whole words and prefixes and Room's fallback is a `LIKE`, so "radioheed" found nothing. `LibraryFuzzyIndex`
  scores every artist, album and song name in Room locally (`FuzzyMatch.kt`: token-based, Damerau; tokens of one
  to three letters match exactly, four to six allow one edit, seven or more two, two edits rank below one; the
  last word may be unfinished; non-Latin words match as substrings only). Albums and songs are indexed as "name
  artist", so an artist's name finds their records as `search3` does. Built lazily on the first search and
  rebuilt when (artist, album, song counts, `lastFullSyncTime`) moves. `SearchRepository.search` runs it beside
  `search3` and merges exact first (`mergeHits`), keeping `search3`'s own order (Room's `IN (…)` does not).
  Measured 2026-10-20 on the owner's library through the VM AVD (debug): 22,427 names, built in 0.6–0.7 s,
  30–115 ms a search (256 ms cold); on the JVM 11 MB and 7 ms. **Offline it searches downloaded songs only**
  (`offlineHits`), never the cached library, and says so. Android Auto's search keeps the old flat
  `search(query): List<Any>` (upstream's caller).
  The unowned half is lb-bot's MusicBrainz lookups with MusicBrainz's own fuzzy terms, still ONE artist and ONE
  album request per settled query, one after the other: `mbFuzzyQuery` puts `~1` on words of four or more Latin
  letters (never `~`: "boy~ genuis~" put Bob Dylan first). **Albums send `(typed) OR (fuzzy)`** (`mbAlbumQuery`):
  measured live, fuzzy terms alone pushed exact titles down ("in rainbows" #1→#2, "blue weekend" #1→#5) while
  rescuing typos; with the typed words beside them the exact title stays first. A MusicBrainz artist the library
  holds is told **by MBID** (override, then tag, then the index mirror's row; `ownedArtistsByMbid`), never by
  name, and is drawn in the library section only when its NAME matches what was typed (`ownedArtistJoinsLibrary`):
  MusicBrainz answers a multi-word query with whoever shares a word, and "hail to the theif" put Various Artists
  and The Beatles at the top of "In your library". Cost: an artist found only by an alias is not promoted.
  A lookup is `Lookup` (Idle, Looking, Done, Failed) and `searchPhase` never reads Looking or Failed as "nothing":
  "No results" waits for every source; a failure is the "Couldn't reach lb-bot" pill, which retries once.

---

## 6. The colour engine

Every browsing and detail page is washed in the current artwork's colours. This is the fork's
largest UI divergence and the thing most likely to be broken by an upstream merge.

**The pipeline.** A cover is reduced to three colours, `CoverSeeds` (`dominant`, `vivid`, `accent`),
by the pure `deriveCoverSeeds` (`CoverSeeds.kt`), and those are **persisted** in `CoverSeedStore` (the
`coverSeeds` preference, LRU 512, written at most every 2 s). `ui/util/CoverColorScheme.kt`'s
`rememberCoverColorScheme` reads the store **in composition**, so a cover seen before is coloured in
the first frame; only a miss fetches (`fetchCoverSeeds`), and the store is also filled ahead of need
by `CoverSeedPrefetcher` — every cover a `CoverArt` tile shows, and the next three songs
(`ColourArrivalEffects`). From the seeds it derives a `CoverColors` (`scheme`, `seed`, `dominant`,
`isDark`, `resolved`, `themed`). `ui/components/common/CoverAmbientBackground.kt`'s
`BrowsingAmbient` / `Washed` draws it: blurred artwork plus a `coverAmbientGradient` scrim, with the
seed read in the *draw* phase, all of the wash's numbers in `WashTuning` (dark pages: two stops at
0.82 / 0.92 over a black 0.4 artwork scrim, as ever; light pages: a white 0.43 scrim lifting the
artwork, a three-stop gradient whose top stays in the cover's own field tone and whose bottom is one
fixed lightness step deeper, neither below luminance 0.5 — `WashTuningTest` holds its contrast over
the darkest artwork, on the seeds a light page can actually get). The library home, every root tab behind `App.kt`'s
`Washed`, and both detail screens go through that one path — not four copies.

Upstream's `rememberColorSchemeFromCoverArt` / `rememberDominantColorState` is the engine this
replaced. It dies with the composition (the fork carries the persisted seed store above plus a
derived-scheme cache, added because covers popped back to neutral while scrolling and
`RootBottomBar` is rebuilt inside every `Scaffold`) and it seeds from `dominantSwatch`, which
reliably picks a flat minority patch.

**The accent on a light cover is not the vivid swatch.** `vivid` (what the wash is pulled toward)
is the most populous colourful family, and on a light cover that is usually the pale field itself:
Mahjong Room's accent was its own pale blue, with the red keyboard nowhere on the page. So on a light
cover `accent` is the family with the most *salience* (population times chroma squared), chosen
from a second palette built without kmpalette's default skin-tone filter, which drops every hue
10 to 37 degrees with saturation up to 0.82 and so took Lookaftering's brown hare out entirely. A
dark cover's `accent` is its `vivid`, unchanged: there the dark field already fails the colourful
bounds. Page brightness and the wash still come from the default palette alone.

Nine rules, each of which is a bug that shipped:

1. **An unresolved cover is an absence, not a colour.** The derivation used to run whether or not a
   palette had landed: with none, the dominant fell back to `colorScheme.surface`, the accent seed
   became that near-white surface at 1.6× saturation under `PaletteStyle.Content`, and the theme's
   own faint cast was amplified into a real accent — a dusty rose home page while a navy sleeve
   played. `CoverColors.resolved` exists for this; gate on it, **never** on `coverArtId != null`. A
   cover id says a song is playing, not that its colours are known. (`themed` is the same idea for
   callers that paint their own surface from `seed` and never read `scheme`.)
2. **The palette fetch is its own path and fails silently.** It does not go through Coil — it is a
   bare Ktor client with its own cache. Two traps, both hit: `getCoverArtUrl` already carries the
   user's cover-art quality, so appending `&size=128` produced `…&size=4096&size=128` and Subsonic
   honoured the *first* (the size is a parameter on `getCoverArtUrl` now, and the **2-arg**
   overload is the one to keep through a merge); and the failure path was
   `runCatching{}.getOrNull() ?: return`, unlogged and **permanent for that composable**, because
   the `LaunchedEffect` key never changes again. Retried three times and logged now
   (`fetchCoverSeeds`, the one fetch the page and the prefetcher share).
3. **Nothing may theme off Navidrome's generic artist avatar.** `util/CoverPlaceholder.kt` is
   consulted inside `rememberCoverColorScheme` as well as in `CoverArt`; quantising the served grey
   glyph otherwise answers "this artist is grey" for every artist without a picture. Likewise
   `AmbientColorHolder`'s `initialSeed` is honoured only while a fetch is actually pending — with
   no art to fetch, nothing ever replaces it and the artist page kept the colour of whatever album
   you arrived from.
4. **A genuinely achromatic image gives an achromatic page, on purpose.** A white-silhouette artist
   photo resolving `dominant #f0f0f0, vivid null` is the artwork, not a fault; the greyscale-family
   rule in `dominantByColorFamily` is what keeps a black-and-white *sleeve* neutral.
5. **Brightness follows the artwork on browsing pages**, and the wash is a **gradient**, not a flat
   surface colour. Those two go together: a drawn background leaves anything painting `surface`
   sitting on it as a slab, and following the artwork's brightness is what retires that constraint,
   since the scheme's own neutrals then sit in the same tonal register as the gradient. `background`
   is the only role overridden, to `Transparent`; `surface` and the `surfaceContainer` roles are
   left alone so rows and cards still lift in the page's hue. The background is drawn **outside**
   the `NavicTheme` it themes, because `BlendBackground` fills from `colorScheme.background` and
   would otherwise find the transparent one.
6. **A screen must not re-enter `NavicTheme`.** This follows directly from (5): a nested
   `NavicTheme` re-derives a fresh scheme with an *opaque* `background` and paints straight over the
   gradient. It kept alpha59's Statistics tab flat while every other tab followed the artwork — and
   it made *adding* the `Washed` wrapper measure as literally zero change, which is a very
   convincing way to conclude the wrapper was not the problem. The legitimate nested uses are
   `MoreButton`'s, for sheets that deliberately want the app's scheme rather than the page's, and
   `RailAmbient`'s for the tablet rail (below), which sits *beside* the wash rather than inside it:
   nothing is drawn under the rail, so its opaque `surface` hides nothing.
7. **`dynamicTheming` gates the whole engine**, inside `rememberCoverColorScheme`, so one gate
   covers the home, every tab, both detail screens, the mini-player, the now-playing chrome and
   every sheet. It defaults **true** — a pref defaulting false would un-theme every existing
   install. While it is on, the *Theme mode* row and the theme picker are **greyed, not hidden**:
   artwork decides what a browsing page looks like, but those values still drive the settings
   screens, every dialog, and any page with no artwork to read. Off, nothing is fetched or
   prefetched either (`CoverSeedPrefetcher.request`).
8. **A page's colours arrive with the page, never after it (I-043).** Three things, measured on
   the VM AVD: a cold start sat on the app's neutral scheme for ~3 s and then flipped to the wash,
   and the page under it was built TWICE, because `BrowsingAmbient` returned early into a bare
   `NavicTheme` while unresolved and wrapped it in a `Box` once resolved, so the content changed
   place in the composition and lost its scroll position and everything it remembered. Now:
   `BrowsingAmbient` keeps **one** `NavicTheme` call and one structure whatever the state
   (`BrowsingAmbientStructureTest`); a cold start wears the last playing cover
   (`lastNowPlayingCoverArtId`, `washCoverArtId`) until `MediaPlayerViewModel.restored`, a cleared
   queue clears it so nothing playing stays neutral; and the colours come from the persisted store
   (above). **Bump `CoverSeedCodec.VERSION` whenever `deriveCoverSeeds` changes**, or every install
   keeps wearing colours worked out by the old rules. Still after the page, by design: the blurred
   artwork itself (Coil, then its 400 ms crossfade), which on a cold start lands seconds after the
   colour.

9. **The wash is behind the pages, not on them (P1 round 2).** Each washed page used to draw its own opaque wash,
   so while two pages cross-faded (a tab switch, a push, a pop) the app's neutral surface showed through the fade and
   the colours flashed pale for a moment (measured on the AVD at animation scale 10: the page's edge went from lum 189
   to 206 on the tap and settled back over the transition), and after a committed back gesture the page being left is
   removed a few pixels before the page coming in has finished sliding, so a bar of that same neutral surface (white
   on a light base) stood at the edge (12 px of a 336 px frame, shrinking to 0; the same for any pop, root or not).
   `AppWash`, composed once around `NavDisplay` in `App.kt`, draws the playing cover's wash and provides
   `LocalAppWash`; `BrowsingAmbient` draws none of its own while that is true and is transparent over it. Same gate as
   the page's (`themed && resolved`, the cover from `washCoverArtId`), so nothing playing is the plain surface as ever.
   Artist and album pages draw their own opaque wash over it, from their own cover (a thin bar of the PLAYING cover's
   colour at the edge of a pop to one of them is possible and not fixed). The cause of the early removal was not
   found (the committed back runs on, with the page being left already gone); do not rely on the bar being absent
   without the backdrop. Every surface but the artist and album pages is washed: Settings and all its pages, the smart
   playlist form, shares, a genre's albums (`WashedBrowsingScreens`); what a host composes outside every page (the New
   sheet's dialog and paste sheet, the changelog sheet) is themed from `rememberNowPlayingCoverAmbient()`.

**The tablet rail follows browsing pages only (B-008, option 2).** The `SideBar` sits beside
`NavDisplay`, so it cannot read a page's `BrowsingAmbient`. `RailAmbient`, beside it in
`CoverAmbientBackground.kt`, wraps it in `App.kt` and takes the same now-playing scheme itself
while the **page** on top of the back stack is a washed browsing screen: `railFollowsCover()` in
`ui/navigation/WashedBrowsing.kt`, which skips the sheet entries that sit over a page (now
playing, lyrics, queue, speed, song sheet). It is gated on `themed && resolved` as rule 1 says,
**and on a cover id being present** (`railWearsCover`). `rememberCoverColorScheme` keeps its last
palette when the id goes away (clear queue, a radio stream, a coverless track, the generic avatar),
so `resolved` stays true; pages opened after that show the base, and an app-lifetime rail would
otherwise keep the stale colours beside them. A present id whose fetch fails still leaves the
previous palette on the rail, as on a page that was already open. The rail is
`WideNavigationRail`'s non-modal container, i.e. the scheme's `surface`: opaque, and at the
artwork's brightness like the page beside it (see rule 6 for why its nested `NavicTheme` is fine).
**The limit:** detail and artist pages, the image viewer and a two-pane
`CollectionDetail` keep the base rail (Settings and its pages are washed since P1 round 2), so a light rail beside a dark artwork-themed *detail* page
is still expected (option 3, every screen publishing its scheme, would close it). A new washed tab
goes in `WashedBrowsingScreens` as well as behind `Washed` — and so does a washed PUSHED page:
Library Matches, Downloads and Saved queues are (I-026, I-039), wrapped at their `entry<…>` in `App.kt`; `WashedBrowsingDriftTest`
(androidHostTest, JVM-only because it reads the source) fails when the two, the self-washing
`LibraryScreen` or the sheet list drift apart.

---

### Discover, and the table that is written twice

`ui/screens/discover/DiscoverRows.kt` has a twin in Feishin
(`renderer/features/discover/discover-rows.ts`). There is no shared build between
the repos, so the ids are kept in step by hand and are written down in
`navi-connect/CLAUDE.md` §6. The precedent was `MoodCharacter`, and it is a
cautionary one: same three presets both sides, matching numbers, different ids,
and the behaviour has already drifted (Feishin's auto-DJ escalates temperature
per pass; `RadioManager.topUp` did not; since I-040 both read the hub's catalogue
instead). Change a row id here and there.

Three things specific to this tree:

- **One `DiscoverViewModel` owns every row.** `LibraryScreen` paid for this
  lesson — its own comment records that full list viewmodels for the secondary
  rows read the whole library three times in parallel — and a screen of N rows is
  that shape exactly.
- **`horizontalSection` now takes `because`.** That optional reason line under
  the header was the one thing it could not do, which is why `SimilarAlbumsRow`
  was hand-rolled as a third row primitive. New shelves should use
  `horizontalSection`; `ArtCarousel` already carries its own "get rid of this".
  Note a LazyGridScope builder lambda is **not** composable, so `stringResource`
  for a reason line has to be resolved before the grid.
- **The tab gate is wider than Fresh's.** `rememberVisibleNavigationTabs()` hides
  Fresh without lb-bot; Discover also carries mood search and the rediscovery
  set, so it shows when lb-bot *or* AudioMuse is configured.
  `AudioMuseManager.isConfigured` is a preference read rather than a probe
  because that function runs on every bar mount — a network call there is one per
  navigation. And `NavbarConfig.VERSION` is **not** bumped for it (§7).

**"Mixed for You" — and why it is not called a station or a radio.** The word was
already spent twice here: `Screen.RadioList` is Subsonic internet radio, with its
own entity, DAO and dialog, and `SavedQueueSource.RADIO` / `RadioManager.startRadio`
is the *ephemeral* similarity mix. Navidrome's "Instant Mix" is ephemeral too, which
is exactly what these are not. So the user-facing string is **"Mixed for You"** and
the noun in code is `mix`.

Earlier revisions of this section ended "a station is a recipe, and nothing in this
tree stores a recipe". **That is no longer true** — it was the statement of the gap,
and Track C closed it on 2026-09-23. A mix *is* a stored recipe
(`{kind, seedId, moodCharacter, character, count}`), held hub-side beside saved queues and
regenerated locally on every play: `domain/models/Mix.kt` (`Mix` + `MixKind`), the
`mixes` state and four `act` helpers in `HubManager`, `RadioManager.regenerate(mix)`
/ `currentRecipe(seed)`, `ui/screens/mixes/*`, `Screen.MixList` and
`NavbarTab.Id.MIXES`.

The distinction the old paragraph drew still holds and is the whole point:
`playMix` exits into a saved-queue *snapshot*, a frozen list that replays; a mix
stores the recipe and rebuilds the list each time. A saved queue stores a **result**,
a mix stores a **recipe**. That is also why `HubManager.mixes` is a plain read-only
mirror with no local table, no tombstones and no union merge, while
`SavedQueueRepository` has all three: a saved queue is captured automatically and
constantly on every client, a mix is written by hand on one device and never
published concurrently, so last-write-wins is the entire conflict story.

**Two things the first cut of this got wrong, both found by driving it.**

*Nothing could make three of the five kinds.* The only create path was "save what's
playing", whose recipe comes from `RadioManager.currentRecipe` — i.e. from whichever
autoplay mode happens to be on — so `MixKind.GENRE` and `MixKind.ARTIST` were
**unreachable from this client entirely**. `regenerate` could play them, `MixFormat`
could label them and the hub validated them; they could only arrive from Feishin.
`ui/screens/mixes/MixFormSheet.kt` was the general path until P5 replaced it with the custom
radio (§4; the paragraph below is its history): name, kind, and a seed
picker that fits the kind. It makes four, **in Feishin's order and with Feishin's words**
(B-150, `MixFormKind` in `MixFormKind.kt`; `MixRecipesTest`, `MixFormWordsTest`): Radio
("Around a song, steered by your skips and likes": a `station` recipe on the playing track),
Your mix ("Built from what you play most": a seedless `station`, offered only with the
engine), Genre ("A random run through one genre": the genre's NAME in `seedId` and
`seedName`, no character) and Artist ("More like one artist": an artist id, with a
character). An `artist` mix is the artist page's radio (`artistRadioPlan`, shared with
`startArtistRadio`): a station from up to five of their songs with the engine, Navidrome's
similar songs after them without it; it used to shuffle the artist's own songs. A genre
recipe is drawn by name, trying `seedName` after `seedId`, because Feishin wrote the genre's
id there before B-150 (`genreSeedNames`). `similar`, `fingerprint` and `adaptive` still play
(as stations when the engine is there: `similar` a track radio on Balanced, the other two
Your mix) and are no longer made. A station-backed mix has no preview: it is built as it
plays. Your mix, and the two old kinds that play as it, wear a person (`Icons.Outlined.Person`,
B-149), not the clock.
Your mix also heads the Mixed for You list while the engine is there. A sheet rather
than a `FormDialog` because two of the four need a searchable list and `FormDialog` is
300dp wide.

*Every mix rendered as bare text.* `Mix.coverArtId` was on the model and in the hub
record (`_sanitize_mix` has always copied it through) and **nothing ever wrote it**,
so both renderers always took `CoverArt`'s art-less branch. `actSaveMix` carries it
now — no hub change — stamped from the **seed**, which is a fixed part of the recipe
and so stays true on a second play, unlike the last regenerated queue's cover.
`ui/screens/mixes/MixArtwork.kt` is the single renderer both surfaces draw through;
with no stamped cover it draws a kind glyph over a gradient keyed on `mix.id`.
**That is a deliberate divergence from Feishin**, whose `mixes-row.tsx` declines to
generate anything on the grounds that it "would be a lie about what is inside" —
true of invented *album art*, and not of an icon naming the kind.

---

## 7. Conventions and gotchas

- **Navic files use tabs.** Multi-line edit matches are fragile; prefer matching single bare lines.
- **Never take `albumLookup().firstOrNull()`.** That is a MusicBrainz *text score*, passed through
  by lb-bot, and a free-text `"<artist> <title>"` scores a release-group whose TITLE contains both
  words above the one where the artist match is a separate field. Measured 2026-09-23: `Daft Punk
  Discovery` returns "Daft Punk's Discovery but it's in the SM64 Soundfont" by Pignickel **first**
  and the real `Discovery` third, so a browse tile for an album the user owns opened a parody's
  download page. Two things fix it and both are needed: send a **fielded** query
  (`artist:"…" AND releasegroup:"…"` — `q` reaches MusicBrainz verbatim, and the same search then
  returns the right record first and no parodies at all), and **validate the answer against what
  was asked** rather than trusting the order. Declining is a correct outcome; opening the wrong
  album reads as the feature being broken rather than as a near miss.
- **An artist credit that cannot be opened is a dead end, and the MBID was there all along.**
  `ExternalAlbumScreen`'s artist control is gated on having an artist id *or* an artist MBID.
  Reached from Fresh or a discography shelf one of those is set; reached from a **Deezer browse
  row** neither is, because Deezer carries no MBIDs — so the control hid itself and took the only
  route to `indexArtist()` (the discography scan) with it. The fix was upstream of the UI:
  `/lb/album/releases` already fetched the release-group with `inc=artist-credits` to build the
  display name and was **dropping the MBID out of the same payload**. It returns `artistMbid` now.
  `ExternalAlbumViewModel.resolveArtistTarget` picks the destination — caller's `artistId`, then
  **Room by name** (trying both the browse row's plain artist and lb-bot's full MusicBrainz credit,
  since either may be how the library filed them), then the MBID, then nothing — and returns an
  `ArtistTarget` rather than an id, because a bare string can express only the middle case. An
  owned artist must open *their* page: the external one renders every album they own as
  "Added — syncing".
- **lb-bot's ownership badge is not "is this in my library".** `releaseOwned` is marked from its
  release-group index, which only covers artists whose discography it has *scanned* — so a record
  the user owns by an unscanned artist comes back `owned: false` with no rgid, and every step after
  that answers the wrong question. Room holds the whole library and answers it exactly, offline and
  for free, so **check Room before the network** on any "do I already have this" path.
  `DiscoverViewModel.localAlbumFor` is that check. Match on the title with the edition suffix
  dropped: Deezer ships "Discovery (Remastered)" where MusicBrainz and the library both say
  "Discovery", and the parenthetical defeats a `LIKE` and a fielded search alike.
- **Nothing that streams a file may inherit the shared client's `requestTimeoutMillis`.**
  `SessionManager`'s Ktor client caps a request's whole lifetime at 120 s (added for library syncs),
  and when the cap fires mid-body, Ktor ends the body as a **clean end-of-stream, not an error**.
  media3's `KtorDataSource` reports that as the end of the file. ExoPlayer routinely pauses a song's
  load mid-song once the buffer ahead reaches the `LoadControl` cap (600 s), with the connection held
  open, so a song preloaded minutes early lost everything after the pause point. It played silent from
  there while the position crawled on the standalone clock (`hasReadStreamToEnd`, `buf` reads as the
  full duration because the load "finished"), and the next song was fine. That was the
  2026-09 "audio goes silent, the bar keeps moving" bug, blamed first on
  `estimateContentLength`, then Bluetooth, then the Opus decoder. Measured through the real server:
  idle 150 s or 400 s → cut off ~80 KB later with no error; no request timeout → read in full; a
  steady 80 KB/s download → cut off at exactly 120 s. Fixed by `PlaybackService`'s
  `streamingClient` and a per-request `timeout {}` in `DownloadManager`. Don't "unify" them back onto
  the shared client.
- **The player streams over HTTP/1.1 on its own client, through `ResumingDataSource`, and buffers
  at most 300 s ahead.** All three came from the same bug, after the timeout fix alone didn't end it:
  - **Why not HTTP/2.** Every request to the server shares one HTTP/2 connection and one 16 MiB
    OkHttp receive window. A load paused with its buffer unread stalls that window, and through the
    Cloudflare tunnel the stream is then reset: a 57–89 MB FLAC idled 120 s died at exactly
    16,777,216 bytes. Over HTTP/1.1 the same files idled 60–400 s all read in full.
  - **`ResumingDataSource`** reopens a broken stream with a Range at the byte it stopped at. That
    covers a read error, and a body that ends short of its `Content-Length`, which `KtorDataSource`
    otherwise accepts as the end of the file. Verified: the HTTP/2 reset above resumed at byte
    16777216 and delivered the whole file. It logs each resume under `ResumingDataSource`.
  - **`maxBufferMs` 300 s, was 600 s.** The gap to `minBufferMs` (32 s) is how long a load sits
    paused; about 270 s stays inside the 400 s verified.

  Downloads and the warm-up HEADs still use the shared HTTP/2 client. They read continuously, so they
  never stall a window.
- **Never send `estimateContentLength`** (removed 2026-09-24; upstream sends it on every
  stream). It makes Navidrome *compute* a Content-Length as `bitrate / 8 * duration` for a transcode
  it has not finished. For VBR Opus that guess was measured **6 % short to 11 % long**. ExoPlayer
  trusts it twice over:
  - **The duration comes out wrong.** The Ogg extractor reads a track's duration off the last page
    it finds at the declared end. ffmpeg writes about one page a second, so a short guess yields a
    whole-second duration seconds too early: `216000` for 218.25 s of audio. The track change then
    fires while audio is still playing; the sink logs `Unexpected audio track timestamp
    discontinuity`, the next song's bar starts at 0:10, and so on.
  - **The body is cut off.** Navidrome truncates the body at the declared length, and through
    Cloudflare the response ends in an HTTP/2 reset.

  Without the parameter, a transcode Navidrome has cached is served with its exact length and
  Range support. An uncached one streams complete with no length, and ExoPlayer derives the exact
  duration once it has loaded. The cost is that the uncached one cannot be seeked (ExoPlayer turns
  the seek into a seek to 0), so `seek()` ignores it and `currentDurationMs()` falls back to the
  library's duration. `warmUpcomingTranscodes()` HEADs the next two songs so they are cached before
  they play: a HEAD makes Navidrome drain the transcode into its cache, in 3–10 s. Measured with a
  Robolectric ExoPlayer harness against the live server: 5/5 uncached tracks got exact durations
  and a clean single load, where the estimate had given errors, retries and round-number durations.
- **A row's identity is its ID, never the `DomainSong`.** `CollectionDetailScreen` keyed its open
  sheet on `selection == song`, a data-class equality over every field. The list re-emits fresh
  instances whenever the collection is re-read, so rating or starring from inside the sheet changed
  a field, broke the equality and **closed the sheet under the finger of the person who just used
  it**. Compare `selection?.id == song.id`.
- **`ORDER BY RANDOM()` cannot back a `@Relation` query.** Room walks the parent cursor twice for
  `AlbumWithSongs` — once to collect ids, once to assemble — and SQLite re-evaluates `RANDOM()` on
  the second walk, so the passes see different albums and assembling one the first never saw throws
  `NoSuchElementException: Key <albumId> is missing in the map`. `@Transaction` does **not** fix it
  and was already there for this symptom: the problem is a query that does not answer the same
  thing twice, not a concurrent writer. Draw the ids first (`getRandomAlbumIds`) and fetch by them,
  or read a deterministic order and `shuffled()` in Kotlin where the list is unbounded.
- **Discover's rows load CONCURRENTLY, and at most three at a time.** Every row used to be awaited
  in sequence — eight round trips, several of them seconds each, which is where a ~25 s first open
  came from. The cap is not arbitrary: the hub's lb-bot proxy has **four** default in-flight slots
  shared across every non-polled lb-bot route (the polled and cancelling routes have their own
  two-slot fast pool), so fanning them all out queues behind itself and starves everything else the
  app asks for meanwhile. The similar-artists row's own two calls stay sequential inside their
  helper for the same reason.
- **A download needs a foreground service, and `setOngoing(true)` is not one.** `DownloadManager`'s
  scope is a process-scoped `SupervisorJob` on a Koin singleton and **nothing in this tree cancels
  it** — no `ProcessLifecycleOwner`, no lifecycle observer, no `onStop`. So "downloads stop when you
  leave the app" was never a cancellation bug: with no FGS the process drops to *cached* when the
  last Activity stops and Android 12+'s freezer suspends its threads, after which
  `reconcileInterruptedDownloads` parks the rows `FAILED / "Interrupted"` on next launch. That
  string is the fingerprint. `DownloadService` (`dataSync` + `FOREGROUND_SERVICE_DATA_SYNC`, declared
  in **`androidApp`**'s manifest because `composeApp` is a KMP library module with no manifest of its
  own) is started and stopped from `updateDownloadNotification`, the one place the queue's size
  changes. Android 15 gives `dataSync` ~6 h per 24, and `onTimeout` parks the remainder as
  `PAUSED_ERROR` rather than `INTERRUPTED_ERROR` — a budget expiry is not a breakage, and those two
  strings are the only way a user tells them apart. **WorkManager is deliberately not introduced:**
  it is not a dependency anywhere, and `downloadSemaphore` plus `awaitDownloadConstraints` already
  do what `Constraints` would.
  The service is held per **reason** (`ForegroundReason.DOWNLOADS`, `SYNC`): `SyncManager` holds it
  around the full library pull too, which otherwise froze half-done under a notification that still
  said it was running. Its notification id follows the reasons (download while anything downloads,
  sync when only a sync runs), so each manager's own progress notification updates it in place. The
  controller keeps no "service is running" flag (it went stale after a timeout or a refused start);
  the service reports `onServiceGaveUp` instead, and `onTimeout` finishes the park (`NonCancellable`,
  library sweep included) before it stops. Progress notifications carry `setTimeoutAfter(120 s)`:
  with no service behind one, a stalled notification removes itself rather than hanging in the shade.
  **What the controller does keep is "a start has not been answered yet" (B-096).**
  `startForegroundService` obliges the service to call `startForeground`, and a `stopService` before
  it has is not the no-op that stopping an idle service is: the system kills the app with
  `ForegroundServiceDidNotStartInTimeException`. Every hold shorter than the main thread's way to
  `onStartCommand` did that — the full pull of a logged-out fresh install fails within a second, so
  that install died about 5 s into every launch. From `startForegroundService` until the
  `onStartCommand` carrying that start's token (an Intent extra) has called `startForeground`, the
  controller sends nothing: no stop, no "reasons changed", no second start (`ForegroundHold.kt`,
  `ForegroundHoldTest`). That `onStartCommand` ends the wait and reads the reasons in one locked
  step (`onStartAnswered`), and with nothing held stops itself by `stopSelf(startId)`, which a
  start asked for since then survives. Only that command, or the start call throwing, clears the
  token — a timeout or a command an older instance is still working through does not — which is
  why it cannot go stale the way the flag did. `start` and `stop` also make their call to the
  system under one lock, so a stop decided before a start cannot arrive after it.
- **A row left QUEUED / DOWNLOADING by a process death is resumed, not failed.**
  `reconcileInterruptedDownloads` queues it again at its stored quality and source; `Interrupted` is
  now only a row whose song Room no longer holds. `downloadSong` returns at once for a row that is
  `DOWNLOADED` with its file on disk, whoever calls it. A body is only a song if the status is 2xx,
  the content type is not JSON/XML/HTML (Subsonic errors are HTTP 200 envelopes) and, when a
  `Content-Length` came with it, the bytes written match. Live progress is the in-memory
  `DownloadManager.progress` map laid over the rows in `allDownloads`; it is not written to the table,
  so `DownloadEntity.progress` in the database is 0 or 1.
- **A playlist download policy acts only on what the server answered on that pass**
  (`PlaylistSyncPlanner.kt`, `PlaylistSyncPlannerTest`). A failed fetch does nothing; Room's playlist
  rows are never read for membership. **Permanent never deletes** (a song that leaves the playlist
  stays on disk and in `managed`); **rolling** deletes what leaves its window, but not on a single
  empty answer. Before 2026-10-03 both modes evicted against Room, and a pass that met an emptied
  playlist deleted every managed file and downloaded the lot again.
- **Navidrome smart playlists are track-scoped, and there is no album mode to ask for.** So
  `AlbumModeSmartPlaylists` builds one by having the *server* evaluate the rules and expanding the
  result locally — create a smart playlist, read its tracks, expand each to its whole album, write a
  **regular** playlist, delete the scratch one. Regular because Navidrome refuses membership edits
  on a smart playlist, so a snapshot cannot live in one; and the recipe is therefore kept **locally**,
  since the record left on the server has no `rules` field to hold it. It is a snapshot, the editor
  says so, and the sheet offers Refresh. Nothing here re-implements Navidrome's matcher — a local
  evaluator would have to reproduce sixteen criteria types and would drift the first time either
  side changed.
- **A playlist's cover cannot be changed, and no API for it exists.** `updatePlaylist` in the
  bundled `dev.zt64.subsonic` client is `(id, name, comment, public, songIdsToAdd,
  songIndicesToRemove)` — the Subsonic spec has never had an image parameter and there is no
  `uploadCoverArt`. Navidrome's native API exposes no image write either; it derives a playlist's
  cover from its member tracks' art, which is all `DomainPlaylist.coverArtId` ever holds. Written
  down so the next round does not re-derive it.
- **Collect `MediaPlayerViewModel.steadyState`, not `uiState`.** `uiState` re-stamps `progress`
  every 200 ms (250 ms remote), so collecting it recomposes the reader ~5×/sec for the whole of
  playback. Take the narrow `progress` flow only where the playhead is actually drawn. Upstream's
  new surfaces get this wrong by default — its `SideBar` mini player did.
- **A sheet on the shared wrapper has one resting place: fully shown** (I-026). **The one
  exception is the staged queue (P2, §4)**, which is not a sheet on the wrapper but a layer inside
  the player with three places, as the brief asked; nothing else gets more than one. The wrapper's
  default state enables only `Hidden` and `Expanded` — the state upstream's own sheets pass.
  Material's default also enables a half-way place, which the opening heads for and which
  exists only while the sheet is taller than half the screen: a fork sheet that reads after it
  opens started short, showed whole, and when its content passed half the screen dropped to
  the half-way place with its last control cut off ("Find sources" on the missing-album sheet, retired
  since P4, seen on the phone). Content taller than the screen scrolls inside the sheet, so every sheet
  on the default keeps ONE scrolling column. A caller that wants the half-way stop passes its
  own state (`LyricProvidersSheet`, upstream's, does, to stay as upstream has it).
- **A sheet's content height is eased in the shared wrapper** (`ModalBottomSheet`'s
  `animateContentHeight`, on by default; I-026). Material rests a sheet at screen height minus
  sheet height, works that out again on every size change, and restarts the opening animation
  from where it is when the anchors move under it. Upstream's sheets have everything on the
  first frame; the fork's read from lb-bot after opening, so a spinner's height became the first
  resting place and every answer threw the sheet at a new one. The easing is INSIDE the window
  insets, so the keyboard is still followed at once. The two navigation scenes that host whole
  pages (`NowPlayingScene`, `BottomSheetScene`) pass `false`. A new fork sheet gets this by
  using the wrapper; don't reach for Material's `ModalBottomSheet` directly.
  **Never while a finger is on the sheet** (B-121, 2026-10-15). Near the top of the screen
  Material gives the content more room as the sheet is dragged down (the status bar's share
  of the top inset shrinks), so a drag changes the content's height every frame. Eased, that
  height lagged, the resting place moved under the drag and the sheet snapped back: a
  full-height sheet (Fix album match, MusicBrainz match, a long About) could not be swiped away,
  and "stuttered up and down". Measured on the VM emulator with the sheet's offset logged: with
  easing the offset bounced between 3 and 124 px over a 1200 px swipe; with it off during the
  touch the offset followed the finger and the sheet dismissed. The wrapper watches the pointer
  on the Initial pass (never consuming) and, while one is down, reports the measured height and
  resets the easing to it. **One node for the sheet's life** (`easedHeight` in the wrapper), told
  whether to ease: swapping `animateContentSize()` in and out with the finger created a fresh
  node on the lift, which takes its first measurement as its start, so a tap that changed the
  content (the new mix form, Mood Flow to Genre) jumped to full height at once.
- **An ambient sheet's drag handle sits below the status bar** (B-121). The ambient branch used
  to hand Material zero insets so the gradient could reach every edge; the handle then sat in
  the status-bar band of a full-height sheet, where a pull opens the notification shade. Material
  now gets the TOP inset (as upstream's `BottomSheetScene` passes `systemBars.only(Top)`), the
  bottom and sides are still applied inside the gradient, and the container is the gradient's
  top colour, so the band shows no seam. A tall sheet also keeps its title and first line
  OUTSIDE its one scrolling column (`SheetHeader` in `LbBotCommon.kt`, as upstream's Song and
  Collection sheets keep theirs), so there is always somewhere to take hold of it.
- **A covered window keeps drawing; an endless animation must hold under a full-screen sheet**
  (I-041, 2026-10-17). The player and the queue are `ModalBottomSheet`s, i.e. windows of their own,
  and all of an app's windows share one RenderThread. With the queue open over the player while
  playing, the player's rotating 80 dp blur (`BlendBackground`) and moving wave, and the mini
  player's progress under both, were redrawn every frame by nobody's eyes, and the queue got the
  rest: measured on the VM emulator in one boot, the queue window drew 94-104 frames in an 8-fling
  scroll while the two hidden windows drew ~570; with them held it drew 229-230 and they drew none
  (per-window counts from `dumpsys gfxinfo … framestats`; the queue is the last `ViewRootImpl`).
  `SheetCover` holds which sheet covers the screen (open, settled, its top at the top, full width,
  opaque: not the lyrics sheet); content under one reads `rememberCoveredBySheet()` and stops its
  endless animation (the rotation pauses, the wave's velocity goes to 0, the waveform bars and the
  AudioMuse colour hold), and a per-tick value (the position) goes through
  `heldWhileCoveredBySheet()`. Nothing visible stops: a sheet stops covering on the first frame it
  moves off the top. A new endless animation on a page or in the player does the same. Known gap:
  Material's predictive-back shrink of a sheet does not uncover, so for the length of a back
  gesture what shows at the edges holds still.
- **Leaving the now-playing sheet goes through `NowPlayingSheetController.requestHide { … }`.** The
  player is an `OverlayScene` bottom sheet, so `backStack.remove(Screen.NowPlaying)` plus
  `add(destination)` in one frame destroys it outright — the player vanishes and the next screen
  appears with no transition. `requestHide` takes the navigation as a continuation and runs it
  after the hide animation *and* after the pop (running it before would make the pop take the
  destination instead). The queue sheet drops both entries before pushing.
- **With room for two panes, each library page is a scene of its own (B-131).** Every library page
  is a `listPane("root")`, and material's list-detail strategy keys its scene by that key alone,
  so in landscape the home and the page a home button opens (Discover, Fresh, Random, Recently
  added, Mixed for You) were ONE scene: the push swapped the list pane's content with no
  transition, and Back from the page left the app, because material's default Back pops "until
  the layout changes" and two list pages in a row never change it. `ListPageSceneStrategy`
  (`ui/navigation/`) wraps the strategy and keys its scene by the topmost list page as well
  (entries marked with `ListPageSceneStrategy.listPage()` beside `listPane`, since material's own
  marker is internal), and the strategy is built with
  `BackNavigationBehavior.PopUntilContentChange`. The wrapped scene claims only the entries from
  that list page on: claiming the hidden ones under it let NavDisplay take the home out of the
  scene being left at the first frame, and the window's white surface flashed. A detail opening
  beside its list stays inside one scene and keeps the scaffold's own pane motion. Upstream's
  `App.kt` has the same metadata, so re-check this after a merge that touches either.
- **(Superseded by P1, below: the bars are `ShellTab` now and nothing reads `NavigationTab`.)** **The bars share one tab helper.** Upstream writes the `NavbarTab.Id → NavigationTab` mapping out
  longhand in **both** `BottomBar` and `SideBar`; this fork routes both through
  `rememberVisibleNavigationTabs()` / `toNavigationTab()` in `ui/navigation/NavigationTab.kt`. The
  lb-bot gate that hides the Fresh tab when lb-bot is unreachable lives there, and is exactly the
  rule that otherwise gets added to one copy and not the other. Its availability state lives in
  `LbBotManager`, not a `remember`, because both bars are re-mounted per screen.
- **`NavbarConfig.VERSION` is not bumped for a new tab.** `merged()` appends ids a stored config
  has never heard of while keeping the user's own order and visibility. Upstream bumps because a
  bump is its only way to introduce a tab; copying that discards every existing install's
  arrangement to gain one row. It sits at 7 against upstream's 8, deliberately.
- **...and changing a tab's DEFAULT visibility needs a third mechanism.** `merged()` preserves a
  stored tab's `visible` flag — that is the whole point of it — so flipping a default reaches new
  installs only, and a version bump would discard the arrangement `merged()` exists to protect.
  Fresh / Discover / Mixed for You became the library home's top buttons
  (`rememberOverviewButtons` in `ui/screens/library/components/OverviewButton.kt`) and had to come
  off the bar on installs that already had them, so `NavbarConfig.withHomeButtonTabsHidden` runs
  **once**, guarded by its own `navbarHomeButtonTabsMigrated` preference in `NavtabsViewModel`.
  (P3: Discover and Fresh have since left the home for Search's browse cards.) Order untouched, every other tab untouched, and a user who puts one back is not overruled on the
  next launch. The ids stay in `NavbarTab.Id` and in `NavbarConfig.default.tabs` — deleting them
  would make `merged()`'s live filter strip them from every stored config permanently.
- **(Superseded by P5: the Library is chips now, below; the buttons are gone.)** **The home page's top buttons are gated exactly like the tabs.** They are pushed onto the
  library's own stack, so each destination carries `nested = true` or it renders `RootTopBar` with
  no back arrow. The list is padded back out of Recently added / Starred / Frequently played until
  it is four long, because an odd count leaves a hole in the two-column grid and a home page with
  no library shortcuts has lost something it used to do. A fifth primary makes the home six long
  (`overviewButtonCount`) rather than pushing Random off it: Library Matches
  (`libraryMatchesOffered`: wherever the lb-bot layer is on) and Downloads (always) make six,
  which is `OVERVIEW_PRIMARY_MAX`; a seventh would have to raise it and the count with it. My
  Listening was a button until I-039 made it Stats', which the home reaches by upstream's own
  card at its foot.
- **The Library is one list under chips (P5).** No chip is Recents: Liked pinned (the starred
  songs' count), then the 100 newest of every mix, saved queue, playlist, album and artist with a
  clock: last played for albums, artists (their newest played album) and mixes (a mix never played:
  when it last changed), last changed for playlists and saved queues; nothing without a clock
  (`recentsEntries`). A tap opens the thing, a long press its sheet, read whole when it opens (a
  Recents row holds no songs). Each chip is that list page's body, so its sort menu, filters, view
  mode, sheets, scroller and scroll-to-top are the page's; its view model lives in the persistent
  store, so a chip switch keeps its place (Songs' list state is the Library's). Tapping the chosen
  chip clears it; a second Library-tab tap within 2.5 s clears it too. **A chip change shows the bars
  again**: the bottom bar collapses on scroll, and an empty chip could never bring it back. The chip
  is not in `Screen.Library` (whose equality selects the tab): the custom radio's Done asks for the
  Mixes chip through `ShellController.showLibraryChip`. The old home's carousels, statistics card and
  buttons are gone; every page they led to has a way in (chips, the Liked row, Search's Genres tile,
  the avatar menu). Its "sync failed, this is the cached copy" row heads Recents. Measured on the VM
  AVD's library (15,679 songs, 4,320 albums, 44 of them with a `lastPlayedAt`).
- **A mix is opened, not started (P5 round 2).** Every mix tap (the Mixes chip and page, Recents,
  Discover, Your mix's row) opens `Screen.MixDetail`: the playlist page's layout (the face over its own
  wash, the name, `mixKindLabel`, Shuffle, Play, Save as playlist, the playlist's song rows). Its songs
  are built afresh on each opening, as YouTube Music's mixes are (`RadioManager.buildMix`, one
  `MixDetailViewModel` per page entry; a pull builds again). **A mix that plays as a station starts that
  station when the page opens** and shows its seeds and first batch (`startStation`, the first half of
  `playStation`); Play, Shuffle or a row then loads exactly those songs as that station's queue
  (`playMixBuild` → `loadStation`), so what plays is what was shown and the station carries on after
  it. Any other mix runs `generate`. Opening a page is not a play: `actTouchMix` waits for Play. The
  hub holds 8 stations (LRU, the bound one kept), so pages opened and left are evicted, never the one
  playing. "Save as playlist" is the set on screen as a Navidrome playlist (`PlaylistCreateDialog`,
  named after the mix). The old preview sheet is gone (the page is the preview).
- **Every Library list wears Recents' row (P5 round 2).** `LibraryRow` (`ui/components/common`): a
  segment in a 16 dp gutter, 3 dp apart, on `SongRowDefaults.containerColor`, with 50 dp art
  (`LibraryRowCover`, artists round), a title and one line. Recents, Mixes, the Albums / Artists /
  Playlists list modes, Radios (rows now, with Edit and Delete in their sheet) and the downloads use it;
  the Songs rows keep their swipe and status but take its tone and padding. A letter group's header is
  `LetterHeader` (in the gutter, on a pill; stuck only over a grid: over rows it covered the first
  row's picture). The alphabet column is a scrollbar now
  (`AlphabeticalScroller`): a thumb that shows while the list moves and fades 1.2 s after, a drag
  through the whole list with the letter in a bubble beside it; hidden, it takes no touches. A liked
  song's status is a heart (`SongRowStatus`), and the song sheet says Like / Unlike. Liked
  (`Screen.Starred`, the pinned row) is every liked song in one list with Shuffle and Play, a tap
  playing the list from there; its view model is the entry's (one in the persistent store kept the
  first read's songs); starred albums and artists are those chips' filter. **A chip's sort row says
  how many it holds**, not the chip's name again ("2474 artists"; `chipSummary`, from the bodies' own
  view models), and Artists' list drops its "N artists" line. The Songs rows' swipe backing is drawn
  only while a swipe is under way (at rest it tinted every translucent row).
- **Library tools (P6): Needs attention and Source history, from lb-bot's own lists.** The avatar menu's top rows are
  gone; Library tools holds, in order, Needs attention (badge: the count), Source history, Library Matches, and, only while
  the hub advertises a `webUrl`, Duplicates and trash, Source rules and Logs (`AvatarMenuEntry.External`: they open
  `webUrl/#/<path>` in a browser). **The count is lb-bot's web UI's own**, `failedGaps + failedRequests + folders`
  (`toFixCount`, from `GET /lb/attention`'s `counts`, PROTOCOL §15.6): the same on every client; albums waiting on a pick
  and artists to check are listed, never counted. It is read on a hub `welcome` / `lb` frame and on a `fill` frame that
  names a failure or a pick (forced), when the menu opens and when a page appears, at most every 30 s otherwise, one read
  at a time; never polled. **A read that did not happen is never zero**: an older hub or lb-bot (a bare 404, a 405) keeps
  P1's ledger count and the page lists the device's rows with one sentence; a failed read keeps the last answer and says
  when it was read; nothing read yet is a spinner. Rows lb-bot lists and the ledger holds appear once (lb-bot's state,
  the device's buttons); a row only lb-bot knows offers **Open album and nothing else** (`FillRow`'s `lbBotOnly`): nothing
  here can retry, cancel or dismiss what the ledger does not hold, and a folder is never filed from here (filing
  retags and moves files): its row says what lb-bot identified it as and how many files, never its name or path, and
  opens `#/place/<id>` in lb-bot. Source history (it was Downloads' Fills and Wishlist chips, "Activity" in the menu) is
  lb-bot's ledger joined with the device's (`mergeHistory`), Running over Earlier with the Activity board's pills, the
  transfers chip only when lb-bot lists any, and `WishlistSection` moved whole; lb-bot's half is re-read every 10 s only
  while one of its own rows runs and the page is on screen. **Statistics' Find sources**: Not in library is rows (the
  StatsMissing board); an album row's button opens `ExternalAlbum(findSources = true)`, and the page starts the ONE source
  search when the edition is known, once per visit (saved with the entry), never over a running fill or search; the tap is
  the consent (P4's rule), every other way in leaves it off. Walked on the VM AVD against a scratch hub and a fake lb-bot
  (`RESULTS-library-tools-2026-10-21.md`); the live lb-bot answered the new routes 404 on 2026-10-21 (not deployed).
- **P5 round 3 (the owner's notes).** No Downloaded chip: downloads are the avatar menu's Downloads
  page only (as a chip it was one long song list with nothing to sort by). The chips' sort button is
  the plain icon (`nested = false`), lined up with the bar's Search and New; the avatar sits 8 dp in so
  its edge is the title's (the bar's navigation slot starts at 4 dp). **A list view model cancels the
  read in flight when a sort or filter changes** (`refreshing`): reads overlapped and the last to finish
  won, so fast taps left 0 or 46 artists. An artist is Downloaded when any song crediting them is
  (`downloadedArtistIds`, one pass; it was every song, one query each, and matched almost nobody).
  Songs' "Recently added" sorts by a map of album added-times (`AlbumDao.getAlbumAddedTimes`; it
  searched every album per comparison). **Home's Fresh shelf is the Fresh page's first releases**
  under its stored scope, type and sort (`freshOrdered`, `FreshFilters.of`), redrawn on each Home
  visit. **The mini player is Now Playing's mini header** (the queue fully open): 56 dp cover rounded
  10 dp, title and line, Devices, a filled play / pause; swipes still skip.
- **The shell (P1): a fixed bar of four, one avatar menu, one New sheet.** The bar and the tablet's rail are
  Home, Search, Library and New (`ShellTab`), and a tap is `onShellTabTapped` for both (the selected tab scrolls to
  top, another tab leaves `[Home]` or `[Home, tab]` (P6, below), New opens a sheet and never touches the stack or the selection).
  **The user-arranged tab list is gone from the UI**: `NavigationTab`, `NavbarConfig` (and its persisted key),
  `NavtabsViewModel`, `NavtabsDialog` and `rememberVisibleNavigationTabs` stay in the tree, **unread**, so the diff
  against upstream stays small; the Settings editor is removed. Albums, Playlists and Artists are three always-present
  buttons on the library home (`rememberOverviewButtons`, `OVERVIEW_PRIMARY_MAX` 9): what the old bar held by default
  must stay one tap from the Library tab. The bars no longer call `lbBot.ensureAvailability()` (the old tab helper did,
  as a side effect); Home and the New sheet do.
  The New sheet (`NewSheetHost()`) and the avatar menu (`AvatarMenuDrawer`) are composed ONCE in `App.kt` and opened
  through `ShellController`: **fork lines whose loss is silent** (§2 rule 3's fourth kind: the avatar and New simply open
  nothing). Two things the drawer needed that the plan did not say: `gesturesEnabled = open` (Material gates the scrim's
  tap on it; closed, there is still no edge swipe, which belongs to the system back gesture) and its own
  `NavigationBackHandler` while open (this drawer does not intercept Back: one press left the app). The avatar is the
  `navigationIcon` of `RootTopBar` (so every root page has it; the Search page's own header places `AvatarButton` itself)
  and one 48 dp node carries its click, role and label; an `IconButton` clipped its dot, and `minimumInteractiveComponentSize`
  split the click from the label. `AccountSheet` is gone; the sleep timer it held is in the song sheet (Now Playing,
  More). A new root page is behind `Washed`, in `WashedBrowsingScreens`, and has a rule-14 row (§2).
  The dot counts lb-bot's own to-fix number where the hub answers `/lb/attention`, the device's own ledger
  (`needsAttentionCount`: settled rows that failed or wait on a pick) where it cannot (P6, below); the
  menu's Autoplay switch goes through `RadioManager.setAutoplayOn` (the manager keeps its own flow: writing the
  preference alone would not reach the driver).
- **Arrival: `rememberArrivalGate()` per page visit, `Modifier.arrive(gate, key, index)` per tile (P1).** The gate is
  saved with the entry (so Back does not replay it; a switch to Search or the Library starts a new visit of it, and Home,
  kept under them since P6, keeps its visit) and a tile is
  its key, never its position. Rise 8 dp and fade over 240 ms, the first five 30 ms apart, the rest together at 150 ms;
  the stagger is stretched by `animator_duration_scale` like the durations. **Reduced motion is the system animation
  scale and needs no flag** (measured: at scale 0 no in-between frame and no blank frame; the tile reads the scale from
  `rememberCoroutineScope()` in composition so it never starts invisible). `Fold` rolls a block's height out and follows the
  animation style (`FoldMotion`: the motion scheme's springs under Expressive and Standard, `PageMotion.foldMs` under
  Quick; the capsule's corners and chevron move with it); `CoverArt`/`RemoteCoverArt`/`ArtGridItem` take `placeholderName` for a tinted box the cover fades in over.
  The arrival's own timings do not follow `AnimationStyle`. A shelf calls `gate.claimAll` after its first frame so a tile scrolled in later
  does not arrive on its own.
- **Home and the shell (P6).** Home is Continue listening, Recently added, Fresh, **Recently played**, **Frequently
  played** (Navidrome's recent and frequent lists from Room, twelve each, a never-played album left out of "recently
  played": the recent order has no filter of its own) and **Your mixes** (`mixTiles`: Your mix first while the station
  engine is there, then every hub mix by last played, a mix never played by last changed, at most 30; a tile OPENS the
  mix's page, never plays it; "See all" is the Library tab on its Mixes chip), each absent when empty (`homeSections`).
  An album can be on three shelves: the open sheet is the shelf's whose tile was pressed. `HomeShelf`'s row has
  `ArtCarousel`'s `animateContentSize` (B-134). Measured on the VM AVD (frames, 360x800): the header under a shelf moved
  over 7 to 11 in-between frames with it, and over 6 to 8 WITHOUT it, because the shelf item's own `animateItem()`
  already animates the next item's placement; the snap the owner saw was not reproduced here, so judge it on the phone.
  **One avatar slot** (`HeaderAvatar`, `SearchHeaderAvatar`): the monogram's edge is the title's, 16 dp in, and Search's
  header row is the app bar's 64 dp so the avatar is at the same height (uiautomator: node (36,175)-(180,319) px on all
  three pages at density 3). **Back goes Home**: a tab tap leaves `[Home]` or `[Home, tab]` (`stackAfterTap`, applied by
  `becomeStack`, the smallest edits, never `clear()` then add), `NavDisplay`'s own pop does the rest (so Back on Search or
  the Library is the ordinary pop with its predictive animation, and only Home leaves the app); a tab's own page is still
  a root for the transitions (`isRootPage`); a restored stack that starts with Search or the Library gets Home put under
  it once (`normalizedStack`, before anything reads it). **Anything outside `NavDisplay` that takes Back must be added
  AFTER it**: the dispatcher asks the last-added handler, and with Home under them `NavDisplay`'s handler is enabled on
  Search and the Library, so the avatar menu's handler, composed for the drawer's whole life, lost to it (one Back popped
  the page under the open menu; measured). It is composed only while the menu is open now. The fallback the plan named (a
  Back handler that switches to Home) was not needed: the walk passed on the AVD in portrait and landscape (rail).
- **The cover ambient under the bottom bar lives in `RootBottomBar`, not in `BottomBar`.**
  `scrimColor ?: LocalCoverAmbientBottom.current ?: colorScheme.surface`, a deferred-read
  `shadowFadeProgress` gradient in `drawBehind`, `expressiveBlurEffect`, and a three-way
  `containerColor`. That separation is what let the fork's floating capsule bar be dropped for
  upstream's docked one without losing the theming — verified by measurement, on two albums 174°
  apart in hue, with the bar landing within 3° of its page each time.
- **`Screen.ExternalArtist` / `Screen.ExternalAlbum` are deliberately not overloads of
  `ArtistDetail` / `CollectionDetail`.** Those take Navidrome ids and load from Room, and keeping
  them strict is what makes their not-in-the-DB error path correct.
- **Keep AudioMuse fail-soft** (grey out and fall back to Tier 1 when the plugin or index is
  missing) and lb-bot **stricter**: switched off — by the user, or by the hub (its proxy off:
  `/lb/status` `configured: false`, or an advert of `available: false` with **no** routes) — and
  unindexed render **nothing**. The artist page must then look exactly as it does without that
  layer; `LbBotManager.isConfigured` is the one gate for both. **Unreachable is different since
  the index mirror (§5):** the mirrored shelf and every cached lb-bot row keep showing while
  lb-bot is down, because answering without it is what the mirror and the response cache are
  for. Only what needs a live lb-bot — the network discography fallback, fetches, the probe-gated
  Fresh tab — goes away. An enabled proxy always advertises its routes, which is how "down" is
  told from "off"; an empty list alone still means "a hub too old to say" to `advertisesRoute`.
- **WebSocket pings are set on the engine, not the plugin.** On the OkHttp engine (Ktor 3.5.2,
  OkHttp 5.3.2) `install(WebSockets) { pingIntervalMillis = … }` sends nothing: Ktor's session only
  reports the interval of the OkHttpClient underneath, 0 by default, so a half-open hub socket
  blocked `incoming` until something else noticed. Real pings need
  `engine { config { pingInterval(10, SECONDS) } }`: `pingingWebSocketClient()`
  (`androidMain/.../domain/manager/HubSocketClient.android.kt`) for every cast bridge, and for
  `HubManager`, which is commonMain and cannot name OkHttp, through `HubSocketClientFactory`
  (registered per platform; §2 rule 3). OkHttp fails a socket whose pong is a full interval late.
- **Cast requires publicly reachable stream and cover URLs** — the speaker fetches them itself, so
  a Tailscale or LAN address will not do.
- **The fork's UI rules the phone taught (I-039, 2026-10-16).** The pass rebuilt every surface on
  upstream's parts and then came back for motion, place and continuity:
  - **A chip that swaps a list's rows resets the scroll in the same frame** (`requestScrollToItem(0)`, Saved queues,
    B-161). `LazyColumn` keeps the first visible KEY in place, so rows that came in above the row on screen arrived out
    of sight and the chip looked inert on the way back; with the reset, rows that leave fade, the ones that stay slide,
    the ones that come in fade in, wherever the page was scrolled. The list is clipped to its bounds: a row animating
    in from above the view otherwise drew through the top bar.
  - **A glitch is fixed without removing the animation.** Material 1.5's `FilterChip` eases its own
    check in (fastSpatial, which overshoots) and out (defaultEffects); two chips trading it briefly
    overflowed a full wrapping row. `PageChips` gives wrapping rows one spec for both (B-130); the
    first fix snapped the size and was sent back for losing the animation.
  - **A parameter change keeps what is shown until the new answer lands**: `ListeningStats` holds the
    last summary and unowned list while the next period's are read, dims them, then changes in
    place (`animateItem`, `RankingItem`'s eased bar) (B-135). The view model still clears.
  - **What is sized by the cards in view eases its size, both ways**: `ArtCarousel`'s row
    (`animateContentSize`) (B-134).
  - **A tap in a list never moves the list**: the queue skips its scroll-to-playing for a track change
    made by tapping one of its rows (B-132).
  - **An action sits beside what it acts on**: "Save as a mix" saves the queue's recipe, so it is in
    the queue's save menu (`QueueSaveButton`), not on Mixed for You; and one glyph per meaning
    (Discover's compass, Journey's route).
  - **A fork page that duplicates an upstream page becomes part of it**: My Listening is Stats' tabs
    (§5), and upstream's own entry, the home's Stats card, follows the history (Q-103).
- **Page, cover and sheet timing follow the animation style (I-042).** `AnimationStyle` has a
  third entry, **Quick**, appended last: the enum is stored by ordinal
  (`BasePreferenceManager.getEnum`), so it is append-only and never reordered (`PageMotionTest`
  locks the order). Every duration is a field of `PageMotion` (`ui/util/PageMotion.kt`, no Compose
  imports): `DefaultPageMotion` is what Expressive and Standard have always used and must not
  move; `QuickPageMotion` is a starting proposal the user tunes on the phone, one number per edit.
  Components keep `MotionScheme.expressive()` under Quick (`Theme.kt`; `standard()` is the
  one-line alternative).
  Page pushes and pops: `NavDisplay`'s `transitionSpec` / `popTransitionSpec` in `App.kt` read
  the style inside the lambda and build `Material3Transitions.enter` / `exit` / `popEnter` /
  `popExit(motion)`; the old `SharedXAxis*` vals are the same specs at the default timing.
  The cover's shared-element flight: the three sites (`ArtGrid`, the collection heading, the
  image viewer) take `rememberCoverBoundsTransform()`, so the cover lands with the page. A new
  shared-element cover goes through it too, never a hard-coded `tween(500)`.
  Sheets: the shared `ModalBottomSheet` wrapper takes its show / hide times from it (so the
  player and queue scenes too); only the times, the one-motion opening is unchanged.
- **The `Form` family is gone and is not to come back.** Upstream deleted `Form`, `FormRow`,
  `FormTitle`, `FormButton`, `Dropdown`, `SettingSelectionRow`, `SettingSwitch` and
  `SettingSwitchRow` in `81e72ffa`; the fork carried them on for nineteen screens until I-039
  rebuilt those on upstream's parts and deleted them (with `SelectionDropdown`). A settings row is a
  `SettingsGroup` of `SettingsToggleItem` / `SettingsChoiceItem` / `SettingsNavItem` /
  `SettingsSliderItem`; a list row is a `SegmentedListItem` with `segmentedShapes`; a menu is M3's
  `DropdownMenu` or, on a row, a sheet on a long press; a dialog's buttons are
  `SegmentedListButton`s; a one-of choice on a page is `PageChips`. A merge that brings any of the
  eight back is a merge to redo.

- **Search is where finding starts (P3).** With the field empty: recent searches (in memory, up to ten), then
  Browse, the cards in this order and with these gates (`browseTiles`): Genres (always), Charts and Editors'
  picks (`isConfigured && advertisesRoute` of the Deezer routes: the empty advert reads as capable), Fresh
  (`lbBot.available`), Discover (lb-bot available or AudioMuse configured, full width); an odd last half card
  spans both columns. **Discover and Fresh are no longer library-home buttons**; Genres is both a card and a
  Library shelf. The library home gained **Songs** and **Radios** (`OVERVIEW_PRIMARY_MAX` 9): both pages were
  hidden tabs, and with the tab editor gone (P1) Radios had no way in at all and Songs only filtered "See all"s.
  The grid has ONE item that is always first (`key = "top"`): the grid keeps its first visible key in place, and
  with the unowned header first while the library was out, the library rows landed above the view and the page
  opened scrolled down. Charts and Editors' picks are `BrowseFeedScreen` (fifty albums, Deezer's genre chips
  sharing `deezerGenre` with Discover, a chip change keeping the tiles until the new ones land, Retry asking for
  the chips again). Discover's four unowned carousels became one stack with a reason per row
  (`discoverStack`: up to 3 Fresh, 3 similar artists, 2 chart, 2 editorial, dealt round-robin; only reasons
  something backs), above "From your library" (Mixed for You, ListenBrainz, Rediscover, Mood search).
  `DiscoverRows.kt`'s ids did not change (Feishin's twin). "Add from a link" (New sheet, Search's "Nothing for …")
  is paste / looking / failed in one sheet; "Paste from clipboard" reads the clipboard once, on the tap.

- **An unowned album is requested from its own page, in one box (P4).** The missing-album sheet is gone; every way
  to a release the library lacks (a discography row on either artist page, Fresh, Discover, Search, a pasted link,
  Downloads' Open album) opens `Screen.ExternalAlbum`, whose request box walks Idle, Searching, Choosing, Starting,
  Active (five steps), then Done, Failed or Attention (`requestStage`, pure). The rules, each with its reason:
  - **The source search does not start by itself** (`SOURCES_AUTO_START = false`): it is a 30 to 90 s slskd fan-out
    holding one of the hub's four proxy slots, and browsing five albums would hold five. One search per tap; an
    answer for an edition no longer on screen is dropped, and sources found for another edition are not selectable.
  - **A source is selected for the user only when lb-bot recommends it AND verified the artist AND reads the folder
    as this album** (`preselectedSource`); otherwise "Request this album" waits for a tap, which is consent.
  - **A running fill is the truth whatever else is set** (started here, on another device, before a restart): the
    box shows it, and `request()` refuses a second POST in the tap, as `starting` is taken in the tap.
  - **The box keeps its place.** A request on its way keeps its cards on screen, disabled; when the box changes kind
    of stage with its top scrolled off, the page brings it back (`onStageKindChanged`). Measured on the AVD: without
    either, the page jumped to the top on the tap, then sat on the tracklist with the box out of sight. Known on the
    debug AVD: one frame of the tracklist before the scroll back begins (check on the phone's release build).
  - **Recovery says only what is backed**: Retry's "From <peer>" is `LbFillEntry.retryPeer`, the peer a plain retry
    really re-sends; lb-bot's `reason` verbatim; no "N left", no "files kept". Allow MP3 only in recovery, where
    `mp3WouldHelp` is the evidence. The buttons go through `FillActions`, the Downloads page's handlers.
  - **The album About** is `/lb/meta/album`, under the box; nothing from lb-bot is no row and no gap.
  - **An unowned artist is scanned on arrival, once per visit** (a flag in `ExternalArtistViewModel`, never retried
    by itself; "Try again" scans once), and the page says which of scanning, scan failed, no releases or releases it
    is (`artistPageState`). It wears the playing song's colours (`Washed`, `WashedBrowsingScreens`); no portrait.
    On the VM's scratch hub the rows took ~61 s after a 1 s scan: the hub caches the pre-scan discography read for
    60 s and gets none of lb-bot's push frames, whose `artistScanned` clears that cache in production.
  - **The discography is rows with a pill** on both artist pages (`ReleaseRows`, `releaseState`); the corner one-tap
    acquire left this shelf (Discover keeps it). A partly-owned row reads "9 of 12 tracks" over a bar. A base state's
    pill sits at the row's end; a request's longer sentence goes under the line, where it cannot crush the title.
  - **Round 2, from the owner's mockups (artist page, fill gaps, storyboard D).** The owned artist page shows a few of
    each and links to the whole: Play, Shuffle, download; Frequently played as five compact rows with "See more" (the
    artist's Songs page); Popular releases (`popularReleases`: most played, then just landed, then the newest missing
    albums) with "See discography". Without lb-bot those rows are the library's albums (`ownedSections`), with no
    pills, since each would say "Owned". The discography page (`Screen.ArtistDiscography`, washed like the Songs page)
    and the unowned artist page draw `DiscographyBrowser`: type chips with counts, an ownership menu only when the
    discography holds more than one kind, a sort (Most played is left out where there are no plays), a rescan capsule.
    An ownership filter flattens the list under a heading that counts the matches, and each row names its type.
  - **Fill gaps is a box on the owned album page, not a sheet** (`GapBox`, `gapStage`). The line ("N tracks missing",
    Fill gaps, a chevron) unfolds to the missing songs and the tagged-release note with Wrong edition?; Fill gaps turns
    the box to the ranked sources (the album box's `SourceCards`, Request under the chosen card, preselection by the
    same rule), a request to the request box's steps, and the finish to a result row that stays `GAP_RESULT_HOLD_MS`
    before the box folds away. A failed fill keeps the box open with lb-bot's reason, also over the sources view it
    was requested from (measured on the AVD: the poll's summary drops the source rows, and the sources view read as
    searching for good). The album sheet's Fill gaps on the artist page opens the album with the box unfolded
    (`Screen.CollectionDetail.openGaps`). The group stays the page's for the visit even after the mirror flips the
    release to complete, so the result row is still owed. Source cards: the selection eases (one fraction for the
    tone and the outline) and a card's own height eases as its listing opens.
  - **Round 3, the owner's notes on round 2.** Every chip or sort change on `DiscographyBrowser` replays the rows'
    arrival (the list is composed under `key(filter)`, so its arrival gate starts empty; the ownership menu already
    did, by switching branches). A fill that lands while the album page is open re-reads the tracklist: from Room
    when the landing sync names the album (`libraryBumps`, `EVENT_LOCAL_SYNCED`), and from Navidrome 5, 15 and 45 s
    after `gapFillLanded` turns true during the visit, stopping once the album holds `heldTracks`. A request that
    lands on the unowned album page follows it to the album: the page re-reads the index mirror on each library bump
    and every 5 s for 3 minutes, pulls the named album into Room if the landing sync has not (CollectionDetail reads
    Room), and replaces its own back-stack entry with the album's. The artist Songs list ("See more") is scoped to
    the artist again (`SongListArtist`): upstream's paging revert (d6671510) had dropped the artist id, so it opened
    the whole library. The Songs lists (the artist's and the library's, `SongListScreenItem`) draw the artist page's
    rows: rounded segments in a 16 dp gutter, 2 dp apart, on `SongRowDefaults.frostedContainerColor()`.

---

## 8. Publishing

```bash
./gradlew :androidApp:assembleRelease          # green before pushing
git push origin navi-connect
```

- **Never `git push --tags`** (§2, rule 13).
- Working notes, audits and design handoffs are `.gitignore`d on purpose: the durable content is
  here and in `README.md`, and the dated notes live beside the tree in navi-connect's working
  folder, which is not a git repo.
- `.github/` is upstream's and is left alone so merges stay clean; the fork's README is at the repo
  root, which GitHub prefers over `.github/README.md`.
