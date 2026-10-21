# TempMail OTP — Code Analysis

**Commit analysed:** `982ea6b` · **Date:** 2026-10-21
**Scope:** 9 Kotlin files (1,425 LOC), Gradle build, manifest, CI workflow, README
**Method:** full source read + live API contract check against `mailboxtemp.com/docs` + framework
behaviour verified against Retrofit/AOSP sources + CI run inspection.

---

## 1. Verdict

A compact, readable MVVM prototype that **builds and ships a working debug APK** (CI run
`38854068942` — success, 2m37s), and the REST/WebSocket endpoints it calls match the live
MailboxTemp API. The happy path is well understood, state is persisted, and the WebSocket parsing is
defensively written.

It is **not production-ready**. There is one crash-on-the-main-use-case defect, the inbox list never
reflects pushed mail, restore replays old OTPs, the background service and the ViewModel fight over
the same socket, and the service is unhandled under Android 15's `dataSync` limit.

| Area | Score | Note |
|---|---|---|
| Build / CI | 7/10 | Green, but debug-only, no tests, no lint gate |
| Architecture | 5/10 | Clear layers, but two competing owners of one singleton |
| Correctness | 3/10 | List never updates, restore replays, copy-from-WS can crash |
| Reliability | 3/10 | Fake `isConnected`, reset backoff, zombie reconnects |
| Security / privacy | 4/10 | Signing secrets in VCS, `allowBackup`, plaintext OTP prefs, OTP on lock screen |
| UX | 6/10 | Clean Compose UI, stale labels, no TTL/countdown |
| Testing | 0/10 | No unit, instrumentation or UI tests at all |
| Docs | 6/10 | Good README, but stale (still describes 5 s polling) |

**Overall: ~4.5/10 — good prototype, needs a hardening pass before real users.**

---

## 2. Architecture as built

```
 MainActivity (Compose)                             OtpPollingService (FGS, dataSync)
        │  collectAsState()                                  │
        ▼                                                    │
 MainViewModel ──────────────┐                               │
   • _uiState / _inboxes     │                               │
   • seenIds (memory)        │                               │
   • saveState()/restore     │                               │
                             ▼                               ▼
                    ApiClient (singleton object)
                    ├── Retrofit + Moshi ──► MailboxTempApi (3 REST calls)
                    └── MailboxWebSocketClient ──► wss://mailboxtemp.com/ws/inbox?address=…
                              activeSockets: MutableMap<address, WebSocket>  ← shared by BOTH owners

 util: ClipboardHelper (Toast + setPrimaryClip) · NotificationHelper (2 channels)
```

* MVVM + StateFlow is used correctly in the UI layer; `AndroidViewModel` is created by the default
  Compose factory, so no custom factory is needed.
* `ApiClient` is a process-wide singleton, which is what makes the socket-ownership conflict (F-06)
  and the shared-state races (F-09) possible.
* Business rules (`handleNewEmail`, `extractOtpRegex`, `scheduleReconnect`) are **duplicated** in
  `MainViewModel.kt` and `OtpPollingService.kt` and have already drifted (clipboard vs. notify-only).

### Runtime flow (actual)

1. `POST /api/inbox/generate` → address → clipboard → `InboxEntry` → `startWebSocket`.
2. WS `onEmailEvent` → dedupe by `id` → `handleNewEmail` → `otp_code` present?
   * **yes** → `onOtpFound` *synchronously on the OkHttp thread* → clipboard + Toast + notification.
   * no → `GET /api/email/{address}/{id}` on `viewModelScope` → regex fallback → `onOtpFound` on main.
3. `onOtpFound` writes `lastOtp`, sets `UiState.OtpFound`, saves prefs.
4. `inbox.emails` is populated **only** by `initialFetch` on app start — never by WS events.

---

## 3. Findings

Severity: **P0** = crash / wrong behaviour on the primary flow · **P1** = reliability / data integrity
· **P2** = security / platform compliance · **P3** = UX / maintainability.


### P0 — must fix before any real use

| ID | Finding | Evidence | Impact | Fix |
|----|---------|----------|--------|-----|
| F-01 | **Toast on the WebSocket thread crashes the app.** `onEmailEvent` is delivered on an OkHttp reader thread; if `otp_code` is present, `handleNewEmail` calls `onOtpFound` → `ClipboardHelper.copyText` → `Toast.show()`. `Toast.show()` throws `RuntimeException: Can't toast on a thread that has not called Looper.prepare()` when the thread has no Looper. | `MailboxWebSocketClient.kt:66-75,127`; `MainViewModel.kt:341-355`; `ClipboardHelper.kt:18` | App dies exactly when the OTP arrives — the main use case. | Marshal `onEmailEvent` onto the main dispatcher (`viewModelScope.launch { … }`), or make `ClipboardHelper` post the Toast internally. |
| F-02 | **New emails never appear in the list.** `inbox.emails` is assigned only inside `initialFetch`; WS events update `lastOtp` only, so "Inbox (N)" stays frozen at launch state. | `MainViewModel.kt:262` (only call site of `updateInboxEmails`), `309-319`, `324-344` | Users cannot see incoming mail; only the last-OTP card changes. | Prepend each WS `email` to the inbox's `emails` list (dedupe by id). |
| F-03 | **UI freezes after the first OTP.** `_uiState` becomes `UiState.OtpFound` and never returns to `Ready`; `updateInboxEmails` publishes only while the state *is* `Ready`. | `MainViewModel.kt:359`, `315-317` | Email list stops refreshing until the user switches/creates/deletes an inbox. | Keep UI state `Ready`; model "OTP found" as a one-shot event (`Channel`/`SharedFlow`). |
| F-04 | **Restore replays every old email.** After process death `seenIds` is empty, so `initialFetch` dispatches *all* stored messages to `handleNewEmail`. | `MainViewModel.kt:55,121,257-269` | On every launch: N notifications, and because the API lists newest-first the clipboard typically ends up holding the **oldest** OTP — actively wrong data. | Persist seen ids, or seed them from the fetch and dispatch nothing on restore. |
| F-05 | **Deleted inbox reconnects forever.** `stopWebSocket` cancels the reconnect job then closes the socket; the close callback fires `onDisconnected` → `scheduleReconnect`, creating a *new* job in a live scope. | `MainViewModel.kt:372-376,388,220-228,235-251` | Zombie sockets for deleted addresses; wasted quota, duplicate notifications. | Tombstone removed addresses and early-return in `onDisconnected`/`onError`. |
| F-06 | **Two owners, one socket.** `connect()` calls `disconnect(address)` first and keys `activeSockets` by address only, so starting the background service closes the ViewModel's socket (and vice-versa); the orphaned owner reconnects and steals it back. | `MailboxWebSocketClient.kt:46,52-54,95,151-156`; `MainActivity.kt:349`; `OtpPollingService.kt:94-117` | Reconnect ping-pong every few seconds while "Start BG" is active; unreliable delivery. | Single owner (service or repository) **or** key sockets by `owner+address` and expose stream state via `StateFlow`. |
| F-07 | **Service restart loses its inboxes.** `START_STICKY` redelivers a null intent, so `onStartCommand` monitors nothing but still shows a "Monitoring…" notification. | `OtpPollingService.kt:65-76` | Silent no-op background service after system/OEM restart. | `START_REDELIVER_INTENT`, or persist the address list and reload it. |
| F-08 | **Android 15 `dataSync` time limit is unhandled.** targetSdk 35 + `foregroundServiceType="dataSync"` is capped at 6 h per rolling 24 h; on timeout `Service.onTimeout(startId, fgsType)` must `stopSelf()` within seconds or the system fails the app. Not overridden. | `AndroidManifest.xml:33-37`; `OtpPollingService.kt` (no `onTimeout`); AOSP `Service.callOnTimeLimitExceeded` | Crash/failure after 6 h of background monitoring; no new dataSync FGS possible for the rest of the window. | Override `onTimeout` → `stopSelf()` + notify, and/or use a type that matches the real use case. |


### P1 — reliability & data integrity

| ID | Finding | Evidence |
|----|---------|----------|
| F-09 | Shared mutable state touched from multiple threads: `activeSockets`,`seenIds`,`reconnectJobs` are plain `mutableMapOf`/`mutableSetOf`, mutated from OkHttp reader threads and the main thread → `ConcurrentModificationException` / lost updates. Use `ConcurrentHashMap` or confine to one dispatcher. | `MailboxWebSocketClient.kt:46,95,169`; `MainViewModel.kt:55-56,212-217`; `OtpPollingService.kt:57-58` |
| F-10 | `isConnected()` is true as soon as `connect()` is called (socket is put in the map *before* the handshake), so both reconnect loops treat a not-yet-handshaken socket as connected and stop retrying; and because each failure callback re-enters `scheduleReconnect` (cancelling the job), `attempt` resets to 0 and the backoff never grows. Track state in `onOpen`/`onFailure` per socket generation. | `MailboxWebSocketClient.kt:95,169`; `MainViewModel.kt:243-248`; `OtpPollingService.kt:130-134` |
| F-11 | Silent `catch (_: Exception) {}` in multiple places — network, parse and 429 failures vanish with no log or UI signal. | `MainViewModel.kt:272,339`; `OtpPollingService.kt:155`; `MailboxWebSocketClient.kt:138` |
| F-12 | No inbox lifetime handling. Free inboxes expire after 60 min and return `404 NOT_FOUND`; the model ignores `expires`/`inboxId`, there is no countdown, no `POST /api/inbox/{address}/extend`, no dead-inbox cleanup, and restored inboxes are reconnected forever. | `Models.kt:10-14`; `MailboxTempApi.kt`; `MainViewModel.kt:84-123` |
| F-13 | Restore fan-out: N inboxes × `listEmails` plus up to 50 `readEmail` calls each can exceed the documented 100 req/min/IP read budget → silent 429s. Stagger or batch. | `MainViewModel.kt:121,257-274` |
| F-14 | `createInbox` retries immediately when the API answers `ok=false` (no delay in that branch) — five rapid POSTs against a 20/min endpoint; progress is also reported as `UiState.Error("Creating inbox…")`. | `MainViewModel.kt:153-183` |
| F-15 | `@JsonClass(generateAdapter = true)` is a no-op: no KSP/kapt `moshi-kotlin-codegen` processor is configured, so every model resolves via `KotlinJsonAdapterFactory` reflection. Add codegen or drop the flag. | `Models.kt:9,19,34,44,56`; `app/build.gradle.kts:76-80`; `ApiClient.kt:15-17` |
| F-16 | Logging interceptor is active in release; MailboxTemp treats the address as a bearer token, so `Level.BASIC` writes credentials to logcat. Gate on `BuildConfig.DEBUG`. | `ApiClient.kt:19-26` |
| F-17 | `pingInterval(30s)` is set on the shared client that also serves REST; move it to a WS-only client. | `ApiClient.kt:22` |

### P2 — security, privacy, platform compliance

| ID | Finding | Evidence |
|----|---------|----------|
| F-18 | Release signing secrets committed in plain text, pointing at a `.jks` that is gitignored and absent → release build is unreproducible and the password is public. Use env vars / `local.properties`. | `app/build.gradle.kts:19-26`; `.gitignore:9` |
| F-19 | `allowBackup="true"` with plaintext `SharedPreferences` (addresses + OTPs) uploads disposable-mail credentials to cloud backup — contradicts the product's privacy posture. | `AndroidManifest.xml:14`; `MainViewModel.kt:62-66,128-143` |
| F-20 | OTP notification is `IMPORTANCE_HIGH` with no `setVisibility`/lock-screen redaction, so the code is readable by anyone holding the locked phone. | `NotificationHelper.kt:21-25,31-41` |
| F-21 | Clipboard entries are not marked sensitive (`ClipDescription.EXTRA_IS_SENSITIVE`), so the OTP appears in the Android 13+ clipboard preview and history. | `ClipboardHelper.kt:16-18` |
| F-22 | Background clipboard writes are impossible on Android 10+ (focused-app restriction) yet the notification always says "code copied to clipboard"; the FGS path never copies at all. | `NotificationHelper.kt:35`; `OtpPollingService.kt:139-162` |
| F-23 | Unused permissions: `WAKE_LOCK`, `RECEIVE_BOOT_COMPLETED`, `ACCESS_NETWORK_STATE` — no wake lock, no receiver, no connectivity check in code. | `AndroidManifest.xml:6-11` |
| F-24 | No R8/minify (`isMinifyEnabled = false`) and no network-security-config; release ships reflection metadata and full symbol names. | `app/build.gradle.kts:28-37` |

### P3 — UX, docs, maintainability

| ID | Finding | Evidence |
|----|---------|----------|
| F-25 | targetSdk 35 enforces edge-to-edge on Android 15 but the app never calls `enableEdgeToEdge()` nor consumes `WindowInsets`, so the header draws under the status bar. | `MainActivity.kt:49-71,85-89` |
| F-26 | No dark theme: `MaterialTheme {}` uses the light scheme unconditionally and the XML theme is platform `Theme.Material.Light`. | `MainActivity.kt:62`; `themes.xml:3` |
| F-27 | The Send icon is used as the "copy" affordance in two places; a copy/content icon is the correct metaphor. | `MainActivity.kt:294,333` |
| F-28 | Notification has no `contentIntent` (tap does nothing) and one shared `NOTIFICATION_ID = 1001`, so inboxes overwrite each other's OTP notifications. | `NotificationHelper.kt:15,31-41` |
| F-29 | All UI strings are hardcoded in Compose (no `strings.xml`) → no localisation; `colors.xml` is effectively unused. | `MainActivity.kt` passim |
| F-30 | Docs and copy are stale: README still documents 5-second polling and a `startPolling(address, intervalMs)` API that no longer exists; the class is still `OtpPollingService` and the header says "Background polling". | `README.md:100,117`; `MainActivity.kt:109`; `OtpPollingService.kt:30` |
| F-31 | Zero tests: no `src/test`, no `src/androidTest`, no test dependencies, CI only runs `assembleDebug`. OTP extraction, prefs (de)serialisation and dedupe/reconnect state machines are untested. | `app/build.gradle.kts:58-87`; `.github/workflows/build-apk.yml:28-29` |
| F-32 | CI hygiene: `actions/setup-java@v4` is deprecated (warning in run log), actions are not SHA-pinned, no lint/test job, `lint.abortOnError = false`. | `.github/workflows/build-apk.yml`; `app/build.gradle.kts:52-55` |
| F-33 | Duplicated logic: `handleNewEmail`, `extractOtpRegex`, `scheduleReconnect` exist twice and differ; `\b(\d{4,8})\b` grabs the first number in the body (dates, order ids, phone fragments) while the API already extracts `otp_code`. | `MainViewModel.kt:324-366`; `OtpPollingService.kt:139-167` |
| F-34 | UI nits: the `UiState.Error` branch indexes `inboxes[activeIndex]` without a bounds check; a `fillMaxSize` `LazyColumn` sits inside a `Column`; unused imports (`Refresh`, `verticalScroll`, `rememberScrollState`). | `MainActivity.kt:155-162,397,13-23` |
| F-35 | No DI and no repository layer — the ViewModel talks to Retrofit directly, which is the main reason the logic is untestable. | `MainViewModel.kt:43-44` |

---

## 4. API contract verification

Checked against the live docs at `https://mailboxtemp.com/docs/`:

| App usage | Documented | Verdict |
|---|---|---|
| `POST api/inbox/generate` (no body) | `POST /api/inbox/generate`, no body for API calls | ✅ Retrofit sends an empty body for a bodiless `@POST` (`RequestBuilder.java:249-258`) |
| `GET api/inbox/{address}/emails` | Same; newest first, max 50, `otp_code` auto-extracted | ✅ Models match the documented JSON exactly |
| `GET api/email/{address}/{id}` | Same; marks message read | ✅ `attachments` is extra and safely ignored |
| `wss://mailboxtemp.com/ws/inbox?address=` | Same, `{type:"connected"}` then one event per email | ✅ endpoint exists; ⚠️ payload beyond `connected` is undocumented, so the defensive parser is the right call |
| — | `POST /api/inbox/{address}/extend` (+10 min) | ❌ unused by the app |
| — | `GET /api/inbox/{address}` (metadata + ttl) | ❌ unused, so no expiry UX |

**Quota reality (free tier):** 20 inbox creations/min/IP, 100 reads/min/IP, 10 new inboxes/day,
60-minute lifetime, 50 messages per list, 10 MB intake. The multi-inbox UI can hit the daily and
concurrency limits, and nothing in the UI explains that.

---

## 5. Build & CI

* `./gradlew assembleDebug` is green on commit `982ea6b`; debug APK uploaded as an artifact.
* `gradlew` is a **3-line stub** (`exec java -classpath gradle/wrapper/gradle-wrapper.jar …`) instead of
  the canonical wrapper script: no `JAVA_HOME` fallback, no `gradlew.bat`, no checksum verification.
  It works on GitHub runners because `java` is on `PATH`.
* Last run warnings: `setup-java v4 is deprecated`, Node 20 → 24 forcing, Gradle cache save failures
  (infrastructure, non-fatal), `Unable to strip libandroidx.graphics.path.so`.
* No lint/test/release job; lint is non-blocking; `isMinifyEnabled = false`.

---

## 6. Recommended roadmap

**Sprint 1 — stop the bleeding (P0, ~1–2 days)**
1. F-01: post all WS callbacks to the main dispatcher; make `ClipboardHelper` main-safe.
2. F-02/F-03: append WS emails to `inbox.emails`; turn `OtpFound` into a one-shot event.
3. F-04: persist `seenIds` (or suppress dispatch on restore) and never auto-copy the backlog.
4. F-05: tombstone deleted addresses; ignore callbacks for closed owners.

**Sprint 2 — one owner, real state (P0/P1, ~3–4 days)**
5. F-06/F-10: move socket ownership into a repository used by both ViewModel and service; key by
   owner + address; real `onOpen`/`onFailure` state; connection state as `StateFlow`.
6. F-07/F-08: persist addresses for the service, override `onTimeout`, reconsider the FGS type.
7. F-09/F-11/F-13: thread-safe collections, log instead of swallowing, stagger cold-start fetches.
8. F-12: surface `expires`, add extend/expired UI, drop dead inboxes.

**Sprint 3 — hardening (P2/P3)**
9. F-18/F-19/F-20/F-21: signing via env, `allowBackup=false`, private notification, sensitive clip.
10. F-15/F-16/F-24: KSP codegen, debug-only logging, enable R8.
11. F-31: unit tests for OTP extraction + prefs round-trip + reconnect state machine; add a CI
    `testDebugUnitTest lintDebug assembleRelease` job.
12. F-25–F-30: insets, dark theme, copy icon, per-inbox notifications, `strings.xml`, refresh README.

---

## 7. File inventory

| File | LOC | Role |
|---|---|---|
| `app/src/main/java/com/tempmail/otp/MainActivity.kt` | 442 | Compose UI (header, switcher, inbox, email rows) |
| `app/src/main/java/com/tempmail/otp/ui/MainViewModel.kt` | 408 | State, persistence, WS lifecycle, OTP handling |
| `app/src/main/java/com/tempmail/otp/service/OtpPollingService.kt` | 207 | Foreground service (WS keep-alive + notifications) |
| `app/src/main/java/com/tempmail/otp/data/MailboxWebSocketClient.kt` | 178 | OkHttp WS client, message parsing |
| `app/src/main/java/com/tempmail/otp/data/Models.kt` | 61 | Moshi DTOs |
| `app/src/main/java/com/tempmail/otp/data/ApiClient.kt` | 40 | Retrofit/OkHttp singletons |
| `app/src/main/java/com/tempmail/otp/data/MailboxTempApi.kt` | 27 | Retrofit interface |
| `app/src/main/java/com/tempmail/otp/util/NotificationHelper.kt` | 42 | OTP notifications |
| `app/src/main/java/com/tempmail/otp/util/ClipboardHelper.kt` | 20 | Clipboard + Toast |
