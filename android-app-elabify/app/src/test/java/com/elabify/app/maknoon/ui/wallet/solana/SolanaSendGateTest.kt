package com.elabify.app.maknoon.ui.wallet.solana

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the Solana send submit gate. Previously the button enabled on
 * amount.isNotBlank() with a charset-only recipient check, so a wrong-network
 * address or an unparseable / zero / over-precision amount could reach Send and
 * only fail at signing time. solanaSendReady requires a resolved-or-valid
 * recipient AND an amount that parses to positive base units.
 */
class SolanaSendGateTest {
    @Test fun blocksWhenRecipientInvalid() =
        assertFalse(solanaSendReady(recipientValidOrResolved = false, amountInput = "1.5", tokenDecimals = null))

    @Test fun blocksWhenAmountEmpty() =
        assertFalse(solanaSendReady(recipientValidOrResolved = true, amountInput = "", tokenDecimals = null))

    @Test fun blocksWhenAmountZero() =
        assertFalse(solanaSendReady(recipientValidOrResolved = true, amountInput = "0", tokenDecimals = null))

    @Test fun blocksWhenTokenAmountOverPrecision() =
        assertFalse(solanaSendReady(recipientValidOrResolved = true, amountInput = "0.1234567", tokenDecimals = 6))

    @Test fun allowsValidNativeSend() =
        assertTrue(solanaSendReady(recipientValidOrResolved = true, amountInput = "1.5", tokenDecimals = null))

    @Test fun allowsValidTokenSend() =
        assertTrue(solanaSendReady(recipientValidOrResolved = true, amountInput = "10.5", tokenDecimals = 6))
}
