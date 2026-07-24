package com.elabify.musnad

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.elabify.musnad.wallet.ethereum.EIP55
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device (instrumented) EIP-55 checksum validation. checksum() calls the
 * native keccak (TrustWalletCore JNI), so this cannot be a plain JVM unit test.
 * Mirrors the iOS AddressNetworkGuardTests.testEIP55ChecksumValidation.
 *
 * Run: ./gradlew :musnad-sdk:connectedDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class Eip55DeviceTest {
    @Test
    fun checksumValidation() {
        val lower = "0x5aaeb6053f3e94c9b9a09f33669435e7ef1beaed"
        val correct = EIP55.checksum(lower) // canonical mixed-case
        assertTrue("all-lower accepted", EIP55.passesChecksum(lower))
        assertTrue("all-upper accepted", EIP55.passesChecksum(lower.uppercase().replace("0X", "0x")))
        assertTrue("canonical checksum passes", EIP55.passesChecksum(correct))

        // Flip one body letter -> mixed case that no longer matches -> rejected.
        val body = correct.substring(2).toCharArray()
        for (i in body.indices) {
            val c = body[i]
            if (c.isLetter()) {
                body[i] = if (c.isUpperCase()) c.lowercaseChar() else c.uppercaseChar()
                break
            }
        }
        assertFalse("bad-checksum mixed-case rejected", EIP55.passesChecksum("0x" + String(body)))
        assertFalse("wrong length rejected", EIP55.passesChecksum("0x123"))
    }
}
