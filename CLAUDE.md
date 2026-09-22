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
| lb-bot | `domain/manager/LbBotManager.kt` (the whole surface plus the persisted watch map), `ui/components/sheets/{MissingAlbumSheet,GapFillSheet,LbBotCommon}.kt`, `ui/screens/artist/components/DiscographyShelf.kt`, `ui/screens/fresh/*`, `ui/screens/external/*` |
| Saved queues | `domain/repositories/SavedQueueRepository.kt`, `ui/screens/savedqueues/*` |
| Downloads | `domain/manager/{DownloadManager,PlaylistDownloadManager}.kt`, `ui/screens/settings/DownloadCenterScreen.kt` |
| Native Navidrome API | `domain/manager/NativeApiManager.kt` — smart playlists, and "Appears on" (Subsonic's `getArtist` is album-artist only) |
| Colour engine | `ui/util/CoverColorScheme.kt`, `ui/components/common/CoverAmbientBackground.kt`, `ui/util/AmbientColorHolder.kt`, `ui/components/common/BlendBackground.kt`, `ui/components/common/blur/ExpressiveBlur.kt` |

Two Room databases. **`CacheDatabase` is at 22** and is `fallbackToDestructiveMigration(true)` — it
is a cache, and saved queues re-reconcile from the hub. **`DownloadDatabase` (at 4) holds real user
data** and must never be treated that way.

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
- **`App.kt`** — every nav entry, the `Washed` wrapper on the root tabs, the composition locals.
  Take the fork's whole and re-apply upstream's structural changes by hand.
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
- **A failed lb-bot poll is not an answer.** Collapsing it into the default `unknown` state makes
  it indistinguishable from "nothing is filling this". Poll no tighter than 5 s and back off to
  10/20 s while the payload is unchanged.
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
   convincing way to conclude the wrapper was not the problem. The only legitimate nested use is
   `MoreButton`'s, for sheets that deliberately want the app's scheme rather than the page's.
7. **`dynamicTheming` gates the whole engine**, inside `rememberCoverColorScheme`, so one gate
   covers the home, every tab, both detail screens, the mini-player, the now-playing chrome and
   every sheet. It defaults **true** — a pref defaulting false would un-theme every existing
   install. While it is on, the *Theme mode* row and the theme picker are **greyed, not hidden**:
   artwork decides what a browsing page looks like, but those values still drive the settings
   screens, every dialog, and any page with no artwork to read.

**Known gap:** the `SideBar` is not themed. It sits outside `NavDisplay`, so it cannot read the
per-screen cover ambient and stays on the app's base scheme — a light rail against a dark
artwork-themed page. Cosmetic, tablet-only.

---

## 7. Conventions and gotchas

- **Navic files use tabs.** Multi-line edit matches are fragile; prefer matching single bare lines.
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
  missing) and lb-bot **stricter**: not configured, unreachable and unindexed all render
  **nothing**. The artist page must look exactly as it does without that layer.
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
