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
| Shared session | `domain/manager/HubManager.kt` (act helpers, `resolveQueue`, remote mirror), `androidMain/.../shared/RemoteSessionPlayer.kt` (a media3 `SimpleBasePlayer` facade so the notification and Bluetooth keys drive the *remote* session) |
| Player | `shared/MediaPlayer.kt` (blended `uiState` + raw `localUiState`), `androidMain/.../shared/MediaPlayer.android.kt` |
| Chromecast | `androidMain/.../domain/manager/cast/*` — `CastDiscovery` (`NsdManager`), a hand-rolled castv2 client (`CastChannel`/`CastProtocol`/`CastPayloads`), `CastDeviceBridge`, `CastBridgeManager`. No Cast SDK, no Play Services. Scrobbling in `domain/manager/CastScrobbler.kt` |
| AudioMuse-AI | `domain/manager/{AudioMuseManager,RadioManager}.kt`, `domain/models/settings/{AutoplayMode,MoodCharacter}.kt`, `ui/screens/nowPlaying/components/controls/{AdaptiveMoodBackground,NowPlayingAutoplaySelector}.kt` |
| lb-bot | `domain/manager/LbBotManager.kt` (the whole surface plus the persisted watch map; `applyAlbumStatus` / `applyGapSummary` are the ONE writer each, fed by the `/lb/fills` poll and the hub's `fill` frame alike), `ui/components/common/FillVocabulary.kt` (the acquisition vocabulary, PROTOCOL §15.2 — mirrored in Feishin's `fill-vocabulary.ts`), `ui/components/sheets/{MissingAlbumSheet,GapFillSheet,LbBotCommon}.kt`, `ui/screens/artist/components/DiscographyShelf.kt`, `ui/screens/fresh/*`, `ui/screens/external/*` |
| Discover | `ui/screens/discover/*` — `DiscoverRows.kt` (the row catalogue, **duplicated in Feishin**; see below), `DiscoverScreen.kt`, `viewmodels/DiscoverViewModel.kt` |
| Saved queues | `domain/repositories/SavedQueueRepository.kt`, `ui/screens/savedqueues/*` |
| Downloads | `domain/manager/{DownloadManager,PlaylistDownloadManager,DownloadForegroundController}.kt`, `androidMain/.../shared/DownloadService.kt`, `ui/screens/settings/DownloadCenterScreen.kt` |
| Mixed for You | `domain/models/Mix.kt`, `ui/screens/mixes/*` (`MixListScreen`, `MixFormSheet`, `MixPreviewSheet`, `MixArtwork`, `MixFormat`), `RadioManager.generate`/`regenerate`/`currentRecipe` |
| Native Navidrome API | `domain/manager/{NativeApiManager,AlbumModeSmartPlaylists}.kt` — smart playlists (create, **read rules, update**), album-mode expansion, and "Appears on" (Subsonic's `getArtist` is album-artist only) |
| Colour engine | `ui/util/CoverColorScheme.kt`, `ui/components/common/CoverAmbientBackground.kt`, `ui/util/AmbientColorHolder.kt`, `ui/components/common/BlendBackground.kt`, `ui/components/common/blur/ExpressiveBlur.kt` |
| Playback reporting | `domain/manager/PlaybackReporter.kt` (OpenSubsonic `reportPlayback`, §4). **Two upstream files carry its wiring, and a merge must keep the fork's lines in both.** `androidMain/.../domain/manager/ScrobbleManager.android.kt` is upstream's `AndroidScrobbleManager` listener; it holds the `playbackReporter` field and `snapshot()`, the reporter call in each listener callback, the `nowPlayingReported` hook assignment and the `isRemoteActive` takeover collector in `init`, and `playbackReporter.release(snapshot())` in `release()` (B-009, B-027). Taking upstream's copy silently drops all reporting, the takeover close and the teardown `stopped`. `commonMain/.../ScrobbleManager.kt` holds the `nowPlayingReported` hook and its guard in `scrobbleNowPlaying` (B-027); lose them and the legacy now-playing ping comes back, resetting the entry's position to 0 |

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
    popped — and **a tab switch clears the whole back stack** (`BottomBar` / `SideBar`), so every
    root tab loses its ViewModel on every switch unless it opts out through
    `koinViewModel(viewModelStoreOwner = koinInject<PersistentViewModelStoreOwner>())`. The
    decorator and the opt-outs are ONE change upstream (`bbda3447`); restoring one without the
    other is how this fork briefly re-fetched Discover and Fresh over the network on every tab
    visit. The opt-out sites, all of which must survive a merge:

    | Site | Opts out | Source |
    |---|---|---|
    | `AlbumListScreen`, `ArtistListScreen`, `GenreListScreen`, `PlaylistListScreen`, `RadioListScreen`, `SearchScreen` | when `!nested` | upstream |
    | `SongListScreen` | when `!nested` (on the fork's `artistId` key) | upstream |
    | `StarredScreen`, `LibraryScreen` (its upstream ViewModels) | always | upstream |
    | `LyricsScreen` | always, keyed on the song | upstream |
    | `FreshScreen`, `DiscoverScreen`, `MixListScreen` | when `!nested` | fork — their `init` loads over the network |
    | `LibraryScreen`'s `SavedQueuesViewModel` | always | fork |

    Grep `PersistentViewModelStoreOwner` against both parents after a merge; a fork-only root tab
    added later needs its own row here. Anything else that must outlive its screen belongs in a
    process-scoped manager, never in a ViewModel on a popped entry. A ViewModel that must start
    such work itself shields it instead: `DownloadCenterViewModel` (a settings detail pane, not a
    tab) runs its cancel / retry / another-source / allow-mp3 / wishlist POSTs through
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
  the `NavDisplay` entry decorators (rule 14). Take the fork's whole and re-apply upstream's
  structural changes by hand.
- **`PreferenceManager.kt`**, **`SessionManager.kt`**, **`DownloadManager.kt`**,
  **`DbRepository.kt`**, **`SongDao.kt`** — union merges where both sides append. Usually textual
  collisions only; read them as unions rather than as choices.
- **`strings.xml`** — 237 fork keys against upstream's, historically with zero name collisions.

---

## 3. Building, signing, driving

```bash
./gradlew :androidApp:assembleRelease     # ~2 min → androidApp/build/outputs/apk/release/Navic.apk
./gradlew :androidApp:assembleDebug       # for correctness work
```

There is no `:composeApp:compileDebugKotlinAndroid` task. Gradle provisions its own JDK 21
toolchain — don't set `JAVA_HOME`. **Judge smoothness on release only**; debug Compose is
dramatically choppier and will mislead you.

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
- **Android Auto knows nothing about the hub.** Its `resolveStreamUrl` builds a *local* Navidrome
  URL, so browsing or playing from Auto during a remote session may start local playback. Untested
  as of alpha59.

The cast bridge's own rules — self-exclusion on reconnect, join-don't-launch, release by pausing
rather than stopping — are protocol-level and live in navi-connect's `CLAUDE.md` and `PROTOCOL.md`,
because both clients must agree on them.

---

## 5. Data and sync

- **Don't overwrite a track's artist with its album's.** The library sync used to, which discarded
  the full "A feat. B" credit Navidrome sends and made featured artists invisible app-wide. The
  album artist is a *fallback* for a track that names none.
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
- **A failed lb-bot poll is not an answer.** Collapsing it into the default `unknown` state makes
  it indistinguishable from "nothing is filling this". A failed poll goes through `noteError` — the
  row keeps its state and the Download Center says "Can't reach lb-bot — last checked Ns ago" after
  two missed ticks; a real `unknown` is bounded patience on the clock (30 s grace after the tap, two
  minutes continuous before `gave_up`). The poll is one `GET /lb/fills` for every due row, 5 s
  backing off to 10/20 s while the payload is unchanged, and 30 s while the hub socket is up and
  `fill` frames are arriving (`HubManager` sets `hubConnected`). Expiry is a settle measured from
  the row's last progress, never a delete. A terminal status stays in `_fills` so a sheet shows the
  outcome and re-enables its picker; the map used to keep the last live state forever.
- **A `/lb/fills` gap summary carries no source rows.** It is the gap view with `sources`
  dropped (only `sourcesTotal` / `sourcesFoundAt`), and `applyGapSummary` used to publish it
  whole — every poll tick blanked `GapFillSheet`'s picker, which then read `picking` with nothing
  to pick and told the user lb-bot needed a decision. It keeps the rows it holds while
  `sourcesFoundAt` is unchanged, and re-reads the full `/lb/gap` once when a summary reports
  sources it has never read. A sourceless `picking` is three cases (PROTOCOL §15): tracks reading
  `downloaded` await a manual match in lb-bot's workspace (the sheet says so and promotes "Open in
  lb-bot"), a `noSourceReason` is shown as lb-bot wrote it, and anything else says "search again"; and
  "Open in lb-bot" opens `<webUrl>/#/gaps/<groupId>` when the hub advertises `webUrl`.
- **A cancel is `cancelled`, not a retryable failure.** `canRetry` is false on a cancelled row —
  a cancel is restarted from the album page — and Cancel is offered from the server's own
  `cancellable` field, never on `placing`/`placed`. `cancelFill` answers "too late" when lb-bot
  says placement has begun, and applies lb-bot's status instead of settling the row as cancelled.
- **Android backup excludes the preferences file** — the one holding the Navidrome password, hub
  token and AudioMuse token. A restored install asks for the server details again, on purpose.
- **Release builds no longer trust user-installed CAs** (they moved to `debug-overrides`, since the
  base config applied them to every HTTPS destination the app touches). Plain-HTTP LAN servers are
  unaffected; a self-signed HTTPS server needs its certificate in the system store.

---

## 6. The colour engine

Every browsing and detail page is washed in the current artwork's colours. This is the fork's
largest UI divergence and the thing most likely to be broken by an upstream merge.

**The pipeline.** `ui/util/CoverColorScheme.kt`'s `rememberCoverColorScheme` fetches a palette,
picks a seed and derives a `CoverColors` (`scheme`, `seed`, `dominant`, `isDark`, `resolved`,
`themed`). `ui/components/common/CoverAmbientBackground.kt`'s `BrowsingAmbient` / `Washed` draws it:
blurred artwork plus a `coverAmbientGradient` scrim, with the seed read in the *draw* phase. The
library home, every root tab behind `App.kt`'s `Washed`, and both detail screens go through that
one path — not four copies.

Upstream's `rememberColorSchemeFromCoverArt` / `rememberDominantColorState` is the engine this
replaced. It dies with the composition (the fork carries a 128-entry palette cache plus a
derived-scheme cache, added because covers popped back to neutral while scrolling and
`RootBottomBar` is rebuilt inside every `Scaffold`) and it seeds from `dominantSwatch`, which
reliably picks a flat minority patch.

Seven rules, each of which is a bug that shipped:

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
   the `LaunchedEffect` key never changes again. Retried three times and logged now.
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
   screens, every dialog, and any page with no artwork to read.

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
**The limit:** detail and artist pages, Settings, the image viewer and a two-pane
`CollectionDetail` keep the base rail, so a light rail beside a dark artwork-themed *detail* page
is still expected (option 3, every screen publishing its scheme, would close it). A new washed tab
goes in `WashedBrowsingScreens` as well as behind `Washed`; `WashedBrowsingDriftTest`
(androidHostTest, JVM-only because it reads the source) fails when the two, the self-washing
`LibraryScreen` or the sheet list drift apart.

---

### Discover, and the table that is written twice

`ui/screens/discover/DiscoverRows.kt` has a twin in Feishin
(`renderer/features/discover/discover-rows.ts`). There is no shared build between
the repos, so the ids are kept in step by hand and are written down in
`navi-connect/CLAUDE.md` §6. The precedent is `MoodCharacter`, and it is a
cautionary one: same three presets both sides, matching numbers, different ids,
and the behaviour has already drifted (Feishin's auto-DJ escalates temperature
per pass; `RadioManager.topUp` does not). Change a row id here and there.

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
(`{kind, seedId, moodCharacter, count}`), held hub-side beside saved queues and
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
`ui/screens/mixes/MixFormSheet.kt` is the general path: name, kind, and a seed
picker that fits the kind — the playing track for `similar`, a searchable artist or
genre list out of Room for the two that resolve against it, a `MoodCharacter` for
`adaptive`, nothing for `fingerprint`. A sheet rather than a `FormDialog` because
two of the five need a searchable list and `FormDialog` is 300dp wide.

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
- **Leaving the now-playing sheet goes through `NowPlayingSheetController.requestHide { … }`.** The
  player is an `OverlayScene` bottom sheet, so `backStack.remove(Screen.NowPlaying)` plus
  `add(destination)` in one frame destroys it outright — the player vanishes and the next screen
  appears with no transition. `requestHide` takes the navigation as a continuation and runs it
  after the hide animation *and* after the pop (running it before would make the pop take the
  destination instead). The queue sheet drops both entries before pushing.
- **The bars share one tab helper.** Upstream writes the `NavbarTab.Id → NavigationTab` mapping out
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
  Order untouched, every other tab untouched, and a user who puts one back is not overruled on the
  next launch. The ids stay in `NavbarTab.Id` and in `NavbarConfig.default.tabs` — deleting them
  would make `merged()`'s live filter strip them from every stored config permanently.
- **The home page's top buttons are gated exactly like the tabs.** They are pushed onto the
  library's own stack, so each destination carries `nested = true` or it renders `RootTopBar` with
  no back arrow. The list is padded back out of Recently added / Starred / Frequently played until
  it is four long, because an odd count leaves a hole in the two-column grid and a home page with
  no library shortcuts has lost something it used to do.
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
- **Cast requires publicly reachable stream and cover URLs** — the speaker fetches them itself, so
  a Tailscale or LAN address will not do.
- `ui/components/common/Form*` components are carried as fork code; upstream deleted them and
  taking that deletion means rewriting nine settings screens.

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
