# Roadmap — TempMail OTP

**Updated:** 2026-10-21 · **Baseline:** `be85c87` (F-01…F-04 fixed) · **Next version:** 1.1 (versionCode 2)

## Status snapshot

| Done | Detail |
|---|---|
| Code audit | 35 findings (P0–P3) in `docs/CODE_ANALYSIS.md` |
| Test suite | 21 JVM tests (models, OTP extractor, email list) + 1 opt-in live smoke |
| P0 fixes | F-01 WS-thread crash · F-02 live email list · F-03 UI freeze · F-04 restore replay |
| CI | tests hooked onto `assembleDebug` (no workflow edit needed) |
| Build | debug APK green; release still blocked by the missing keystore (F-18) |

## Priority 1 — "app actually usable for more than 10 minutes"

These are the issues a real user hits in the first session.

| # | Update | Why it matters | Files | Effort |
|---|---|---|---|---|
| 1 | **Inbox expiry (TTL) support** — parse `expires`/`ttl`, show a countdown, add an **Extend +10 min** button (`POST /api/inbox/{address}/extend`), drop the inbox on `404 NOT_FOUND` | Free inboxes die after 60 min; today the app keeps a dead address and the user silently stops receiving mail (F-12) | `Models.kt`, `MailboxTempApi.kt`, `MainViewModel.kt`, `MainActivity.kt` | 1 day |
| 2 | **Daily creation limit UX** — track creations per day, disable "Add Email" at 10, show a clear message on `RATE_LIMITED`, stop retrying a non-recoverable 429 | Free tier is 10/day; today the app retries 5× and ends with a generic error | `MainViewModel.kt`, `MainActivity.kt` | 0.5 day |
| 3 | **Single socket owner** — move WebSocket ownership into one repository/session object used by both the ViewModel and the service; key by owner + address | App + background service currently steal each other's socket, causing reconnect ping-pong and missed OTPs (F-06) | new `data/InboxSession.kt`, `MainViewModel.kt`, `OtpPollingService.kt` | 1.5 days |
| 4 | **Real connection state** — mark connected only in `onOpen`, failed in `onFailure`; per-socket generation counter so old callbacks are ignored | `isConnected()` lies today, so backoff never escalates and retries stop too early (F-10) | `MailboxWebSocketClient.kt` | 0.5 day |
| 5 | **Delete/stop tombstones** — ignore callbacks for addresses the user deleted or stopped | Deleting an inbox starts an endless reconnect loop (F-05) | `MainViewModel.kt`, `OtpPollingService.kt` | 0.5 day |

## Priority 2 — background reliability

| # | Update | Why | Files | Effort |
|---|---|---|---|---|
| 6 | **Service restart recovery** — persist the monitored address list, restore it on `START_STICKY` (or use `START_REDELIVER_INTENT`) | After an OEM kill the service shows "Monitoring…" but monitors nothing (F-07) | `OtpPollingService.kt` | 0.5 day |
| 7 | **Android 15 FGS timeout** — override `onTimeout(startId, fgsType)` → `stopSelf()` + user-visible notice; consider `specialUse` or a WorkManager fallback | `dataSync` is capped at 6 h/24 h; without `onTimeout` the system fails the app (F-08) | `OtpPollingService.kt`, `AndroidManifest.xml` | 0.5 day |
| 8 | **Network awareness** — use the already-declared `ACCESS_NETWORK_STATE` to reconnect immediately on network return, and pause retries while offline | Saves quota, fixes "connected but dead" after Wi-Fi ↔ mobile switch | new `util/NetworkMonitor.kt` | 0.5 day |
| 9 | **Structured error surfacing** — replace `catch (_: Exception) {}` with logging + a retry banner; distinguish 404 / 429 / network | Failures are invisible today, so bugs look like "app does nothing" (F-11) | `MainViewModel.kt`, `OtpPollingService.kt` | 0.5 day |
| 10 | **Staggered restore fetch** — cap concurrent `listEmails` calls on cold start | Many inboxes × 50-message detail reads can blow the 100 req/min budget (F-13) | `MainViewModel.kt` | 0.25 day |

## Priority 3 — security, privacy, release readiness

| # | Update | Why | Files | Effort |
|---|---|---|---|---|
| 11 | **Fix release signing** — keystore from env / `local.properties`, never hardcoded | `assembleRelease` does not build at all today (F-18) | `app/build.gradle.kts`, `.gitignore` | 0.25 day |
| 12 | **`allowBackup=false`** (or `dataExtractionRules`) | OTPs and inbox addresses are currently cloud-backed up (F-19) | `AndroidManifest.xml` | 0.1 day |
| 13 | **Notification privacy** — `setVisibility(PRIVATE)`, no OTP in the title on the lock screen | Any passer-by can read the code on a locked phone (F-20) | `NotificationHelper.kt` | 0.1 day |
| 14 | **Sensitive clipboard flag** — `ClipDescription.EXTRA_IS_SENSITIVE` | OTP shows in the Android 13+ clipboard preview/history (F-21) | `ClipboardHelper.kt` | 0.1 day |
| 15 | **Fix the "copied to clipboard" lie** — the service never copies in the background; make the notification open the app and copy there, or say "tap to copy" | Misleading copy in the service notification (F-22) | `NotificationHelper.kt`, `MainActivity.kt` | 0.5 day |
| 16 | **Debug-only HTTP logging** | Inbox addresses are bearer tokens and are logged in release builds (F-16) | `ApiClient.kt` | 0.1 day |
| 17 | **Enable R8** + keep rules for Moshi models | Smaller APK, less symbol leakage (F-24, F-15) | `app/build.gradle.kts`, `proguard-rules.pro` | 0.25 day |
| 18 | **Drop unused permissions** (`WAKE_LOCK`, `RECEIVE_BOOT_COMPLETED` unless used) | Play review friction (F-23) | `AndroidManifest.xml` | 0.1 day |

## Priority 4 — UX & polish

| # | Update | Why | Effort |
|---|---|---|---|
| 19 | Edge-to-edge insets (`enableEdgeToEdge`, `WindowInsets`) | Android 15 draws the header under the status bar (F-25) | 0.25 day |
| 20 | Dark theme (`darkColorScheme`, XML theme) | Follows system dark mode (F-26) | 0.25 day |
| 21 | Per-inbox notifications + `contentIntent` (tap opens that inbox) | One shared ID means inboxes overwrite each other (F-28) | 0.25 day |
| 22 | Copy icon instead of the Send icon for "copy" | Wrong affordance (F-27) | 0.1 day |
| 23 | `strings.xml` for all UI text | No localisation today (F-29) | 0.5 day |
| 24 | Rename `OtpPollingService` → `OtpMonitorService`; fix the "Background polling" label and the stale README | Code/docs drift (F-30) | 0.25 day |
| 25 | Inbox switcher: unread badge + expiry timer per inbox | Makes multi-inbox usable at a glance | 0.5 day |

## Priority 5 — test debt

| # | Update | Why | Effort |
|---|---|---|---|
| 26 | Robolectric tests for `MainViewModel`: restore seeds seen ids, WS event prepends + copies once, delete stops reconnects | The P0 fixes are only covered indirectly today | 1 day |
| 27 | Unit tests for reconnect/backoff and the prefs codec | F-05/F-10 regressions are easy to reintroduce | 0.5 day |
| 28 | Instrumented smoke test (needs a device/emulator) | UI, notifications, clipboard, FGS are untested | 1 day |
| 29 | Add `release` job + lint gate (`abortOnError = true`) to CI | Current CI only builds debug | 0.25 day |

## Feature backlog (after the fixes)

- **Copy OTP from the notification** via a foreground trampoline activity (Android 10+ blocks background clipboard writes).
- **Manual refresh** button as a fallback when the WebSocket is down.
- **Inbox history** — persist seen emails so restore shows the full list offline.
- **Auto-extend** option (one tap extends every live inbox by 10 minutes).
- **Battery optimisation prompt** — guide users to exempt the app so the FGS is not killed.
- **QR/share** for the address; **custom prefix** support if the user has a Pro session (`PRO_REQUIRED` handling).

## Suggested order of work

1. **Batch A (1.5 days):** #1 TTL + #2 daily-limit UX → biggest real-user impact.
2. **Batch B (2 days):** #3 single socket owner + #4 real connection state + #5 tombstones → reliable delivery.
3. **Batch C (1.5 days):** #6 service recovery + #7 Android 15 timeout + #9 error surfacing.
4. **Batch D (1 day):** #11–#18 security/release hardening.
5. **Batch E (2 days):** #19–#25 polish + #26–#29 tests.

Ship **1.1** after Batch C; **1.2** after Batch E.
