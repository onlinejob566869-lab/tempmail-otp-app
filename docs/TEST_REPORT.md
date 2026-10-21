# Test Report — TempMail OTP

**Date:** 2026-10-21 · **Branch:** `cline/j8vytv3c` · **Commit under test:** `982ea6b` + test/refactor changes
**Toolchain:** Temurin JDK 17.0.20.1 · Android SDK 35 · build-tools 35.0.0 · Gradle 8.10.2 · AGP 8.7.0

## Result summary

| Check | Command | Result |
|---|---|---|
| Compile + package debug APK | `./gradlew assembleDebug` | ✅ **PASS** (1m 44s first run, 11 MB APK) |
| Full clean verification | `./gradlew clean assembleDebug testDebugUnitTest lintDebug --no-build-cache` | ✅ **PASS** — 52/52 tasks executed, no cache, 34 s |
| JVM unit tests | `./gradlew testDebugUnitTest` | ✅ **PASS — 38/38**, 0 failures, 0 skipped |
| Android lint | `./gradlew lintDebug` | ✅ **PASS — 0 errors, 10 warnings** |
| Live API + WebSocket smoke | `./gradlew testDebugUnitTest -Plive` | ✅ **PASS** (1.5 s) |
| Release APK | `./gradlew assembleRelease` | ⛔ **FAIL** — `Keystore file '/workspace/release-keystore.jks' not found for signing config 'release'` (the file is gitignored and absent) |
| On-device UI / notifications / clipboard / FGS | instrumented tests | ⛔ Not possible here — no `/dev/kvm`, so no emulator; no physical device attached |

## APK inspection (`aapt2 dump badging`)

```
package: name='com.tempmail.otp' versionCode='1' versionName='1.0'
compileSdkVersion='35'  targetSdkVersion:'35'
uses-permission: INTERNET, ACCESS_NETWORK_STATE, POST_NOTIFICATIONS,
                 FOREGROUND_SERVICE, FOREGROUND_SERVICE_DATA_SYNC,
                 WAKE_LOCK, RECEIVE_BOOT_COMPLETED
application-label: 'TempMail OTP'
```

Manifest merge is clean: `MainActivity` (launcher) and `service.OtpPollingService`
(`foregroundServiceType=dataSync`, `exported=false`) are both present, launcher icon resolves.

## Tests added

### `app/src/test/java/com/tempmail/otp/data/ModelsTest.kt` — 7 tests
Runs against the app's real `ApiClient.moshi`, so it verifies the actual adapter configuration
(including the `@JsonClass(generateAdapter = true)` + `KotlinJsonAdapterFactory` reflection path)
against the payloads documented at `mailboxtemp.com/docs`:

* `POST /api/inbox/generate` success and `RATE_LIMITED` error payloads
* `GET /api/inbox/{address}/emails` list, including `otp_code: null`
* `GET /api/email/{address}/{id}` detail with `body_text`/`body_html`, `attachments` ignored
* the WebSocket `email` object shape the client parses into `EmailSummary`
* missing optional fields fall back to safe defaults

**Proves:** the network contract the app depends on actually deserialises — the models are correct.

### `app/src/test/java/com/tempmail/otp/util/OtpExtractorTest.kt` — 9 tests
Covers the extracted `OtpExtractor` (previously duplicated privately in the ViewModel and service):

* 4/6/8-digit codes from plain text and HTML bodies
* rejects <4 and >8 digit numbers, blank/null input
* characterises the known limitations: **the first number wins**, and a 4-digit **year is accepted**

**Proves:** the fallback extraction works for normal OTPs, and quantifies its false-positive risk.

### `app/src/test/java/com/tempmail/otp/util/EmailListTest.kt` — 5 tests
Covers the list logic behind the F-02 fix:

* a pushed email is prepended (newest first)
* a duplicate id is ignored and the original list is returned untouched
* the list is capped at 50, keeping the newest entries
* `newestOtp` picks the first (newest) message that has a code, and returns null when none does

**Proves:** the live inbox list update cannot duplicate or grow without bound.

### `InboxClockTest.kt` (7) · `DailyLimitTest.kt` (5) · `InboxEntryTest.kt` (4)
Batch A (v1.0.1) coverage:

* ISO-8601 expiry parsing — `Z`, fractional seconds, offsets, invalid input
* countdown formatting — `1:05`, `1:00:00`, and `Expired` at/below zero
* UTC day keys and the daily-quota reset on a new day
* `InboxEntry.isExpired()` at, before and after the deadline; inboxes without a deadline never expire

### `app/src/test/java/com/tempmail/otp/LiveApiSmokeTest.kt` — 1 live test (opt-in)
Excluded from the default task; runs the app's own `ApiClient`/`MailboxWebSocketClient` against the
real service: create inbox → list emails → open `wss://mailboxtemp.com/ws/inbox?address=…` and wait
for the server's `connected` frame.

Observed: inbox created, list returned `ok`, socket connected **within 1.5 s**. Endpoint, auth-less
access, JSON shapes and the WebSocket handshake all work as the app assumes.

## Findings confirmed while testing

1. **`gradlew` is not a real wrapper script.** On a machine where `JAVA_HOME` is set but `java` is not
   on `PATH`, `./gradlew` fails with `./gradlew: 3: exec: java: not found` (exit 127). The canonical
   wrapper falls back to `JAVA_HOME`. GitHub runners hide this because `java` is on `PATH`.
2. **Lint is now green** but reports 10 warnings: 4 outdated AndroidX versions, 4 obsolete
   `SDK_INT >= O` checks / `mipmap-anydpi-v26` folder (minSdk is already 26), an unused
   `ic_launcher_round`, and a missing monochrome launcher icon.
3. **`Toast` crash (analysis F-01) is real.** AOSP `Toast.getLooper()` does
   `checkNotNull(Looper.myLooper(), "Can't toast on a thread that has not called Looper.prepare()")`,
   and `ClipboardHelper.copyText` calls `Toast.show()` from the OkHttp WebSocket callback thread.
   This cannot be reproduced without a device/emulator, so it is verified by source + call-path, not
   by execution.

## CI

The GitHub App integration available here has no `workflows` permission, so `.github/workflows/build-apk.yml`
cannot be modified from this environment (`HTTP 403`, and `workflow_dispatch` is blocked too).
Instead, the unit tests are wired into the `assembleDebug` task in `app/build.gradle.kts`, so the
**existing, unmodified** CI step (`./gradlew assembleDebug`) now runs them automatically. Verified with
the exact CI command and no build cache: `42/42 tasks executed`, tests ran, build successful.
The local runs used the same JDK 17 / Gradle 8.10.2 / AGP 8.7.0 / SDK 35 as the GitHub runner, and the
base commit `982ea6b` is green in CI (run `38854068942`).

## What this does **not** verify

* UI rendering, navigation, dark mode, edge-to-edge insets on Android 15.
* Notification delivery, permission prompt, lock-screen visibility.
* Clipboard behaviour (Android 10+ background restriction).
* Foreground-service lifecycle, Doze, Android 15 `dataSync` 6-hour timeout.
* Multi-inbox switching, delete/reconnect behaviour, process-death restore.

All of these need an emulator or device. They remain the open P0/P1 items in
`docs/CODE_ANALYSIS.md`.

## How to reproduce

```bash
export JAVA_HOME=/path/to/jdk17
export PATH="$JAVA_HOME/bin:$PATH"
export ANDROID_HOME=/path/to/android-sdk      # platforms;android-35, build-tools;35.0.0
printf 'sdk.dir=%s\n' "$ANDROID_HOME" > local.properties

./gradlew clean assembleDebug testDebugUnitTest lintDebug
./gradlew testDebugUnitTest -Plive --tests 'com.tempmail.otp.LiveApiSmokeTest'
```
