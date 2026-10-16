# TempMail OTP — Android Native App

Ek native Android app jo automatically:
1. Temporary email address banata hai
2. Usko clipboard pe copy karta hai
3. Inbox pe laga rehta hai (polling)
4. Jab website OTP code bhejta hai, woh detect kar ke clipboard pe copy kar deta hai
5. Notification bhejta hai

## ⚠️ Important Notes

- **AdGuard Temp Mail ki EULA automation/scraping ko mana karti hai**, isliye ye app AdGuard nahi balki **MailboxTemp API** use karta hai jo specifically automation ke liye free hai — koi API key nahi, koi auth nahi, OTP codes khud extract karta hai.
- Ye app **testing, development, aur personal automation** ke liye hai. Spam, fraud ya kisi illegal activity ke liye use na karein.
- Temporary inboxes ka lifetime limited hota hai (taqreeban 60 minutes).

## Tech Stack

| Component | Technology |
|-----------|-----------|
| Language | Kotlin |
| UI | Jetpack Compose + Material 3 |
| Networking | Retrofit 2 + OkHttp + Moshi |
| Async | Kotlin Coroutines + Flow |
| Architecture | MVVM (ViewModel + StateFlow) |
| Min SDK | 26 (Android 8.0) |
| Target SDK | 35 (Android 15) |

## Project Structure

```
android-app/
├── settings.gradle.kts
├── build.gradle.kts
├── app/
│   ├── build.gradle.kts
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml
│       └── java/com/tempmail/otp/
│           ├── MainActivity.kt          ← Compose UI
│           ├── data/
│           │   ├── Models.kt            ← Data classes (Moshi)
│           │   ├── MailboxTempApi.kt    ← Retrofit API interface
│           │   └── ApiClient.kt         ← Retrofit singleton
│           ├── ui/
│           │   └── MainViewModel.kt     ← ViewModel + polling logic
│           └── util/
│               ├── ClipboardHelper.kt   ← Clipboard copy
│               └── NotificationHelper.kt← OTP notifications
```

## How to Build & Run

### Requirements
- Android Studio Koala 2024.1.1 ya newer
- JDK 17
- Android SDK 35

### Steps

1. **Android Studio mein project open karein**
   - Android Studio → File → Open → `android-app/` folder select karein

2. **Gradle sync hone dein**
   - Android Studio automatically Gradle sync karega
   - Agar koi dependency download nahi hua to "Sync Now" pe click karein

3. **Run karein**
   - Ek Android device ya emulator connect karein
   - Run ▶️ button pe click karein

4. **App use karna**
   - App khulte hi "Generate Temp Email" button pe tap karein
   - Email address clipboard pe auto-copy ho jayega
   - Us email ko website pe paste kar ke signup karein
   - Jab website OTP bhejega, app usko detect kar ke clipboard pe copy kar dega
   - Notification bhi aayegi

## API Used

**MailboxTemp** — `https://mailboxtemp.com`
- Free, no API key, no authentication
- OTP codes auto-extract karta hai (`otp_code` field)
- REST JSON API

### Endpoints:
| Endpoint | Method | Purpose |
|----------|--------|---------|
| `/api/inbox/generate` | POST | Naya inbox banayein |
| `/api/inbox/{address}/emails` | GET | Messages list (with OTP) |
| `/api/email/{address}/{id}` | GET | Full message read |

## How It Works (Flow)

```
1. User taps "Generate Temp Email"
2. → POST /api/inbox/generate → temp email address milta hai
3. → Address clipboard pe copy ho jata hai
4. → User website pe ja kar email paste kare, signup kare
5. → App inbox ko har 5 second baad poll karta hai (Kotlin Flow)
6. → Jab email aata hai, OTP detect hota hai
7. → OTP clipboard pe auto-copy + notification
8. → User OTP paste kar ke verification complete kare
```

## Permissions

| Permission | Purpose |
|-----------|---------|
| `INTERNET` | API calls ke liye |
| `ACCESS_NETWORK_STATE` | Network availability check |
| `POST_NOTIFICATIONS` | OTP notification dikhane ke liye (Android 13+) |

## Customization

### Polling interval change karna
`MainViewModel.kt` mein `startPolling(address, intervalMs = 5000L)` — `5000L` ko apni marzi se change karein (milliseconds mein).

### API change karna
`ApiClient.kt` mein `BASE_URL` change kar ke koi bhi compatible API laga sakte hain. Alternatives:
- **TempMailGrab** — `https://tempmailgrab.com/api/v1/` (free API key chahiye)
- **MTempMail** — `https://mtempmail.com/` (free public key)

## Troubleshooting

| Problem | Solution |
|---------|----------|
| Gradle sync fail | JDK 17 set karein, internet check karein |
| App crash on launch | minSdk 26 — device Android 8+ hona chahiye |
| Network error | Internet connection check karein |
| OTP nahi aa raha | 60-second wait karein, phir "New Email" se dobara try karein |
| Notification nahi aa rahi | Settings → App → TempMail OTP → Notifications enable karein |
