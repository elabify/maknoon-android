package com.elabify.musnad

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.elabify.musnad.wallet.ethereum.EthereumABI
import com.elabify.musnad.wallet.ethereum.EthereumTxEncoder
import com.elabify.musnad.wallet.ethereum.EthereumTxPlan
import com.elabify.musnad.wallet.ethereum.EthereumWeiValue
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.math.BigInteger

/**
 * On-device (instrumented) ERC-20 encode KAT: the transfer selector is computed
 * with the native keccak (TrustWalletCore JNI), so this cannot be a plain JVM
 * unit test. Asserts the ERC-20 transfer calldata carries the RECIPIENT (never
 * the token contract) and the tx value is zero for a token send. The iOS
 * EthereumSendEncodingTests lock the same bytes as a plain unit test; this
 * confirms the Android native path produces the identical, standard encoding.
 *
 * Run: ./gradlew :musnad-sdk:connectedDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class EthereumEncodeKatDeviceTest {
    private val contract = "0xaf88d065e77c8cc2239327c5edb3a432268e5831"
    private val recipient = "0x1bd4e1b715213bd0c43d2623af4d77c46a6e5c2f"

    private fun encodedAddress(cd: ByteArray): String =
        "0x" + cd.copyOfRange(16, 36).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it.toInt() and 0xff) }

    @Test
    fun transferDataCarriesRecipientNotContract() {
        val amount = EthereumWeiValue.fromBigInteger(BigInteger.valueOf(200_000_000L)) // 200 USDC (6dp)
        val data = EthereumABI.transferData(recipient, amount)!!
        assertEquals(4 + 32 + 32, data.size)
        assertEquals("a9059cbb", hex(data.copyOfRange(0, 4))) // transfer(address,uint256)
        assertEquals(recipient.lowercase(), encodedAddress(data).lowercase())
        assertNotEquals(contract.lowercase(), encodedAddress(data).lowercase())
    }

    @Test
    fun encoderErc20CalldataCarriesRecipientAndZeroValue() {
        val amount = EthereumWeiValue.fromBigInteger(BigInteger.valueOf(200_000_000L))
        val plan = EthereumTxPlan(
            42161L, 0L, contract, amount, 90_000L,
            EthereumWeiValue.fromBigInteger(BigInteger.valueOf(1_000_000_000L)),
            EthereumWeiValue.fromBigInteger(BigInteger.valueOf(100_000_000L)),
            EthereumTxPlan.Payload.Erc20(recipient),
        )
        val cd = EthereumTxEncoder.callData(plan)
        assertArrayEquals(EthereumABI.transferData(recipient, amount), cd)
        assertEquals(recipient.lowercase(), encodedAddress(cd).lowercase())
    }
}
