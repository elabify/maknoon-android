# Elabify for Android, Privacy Policy

**Effective date**: 2026-05-25
**Applies to**: the Elabify Android application (the M5b super-app, Android counterpart to Maknoon for iOS)
**Status**: Application is pre-release. Specification is complete; an implementation is in progress per [`README.md`](README.md).
**Publisher**: Elabify

Elabify on Android is a self-custodial holder app. It is the Android reference surface for the Musnad post-quantum identity stack and surfaces the same wallet, presentation, issuance, and marketplace flows as the iOS counterpart. This document describes what data the app handles, where that data lives, and what (very little) leaves your device.

For the project-wide privacy stance, see [`/PRIVACY.md`](https://musnad.elabify.com/privacy) at the repository root. This document is the Android-app overlay.

---

## 1. Plain-language summary

1. Elabify on Android does **not** ship analytics, crash reporting, advertising SDKs, attribution SDKs, or any third-party telemetry. There is no Firebase, no Crashlytics, no Sentry, no Mixpanel, no Amplitude. The dependency floor is documented in [`README.md`](README.md) §3.
2. Identity material (Trezor delegations, ML-DSA-65 signing keys, FIDO2 wrap material, BIP39 entropy) lives only inside `MusnadSDK` on the device. Wallet keys live in the platform Keystore behind biometric authentication. None of this leaves the phone.
3. When you broadcast a Bitcoin or Ethereum transaction, that transaction is published to the relevant public blockchain by definition. That is a property of the blockchains.
4. Elabify talks to a small set of public endpoints (mempool.space or equivalent, a configured Ethereum RPC, optionally an issuer or verifier you choose to scan a QR for or open a deep link to). It does not phone home to any Elabify service for telemetry.
5. `android:allowBackup="false"` is set on the manifest; SDK-side data is additionally excluded via `backup_rules.xml`. Uninstalling the app removes all app-local storage.

---

## 2. Information processed on the device

### 2.1 Keys and secrets (never leave the phone)
- BIP39 master entropy and derived signing keys for Bitcoin and Ethereum wallets.
- ML-DSA-65 signing key for credential presentations.
- Identity Sandwich wrap material: AES-256-GCM-sealed master entropy plus the per-device wrap blobs from your enrolled hardware devices.
- FIDO2 credential identifiers for any YubiKey enrolled in the Identity Sandwich, when present.
- Trezor-signed delegation certificates and the resulting ephemeral keys (default 24 h lifetime, max 7 days).

All of the above are stored exclusively in Android Keystore-backed storage inside `MusnadSDK`. Access is gated by `BiometricPrompt` via `AndroidX Biometric`. The hardware-backed StrongBox or TEE wraps the items where the platform supports it. None of these values are transmitted anywhere by Elabify.

### 2.2 Wallet and credential metadata (local persistence)
- The list of wallets and credentials you have created, imported, or received.
- Your chosen labels for wallets, addresses, and outputs.
- The registry of hardware devices you have paired (kind, serial, label, BLE peripheral UUID for BLE devices).
- Cached credential records you have received from issuers. The encrypted PII envelope inside each credential remains encrypted at rest.
- DataStore Preferences for non-sensitive user prefs only: selected mode (Trezor or software), language, "show on-chain status by default", "show marketplace demos". Documented in [`README.md`](README.md) §10.

This metadata lives in app-private storage under the SDK's data root and is excluded from auto-backup.

### 2.3 Diagnostic logs
The SDK keeps a small in-process ring buffer for troubleshooting. The buffer never records credential plaintext, claim values, private keys, or wrap material; the CI lint enforces this rule (see [`README.md`](README.md) §9, "No `Log.d / Log.e` of Credential, Claims, Delegation types"). A "Share diagnostics" affordance lets you send the buffer to support yourself; Elabify never uploads it on your behalf.

---

## 3. Network endpoints Elabify contacts

Elabify makes outbound network calls only in response to a user action. All network calls go through `MusnadSDK`'s pinned HTTP client; app code does not open arbitrary sockets (enforced by CI lint per [`README.md`](README.md) §9).

| Purpose | Default endpoint | What is sent |
|---|---|---|
| Issuer endpoint, when you accept a credential | The host configured in `ELABIFY_DEFAULT_ISSUER_HOSTS` and pinned via `ELABIFY_PINNED_FINGERPRINTS` | The issuance acknowledgement defined in the Musnad wire formats |
| Verifier endpoint, when you complete a presentation | The verifier host in the request you scanned, validated by the SDK against your allowlist | A ML-DSA-65-signed presentation with selective-disclosure encryption of any PII leaves |
| Chain RPC, for on-chain issuer status and revocation | `ELABIFY_DEFAULT_CHAIN_RPC` | Standard JSON-RPC reads against `IdentityRegistry` and `RevocationRegistry` at the configured addresses |
| Bitcoin mempool and broadcast, when wallet features are enabled | mempool.space-class endpoint | Your wallet's descriptor-derived addresses and signed transactions |

Elabify does not contact any Elabify-operated telemetry, analytics, crash-report, attribution, or feature-flag endpoint. There is no such endpoint to contact.

Deep links and intents pointing at hosts outside the configured allowlist are classified as `MusnadDeepLink.Unknown` by the SDK and ignored with a Snackbar; Elabify never hand-parses URIs for cryptographic consumers ([`README.md`](README.md) §7).

---

## 4. Hardware wallet transports

- **Trezor over USB-OTG**: the preferred transport on Android. Sent: protocol-buffer requests defined by Trezor firmware. Received: device-side confirmations and signatures. No data leaves the phone besides via the cable.
- **Trezor over BLE**: fallback transport, paired to a specific peripheral UUID.
- **YubiKey over NFC**: NFC tap to the back of the device opens a FIDO2 session via `YubiKitManager`. Used for Identity Sandwich enrollment and unlock. Wrap signatures are computed on the key and consumed locally.

Hardware-device serials and (for BLE) peripheral UUIDs are recorded in app-local storage so subsequent connects target the same physical device. These identifiers are not transmitted to any Elabify service.

---

## 5. Information Elabify does not collect

Elabify does **not** collect, store, or transmit:

- Your name, address, phone number, email address, or other directly identifying contact data.
- Your location.
- Your photos, contacts, microphone, calendar, or health data.
- Android Advertising ID. The app does not link to Google Play Services Ads and does not request the `com.google.android.gms.permission.AD_ID` permission.
- Crash reports or ANRs. Android's platform crash reporting may collect anonymous data subject to your Google account and Play Services settings; Elabify is not the recipient and the app does not embed Crashlytics or any other third-party crash reporter.
- App-usage telemetry, screen views, button taps, or session metrics.

---

## 6. Permissions Elabify declares

| Manifest permission | Reason | Trigger |
|---|---|---|
| `INTERNET` | All network operations via the SDK's pinned HTTP client | Implicit, all flows |
| `CAMERA` | Scan QR codes from issuers, verifiers, or hardware devices | QR scan flows; permission requested at use |
| `USE_BIOMETRIC` | Gate signing operations behind `BiometricPrompt` | First and every subsequent signing operation |
| `BLUETOOTH_CONNECT` | Talk to a paired Trezor over BLE | Hardware wallet BLE operations; permission requested at use |
| `USB_HOST` | Talk to a Trezor over USB-OTG | Hardware wallet USB operations |
| `NFC` | Talk to a YubiKey over NFC, if YubiKey is in scope | YubiKey serial read or FIDO2 wrap |

Elabify does not request `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`, `READ_CONTACTS`, `READ_SMS`, `READ_PHONE_STATE`, `READ_EXTERNAL_STORAGE`, microphone, or any other sensitive permission. The API 33 photo picker is used for any rare image-pickup flows, which does not require a broad media permission.

---

## 7. Backups, sync, and reset

- **Android auto-backup**: `android:allowBackup="false"` is set on `<application>`. The SDK additionally declares `<full-backup-content>` rules that exclude every directory containing key or credential material.
- **Cross-device sync**: no key, credential, or wrap material is synced to your Google account or any cross-device store. You re-enroll on a new device via your paper seed and your registered hardware device.
- **Reset**: the in-app reset option wipes every Identity Sandwich blob, every wrapped material item, every registered device, every chain wallet, and every label. After reset you can restore from your 24-word paper seed.
- **Uninstall**: Android removes all app-local storage when you uninstall the app.

---

## 8. Children

Elabify is not directed at children under 13 and Elabify does not knowingly handle data from children. The app is intended for users who can legally hold a self-custodial wallet in their jurisdiction.

---

## 9. Regional notes

- **GDPR / UK GDPR**: Elabify does not act as a controller of personal data because no personal data is sent to any server Elabify operates. Issuance and presentation flows you initiate may involve an issuer or verifier that is itself a controller; you contract with those parties directly.
- **CCPA / CPRA**: Elabify does not sell or share personal information about you because Elabify does not have personal information about you.
- **UAE PDPL**: same posture as GDPR: there is no cross-border transfer because there is no transfer at all.

---

## 10. Play Store Data Safety

The Play Store Data Safety form is filled out as follows when this app is published:

- **Data collected**: none.
- **Data shared with third parties**: none.
- **Encryption in transit**: yes (TLS 1.3 with pinned fingerprints).
- **User can request data deletion**: trivially, by uninstalling. There is no server-side dataset to delete because Elabify does not run a server-side dataset.

The Data Safety form is regenerated from this file and the SDK's privacy contribution at every release; see [`README.md`](README.md) §11.

---

## 11. Changes to this policy

Material changes land as commits to this file in the public repository. The Effective date at the top of this document is updated when the substance changes. The git history is the canonical changelog.

---

## 12. Contact

Privacy questions: `privacy@elabify.com`
Security issues: `security@elabify.com` (please do not file public GitHub issues for unpatched vulnerabilities)
