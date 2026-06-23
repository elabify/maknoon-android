// On-device test for the SQLCipher-encrypted Room store on the Pixel 9:
// insert/query credentials/devices/issuers, confirm the DB key is
// StrongBox-sealed, data survives reopen, and the raw .db file on disk is
// not plaintext-readable.

package com.elabify.musnad

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.elabify.musnad.crypto.AndroidSecureStore
import com.elabify.musnad.data.CredentialEntity
import com.elabify.musnad.data.DeviceEntity
import com.elabify.musnad.data.IssuerEntity
import com.elabify.musnad.data.MaknoonDatabase
import com.elabify.musnad.data.MaknoonStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MaknoonStoreDeviceTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val secure = AndroidSecureStore("maknoon.test.db.wrap", requireUnlockedDevice = false)
    private val dbName = "maknoon-test.db"

    private fun open(): MaknoonDatabase = MaknoonStore.open(ctx, secure, dbName)

    @Before
    fun clean() {
        ctx.deleteDatabase(dbName)
        ctx.getSharedPreferences("$dbName.key", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        secure.deleteKey()
    }

    @After
    fun cleanup() = clean()

    @Test
    fun dbKeyIsStrongBoxSealed() {
        open().close()
        assertEquals(AndroidSecureStore.SecurityLevel.STRONGBOX, secure.securityLevel)
    }

    @Test
    fun persistsCredentialsDevicesIssuersAcrossReopen() = runBlocking {
        val db = open()
        db.credentials().upsert(
            CredentialEntity("cid-1", "did:elabify:sepolia:issuer:musnad", "did:holder:0xabc",
                "elabify://schema/global/passport/v1", "{\"claim\":\"x\"}", "My Passport", 1_000),
        )
        db.devices().upsert(DeviceEntity("dev-1", "SN123", "ledger", "Ledger", "0xpub"))
        db.issuers().upsert(IssuerEntity("musnad-issuer.elabify.com", true, "Musnad"))
        db.close()

        // Reopen with the same StrongBox-sealed key.
        val reopened = open()
        assertEquals(1, reopened.credentials().all().size)
        val latest = reopened.credentials().latest(
            "did:elabify:sepolia:issuer:musnad", "did:holder:0xabc",
            "elabify://schema/global/passport/v1",
        )
        assertNotNull(latest)
        assertEquals("My Passport", latest!!.nickname)
        assertEquals(1, reopened.devices().all().size)
        assertEquals("musnad-issuer.elabify.com", reopened.issuers().all().single().host)
        reopened.close()
    }

    @Test
    fun rawDbFileIsEncryptedNotPlaintext() = runBlocking {
        val db = open()
        db.issuers().upsert(IssuerEntity("SECRET-HOST-marker.example", true, "PLAINTEXT-MARKER"))
        db.close()
        val raw = ctx.getDatabasePath(dbName).readBytes()
        val asText = String(raw, Charsets.ISO_8859_1)
        assertFalse("plaintext marker leaked into the db file", asText.contains("PLAINTEXT-MARKER"))
        assertFalse("host leaked into the db file", asText.contains("SECRET-HOST-marker"))
        // SQLCipher files do not carry the plaintext SQLite header.
        assertFalse("SQLite plaintext header present", asText.startsWith("SQLite format 3"))
    }
}
