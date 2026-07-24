package com.elabify.app.maknoon.ui.wallet

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelfSendGuardTest {
    @Test fun exactMatchBase58() {
        val own = listOf("9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM")
        assertTrue(SelfSendGuard.isSelfSend("9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM", own, caseInsensitive = false))
        assertTrue(SelfSendGuard.isSelfSend("  9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM  ", own, caseInsensitive = false))
        assertFalse(SelfSendGuard.isSelfSend("TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t", own, caseInsensitive = false))
    }

    @Test fun caseInsensitiveHexForEthereum() {
        val own = listOf("0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed")
        assertTrue(SelfSendGuard.isSelfSend("0x5aaeb6053f3e94c9b9a09f33669435e7ef1beaed", own, caseInsensitive = true))
        assertFalse(SelfSendGuard.isSelfSend("0x1bd4e1b715213bd0c43d2623af4d77c46a6e5c2f", own, caseInsensitive = true))
    }

    @Test fun emptyRecipientIsNotSelfSend() {
        assertFalse(SelfSendGuard.isSelfSend("", listOf("abc"), caseInsensitive = false))
        assertFalse(SelfSendGuard.isSelfSend("abc", emptyList(), caseInsensitive = false))
    }
}
