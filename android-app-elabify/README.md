# android-app-elabify (M5b)

**Component**: Elabify Android super-app: reference consumer surface for the Musnad identity SDK
**Milestone**: M5b (depends on M5a `android-sdk-musnad`)
**Status**: Spec complete · Phase 0.2 BLE work tracked (not implemented)
**Owner**: TBD

## Phase 0.2 status (added 2026-05-20)

The BLE engagement + transport architecture is documented in
ADR-0028 (internal design doc, not published)
and shipped on iOS at `ios-app-maknoon/Maknoon/Transport/`.
Android needs the parallel implementation:

| Component | iOS reference | Android scope |
|---|---|---|
| Engagement wire format | `Transport/TransportSession.swift` (lines 1-30) | unchanged JSON shape |
| HPKE XWingMLKEM768X25519 ciphersuite | iOS 26 CryptoKit native | needs BouncyCastle + custom X-Wing impl OR plain ML-KEM-768 fallback |
| BLE peripheral (holder advertises) | `BLEPeripheralHost.swift` | `BluetoothLeAdvertiser` + GATT server |
| BLE central (verifier scans + connects) | `BLECentralClient.swift` | `BluetoothGatt` client |
| GATT layout | `service / handshake (0x01) / payload (0x02) / status (0x03)` | mirror UUID derivation |

Cross-platform interop matrix (target):

|  | iOS holder | Android holder |
|---|---|---|
| iOS verifier | ✅ Phase 0.1 ships | needs Phase 0.2 |
| Android verifier | needs Phase 0.2 | needs Phase 0.2 |
| Chrome desktop verifier | Web Bluetooth scaffold landed in `react/src/verifier/WebBluetooth.tsx`; heterogeneous KEM bridge still open | needs Phase 0.2 |

The largest open question is the X-Wing KEM bridge: Apple ships native
`XWingMLKEM768X25519` in iOS 26 CryptoKit; Android equivalents would
need either a BouncyCastle X-Wing implementation (none ships
out-of-the-box) or a fallback to plain ML-KEM-768 from `pqclean`
(would require the iOS holder to advertise a second ciphersuite in
the engagement payload). Either path is straightforward and tracked
as the first concrete Android task.

---

## 1. Purpose

Elabify on Android is the peer of `ios-app-maknoon`. It is the **super-app** that demonstrates the full Musnad SDK surface on Android, and is the reference UX implementation that embedding partners look at when they ask "how should we wire X into our Android app?".

Elabify is **not** the trust-critical layer. Every cryptographic operation, every key, every credential, every selective-disclosure presentation runs inside `MusnadSDK` (`android-sdk-musnad`). The split is recorded in ADR-0016 (internal design doc, not published).

The Android Elabify and iOS Elabify aim for feature and behavioural parity. Cross-platform tests exercise the same flows on both. A user with credentials on iOS who switches to Android (re-enrolls via Trezor) should find the same tabs in the same order doing the same things.

---

## 2. Why "super-app"

Same framing as the iOS app: Elabify surfaces every Musnad SDK capability in one app: wallet, presentation, KYC issuance, on-chain status, plus the DeFi marketplace demos (Uniswap v4 hook, Railgun, AA validator). Partners install Elabify, run through pilot flows, and see what they would inherit by embedding the SDK.

See [`ios-app-maknoon/README.md`](../ios-app-maknoon/README.md) §2 for the full capability matrix; the Android app implements the same set.

---

## 3. Stack

| Layer | Choice | Rationale |
|---|---|---|
| Min SDK | API 33 (Android 13) | Matches the SDK's floor |
| Target SDK | latest stable | Standard Play Store policy |
| UI | Jetpack Compose 1.7+ | Modern declarative |
| Language | Kotlin 2.0+ | Coroutines + Flow |
| Identity / crypto / network | `MusnadSDK` AAR | All trust-critical logic |
| State | MVI via Molecule + StateFlow | Predictable, testable |
| Charts / DeFi widgets | Compose Charts (Vico) | Open-source, no third-party telemetry |
| DI | Hilt | Standard for new Android |
| Testing | JUnit5 + Mockk + Espresso + Compose UI test | Standard |

What Elabify does NOT add beyond the SDK's dependency floor:
- No Firebase / Crashlytics / Mixpanel / Sentry / any third-party analytics or crash reporter.
- No third-party HTTP libraries (the SDK provides a pinned client; the app uses it).
- No cross-platform abstractions (React Native, Flutter, Capacitor).

---

## 4. App architecture

```
┌────────────────────────────────────────────────────────────────┐
│                  Compose Screens (Elabify)                     │
│  WalletScreen · ReceiveScreen · PresentScreen                  │
│  MarketplaceScreen · SettingsScreen · ProfileScreen            │
└──────────────────────────┬─────────────────────────────────────┘
                           │ MVI intents
┌──────────────────────────▼─────────────────────────────────────┐
│                ViewModels / Presenters (Elabify)               │
│  WalletPresenter · ReceivePresenter · PresentPresenter         │
│  MarketplacePresenter · SettingsPresenter                      │
└──────────────────────────┬─────────────────────────────────────┘
                           │ suspend function calls
┌──────────────────────────▼─────────────────────────────────────┐
│                       MusnadSDK (M5a)                          │
│  MusnadIdentity · MusnadWallet · MusnadIssuance                │
│  MusnadPresentation · MusnadChainClient                        │
│  PresentationApprovalScreen (Compose, FLAG_SECURE, sealed)     │
└────────────────────────────────────────────────────────────────┘
```

Elabify never imports `elabify-core`, `pqclean`, or platform crypto APIs directly. It imports `MusnadSDK` and uses what the SDK exposes.

---

## 5. User-visible surfaces

Same seven surfaces as iOS Elabify (`ios-app-maknoon/README.md` §5):

1. **Onboarding**: mode selection (Trezor / software-only), biometric setup, optional Trezor connect.
2. **Wallet**: credential list, detail view, biometric-gated `disclose(...)`.
3. **Receive**: QR scan, deep-link receipt, Emirates ID OCR + liveness.
4. **Present**: QR scan, deep-link receipt, embedded `PresentationApprovalScreen`.
5. **Marketplace**: Uniswap v4 hook demo, Railgun private swap demo, AA validator demo.
6. **Settings**: Identity Sandwich status, hardware connection, privacy posture, diagnostics.
7. **Profile**: DID display + QR, refresh credentials, about.

Android-specific differences from iOS:

- **Trezor connection**: USB-OTG is the preferred transport on Android (vs. BLE on iOS). The Settings hardware screen prefers USB and falls back to BLE / WebUSB-relay only if USB is unavailable.
- **Deep links**: registered as App Links (universal-link equivalent) plus the `elabify://` scheme. Domain verification handled via `assetlinks.json` on the Elabify domain.
- **Back gesture**: predictive back is enabled across all screens.
- **Photo picker**: for any user-uploaded image flows (rare in Elabify), uses the API 33 photo picker, never broad media permissions.
- **Theming**: supports themed app icons (API 33+) and Material You dynamic color.
- **Localization**: English + Arabic, with Arabic RTL layout verified via Compose preview snapshots.

---

## 6. State management

MVI via Molecule presenters. Each presenter holds a small piece of view state (search filter, scroll position, sheet visibility) and a reference to the `MusnadSDK` instance (Hilt-injected as singleton).

Long-running async work uses Coroutines with structured cancellation tied to the screen's `LifecycleOwner`; navigating away cancels in-flight SDK calls.

No credential plaintext, no key material, no claim values are retained in presenter state across configuration changes.

---

## 7. Deep link handling

`AndroidManifest.xml` registers the `elabify://` scheme and the verified App Link host. `MainActivity.onNewIntent` forwards every incoming `Intent.data` to `MusnadSDK.classify(...)` first; only after the SDK has validated does Elabify route to the corresponding screen.

URLs the app accepts:
- `elabify://issuance/pickup/<token>`
- `elabify://present/<base64-request>`
- `https://issue.elabify.example/pickup/<token>`

Any URL the SDK classifies as `MusnadDeepLink.Unknown` is ignored with a Snackbar; Elabify never hand-parses URI components for cryptographic consumers.

---

## 8. Performance budgets

Pixel 7 / Galaxy S22-class targets:

| Operation | Target |
|---|---|
| App cold start to wallet screen | < 1.8 s |
| Tab switch | < 100 ms |
| Wallet list render (50 credentials) | < 200 ms |
| Marketplace tab cold render | < 500 ms |

SDK-bound budgets are stated in `android-sdk-musnad/README.md` §9.

---

## 9. Testing

- **Unit**: presenters tested with mocked SDK interfaces (Mockk).
- **UI**: Espresso + Compose UI test covering onboarding, receive (pickup + OCR), present, settings, marketplace.
- **Snapshot**: Paparazzi screenshot tests for each screen in light + dark, English + Arabic (RTL), Material You + non-Material You.
- **End-to-end**: against the docker-compose stack from `android-sdk-musnad/embed-tests/`.
- **Cross-platform**: a presentation initiated on iOS Elabify is verified on the same verifier server when initiated from Android Elabify (and vice versa).

Static checks:
- No imports outside `MusnadSDK`, AndroidX, Compose, Hilt, Vico, Mockk.
- No `OkHttpClient` or `HttpURLConnection` calls in app code (must go through SDK).
- No `Log.d / Log.e` of Credential, Claims, Delegation types.

---

## 10. Configuration

`build.gradle.kts` `BuildConfig` fields, forwarded into `MusnadConfig`:

| Field | Example | Notes |
|---|---|---|
| `ELABIFY_DEFAULT_ISSUER_HOSTS` | `["issue.elabify.example"]` | `MusnadConfig.allowedIssuerHosts` |
| `ELABIFY_PINNED_FINGERPRINTS` | JSON string | Parsed into `pinnedFingerprintsByHost` |
| `ELABIFY_DEFAULT_CHAIN_RPC` | URL | `MusnadConfig.chainRpcUrl` |
| `ELABIFY_IDENTITY_REGISTRY_ADDR` | `0x...` | |
| `ELABIFY_REVOCATION_REGISTRY_ADDR` | `0x...` | |
| `ELABIFY_DEFAULT_DELEGATION_HOURS` | `24` | |
| `ELABIFY_TREZOR_COIN_TYPE` | `9999` | |

Runtime user prefs (in `DataStore` Preferences, non-sensitive):
- Selected mode (Trezor / Software).
- Selected language.
- "Show on-chain status by default".
- "Show marketplace demos".

---

## 11. Play Store / privacy

- **Data Safety form**: paste from the SDK's contribution doc (`META-INF/PRIVACY.md`), then add Elabify-specific data uses (none beyond the SDK).
- **Permissions declared in manifest**: `INTERNET`, `CAMERA`, `USE_BIOMETRIC`, `BLUETOOTH_CONNECT` (Track B), `USB_HOST` (Track B). No location, no contacts, no phone state.
- **Data backup**: `android:allowBackup="false"` for Elabify; SDK data already excluded via `backup_rules.xml`.
- **Pre-launch testing**: include test Trezor or Track A toggle for review team.

---

## 12. Implementation checklist

- [ ] Min SDK 33; target latest stable.
- [ ] All screens use Jetpack Compose.
- [ ] `MusnadSDK` integrated; SDK is the only identity / crypto path.
- [ ] All seven primary surfaces (§5) implemented and snapshot-tested.
- [ ] Marketplace tab demos wired to SDK + integration backends.
- [ ] No third-party identity / network code outside the SDK (CI lint).
- [ ] Data Safety form drafted; SDK contribution merged.
- [ ] All performance budgets met on Pixel 7-class hardware.
- [ ] Localization complete for English + Arabic (RTL).
- [ ] All tests in §9 passing.

---

## 13. What this AI agent is allowed to assume

- `MusnadSDK` (M5a) is shipped as an AAR via Maven Central and produces the public API in `android-sdk-musnad/README.md` §4.
- Backend services (issuer, verifier) implement the wire formats per `the wire-format spec` (internal design doc, not published).
- API 33+ is the minimum target.
- A pilot deployment of issuer + verifier + Sepolia is reachable for end-to-end testing.

If a feature requires SDK functionality not yet shipped (e.g. Track B Trezor when firmware is unavailable), Elabify hides the corresponding marketplace surface behind a feature flag rather than implementing the missing piece in the app.

---

*End of android-app-elabify/README.md · v1.0*
