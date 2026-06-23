# android-sdk-musnad (M5a)

**Component**: Embeddable Android SDK for post-quantum identity custody and selective disclosure
**Milestone**: M5a (mirrors `ios-sdk-musnad` after iOS ships)
**Status**: Spec complete · not implemented
**Owner**: TBD

---

## 1. Purpose

`musnad-sdk` is the Android peer of `ios-sdk-musnad`. It is consumed by:

- **The Elabify Android super-app** (`android-app-elabify`): the reference consumer surface.
- **Third-party host apps**: banks, exchanges, custodians who want post-quantum identity custody and selective disclosure inside their Android app.

The SDK is **trust-critical**. Every byte of long-term key material, every ML-DSA signature, every selective-disclosure presentation passes through this code. The architectural separation is recorded in ADR-0016 (internal design doc, not published). Read that ADR before changing the SDK/host boundary.

The Android SDK aims for behavioural parity with the iOS SDK at the wire level. Cross-platform tests exercise the same KAT vectors and present-then-verify flows on both platforms; a credential issued on iOS MUST be readable on Android, and vice versa.

---

## 2. Scope: same as iOS SDK

The "what is in the SDK and what is not" matrix from `the Musnad iOS SDK` §2 applies verbatim. The Kotlin API in §4 below is the line-by-line peer of the Swift API in `the Musnad iOS SDK` §4.

---

## 3. Stack

| Layer | Choice | Rationale |
|---|---|---|
| Min SDK | API 33 (Android 13) | See §3.1; bank-grade floor with modern Keystore, photo picker, predictive back, runtime notification permissions |
| Target SDK | latest stable (API 35 at time of writing) | Standard Play Store policy |
| Distribution | Maven Central as `.aar` library | Standard for Android library consumers |
| Module | `com.elabify.musnad:musnad-sdk` | Gradle coordinates |
| Language | Kotlin 2.0+ with Coroutines + Flow | Standard for new Android |
| UI | Jetpack Compose 1.7+ | Modern declarative; UIKit-equivalent of SwiftUI for view modifiers |
| PQ crypto | PQClean compiled to `.so` for `arm64-v8a`, `x86_64`; loaded via JNI | Audited reference implementation; matches iOS |
| Hash + Merkle + canonical JSON | `com.elabify:elabify-core-android` (pure Kotlin AAR from M0) | Cross-impl parity via the frozen JSON KAT corpus; see ADR-0017 (internal design doc, not published) |
| Local DB | Room with SQLCipher (AES-256-CBC by default) | Encrypted at rest; well-audited |
| Networking | OkHttp 4.x with `CertificatePinner` | De-facto Android standard; canonical pinning API |
| Trezor comm | USB Host API (USB-OTG), WebUSB-relay over WebSocket; BLE via `BluetoothLeScanner` | See §5 |
| OCR | ML Kit on-device text recognition + CameraX | On-device, no Google Cloud calls |
| Biometrics | `androidx.biometric:biometric` (BiometricPrompt) | Standard, Class 3 (Strong) required |
| Background work | `androidx.work` (WorkManager) for scheduled status refresh | Standard |
| Testing | JUnit5 + Mockk + Espresso + Compose UI test | Standard |

### 3.1 Why API 33 (Android 13)

Bank reference: HSBC UK currently allows Android 9.0+ (10+ for full feature set). HSBC Hong Kong moved its floor to Android 10+ from March 2026. We sit comfortably above bank-grade because:

- This is a greenfield product with no install base to maintain.
- API 33 brings the photo picker (privacy-friendly, no broad media permission), predictive back gestures, themed app icons, and notification runtime permissions; all of which materially improve the user-facing privacy posture.
- StrongBox-backed Keystore is reliable across vendors at API 31+; API 33 hardens runtime permissions further.
- The long tail below API 33 is largely devices that are 4+ years old; their hardware-attested key storage is uneven.
- Above-bank floor signals a deliberate "modern devices only" posture for a product whose entire premise is forward-looking cryptography.

If a partner needs to ship on a lower floor, the conversation is "use a fork" or "wait for the parity backport ADR", not "we'll silently lower the floor."

### 3.2 What the SDK does NOT depend on

- No Firebase, no Crashlytics, no Mixpanel, no Sentry, no third-party analytics; ever.
- No Google Play Services-only code paths beyond what's required for biometric attestation and (optionally) ML Kit's on-device models. The SDK works on AOSP and de-Googled devices for the credential / presentation core; only the OCR feature requires Play Services on devices without ML Kit's on-device shipping module.
- No closed-source AAR dependencies. `elabify-core-android` is a pure-Kotlin AAR (no native code, no `.so`); the only native library shipped is `pqclean.so` (audited PQClean C source, JNI-loaded).
- No cross-platform abstraction (React Native, Flutter, KMM-shared UI) inside the SDK.

A host app may have all of those things in its own code. The SDK's dependency surface is what banks have to vet.

---

## 4. Public API

The Kotlin API mirrors the Swift API one-for-one. Type names match where possible; serialization is identical; error variants are isomorphic.

### 4.1 Initialization

```kotlin
// AI: do not deviate
data class MusnadConfig(
  val mode: Mode,
  val allowedIssuerHosts: Set<String>,
  val pinnedFingerprintsByHost: Map<String, Set<ByteArray>>,
  val chainRpcUrl: String,
  val identityRegistryAddress: String,
  val revocationRegistryAddress: String,
  val defaultDelegationLifetime: Duration = 24.hours,
  val trezorCoinType: UInt = 9999u,
  val logSink: MusnadLogSink,
) {
  enum class Mode { TREZOR_BACKED, SOFTWARE_ONLY }
}

class MusnadSDK private constructor(/* ... */) {
  companion object {
    fun init(context: Context, config: MusnadConfig): MusnadSDK
    val version: String
  }
}
```

The host constructs one `MusnadSDK` instance at app launch (typically in `Application.onCreate`) and retains it. Re-initializing across configurations invalidates in-memory ephemeral key handles.

### 4.2 Identity

```kotlin
// AI: do not deviate
interface MusnadIdentity {
  suspend fun holderDid(): String
  suspend fun hasActiveDelegation(policy: DelegationPolicy? = null): Boolean
  suspend fun createDelegation(lifetime: Duration, scope: List<DelegationScope>): Delegation
  suspend fun revokeActiveDelegation()
  suspend fun currentStatus(): IdentityStatus
}
```

### 4.3 Wallet

```kotlin
// AI: do not deviate
interface MusnadWallet {
  suspend fun listCredentials(): List<CredentialSummary>
  suspend fun credentialDetail(id: CredentialId): CredentialDetail

  /// Decrypts and yields claim values to `body`, gated by BiometricPrompt.
  /// Returned values are zeroized after `body` returns.
  suspend fun <T> disclose(
    id: CredentialId,
    keys: Set<String>,
    body: suspend (Map<String, ClaimValue>) -> T,
  ): T

  suspend fun refreshStatus(id: CredentialId): CredentialStatus
}
```

The `disclose(...)` block uses Kotlin's `use`-style closure pattern to give claim values a deterministic lifetime. Plaintext is zeroized before the function returns to the caller, with a `try { ... } finally { zeroize(...) }` enforced by the SDK.

### 4.4 Issuance

```kotlin
// AI: do not deviate
interface MusnadIssuance {
  suspend fun resolvePickup(uri: Uri): IssuancePreview
  suspend fun acceptIssuance(preview: IssuancePreview): CredentialId

  /// Run on-device Emirates ID OCR via ML Kit + CameraX.
  suspend fun scanEmiratesId(activity: ComponentActivity): EmiratesIdFields

  /// Run on-device biometric liveness (3-second front-camera capture). Returns 32-byte hash.
  suspend fun captureLiveness(activity: ComponentActivity): ByteArray

  suspend fun submitOcrIssuance(
    issuerDid: String,
    fields: EmiratesIdFields,
    livenessHash: ByteArray,
  ): CredentialId
}
```

The `ComponentActivity` parameter is required for camera lifecycle integration; the SDK does NOT attempt to manage activity lifecycle on behalf of the host.

### 4.5 Presentation

```kotlin
// AI: do not deviate
interface MusnadPresentation {
  suspend fun resolveRequest(uri: Uri): PresentationRequest
  suspend fun present(
    request: PresentationRequest,
    disclose: Set<String>,
    includePiiHandoff: Boolean,
  ): PresentationVerdict
}
```

The SDK ships a Compose composable that hosts MUST embed for the approval screen:

```kotlin
// AI: do not deviate
@Composable
fun PresentationApprovalScreen(
  request: PresentationRequest,
  onApprove: (Set<String>, Boolean) -> Unit,
  onCancel: () -> Unit,
)
```

The composable internally:
- Sets `FLAG_SECURE` on the hosting window for the duration of the screen.
- Triggers `BiometricPrompt` (Class 3) before invoking `onApprove`.
- Renders the verifier DID, requested claims, and disclosure toggles in a layout the host cannot replace.

Hosts MAY style surrounding navigation chrome but MUST NOT replace the body of `PresentationApprovalScreen`.

### 4.6 Chain status reads

```kotlin
// AI: do not deviate
interface MusnadChainClient {
  suspend fun issuerStatus(did: String): IssuerStatus
  suspend fun credentialStatus(issuerDid: String, cid: ByteArray): CredentialStatus
}
```

### 4.7 Deep-link routing

```kotlin
// AI: do not deviate
sealed interface MusnadDeepLink {
  data class IssuancePickup(val uri: Uri) : MusnadDeepLink
  data class PresentationRequest(val uri: Uri) : MusnadDeepLink
  object Unknown : MusnadDeepLink
}

fun MusnadSDK.classify(uri: Uri): MusnadDeepLink
```

Hosts forward incoming intents (`Intent.ACTION_VIEW` data URIs) to `classify(...)` first; the SDK never registers intent filters itself.

---

## 5. Identity Sandwich on Android

Re-stated for SDK implementers; design rationale is in ADR-0005 (internal design doc, not published).

### 5.1 Key roles

| Key | Type | Storage | Lifetime | Used for |
|---|---|---|---|---|
| Long-term ML-DSA-65 (Cold) | ML-DSA-65 | Trezor (BIP-39 derived) | Years | Signing delegation certs |
| Wrapping key | AES-256 | Android Keystore, StrongBox-backed when available, biometric-gated | App-install lifetime | Wrapping ephemeral ML-DSA SKs at rest |
| Ephemeral ML-DSA-65 (Hot) | ML-DSA-65 | App-private storage, wrapped under Keystore key | 24 hours (default) | Signing presentations |
| AES PII key | derived | Ephemeral, in-memory | Per-presentation | Encrypting PII payload to verifier |

### 5.2 Why a wrapping AES key, not RSA / EC

Android Keystore supports symmetric AES-256 with `setUserAuthenticationRequired(true)` and `setIsStrongBoxBacked(true)` cleanly. Using AES-GCM as the wrap primitive is simpler and faster than Android's HPKE-equivalent path on EC keys. The iOS SDK uses HPKE over Secure Enclave P-256; the Android SDK uses AES-GCM over a Keystore AES key. The cryptographic boundary (key never leaves hardware) is equivalent.

### 5.3 StrongBox availability

`KeyInfo.isInsideSecureHardware` is checked at SDK init. If StrongBox is available, the SDK uses `setIsStrongBoxBacked(true)`. If not, the SDK falls back to TEE-backed Keystore with a logged warning. If neither is available (rooted emulator, ancient hardware), the SDK refuses to initialize Track A unless `MusnadConfig` is explicitly set to allow software-only Keystore (intended for development only, surfaces a runtime warning to the host).

### 5.4 Track A vs Track B

Same definitions as iOS:
- **Track A (software-only)**: long-term ML-DSA key in app storage, AES-wrapped under Keystore. Acceptable for evaluation, demo, and low-stakes credentials.
- **Track B (Trezor-backed)**: long-term ML-DSA key on Trezor; SDK requests delegation signing.

Track B activation requires Trezor firmware FIPS-204 support; until then, Track B initialization fails with `MusnadError.TrezorFirmwareUnavailable`.

### 5.5 Trezor transport

| Transport | Availability | Notes |
|---|---|---|
| USB Host (USB-OTG) | All API 33+ devices with USB-C | Preferred |
| Bluetooth LE | Trezor Model T BLE-enabled units only | Slower; requires BLUETOOTH_CONNECT runtime permission |
| WebUSB-relay | Always | User runs a small relay app on a nearby computer |

USB Host is the preferred path on Android (unlike iOS where BLE is preferred). The SDK auto-detects and prefers in this order: USB > BLE > relay.

### 5.6 Delegation revocation

Same flow as iOS (§5.4 of the iOS SDK README): SDK constructs revocation marker → Trezor signs (Track B) or SDK signs with long-term key (Track A) → SDK posts to issuer's revocation endpoint.

---

## 6. Trust contract with the host

### 6.1 What the SDK guarantees

Same as iOS SDK §6.1 with Android-specific implementations:

1. **No key material leaves the SDK process boundary.** Long-term SK, ephemeral SK, the Keystore wrapping key, and KEM secrets are unreachable via any public API.
2. **Claim values are decrypted only inside `disclose(...)` closures** and zeroized on closure exit.
3. **All issuer and verifier HTTP traffic is cert-pinned** via `OkHttpClient.certificatePinner`.
4. **`PresentationApprovalScreen` enforces `FLAG_SECURE`, biometric gating, and verifier-DID display.** Hosts cannot bypass.
5. **No telemetry leaves the SDK.**
6. **Deep links are validated** against the SDK's allowlist before any cryptographic operation.

### 6.2 What the host MUST NOT do

Same as iOS SDK §6.2:
- MUST NOT log, persist, or transmit values returned inside a `disclose(...)` closure beyond the closure body.
- MUST NOT replace or wrap `PresentationApprovalScreen` to alter its body composition.
- MUST NOT bypass the SDK to talk to issuer / verifier endpoints directly.
- MUST NOT initialize the SDK with `pinnedFingerprintsByHost` empty.
- MUST NOT retain `MusnadSDK` instances across user-account switches without re-initialization.

### 6.3 Android-specific guarantees

- The SDK declares only the permissions it needs: `INTERNET`, `CAMERA` (for OCR / liveness, with runtime prompt), `USE_BIOMETRIC`, `BLUETOOTH_CONNECT` (Track B BLE only, runtime prompt), `USB_HOST` (Track B USB only, no runtime prompt). The SDK does NOT declare `READ_EXTERNAL_STORAGE`, `READ_CONTACTS`, `READ_PHONE_STATE`, or location permissions.
- The SDK does NOT use `android:allowBackup="true"` for its data; credential blobs are excluded from auto-backup via a `backup_rules.xml` extracted by the Gradle plugin.
- The SDK's `Application.onCreate` hook (via a `ContentProvider` initializer) is opt-in only; hosts that prefer explicit init call `MusnadSDK.init(...)` themselves.

### 6.4 Logging

Same shape as iOS:

```kotlin
// AI: do not deviate
fun interface MusnadLogSink {
  fun log(event: MusnadLogEvent)
}

data class MusnadLogEvent(
  val level: Level,         // DEBUG, INFO, WARN, ERROR
  val category: Category,   // IDENTITY, WALLET, ISSUANCE, PRESENTATION, CHAIN
  val message: String,      // PII-safe
  val context: Map<String, String>,
)
```

The SDK guarantees no log line ever contains plaintext claims, plaintext keys, or plaintext DIDs.

---

## 7. Networking

### 7.1 Backend dependencies

Same as iOS SDK §7.1 (issuer, verifier, chain RPC).

### 7.2 Cert pinning via OkHttp

```kotlin
// AI: do not deviate
class PinnedHttpClient(pins: Map<String, Set<ByteArray>>) {
  private val client = OkHttpClient.Builder()
    .certificatePinner(
      CertificatePinner.Builder()
        .also { builder ->
          pins.forEach { (host, fingerprints) ->
            fingerprints.forEach { fp ->
              // OkHttp expects "sha256/<base64>" pins computed over the SPKI.
              // We compute RPO-256 over the pubkey ourselves to match cross-platform behaviour.
              builder.add(host, "rpo256/" + fp.toBase64())
            }
          }
        }
        .build()
    )
    .build()
}
```

OkHttp's standard `CertificatePinner` uses SHA-256 over SPKI; the SDK extends it with an RPO-256 pin format for parity with iOS. The custom pin format is implemented via an `Interceptor` that runs after TLS handshake and verifies the pin against the negotiated cert pubkey using `ElabifyCore.rpo256` from the M0 Kotlin port.

Pin rotation requires either an SDK update or a signed configuration fetched at first launch (host opt-in).

### 7.3 Chain status reads

Same as iOS SDK §7.3: lazy, cached, no per-launch beaconing.

---

## 8. Local data model

### 8.1 Room schema (SDK-internal)

The SDK owns and manages its own SQLite database (via Room with SQLCipher encryption) inside the SDK's app-private data directory. Hosts do NOT see this schema. Entities mirror the wire-format objects (Credential, Delegation, IssuerMetadata); see `the wire-format spec` (internal design doc, not published).

### 8.2 Encryption at rest

Three layers:

1. App-private storage with `MODE_PRIVATE` permissions.
2. SQLCipher encrypts the database file with an AES-256 key derived via PBKDF2 from a Keystore-held master secret. Key is never present in plaintext on disk.
3. The `claims_encrypted` blob inside each credential is additionally AES-GCM-encrypted under a per-credential symmetric key, which is itself wrapped by the Keystore wrapping key. Reading claims requires the Keystore (which requires biometric).

```kotlin
// AI: do not deviate
fun wrapClaimsKey(key: ByteArray): ByteArray {
  val keystoreKey = AndroidKeyStore.getOrCreateKey(
    alias = "musnad-claims-wrap",
    purposes = KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
    blockModes = arrayOf(KeyProperties.BLOCK_MODE_GCM),
    paddings = arrayOf(KeyProperties.ENCRYPTION_PADDING_NONE),
    keySize = 256,
    userAuthenticationRequired = true,
    isStrongBoxBacked = isStrongBoxAvailable(),
  )
  val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
    init(Cipher.ENCRYPT_MODE, keystoreKey)
  }
  return cipher.iv + cipher.doFinal(key)
}
```

Claim values are decrypted into memory only inside `MusnadWallet.disclose(...)` closures.

---

## 9. Performance budgets

Pixel 7 / Galaxy S22-class targets:

| Operation | Target |
|---|---|
| `MusnadSDK.init` (cold) | < 250 ms |
| Credential save (pickup) | < 500 ms (excluding network) |
| Presentation construction (4 disclosed claims) | < 250 ms |
| Ephemeral key sign (challenge) | < 150 ms |
| Ephemeral key decrypt (BiometricPrompt → SK in memory) | < 1.5 s including biometric prompt |
| OCR Emirates ID | < 2 s |
| Liveness check (3-second video → hash) | < 4 s including capture |

If pure-Kotlin `elabify-core-android` blows these budgets by > 3× on Pixel 7-class hardware, the §9 of `elabify-core/README.md` calls for a per-platform SIMD-aware Goldilocks implementation. Profile before optimizing.

---

## 10. Testing

### 10.1 Unit tests

- All `KeyManager` operations with mocked Android Keystore (Robolectric).
- All wire-format construction matches `elabify-core` test vectors.
- Deep link validation rejects all malformed inputs (`tests/fixtures/bad-uris.txt`).
- `disclose(...)` closure pattern: claim values are zeroized on closure exit; verified via heap scan.

### 10.2 Integration tests

Run against the docker-compose stack (issuer + verifier + Anvil chain), same as iOS:
- Receive credential → present → GRANT.
- Receive credential → revoke → present → DENY with `revoked`.
- Receive credential → expire → present → DENY with `expired`.
- **Cross-platform**: a credential issued via the iOS SDK is read and presented by the Android SDK (and vice versa) in CI.

### 10.3 Embedding tests

A test harness host app embeds the SDK and exercises every API. Located in `android-sdk-musnad/embed-tests/`.

### 10.4 Security tests

- Verify `FLAG_SECURE` blocks screenshots and screen recording (Espresso).
- Verify ephemeral SK is zeroed from memory post-sign (heap dump after sign returns).
- Verify cert pinning rejects unknown certs (mitm test).
- Verify deep link parser rejects all entries in `tests/fixtures/bad-uris.txt`.
- Verify `BiometricPrompt` requires Class 3 (Strong) authenticator.

### 10.5 Privacy / supply-chain tests

Static check at CI time:
- No imports from third-party analytics SDKs.
- No `OkHttpClient` constructor outside `PinnedHttpClient`.
- No `Log.d / Log.e` of sensitive types.
- SBOM generated and checked against an allowlist.

---

## 11. Distribution

### 11.1 Maven Central

```kotlin
// In a host's build.gradle.kts
dependencies {
  implementation("com.elabify.musnad:musnad-sdk:0.1.0")
}
```

The artifact transitively pulls `com.elabify:elabify-core-android` (pure Kotlin, no native code) and bundles `pqclean.so` for `arm64-v8a` and `x86_64`. 32-bit ABIs (`armeabi-v7a`) are NOT shipped; they are below the SDK's modern-device floor.

### 11.2 Versioning

Pre-1.0: any release MAY break compatibility. Pin to exact version.

1.0+: semver. Major bump for any change in:
- Public API surface (§4)
- Trust contract (§6)
- Wire formats (those follow `the wire-format spec` versioning)
- Minimum SDK version
- ABI list

### 11.3 Code-signing and reproducibility

Releases are tagged in git, signed with a release key whose fingerprint is published in this README. Reproducible builds: a host MAY rebuild from source and verify byte-equality against the released `.aar`.

---

## 12. Configuration reference

`MusnadConfig` keys, with example values:

| Key | Example | Notes |
|---|---|---|
| `mode` | `Mode.SOFTWARE_ONLY` | `TREZOR_BACKED` once Trezor firmware ships |
| `allowedIssuerHosts` | `["issue.elabify.example"]` | Allowed issuer pickup URI hosts |
| `pinnedFingerprintsByHost` | map | host → set of RPO-256 cert fingerprints |
| `chainRpcUrl` | `https://sepolia.example/rpc` | Default RPC for status reads |
| `identityRegistryAddress` | `0x...` | Contract address |
| `revocationRegistryAddress` | `0x...` | Contract address |
| `defaultDelegationLifetime` | `24.hours` | |
| `trezorCoinType` | `9999u` | SLIP-0044 placeholder |
| `logSink` | host-supplied | See §6.4 |

---

## 13. Play Store / privacy considerations (for hosts)

A host embedding the SDK MUST update its Play Store data safety form to reflect:

- **Data collected**: Identity / Personal Info, encrypted, processed on-device, NOT shared.
- **Data shared**: depends on the host's flows; the SDK itself shares zero data with third parties.

The SDK ships a Data Safety contribution document (`PRIVACY.md` inside the AAR's `META-INF`) that hosts can paste into their Play Console submission as a starting point.

---

## 14. Implementation checklist (M5a ship)

- [ ] Min SDK 33, target latest stable.
- [ ] All public APIs in §4 implemented.
- [ ] PQClean compiled to native `.so` and integrated via JNI.
- [ ] `com.elabify:elabify-core-android` (pure Kotlin port from M0) integrated; cross-impl KAT vectors pass against the M0 frozen corpus alongside iOS and Node.
- [ ] Identity Sandwich implemented per §5; both Track A and Track B feature-flagged.
- [ ] StrongBox detection; TEE fallback with warning; software-only refused except in dev mode.
- [ ] Trezor integration via USB Host, BLE, WebUSB-relay.
- [ ] `FLAG_SECURE` enforced inside `PresentationApprovalScreen`.
- [ ] Cert pinning on all SDK HTTP via `PinnedHttpClient`.
- [ ] No third-party SDKs (CI lint).
- [ ] Room with SQLCipher for at-rest encryption.
- [ ] Emirates ID OCR via ML Kit on-device.
- [ ] BiometricPrompt (Class 3) gating sensitive operations.
- [ ] Cross-platform interop tests with iOS SDK green.
- [ ] All performance budgets met on Pixel 7-class hardware.

After M5a ships, M5b (`android-app-elabify`) builds against the released SDK artifact.

---

## 15. What this AI agent is allowed to assume

- `com.elabify:elabify-core-android` (pure Kotlin port from M0) is published to Maven Central and exposes the API in [`elabify-core/README.md`](../elabify-core/README.md) §4.3.
- PQClean is wrapped in a `pqclean-android` native library producing the same ML-DSA-65 / ML-KEM-768 outputs as the iOS `SwiftPQCrypto` and Node `@noble/post-quantum` for the same inputs.
- Backend services (issuer, verifier) implement the wire formats per `the wire-format spec` (internal design doc, not published).
- API 33+ is the minimum target; older devices not supported.
- A Trezor with FIPS-204 firmware exists in the hands of testers, OR the agent uses a Trezor mock and labels Track B "coming soon".
- The user has enrolled a Class 3 biometric at OS level.

If Trezor firmware lacks FIPS-204 support during development, the agent ships Track A (software-only) first and surfaces Track B as a coming-soon mode at runtime. See ADR-0005 (internal design doc, not published).

---

*End of android-sdk-musnad/README.md · v1.0*
