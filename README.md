# Maknoon (Android)

Maknoon is a post-quantum, self-custodial identity and hardware-wallet Android
app (Jetpack Compose, GMS-free, runs on GrapheneOS). It holds verifiable
credentials, makes selective-disclosure presentations to verifiers, and signs
transactions on Ledger and Trezor devices, with the trust-critical cryptography
in shared, audited native cores.

> **0.6.0-rc0 release candidate.** This branch is a pre-release for testing; it
> is not yet the `main` line. See [`RELEASE.md`](android-app-elabify/RELEASE.md)
> for the test-distribution channels (Play internal testing + direct APK).

## Repository layout

The app is `android-app-elabify/` and the trust core is `android-sdk-musnad/`
(both shipped in this repo). The native cores are **git submodules** checked out
at the sibling paths the Gradle composite build expects:

| Path | Source |
|------|--------|
| `android-app-elabify/` | the super-app (UI, wallets, mini-app host) |
| `android-sdk-musnad/`  | the trust SDK (PQ crypto, identity, storage, presentation) |
| `elabify-core/`        | cross-platform crypto core (RPO-256, Merkle, DID, ML-DSA-65) — submodule |
| `pq-crypto-rs/`         | ML-DSA / ML-KEM Rust core (AAR) — submodule |
| `ledger-btc-rs/`        | Ledger Bitcoin Rust + UniFFI core — submodule |
| `ledger-eth-rs/`        | Ledger Ethereum core — submodule |
| `ledger-sol-rs/`        | Ledger Solana core — submodule |
| `ledger-tron-rs/`       | Ledger Tron core — submodule |
| `trezor-core-rs/`       | Trezor (THP v2 / BLE) core for all four chains — submodule |

These are the same cores the companion iOS app builds against.

## Clone (with submodules)

```sh
git clone --recursive -b 0.6.0-rc0 https://github.com/elabify/maknoon-android.git
# already cloned without --recursive?
git submodule update --init --recursive
```

## Build (debug)

Prerequisites: JDK 17, the Android SDK + NDK (`ANDROID_NDK_HOME`), and a Rust
toolchain with the Android targets (`rustup target add aarch64-linux-android
armv7-linux-androideabi x86_64-linux-android i686-linux-android`).

```sh
# 1. PQ-crypto core AAR (the SDK's flatDir repo reads it in place).
bash pq-crypto-rs/android/build-aar.sh

# 2. The five hardware-wallet core AARs. Each crate's build-aar.sh emits
#    android/library/build/outputs/aar/library-release.aar; copy each into
#    android-sdk-musnad/hwlibs/ under a distinct name (flatDir needs unique
#    artifact names).
mkdir -p android-sdk-musnad/hwlibs
for c in ledger-btc-rs ledger-eth-rs ledger-sol-rs ledger-tron-rs trezor-core-rs; do
  bash "$c/android/build-aar.sh"
  cp "$c/android/library/build/outputs/aar/library-release.aar" \
     "android-sdk-musnad/hwlibs/${c%-rs}.aar"
done

# 3. Build the app (elabify-core resolves as a Gradle composite build at
#    ../elabify-core/bindings/kotlin; the SDK at ../android-sdk-musnad).
cd android-app-elabify
./gradlew :app:assembleDebug
```

For release-signed builds and the Play / Obtainium test channels, see
[`android-app-elabify/RELEASE.md`](android-app-elabify/RELEASE.md).

## License

Apache-2.0, see [`LICENSE`](LICENSE). Third-party attributions in [`NOTICE`](NOTICE).
Each submodule carries its own license.
