package com.elabify.app.maknoon

import android.app.Application
import android.content.Context
import android.os.Build
import com.elabify.musnad.hardware.HardwareWalletFactory
import com.elabify.musnad.hardware.RealHardwareWalletProvider

// Plain Application for now. Hilt (@HiltAndroidApp) + the long-lived
// HolderStore that constructs MusnadSDK once are wired in at P1.
class MaknoonApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        appContext = applicationContext

        // Install the real BLE-backed hardware-wallet provider so the
        // register + discover + sign flows talk to the actual Ledger /
        // Trezor over Bluetooth instead of the demo MockHardwareWallet.
        // Mirrors iOS HardwareWalletFactory routing to LedgerBLE / TrezorBLE
        // on a physical device.
        HardwareWalletFactory.setRealClientProvider(
            RealHardwareWalletProvider(applicationContext),
        )

        // The Android analog of iOS's `#if targetEnvironment(simulator)`
        // guard: emulators have no real BLE radio, so force the mock there
        // and keep the demo path working. Detected via the well-known
        // emulator Build fingerprints (this is the app layer, so the
        // android.os.Build import the SDK avoids is fine here).
        HardwareWalletFactory.forceMock = isProbablyEmulator()
    }

    companion object {
        /** Process-wide application context, set in onCreate. Used by low-level
         *  helpers (e.g. the hardware-connect serial guard) that need a
         *  DeviceRegistry but are not Composables / Activities and so cannot be
         *  handed a Context through their signature without threading it through
         *  every chain's call site. */
        lateinit var appContext: Context
            private set
    }

    private fun isProbablyEmulator(): Boolean {
        val fp = Build.FINGERPRINT.orEmpty()
        val product = Build.PRODUCT.orEmpty()
        val model = Build.MODEL.orEmpty()
        val brand = Build.BRAND.orEmpty()
        val device = Build.DEVICE.orEmpty()
        return fp.startsWith("generic") ||
            fp.startsWith("unknown") ||
            fp.contains("emulator", ignoreCase = true) ||
            fp.contains("vbox", ignoreCase = true) ||
            fp.contains("sdk_gphone", ignoreCase = true) ||
            model.contains("Emulator", ignoreCase = true) ||
            model.contains("Android SDK built for", ignoreCase = true) ||
            product.contains("sdk", ignoreCase = true) ||
            product.contains("emulator", ignoreCase = true) ||
            product.contains("simulator", ignoreCase = true) ||
            brand.startsWith("generic") ||
            device.startsWith("generic") ||
            (brand.startsWith("google") && device.startsWith("generic"))
    }
}
