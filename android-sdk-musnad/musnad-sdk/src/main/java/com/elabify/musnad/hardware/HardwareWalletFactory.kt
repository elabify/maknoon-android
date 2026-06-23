// Build a vendor-specific HardwareWallet client, ported from iOS
// HardwareWalletFactory.
//
// iOS routes `.ledger` / `.trezor` through MockHardwareWallet under
// `#if targetEnvironment(simulator)` (real BLE transports are unavailable
// on the simulator) and through the real BLE clients on a physical device.
//
// Android has no compile-time `simulator` flag, and the real Ledger /
// Trezor BLE clients are sibling components that may not be linked into
// every build (CI, instrumentation harnesses, library consumers that only
// want the demo path). So instead of a hard reference to those classes,
// the factory exposes a small PROVIDER SEAM: the app registers the real
// BLE-client constructors at startup, the factory falls back to the mock
// when no provider is registered or when running on an emulator. This
// keeps the SDK compiling without the BLE-transport components present and
// mirrors the iOS "mock on simulator, real on device" routing 1:1.
//
// Each call returns a FRESH client. Earlier iOS returned a shared
// singleton on the theory that BLE state benefits from continuity across
// calls, but in practice singleton state outlives the reset paths in
// subtle ways and produced intermittent connect timeouts on retry. A fresh
// instance per call guarantees zero stale BLE state inside the app.

package com.elabify.musnad.hardware

object HardwareWalletFactory {

    /** Supplies a real BLE-transport client for a hardware-requiring kind.
     *  Implemented and registered by the app (or the BLE-transport
     *  components) at startup; until then, hardware kinds fall back to the
     *  mock so the demo path always works. Return null to decline a kind
     *  (e.g. emulator, or transport not linked) and let the factory mock it. */
    fun interface RealClientProvider {
        fun make(kind: HardwareWalletKind): HardwareWallet?
    }

    @Volatile
    private var realProvider: RealClientProvider? = null

    /** When true, every hardware kind is mocked regardless of the provider.
     *  The app sets this on emulator builds (mirrors iOS's simulator guard),
     *  detected via Build.FINGERPRINT / Build.PRODUCT in the app layer so
     *  this pure-Kotlin SDK module stays free of an android.os.Build import. */
    @Volatile
    var forceMock: Boolean = false

    /** Wire the real BLE-transport clients. Called once at app startup on a
     *  physical device. Passing null reverts to the always-mock behaviour. */
    fun setRealClientProvider(provider: RealClientProvider?) {
        realProvider = provider
    }

    /** Return a client for `kind`. Mock kinds always mock. Hardware kinds
     *  use the registered real provider on a physical device, falling back
     *  to the mock when forced, when no provider is registered, or when the
     *  provider declines the kind. */
    fun make(kind: HardwareWalletKind): HardwareWallet {
        if (kind == HardwareWalletKind.MOCK) return MockHardwareWallet()
        if (forceMock) return MockHardwareWallet()
        val provider = realProvider ?: return MockHardwareWallet()
        return provider.make(kind) ?: MockHardwareWallet()
    }
}
