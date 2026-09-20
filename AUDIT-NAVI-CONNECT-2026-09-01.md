# navi-connect engineering audit

**Date:** 2026-09-01  
**Scope:** hub, Feishin hub/receiver/Cast paths, Navic hub/Media3/Cast/lb-bot/persistence paths  
**Method:** static code review of the selected working trees, protocol and handoff documents, current diffs, and available manual test harnesses

## Executive assessment

The codebase is substantially implemented and shows unusually strong awareness of distributed-playback failure modes. The hub has explicit transfer serialization, active-receiver authority, intent grace windows, saved-queue tombstones, Cast reachability, and load rollback. Both Cast bridges now embody lessons from real hardware: join before launching, compare stable track identity rather than signed URLs, pause rather than stop on release, serialize session setup, and verify command completion. The project is therefore beyond prototype quality in design intent.

The audit nevertheless found four high-severity correctness defects. First, both ordinary client receivers advertise `loadAck` but acknowledge a transfer before their local playback engines have actually accepted or prepared it. Second, the hub correlates `loaded` and `released` frames only by device, not by transfer attempt, allowing stale replies and an active-device ABA sequence to complete or roll back the wrong transfer. Third, Feishin's main-process transport callbacks operate on a mutable module-level socket; a late close or heartbeat from an old socket can clear or terminate a replacement connection. Fourth, Navic's Cast setup deadline is 25 seconds while the hub rolls a transfer back after 10 seconds, permitting a late Cast load to start audio after the session has already been reassigned.

The main security concern is configuration-dependent rather than an unconditional vulnerability: an empty `HUB_TOKEN` deliberately leaves the WebSocket control plane unauthenticated. The HTTP proxy correctly disables sensitive upstream relaying in that state, but any party that can reach the WebSocket port can still claim devices, replace an existing device ID, control playback, alter queues, and observe session metadata. For a service documented as publicly reachable through self-hosted infrastructure, warning-only behavior is too permissive.

No critical finding was assigned. The highest priorities are to make load acknowledgement truthful and correlated, eliminate Feishin's stale-socket callbacks, align all receiver work to a hub-supplied deadline, and fail closed on missing authentication unless an explicit insecure-local mode is enabled. The audit also identified medium-severity races in asynchronous queue publication, outbound frame generation, Cast request cancellation, and lb-bot ledger persistence.


## Severity model

| Severity | Meaning in this audit |
|---|---|
| Critical | Direct compromise or near-certain destructive failure with no meaningful prerequisite |
| High | Session corruption, silent playback failure, security exposure, or a reproducible race in a core path |
| Medium | Material reliability or data-integrity weakness requiring timing, failure, or unusual state |
| Low | Hardening, observability, maintainability, or bounded edge-case concern |

## Findings overview

| ID | Severity | Confidence | Component | Finding |
|---|---|---|---|---|
| NC-01 | High | Confirmed | Navic ordinary receiver | `loaded {ok:true}` is sent before Media3 accepts or prepares the queue |
| NC-02 | High | Confirmed | Feishin ordinary receiver | `loaded {ok:true}` is sent immediately after imperative player calls, not playback readiness |
| NC-03 | High | Confirmed | Hub protocol/transfer | Load and release acknowledgements have no transfer identity; stale replies and ABA rollback are possible |
| NC-04 | High | Confirmed | Feishin transport | Old socket callbacks mutate and can terminate the current socket |
| NC-05 | High | Confirmed | Navic Cast/hub | Cast setup may run 25 seconds despite the hub's 10-second load deadline |
| NC-06 | High | Configuration-dependent | Hub security | Empty `HUB_TOKEN` leaves the WebSocket control plane unauthenticated |
| NC-07 | Medium | Confirmed | Feishin renderer | Asynchronous queue publications can complete and arrive out of order |
| NC-08 | Medium | Confirmed | Feishin renderer | Hub directives are started concurrently without a command-generation or serialization policy |
| NC-09 | Medium | Confirmed | Navic hub client | Global `sendAsync` work can cross WebSocket generations and direct sends bypass its ordering mutex |
| NC-10 | Medium | Confirmed | Navic Cast transport | Closing a Cast channel does not immediately fail pending request waiters |
| NC-11 | Medium | Strong evidence | Navic Cast lifecycle | Channel access is mutexed, but high-level load/recovery/teardown work is not one serialized state machine |
| NC-12 | Medium | Confirmed | lb-bot ledger | Expired live watches are filtered out before the polling loop can settle them |
| NC-13 | Medium | Confirmed | lb-bot retry | Retry omits selected release-edition metadata and can resolve a different pressing |
| NC-14 | Medium | Confirmed | lb-bot state | Concurrent read-modify-write StateFlow map assignments can lose sibling updates |
| NC-15 | Medium | Confirmed | Android notifications | Terminal fill events emitted while the activity is stopped can miss their one-time notification |
| NC-16 | Medium | Confirmed | Database migrations | Broad `Throwable` catches treat every migration failure as "column already present" |
| NC-17 | Medium | Configuration-dependent | Android network policy | Cleartext and user-installed CA trust are enabled globally |
| NC-18 | Low | Confirmed | Hub delivery/observability | Required sends use the same exception-swallowing helper as best-effort broadcasts |
| NC-19 | Low | Confirmed | Persistence | Saved-queue progress updates do not expose zero-row writes |
| NC-20 | Low | Confirmed | Android widget/lifecycle | Per-broadcast unmanaged `MainScope` and silent controller failures reduce diagnosability |

No finding was based solely on line-ending churn. The hub workflow diff appeared to be normalization noise rather than a semantic change, and CRLF-driven `git diff --check` warnings were excluded from the defect count.


## Detailed findings

### NC-01 — Navic acknowledges ordinary receiver loads before Media3 readiness

**Severity:** High  
**Confidence:** Confirmed  
**Affected code:** `navic/composeApp/src/commonMain/.../HubManager.kt`, `navic/composeApp/src/commonMain/.../MediaPlayer.kt`, `navic/composeApp/src/androidMain/.../MediaPlayer.android.kt`

`HubManager` advertises `loadAck`, invokes `mediaPlayer.loadRemoteQueue(...)`, and immediately sends success:

```kotlin
mediaPlayer.loadRemoteQueue(songs, index, positionMs, play)
sendLoaded(true, null)
```

The Android implementation returns before doing any work because it starts a `viewModelScope.launch`. It can then exit silently when the MediaController is absent or the input is empty:

```kotlin
viewModelScope.launch {
    val player = controller ?: return@launch
    if (songs.isEmpty()) return@launch
    // conversion and player calls happen later
    player.setMediaItems(mediaItems)
    player.seekTo(idx, positionMs)
    player.prepare()
    if (play) player.play() else player.pause()
}
```

This defeats the purpose of the hub's acknowledgement rollback. The system may commit Navic as active and display playback everywhere even though no player was available, media conversion failed, preparation failed, the stream was inaccessible, or the requested state was never reached.

**Recommendation:** Change `loadRemoteQueue` to a result-bearing asynchronous contract, preferably `suspend fun ...: LoadResult`. Allocate a monotonically increasing local load generation, cancel or supersede older loads, perform MediaItem conversion, apply the queue, and await a correlated Media3 outcome. Success should mean at minimum that the requested item is current and the player reached a viable state (`READY`, or a clearly defined buffering state with no error) before a deadline. Failure reasons should include no controller, no resolved tracks, conversion failure, player error, timeout, and supersession. Only then should `HubManager` send `loaded`. Do not let callbacks from an older generation mutate or acknowledge a newer load.

### NC-02 — Feishin also acknowledges local loads before engine readiness

**Severity:** High  
**Confidence:** Confirmed  
**Affected code:** `feishin/src/renderer/features/hub/hooks/use-hub.tsx`

Feishin has better resolution failure handling than Navic, but still sets `loadOk = true` after imperative queue/seek calls and immediately sends `loaded`:

```typescript
setQueue(songs, msg.index ?? 0, targetSec, !wantPause);
loadOk = true;
...
hub?.send({ ok: loadOk, t: 'loaded' });
```

The same-queue branch similarly marks success after `mediaPlayByIndex` or `mediaSeekToTimestamp`. The code's own delayed seek and pause workarounds demonstrate that these player operations complete asynchronously. The acknowledgement therefore proves that commands were issued, not that playback was established.

**Recommendation:** Introduce a receiver-load coordinator in the renderer. Give every inbound load a local generation, resolve its tracks, issue queue operations, and await player events proving the target index/source became current and no terminal load error occurred. For a paused transfer, verify positioning and a non-playing final state; for a playing transfer, require a viable loading/playing state according to the engine's event model. Time out inside the hub deadline and acknowledge only the current generation. This coordinator should also own pending seek cleanup and replace the current fixed-delay success assumption.

### NC-03 — Transfer acknowledgements are not correlated to a transfer attempt

**Severity:** High  
**Confidence:** Confirmed  
**Affected code:** `hub/hub.py`, protocol load/release frames, both clients

The hub stores one `load_future` and one `release_future` per device. Any `loaded` frame from that device completes the current future:

```python
elif t == "loaded":
    fut = dev.load_future
    if fut is not None and not fut.done():
        fut.set_result(msg)
```

The frames carry no transfer identifier. A delayed `loaded` from an earlier attempt can therefore satisfy a newer attempt to the same device. The transfer lock serializes `_transfer`, but load acknowledgement waits outside that lock, so another transfer can begin while a prior load is unresolved.

Rollback is guarded only by active device ID:

```python
if s.active_device_id != target.id:
    return
```

That does not prevent an ABA sequence: transfer A targets device X; transfer B moves to Y; transfer C moves back to X; then A's delayed timeout/failure observes X active again and can roll back C. Replacing `target.load_future` also leaves the older waiter alive until timeout. Release frames have the same identity problem: a delayed duplicate from a prior handoff can complete a newly armed release future for the same device.

**Recommendation:** Add an opaque `transferId` generated by the hub for every handoff. Include it in `do:release`, `released`, `do:load`, and `loaded`; ignore acknowledgements that do not match the exact pending phase. Store pending transfers centrally rather than as unversioned device futures. Rollback must require both the same `transferId` and the same committed session generation, not merely the same active ID. On supersession, explicitly cancel and clear the older future. For compatibility, either negotiate a new capability such as `transferAckV2` or treat legacy uncorrelated receivers as best-effort without overlapping attempts.

### NC-04 — Feishin stale socket callbacks operate on the replacement socket

**Severity:** High  
**Confidence:** Confirmed  
**Affected code:** `feishin/src/main/features/core/hub/index.ts`

Every event callback closes over the module-level variable `ws`, not the socket instance created by that invocation of `connect()`. For example, the heartbeat calls `ws?.terminate()` and `ws?.ping()`, while an arbitrary socket's close callback always calls `scheduleReconnect()`, which sets global `ws = undefined`.

A plausible sequence is: socket A fails; reconnect creates socket B; A emits a late `close`; A's callback sets the global `ws` to undefined and stops the shared heartbeat. A stale interval created for A can also ping or terminate B. Settings changes call `stop()` and `start()` immediately, so the old socket's asynchronous close can disrupt the newly configured connection.

The same race can emit a synthetic `disconnected` frame after B is connected, temporarily clearing renderer state. `knownDevices` also survives a disconnect and can be mistaken for a fresh arbitration registry.

**Recommendation:** In `connect()`, capture `const socket = new WebSocket(...)` and use only `socket` inside its listeners. Add a connection generation counter. Every listener should return unless `socket === ws` and its generation is current before mutating global state or emitting transport status. Give each connection its own heartbeat timer or bind the global timer to a generation. `scheduleReconnect(socket, generation)` should clear global state only if that connection is still current. Clear or mark `knownDevices` unknown on current-connection loss, and emit registry invalidation to Cast arbitration.

### NC-05 — Navic Cast work can outlive the hub's transfer deadline

**Severity:** High  
**Confidence:** Confirmed  
**Affected code:** `navic/composeApp/src/androidMain/.../cast/CastDeviceBridge.kt`, `hub/hub.py`

Navic permits Cast session setup to run for 25 seconds:

```kotlin
private val SESSION_SETUP_TIMEOUT = 25.seconds
```

The hub waits only 10 seconds for `loaded`. It can roll the active slot back while the Cast bridge is still connecting, joining/launching, and loading. If the receiver starts after rollback, audio can play on a speaker that no client considers active. A late `loaded` is then either ignored or, without transfer IDs, risks interacting with a later transfer.

This directly violates the project's documented requirement to keep the whole Cast load inside `LOAD_TIMEOUT`.

**Recommendation:** Put one end-to-end deadline on each `do:load`, preferably sent by the hub as an absolute monotonic-relative `timeoutMs` or deadline budget. Navic should reserve time for sending the acknowledgement and use the remaining budget across connect, receiver status, launch/join, and media load—not 25 seconds per substage. On expiry or supersession, close/cancel the attempt and ensure it cannot issue a late `LOAD`. A protocol-level deadline is safer than duplicating constants between repositories.

### NC-06 — Empty hub token leaves playback control unauthenticated

**Severity:** High when reachable by untrusted networks; otherwise Medium  
**Confidence:** Confirmed/configuration-dependent  
**Affected code:** `hub/hub.py`, deployment configuration

WebSocket authentication explicitly succeeds when `HUB_TOKEN` is empty:

```python
token_ok = not TOKEN or hmac.compare_digest(str(msg.get("token") or ""), TOKEN)
```

Startup only warns. Sensitive HTTP proxies correctly disable themselves without a token, but the WebSocket remains a complete playback-control and metadata channel. An attacker with network access can register a controller, inspect device/session data, alter the queue, transfer playback, and claim an existing device ID; registration intentionally supersedes an existing socket with code 4003.

The token also travels in the initial application frame. On `ws://` rather than `wss://`, it is observable to an on-path party. Logs reveal the first four characters of a rejected supplied token, which is unnecessary even though it does not print the expected secret.

**Recommendation:** Fail startup when `HUB_TOKEN` is absent. If unauthenticated LAN development is genuinely needed, require an explicit flag such as `HUB_ALLOW_INSECURE_NO_AUTH=true` and, by default, restrict that mode to loopback. Document TLS termination as mandatory for any non-local deployment and reject or loudly gate public `ws://` examples. Stop logging any submitted token prefix; log only length and device name. Consider rate limiting failed handshakes and validating device IDs before allowing replacement.

### NC-07 — Feishin queue publication has a latest-wins race

**Severity:** Medium  
**Confidence:** Confirmed  
**Affected code:** `feishin/src/renderer/features/hub/hooks/use-hub.tsx`

`publishQueue()` captures state, updates `lastQueueSig`, then asynchronously awaits `buildHubTracks(items)`. Multiple invocations can resolve out of order:

```typescript
void (async () =>
    hub.send({
        // captured index/position metadata
        tracks: await buildHubTracks(items),
    }))();
```

If publication A starts, publication B starts with newer queue state, B resolves first, and A resolves later, the hub ends on stale queue A. Because the signature was advanced before either send, normal dedupe may suppress a corrective publication. `routeLocalPlayToRemote` has the same pattern.

**Recommendation:** Increment a publication generation before each async build. After the await, compare it to the latest generation and re-read the authoritative queue/index/status before sending. Alternatively, serialize builds through a latest-wins worker that coalesces pending state. Update `lastQueueSig` only after the current publication is actually accepted for sending; roll it back or schedule a retry when transport reports failure.

### NC-08 — Feishin executes hub directives concurrently

**Severity:** Medium  
**Confidence:** Confirmed  
**Affected code:** `feishin/src/renderer/features/hub/hooks/use-hub.tsx`

Inbound directives are dispatched as independent promises:

```typescript
} else if (msg.t === 'do') {
    void handleDo(msg);
}
```

A slow `load` or `queueChanged` resolving tracks can overlap a later seek, pause, release, or another load. The older operation can finish last, reset `hubDrivenUntil`, replace the queue, arm a stale pending seek, or send an acknowledgement after newer intent. The single mutable `pendingSeek` is not sufficient command correlation.

**Recommendation:** Route directives through a receiver command executor. Preserve strict order for ordinary commands, while allowing a new `load` or `release` to supersede/cancel older load-resolution work. Tie pending seeks, delayed pauses, and acknowledgements to the command/transfer generation. Once superseded, an operation must not mutate the player or send success.

### NC-09 — Navic outbound frames can cross connection generations

**Severity:** Medium  
**Confidence:** Confirmed  
**Affected code:** `navic/composeApp/src/commonMain/.../HubManager.kt`

`sendAsync` launches one coroutine per frame and serializes only those calls with `sendMutex`. `sendFrame` resolves the mutable `wsSession` when the coroutine eventually runs:

```kotlin
private suspend fun sendFrame(obj: JsonObject) {
    wsSession?.send(Frame.Text(obj.toString()))
}
```

A frame created under socket A may wait, reconnect may install socket B, and then the old frame can be sent over B. That can replay stale queue mutations or control intent into a new hub session. Direct sends for hello, pings, reports, release acknowledgements, saved-queue sync, and other paths bypass the `sendAsync` mutex, so the comment that fire-and-forget sends reach the hub in order is not a global guarantee. A null session silently drops frames.

`restart()` correctly uses `cancelAndJoin`, which reduces duplicate sockets, but it does not bind queued global-scope send coroutines to the old connection.

**Recommendation:** Give each connection a dedicated outbound `Channel<Envelope>` and one writer coroutine that owns that exact `WebSocketSession`. Tag envelopes with connection generation and delivery policy. Cancel/drain the writer on disconnect; reject stale transient acts rather than forwarding them to a replacement socket. Intentionally replay only durable synchronization, such as saved-queue reconciliation, after a new welcome. Make send return a result for user actions and critical acknowledgements.

### NC-10 — Cast channel closure leaves pending requests sleeping until timeout

**Severity:** Medium  
**Confidence:** Confirmed  
**Affected code:** `navic/composeApp/src/androidMain/.../cast/CastChannel.kt`

`requestRaw` installs a pending waiter and relies on a response or timeout. `markClosed()` closes the socket and emits `_closedEvents`, but does not complete pending waiters. All in-flight operations therefore remain suspended until their individual timeout even though failure is already known. During a transfer, this can consume most or all of the hub's deadline and delay recovery.

**Recommendation:** Represent pending requests as `CompletableDeferred<Result<JsonObject>>`. In `markClosed`, atomically copy and clear the map under `pendingMutex`, then complete every waiter with a connection-closed failure outside the lock. Make close idempotent and ensure request `finally` removal tolerates an already-drained entry. Include request type and elapsed time in structured logs.

### NC-11 — Navic Cast lifecycle lacks one high-level serialized state machine

**Severity:** Medium  
**Confidence:** Strong evidence; runtime stress test recommended  
**Affected code:** `CastDeviceBridge.kt`, `CastBridgeManager.kt`

`castMutex` protects access to `channel`, but setup, load, ticker-driven recovery, status callbacks, closure handling, and teardown are broader operations with side effects outside that critical section. A stale callback can initiate recovery while another load is establishing a new session; teardown can announce the app gone and clear state after a newer channel was installed. `CastBridgeManager.stop()` launches asynchronous destruction and returns immediately, permitting a restart/reconfiguration to overlap old bridge cleanup.

**Recommendation:** Model each bridge as a single actor/event loop with explicit states such as Disconnected, Connecting(generation), Joined(generation), Loading(transferId), Ready, Releasing, and Stopped. Feed hub commands, channel closure, status updates, and discovery changes into that actor. Only the current generation may publish reports or mutate the adopted session. Make manager shutdown suspend until every bridge actor terminates, or return a `Job` that callers must join.

### NC-12 — Expired lb-bot watches vanish instead of settling

**Severity:** Medium  
**Confidence:** Confirmed  
**Affected code:** `navic/composeApp/src/commonMain/.../LbBotManager.kt`

`loadWatches()` removes unsettled watches older than `WATCH_TIMEOUT_MS`:

```kotlin
.filterValues { watch ->
    if (watch.settled) now - watch.finishedAtOrStart() < LEDGER_RETAIN_MS
    else now - watch.startedAt < WATCH_TIMEOUT_MS
}
```

The poll loop later tries to locate expired live watches and settle them as `OUTCOME_GAVE_UP`, but it obtains `live` from `loadWatches()`. The expired entries have already disappeared, so they cannot be settled, persisted as terminal history, or announced.

**Recommendation:** Never apply timeout retention to unsettled rows in the deserializer. Return them to the poll loop, explicitly transition them to a terminal outcome, persist that transition, and only then apply terminal retention. Separate `decodeAll`, `settleExpired`, and `pruneSettled` so policy order is unambiguous and unit-testable.

### NC-13 — lb-bot retry can select a different edition

**Severity:** Medium  
**Confidence:** Confirmed  
**Affected code:** `LbBotManager.kt`, persisted `LbWatch`

The initial album fill receives `releaseMbid`, but retry calls `download` with only release-group ID, quality, and optional Soulseek peer/folder. It does not reconstruct the selected edition metadata. The server may therefore run its own resolution again and choose a different pressing, undoing the edition picker and reintroducing the exact ambiguity the current design was meant to eliminate.

**Recommendation:** Persist an edition snapshot in every album watch: `releaseMbid`, artist, title, canonical track count, and any other fields accepted by the download API. Retry must resend this snapshot. Version the serialized watch format and migrate older entries gracefully; if an old watch lacks the data, label the retry as "re-resolve edition" rather than pretending it is identical.

### NC-14 — Concurrent lb-bot map writes can lose updates

**Severity:** Medium  
**Confidence:** Confirmed  
**Affected code:** `LbBotManager.kt`

Polling runs with bounded concurrency, while map state is updated using read-modify-write assignments such as:

```kotlin
_fills.value = _fills.value + (watch.key to status)
_gaps.value = _gaps.value + (watch.key to gap)
```

Two coroutines can read the same old map and each publish a map containing only its own addition, losing the other's update.

**Recommendation:** Use `MutableStateFlow.update { it + (...) }` if supported on all KMP targets, or guard all related maps with one state mutex. Apply the same rule to removals and multi-map transitions so UI state cannot expose half a settlement.

### NC-15 — Fill outcome notifications can be missed in the background

**Severity:** Medium  
**Confidence:** Confirmed  
**Affected code:** `androidApp/MainActivity.kt`, `LbBotManager` event delivery

`fillEvents` is collected only inside `repeatOnLifecycle(Lifecycle.State.STARTED)`. A terminal event emitted while the activity is stopped is not replayed, so the persisted ledger records completion but the promised one-time Android notification may never be delivered.

**Recommendation:** Persist notification delivery state with the ledger, for example `notifiedAt` or a pending terminal-event row. A process-lifetime observer or WorkManager job should deliver pending notifications when permission permits, and foreground reconciliation should scan for settled-but-unnotified rows. Mark delivery only after the notifier accepts the request. This also makes behavior robust to process death, not just activity backgrounding.

### NC-16 — Migration code masks every failure as a duplicate column

**Severity:** Medium  
**Confidence:** Confirmed  
**Affected code:** `navic/composeApp/src/commonMain/.../data/database/DownloadMigrations.kt` and similar cache migrations

Migration statements catch `Throwable` and assume the column already exists. That also swallows malformed SQL, a locked or corrupt database, storage I/O errors, cancellation, and platform defects. The migration can appear successful with a partially upgraded schema and fail later in unrelated DAO code.

**Recommendation:** Inspect `PRAGMA table_info` before adding columns, or catch only the driver's specific duplicate-column exception. Never catch `Throwable`; preserve cancellation and fatal errors. Execute the migration transactionally, validate the expected final schema, and add migration tests from every supported historical schema.

### NC-17 — Android network policy is globally permissive

**Severity:** Medium in hostile/local-shared networks; otherwise Low  
**Confidence:** Confirmed/configuration-dependent  
**Affected code:** `androidApp/src/main/AndroidManifest.xml`, `res/xml/network_security_config.xml`

The app globally enables cleartext traffic and trusts both system and user-installed CAs in its base configuration. This supports self-hosted LAN servers, but also means credentials, Subsonic tokens, stream URLs, hub actions, and lb-bot/AudioMuse-derived traffic may traverse HTTP when users configure it that way. Global user-CA trust also expands interception possibilities for every HTTPS destination used by the app.

**Recommendation:** Default production traffic to TLS. If arbitrary LAN HTTP endpoints are a required product feature, make insecure-server enablement explicit per server and show a warning; Android's static network security config cannot perfectly express arbitrary runtime hosts, so consider a narrowly scoped client policy rather than a globally permissive base config. Keep Cast's trust-all TLS implementation isolated to the Cast v2 LAN socket and add a test preventing its socket factory from entering general HTTP clients. Review backup rules so server credentials and hub tokens are excluded from cloud/device backup where appropriate.

### NC-18 — Required hub sends silently collapse into timeout

**Severity:** Low  
**Confidence:** Confirmed  
**Affected code:** `hub/hub.py`

`_send` catches every send exception and returns no result. That is appropriate for broadcasts but ambiguous for `do:release` and `do:load`. Transfer logic then waits the full phase timeout even when delivery failed immediately, and logs only a generic timeout.

**Recommendation:** Split `_send_best_effort` from `_send_required`. The latter should return success or raise a typed delivery error. Abort or roll back a transfer phase immediately on failed delivery, cancel its pending future, and include target/phase/transfer ID in logs and client-facing errors.

### NC-19 — Saved-queue progress updates cannot detect missing rows

**Severity:** Low  
**Confidence:** Confirmed  
**Affected code:** `SavedQueueDao.kt` and repository callers

`updateProgress` returns `Unit`, so a write affecting zero rows is indistinguishable from success. A deleted, missing, or failed-to-restore queue can appear updated in memory while persistence contains no row.

**Recommendation:** Return the affected-row count. On zero, either recreate from a complete known snapshot or explicitly detach the stale saved-queue ID and log a structured warning. Add a repository test for delete/update races.

### NC-20 — Widget asynchronous failures are hard to observe

**Severity:** Low  
**Confidence:** Confirmed  
**Affected code:** `NowPlayingReceiver.kt`, `WidgetControlReceiver.kt`

`NowPlayingReceiver` creates a new unmanaged `MainScope()` per broadcast. `goAsync()` is correctly finished, but cancellation and failures are not tied to a retained application scope or logged. `WidgetControlReceiver` suppresses controller creation failure and unavailable-command outcomes, so a dead widget button has no diagnostic trail.

**Recommendation:** Use an injected or application-owned SupervisorJob scope with bounded execution, while still honoring `goAsync()` deadlines. Log controller connection failure and command unavailability at an appropriate non-noisy level. Preserve the current correct behavior: snapshot-only updates, unique data URIs for album PendingIntents, command availability checks, and short-lived controller release.


## Cross-component recommendations

### Protocol correctness

The protocol should move from device-scoped acknowledgements to operation-scoped acknowledgements. A transfer is a distributed transaction with two phases: release the old owner, then establish the new owner. Give it an immutable ID, carry that ID through every directive and reply, and define exactly when an operation is considered committed. The hub should reject duplicate/stale phase replies idempotently. A session revision alone is useful but insufficient unless receivers echo the revision associated with the directive.

Define `loaded.ok` in observable terms. It should not mean "the receiver called its player API." For a local player, it should mean the target item has been selected and the engine reached the protocol's accepted ready/buffering state without an error. For Cast, it should mean the media receiver returned a successful status for the intended content ID. The acknowledgement should carry the resolved index/content identity and actual play state so the hub can detect a receiver acknowledging the wrong queue item.

Make deadline behavior part of the wire protocol. The hub owns `LOAD_TIMEOUT`, but a receiver needs the remaining budget to stop sub-operations before rollback. Send `timeoutMs` with `do:load`, measured from dispatch, and require a receiver to respond before it expires. If clocks need not be synchronized, a relative budget plus transfer ID is sufficient. The hub must ignore all late replies after the transfer is terminal.

### Concurrency model

Each long-lived component should have one clear owner of mutable asynchronous state. The hub already serializes transfer initiation with `_transfer_lock`; extend that ownership to a central pending-transfer record. Feishin's main transport should own each WebSocket through a per-generation object rather than module globals. Feishin's renderer should have a latest-wins queue publisher and directive executor. Navic should have one WebSocket writer per connection. Each Cast bridge should behave as an actor with one event stream.

Avoid fixed sleep windows as correctness barriers. `hubDrivenUntil`, delayed seeks, and delayed re-pauses are practical defensive measures, but they are not causal proof. Wherever possible, correlate to player events and operation generations. Timers should remain a timeout/fallback, not the primary success signal.

### Security posture

Treat the hub token as mandatory because the WebSocket protocol has administrative impact even when the sensitive HTTP proxies are disabled. Validate deployment examples against the actual threat model: a reverse proxy must provide TLS for the hub and for public Navidrome stream URLs, while LAN-only Cast v2 TLS remains a separate self-signed protocol exception.

Secrets should not appear in logs, queue-history artwork URLs, crash reports, backups, or persisted public metadata. The current Navic comments correctly avoid copying another client's authenticated cover URL into local cards; apply that principle systematically to diagnostic logs and persisted state. Add a redaction test over serialized hub state and application logs.

### Failure reporting

Distinguish delivery failure, resolution failure, player failure, receiver timeout, unreachable hardware, authentication failure, and supersession. These are operationally different and should carry stable protocol codes. User messages can remain concise, but logs and tests should retain the phase, transfer ID, target ID, elapsed time, and cause.

Do not silently swallow errors on required paths. Best-effort fan-out may ignore one dead observer; transaction-critical load/release frames, database migrations, and explicit widget commands need an observable outcome.

### Persistence and reconciliation

Keep state transitions explicit: decode, validate, migrate, reconcile, settle, then prune. The lb-bot timeout issue and broad migration catches both result from combining these phases. For saved queues and fill watches, make IDs versioned and mutations return enough information to detect a missing record. Preserve tombstone behavior, which is a strong existing design.


## Verification and test gaps

### Completed static checks

The audit traced transfer initiation, release handling, active-device authority, load futures, rollback, reachability expiry, and disconnect behavior in `hub.py`. It compared ordinary and Cast receiver load paths in both clients, inspected Feishin transport/reconnect and renderer command handling, examined Navic's WebSocket send paths and Media3 implementation, and followed native Cast request correlation and bridge lifecycle. It also reviewed lb-bot watch persistence/polling/retry, Android notification collection, manifest/network policy, widget receivers, database migration patterns, and saved-queue DAO behavior.

`hub.py` passed Python bytecode compilation. Current hub manual tests could not execute because the environment lacked the declared `websockets` dependency. An attempted installation was blocked by the environment's package proxy/network policy, so no test pass is claimed.

Feishin TypeScript and ESLint checks were attempted using the project-specific configurations, but the commands exceeded the available execution window. No pass or failure should be inferred from the timeout.

Navic Gradle verification could not start because the Gradle 9.5.1 distribution was not locally available and `services.gradle.org` could not be resolved. Consequently, Room migration compilation, Android manifest merge, KMP iOS compilation, and unit/instrumentation tests remain unverified.

### Required new automated tests

| Test area | Required scenario and assertion |
|---|---|
| Transfer correlation | A delayed `loaded` from transfer A cannot complete transfer B to the same target |
| ABA rollback | A times out on X, B moves to Y, C returns to X; A cannot roll C back |
| Release correlation | A delayed duplicate `released` cannot complete a later handoff from the same device |
| Navic no controller | `do:load` with a null MediaController returns `ok:false` before deadline |
| Navic player error | A correlated Media3 error returns `ok:false` and cannot affect a superseding load |
| Feishin delayed resolution | Two queue publications resolving in reverse order leave the hub with the newer queue |
| Feishin socket replacement | Late close/pong/heartbeat callbacks from A cannot clear, ping, or terminate B |
| Receiver supersession | Load A resolving after load B cannot replace B's queue or acknowledge B |
| Cast deadline | Connect/launch/load exceeding the budget is cancelled and cannot start late audio |
| Cast channel close | Closing the socket immediately completes all pending requests as failures |
| Cast recovery | Simultaneous status drop, ticker probe, and new load produce one channel/session only |
| lb-bot expiry | An unsettled timed-out watch becomes a retained `gave_up` ledger row exactly once |
| lb-bot retry | Retry sends the exact originally selected release MBID and canonical metadata |
| lb-bot concurrent polls | Parallel updates retain every fill/gap entry |
| Notification recovery | A terminal event while stopped is delivered once after process/activity recovery |
| Database migrations | Every supported old schema reaches the expected final schema; non-duplicate SQL errors fail migration |
| Empty token | Production startup refuses an absent token; explicit insecure loopback mode remains testable |
| Critical send failure | Immediate release/load send failure aborts without waiting a full timeout |

### Hardware and integration matrix

Run transfer tests across Feishin local audio, Navic Media3 local audio, Feishin-bridged Cast, and Navic-bridged Cast in every source/target combination. For each pair, cover playing and paused state, same-track transfer, a track boundary during transfer, queue edits during load, source disconnect, target disconnect, bad stream URL, slow first byte, app already running, stale cached Cast session, and a second controller attempting a transfer.

Run the two Cast bridges simultaneously with deliberate Wi-Fi loss, process suspension, phone locking, desktop sleep, and bridge reconnect. Assert single ownership, bounded stand-down after 4003, correct `reachable` expiry, no double scrobble, no duplicate reports, and no audio after hub rollback.

For protocol parity, build a table for every shared `act` and `do` command and assert both clients implement the same state transition and error semantics. The most important parity gap currently is truthful ordinary-player load acknowledgement; Cast acknowledgement is materially stronger.


## Positive findings

The audit also found design choices worth preserving:

The hub correctly treats only the active receiver as authoritative for live reports. It clears an active slot on disconnect, retains the queue/position as an orphaned session, and lets clients adopt that session paused. Intent grace windows guard stale play/pause and position reports after fresh commands.

Transfers execute outside the issuing socket's read loop, avoiding the classic deadlock where the active receiver cannot process its own release while the hub waits in that same read loop. Self-transfer is a no-op rather than a reload that rewinds to the last one-second report.

Cast presence and hardware reachability are modeled separately. The hub rejects transfers to hardware that a still-connected bridge reports unreachable, expires stale reachability verdicts, and clears the active slot when a speaker disappears while its bridge socket remains healthy.

Both Cast implementations have absorbed important real-hardware lessons: probe cached sessions, prove queue ownership by stable Subsonic track ID rather than signed URL equality, join a running Default Media Receiver before launching, inspect `LAUNCH_ERROR`, pause instead of stop on release, and avoid reporting fire-and-forget commands as successful. Cast scrobbling is assigned to the sole bridge, preventing duplicate plays.

Saved-queue history has a coherent identity model: a listening session survives queue edits and top-ups; the hub is authoritative; clients retain offline caches; field-level merge avoids blanking names; and tombstones prevent stale clients from resurrecting deletions. The hub also preserves the current record through cap eviction.

The hub persists state atomically through a temporary file and `os.replace`. Position-only reports are periodically persisted rather than waiting for another state change. Navidrome `savePlayQueue` mirroring is debounced and serialized to avoid stale concurrent writes.

Navic resolves remote queues one-for-one with placeholders rather than dropping locally unknown songs, preserving protocol indices. Its `restart()` uses `cancelAndJoin` before creating a new connection, an important partial defense against duplicate sockets.

lb-bot polling already handles several subtle cases correctly: transport failure is not treated as a real `unknown` response, unknown credits are consecutive rather than cumulative, polls have a minimum cadence and quiet-response backoff, and retries remain user initiated. The flaws reported above are localized and can be fixed without replacing that design.

Widget handling also includes correct safeguards: ordinary widget redraws use a persisted now-playing snapshot instead of overwriting it with absent extras; `goAsync()` is finished; album shortcuts have unique data URIs because PendingIntent equality ignores extras; and next/previous use a short-lived MediaController with command availability checks.


## Recommended implementation order

### Phase 1 — restore transfer truth

Implement protocol-correlated `transferId` and deadline propagation, then make Navic and Feishin ordinary receiver acknowledgements await real engine outcomes. Add the delayed-acknowledgement and ABA tests before shipping. Align Navic Cast with the same deadline and prohibit late media loads.

### Phase 2 — eliminate connection-generation races

Refactor Feishin's hub transport around a captured socket/generation, clear stale device registries, and centralize Navic outbound frames in a per-connection writer. Make required hub sends return a delivery result. Add deterministic replacement tests using fake sockets.

### Phase 3 — serialize receiver and Cast operations

Add a latest-wins Feishin queue publication worker and a command-generation-aware receiver executor. Convert Navic Cast bridge lifecycle to an actor or equivalent serialized state machine, drain Cast pending requests on close, and make bridge-manager shutdown awaitable.

### Phase 4 — harden security defaults

Fail closed on absent `HUB_TOKEN`, add explicit loopback-only insecure development mode, remove token-prefix logging, document TLS termination, and narrow Android network trust where product constraints permit. Verify backup exclusions for credentials.

### Phase 5 — repair durable background workflows

Fix lb-bot expiry settlement, atomic map updates, edition-preserving retries, and persisted notification delivery. Replace broad migration exception handling, validate final schemas, and make saved-queue writes observable.

### Phase 6 — complete verification

Run the existing hub suites in an environment with dependencies, run Feishin's explicit web/node TypeScript checks and changed-file ESLint, run Navic Android/KMP builds and migration tests, then execute the hardware transfer matrix. Do not publish from the hub working repository; follow the umbrella clone workflow documented in `CLAUDE.md` if fixes are later released.


## Conclusion

The strongest parts of navi-connect are its explicit session-ownership model and its hard-won Chromecast behavior. The most important remaining weakness is that the protocol currently confuses issuing a playback command with proving that playback was established, and then correlates replies only to a device rather than to an operation. Correcting that boundary will remove the largest class of silent-playing-state failures.

After the high-severity transfer and socket-generation work, the remaining findings are bounded and tractable. The recommended changes preserve the existing architecture: the hub remains the owner of intent, clients remain controller/receivers, bridges remain virtual receivers, and external integrations remain fail-soft. The goal is not a redesign; it is to give every asynchronous operation an identity, an owner, a deadline, and an observable result.

