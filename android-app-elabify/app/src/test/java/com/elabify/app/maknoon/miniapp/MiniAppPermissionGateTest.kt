package com.elabify.app.maknoon.miniapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the mini-app web3 permission gate (ADR-0057): the method -> permission
 * mapping and the grant decision MiniAppBridge enforces before dispatching a
 * method. A mini-app that declared only read must be denied sends and signing.
 * Mirrors iOS MiniAppPermissionGateTests.
 */
class MiniAppPermissionGateTest {
    private fun permits(method: String, granted: Set<String>): Boolean =
        miniAppIsAuthorized(web3RequiredPermission(method), granted)

    @Test fun methodPermissionMapping() {
        assertEquals("wallet.ethereum.write", web3RequiredPermission("eth_sendTransaction"))
        assertEquals("wallet.ethereum.sign", web3RequiredPermission("personal_sign"))
        assertEquals("wallet.ethereum.sign", web3RequiredPermission("eth_signTypedData_v4"))
        assertEquals("wallet.ethereum.read", web3RequiredPermission("eth_chainId"))
        assertEquals("wallet.ethereum.read", web3RequiredPermission("eth_accounts"))
    }

    @Test fun readOnlyAppIsDeniedWritesAndSigning() {
        val readOnly = setOf("wallet.ethereum.read")
        assertTrue(permits("eth_chainId", readOnly))
        assertFalse(permits("eth_sendTransaction", readOnly))
        assertFalse(permits("personal_sign", readOnly))
        assertFalse(permits("eth_signTypedData_v4", readOnly))
    }

    @Test fun grantedTokensPermitTheirMethods() {
        assertTrue(permits("eth_sendTransaction", setOf("wallet.ethereum.write")))
        assertTrue(permits("personal_sign", setOf("wallet.ethereum.sign")))
        // write does not imply sign
        assertFalse(permits("personal_sign", setOf("wallet.ethereum.write")))
    }

    @Test fun noGrantsDeniesEverything() {
        assertFalse(permits("eth_chainId", emptySet()))
        assertFalse(permits("eth_sendTransaction", emptySet()))
    }

    @Test fun nilRequirementIsAlwaysAuthorized() {
        assertTrue(miniAppIsAuthorized(null, emptySet()))
    }
}
