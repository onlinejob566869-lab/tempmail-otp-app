# Plan — Multi-provider inbox pool (Option A)

**Goal:** raise real capacity without breaking any provider's rules, by running several
legitimate temp-mail services behind one interface and spreading inboxes across them.

**Non-goals:** IP rotation, multi-account farming, header spoofing, or any rate-limit evasion.
Each adapter stays inside that provider's published limits.

Status: **plan only** (no code yet). Baseline `e7eb382` (v1.1.0).

---

## 1. Provider matrix (verified 2026-10-21)

| Provider | Auth | Creation quota | Read quota | Lifetime | Realtime | ToS notes |
|---|---|---|---|---|---|---|
| **MailboxTemp** (current) | none | 20/min/IP, 10/day free | 100/min/IP | 60 min | WebSocket | no abuse / ban evasion |
| **mail.tm** | none (account + Bearer token) | no daily cap published | **8 QPS/IP** | mailbox persists | SSE (Mercure) | attribution required, no reselling/proxying |
| **mail.gw** | none (same API shape as mail.tm, base `api.mail.gw`) | no daily cap published | **8 QPS/IP** | mailbox persists | SSE (Mercure) | no paid clone of the service |
| **TempMailGrab** | **free API key** (`tmg_live_…`) | 2 inbox/s; 100 req/s per key | 100 req/s | 10 min – 72 h (default 24 h) | webhooks (mobile can't receive) → poll | private inboxes per key |
| Guerrilla Mail | none | unpublished | unpublished | 60 min | polling | "use carefully" — not planned |

**Practical capacity if all are enabled:**

| Setup | Inboxes/day | Parallel |
|---|---|---|
| Current (MailboxTemp free) | ~10 | 10 (burns the day) |
| + mail.tm + mail.gw | 10 + (rate-bound, likely 100s) | 10+ |
| + TempMailGrab key | effectively rate-bound (2 creates/s) | 10+ |

> "Unlimited" is never literal: 8 QPS / 2 creates-per-second still apply, and fair-use/abuse
> rules stay in force. The pool removes the **daily 10** ceiling, not physics.

---

## 2. Architecture

```
UI (Compose)
  └── MainViewModel ── ProviderPool ── QuotaStore (prefs, per provider, UTC day)
                         │
                         ├── MailboxTempProvider  (WebSocket + REST)      existing code, wrapped
                         ├── MailTmProvider       (account+token, SSE/poll)
                         ├── MailGwProvider       (same shape as mail.tm, different base URL)
                         └── TempMailGrabProvider (API key, polling)
                                   │
                            InboxMonitor (provider-agnostic)
                                   │  normalised events
                            UI list / clipboard / notifications
```

### 2.1 Provider contract (new `data/provider/` package)

```kotlin
interface MailProvider {
    val id: String                 // "mailboxtemp" | "mailtm" | "mailgw" | "tempmailgrab"
    val displayName: String
    val dailyLimit: Int?           // null = no published daily cap
    val maxCreationsPerSecond: Int
    val requiresApiKey: Boolean

    suspend fun createInbox(): ProviderInbox      // address + opaque credential + expiresAt
    suspend fun listMessages(inbox: ProviderInbox): List<EmailSummary>
    suspend fun readMessage(inbox: ProviderInbox, id: String): EmailDetail?
    suspend fun extend(inbox: ProviderInbox): Boolean
    suspend fun delete(inbox: ProviderInbox)
    fun openStream(inbox: ProviderInbox, listener: StreamListener): StreamHandle
}
```

* `ProviderInbox.credential` is an **opaque JSON string** owned by the adapter
  (mail.tm → account id + token; TempMailGrab → inbox id + key ref; MailboxTemp → none).
* `StreamHandle` abstracts WebSocket / SSE / polling so `InboxMonitor` stays unchanged in spirit.
* Adapters map their payloads to the existing `EmailSummary` / `EmailDetail`, so the UI is reused.

### 2.2 Data model & migration

`InboxEntry` gains two persisted fields:

```kotlin
val providerId: String = "mailboxtemp"
val credential: String? = null      // opaque, adapter-specific
```

* prefs: `KEY_PROVIDER` / `KEY_CREDENTIAL`, line format `address<TAB>value`.
* **Migration:** existing entries without these keys default to `mailboxtemp` (no data loss).
* Credentials are not bearer tokens for mail.tm/mail.gw (the address is the credential, as today),
  but TempMailGrab's API key **is** — see §6.

---

## 3. Quota & allocation

* `QuotaStore` keeps `providerId → {utcDay, createdCount}` in prefs (same pattern as Batch A).
* `ProviderPool.planBulk(requested)` allocates round-robin over **enabled, non-cooling providers
  with remaining quota**, respecting each provider's `maxCreationsPerSecond`.
* On `429` the provider enters a **cooldown** (exponential, 1 min → 30 min) and the allocator skips it.
* Pure allocation logic lives in `util/ProviderAllocator.kt` so it is unit-testable:

```kotlin
data class ProviderSlot(val id: String, val remaining: Int?, val perSecond: Int,
                        val enabled: Boolean, val cooling: Boolean)

fun plan(requested: Int, slots: List<ProviderSlot>): Map<String, Int>
```

* `+10` fills from the pool: e.g. 4 MailboxTemp (quota allowing) + 3 mail.tm + 3 mail.gw.
* Quota reset and cooldown tests mirror the existing `DailyLimitTest`.

---

## 4. Realtime per provider

| Provider | Strategy | Interval / notes |
|---|---|---|
| MailboxTemp | WebSocket (existing) | push, 30 s ping |
| mail.tm / mail.gw | SSE via Mercure (`/accounts/{id}` topic) with **polling fallback** every 5 s | honour 8 QPS across all inboxes |
| TempMailGrab | polling every 2–3 s per inbox | 100 req/s per key is ample; webhooks need a public endpoint, unusable on mobile |

Rate guard: a shared `RateLimiter` per provider caps total requests/second, so 10 inboxes cannot
exceed 8 QPS on mail.tm by accident.

---

## 5. UI changes

* **Provider chips** above the inbox row: `MailboxTemp 3/10` · `mail.tm ∞` · `TempMailGrab ∞` with
  on/off toggles (disabled providers are skipped by the allocator).
* **Inbox chip badge**: tiny provider initial/dot colour so a mixed list is readable.
* **`+10` split** hint: "will create 4 + 3 + 3 across enabled providers".
* **Settings sheet** (new): enable/disable providers, paste TempMailGrab API key, attribution
  ("Powered by mail.tm · mail.gw") and the per-provider quota reset time.
* Notifications unchanged (per-inbox ids already shipped in v1.1.0).

---

## 6. Security

* TempMailGrab key: store with `androidx.security:security-crypto`
  (`EncryptedSharedPreferences`); never log it and redact it in `HttpLoggingInterceptor`.
* Keep HTTP logging debug-only (already planned in Batch D).
* No key is bundled in the APK; each user supplies their own.
* mail.tm/mail.gw require visible attribution and forbid paid clones — the app is free, so add the
  attribution link in Settings.

---

## 7. Testing

| Type | What |
|---|---|
| Unit | allocator (round-robin, quota, cooldown, per-second caps), quota reset, prefs migration, adapter payload mapping with fixtures |
| Contract (opt-in `-Plive`) | mail.tm: create account → token → list; mail.gw: same; MailboxTemp: existing smoke; TempMailGrab: skipped automatically when no key is configured |
| Regression | existing 46 tests must stay green; `assembleDebug` still runs tests |

---

## 8. Milestones & effort

| Milestone | Work | Effort |
|---|---|---|
| **M0** | `MailProvider` interface + registry + `InboxEntry` fields + prefs migration; wrap the existing MailboxTemp code behind it (no behaviour change) | 0.5 d |
| **M1** | mail.tm adapter: domains → account → token → messages, SSE with polling fallback, 8 QPS limiter, live contract test | 1 d |
| **M2** | `ProviderPool` + allocator + quota store + provider chips/badges/settings skeleton | 0.75 d |
| **M3** | TempMailGrab adapter + API-key settings (encrypted) + polling | 0.75 d |
| **M4** | mail.gw adapter (same shape as mail.tm, different base URL + Mercure topic) | 0.25 d |
| **M5** | Unit tests, docs, APK release | 0.5 d |
| **Total** | | **~3.75 days** |

Order matters: M0–M2 already remove the 10/day ceiling with mail.tm (~2.25 days), so a usable
release can ship before TempMailGrab/mail.gw.

---

## 9. Risks

| Risk | Mitigation |
|---|---|
| Provider API/ToS changes | adapters isolate it; disable a provider remotely via settings |
| mail.tm/mail.gw 8 QPS shared per IP | `RateLimiter` per provider; polling backoff; SSE preferred |
| TempMailGrab key leakage | encrypted prefs, debug-only logging, user-supplied key |
| mail.tm account creation may be throttled in practice | measured in M1 contract test; allocator cools down on 429 |
| mail.gw docs last updated 2023 | lowest priority (M4), can be dropped |
| More providers = more edge cases | all adapters share one interface and one test suite |
| "Unlimited" expectations | UI shows per-provider quota; docs stay explicit |

---

## 10. Decisions needed

1. **Default providers:** MailboxTemp + mail.tm (recommended). mail.gw include?
2. **TempMailGrab:** do you want to create the free API key? Without it that adapter is dead weight.
3. **Attribution:** "Powered by mail.tm · mail.gw" link in Settings — OK?
4. **API key storage:** encrypted prefs (`security-crypto`, adds ~50 KB) — OK?
5. **Guerrilla Mail:** skip (unpublished limits) — confirm.
6. **Release target:** ship after M2 (mail.tm only, ~2.25 d) or wait for the full pool (~3.75 d)?
