# android-app-elabify

**Component**: Maknoon on Android (package `com.elabify.app.maknoon`). The Android
peer of `ios-app-maknoon`: the reference consumer surface for the
Elabify/Musnad post-quantum identity + wallet stack.
**Status**: Shipping. Jetpack Compose, minSdk 33.
**Hard constraint**: **GMS-free** (runs on GrapheneOS with no Google Play Services).

iOS is the reference; Android mirrors it. Same three tabs (Identity, Wallet,
Apps), same behaviour. Trust-critical logic lives in the SDK, `elabify-core`, and
the hardware-wallet crates, not in this UI.

---

## 1. Stack

| Layer | Choice |
|---|---|
| Min / target SDK | 33 / 35 |
| ABI | arm64-v8a only (16 KB-page aligned) |
| UI | Jetpack Compose |
| Language | Kotlin, coroutines + Flow |
| Trust core | `musnad-sdk` (composite `includeBuild` of `../android-sdk-musnad`) |
| Canonical crypto / wire format | `elabify-core` (Kotlin binding) |
| PQ crypto | `pq-crypto-rs` (pure-Rust ML-DSA-65, byte-exact with iOS CryptoKit) |
| Hardware wallets | `ledger-btc/eth/sol/tron-core.aar` + `trezor-core.aar` |
| Wallet chains | WalletCore (EVM/SOL/TRON), BDK (Bitcoin), LND-hub (Lightning) |
| WalletConnect | Reown WalletKit (EVM only), firebase + gms groups excluded |
| Passport | JMRTD / scuba (ICAO 9303, Passive Auth) |
| YubiKey | yubikit-android (NFC/USB, GMS-free) |
| QR scanning | Camera2 + ZXing (no ML Kit) |

**GMS-free is enforced.** No Firebase / Crashlytics / analytics; the build strips
the `com.google.firebase` and `com.google.android.gms` groups and a `checkNoGms`
gate fails the build if they reappear. WalletConnect is serviced in the
foreground (no push), the scanner is Camera2 + ZXing, and YubiKey uses
yubikit-android directly. Distribution is via Play internal + a signed APK for
Obtainium (GrapheneOS testers often have no Play Store). See `RELEASE.md`.

---

## 2. Identity tab

Post-quantum identity and credentials (parity with iOS §4):

- Hold ML-DSA-65-signed credentials; passport documents fold their scanned form
  and issuer-issued form into one card.
- Receive: QR / pickup, Emirates ID (OCR + liveness), and ICAO 9303 passport
  tap-read over NFC with CSCA Passive Authentication against a signed CSCA bundle.
- Present: selective disclosure over rotating multi-frame QR, a one-shot network
  drop, or a no-PII badge; biometric-gated.
- Verify credential (holder as verifier): a single combined verdict from offline
  crypto checks plus holder-independent on-chain verification (issuer registered,
  not revoked, root current, header signature) with no verifier server. Identity
  checks run on the issuer's chain (Sepolia); revocation + root run on whichever
  chain the credential is anchored on, e.g. Base Sepolia, using the anchor's own
  registry address. Plus HAVID issuer X.509 cross-endorsement and passport CSCA
  provenance. Badges run the same issuer-assurance checks minus the signature.
- Identity Sandwich: an optional hardware-wrapped second factor via Ledger,
  Trezor, or YubiKey.

---

## 3. Wallet tab

Multi-chain self-custody wallet:

- **Chains**: Bitcoin, Ethereum and EVM chains (including Base, plus the Sepolia
  and Base Sepolia testnets), Solana, Tron, and Lightning (LND-hub custodial
  accounts).
- **Hardware wallets**: Ledger and Trezor, both across all four chains.
  - Trezor prefers USB-OTG on Android and falls back to BLE; Ledger connects over
    BLE. Trezor uses a hand-rolled THP v2 transport (`trezor-core`).
  - Hidden (passphrase) wallets on every add / discover / send path (Standard,
    OnDevice, or HostTyped passphrase modes for Trezor).
  - Custom and alternative BIP32 derivation paths per account
    (`setDerivationPathOverride`), and Bitcoin script types by purpose (BIP44 /
    BIP49 / BIP84).
- **WalletConnect**: EVM sessions via Reown WalletKit, foreground-only.
- **Commerce / Pay**: settle payments across Bitcoin, EVM, Solana, Tron, and
  Lightning.

---

## 4. Apps tab

A sandboxed mini-app host: HTML/JS bundles served from a SHA-256-pinned local
store inside a `WebView` (`WebViewAssetLoader`), with a catalog to browse and
install. Bridges exposed to a mini-app: `window.ethereum` (EIP-1193),
`window.maknoon.identity` for credential presentation, and payment + commerce
bridges. A mini-app never receives raw key material; sensitive operations route
back through the app's biometric-gated, permission-scoped handlers.

---

## 5. Build

- Requires `JAVA_HOME` on a JDK 17 (e.g. Zulu 17); `./gradlew :app:assembleRelease`.
- arm64-v8a only; the release build embeds a native symbol file and pins the NDK.
- `versionCode` is derived in CI from the run number (monotonic); `versionName`
  is the marketing version. About shows the git short commit.
- Release signing uses a gitignored `keystore.properties`; absent it, release
  builds fall back to the debug key (buildable, not shippable). See `RELEASE.md`.

---

## 6. Privacy

No analytics, crash reporting, or telemetry. Keys and seeds never leave the
device; hardware-wallet secrets never leave the device. `allowBackup` is off. See
`PRIVACY.md`.

---

## 7. Testing

JUnit + Compose UI tests. On-device verification of user-facing flows is the
release gate (a green build is not "tested"); the GrapheneOS Pixel is the
reference device.

---

*Android peer of Maknoon iOS. Trust-critical logic lives in `musnad-sdk`,
`elabify-core`, `pq-crypto-rs`, and the hardware-wallet crates, not in this UI.*
