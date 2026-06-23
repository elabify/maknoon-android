// Builds the SQLCipher-encrypted Room database. The 32-byte DB key is random,
// generated once, and sealed by the StrongBox wrap key (AndroidSecureStore) so
// it never sits in storage in the clear. The DB file on disk is unreadable
// without the hardware-protected key.

package com.elabify.musnad.data

import android.content.Context
import android.util.Base64
import androidx.room.Room
import com.elabify.musnad.crypto.AndroidSecureStore
import java.security.SecureRandom
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

object MaknoonStore {

    // A Room database is designed to be a process-wide singleton: building it
    // loads the SQLCipher native lib, derives the DB key, and opens the
    // encrypted file. Rebuilding it per call (the Identity tab did this on every
    // load + on every credential read) is expensive and shows up as a visible
    // pause. Cache one instance per dbName, built under a lock. The app context
    // is used so this never leaks an Activity.
    @Volatile private var instances: Map<String, MaknoonDatabase> = emptyMap()
    private val buildLock = Any()

    /** Open (or create) the encrypted database. Returns a cached singleton per
     *  [dbName] after the first build. */
    fun open(
        context: Context,
        secure: AndroidSecureStore = AndroidSecureStore(DB_WRAP_ALIAS),
        dbName: String = DB_NAME,
    ): MaknoonDatabase {
        instances[dbName]?.let { return it }
        synchronized(buildLock) {
            instances[dbName]?.let { return it }
            val db = build(context.applicationContext, secure, dbName)
            instances = instances + (dbName to db)
            return db
        }
    }

    private fun build(
        context: Context,
        secure: AndroidSecureStore,
        dbName: String,
    ): MaknoonDatabase {
        System.loadLibrary("sqlcipher")
        val key = databaseKey(context, secure, dbName)
        val factory = SupportOpenHelperFactory(key)
        return Room.databaseBuilder(context, MaknoonDatabase::class.java, dbName)
            .openHelperFactory(factory)
            // Dev DB: a schema version bump (e.g. the v2 verifier_history table)
            // recreates tables rather than requiring a hand-written Migration.
            // Acceptable here because at-rest state is reconstructible. Revisit
            // before any GA persistence guarantee.
            .fallbackToDestructiveMigration()
            .build()
    }

    /** Get-or-create the random DB key, sealed by the StrongBox wrap key. */
    private fun databaseKey(context: Context, secure: AndroidSecureStore, dbName: String): ByteArray {
        val prefs = context.getSharedPreferences("$dbName.key", Context.MODE_PRIVATE)
        prefs.getString(K_SEALED, null)?.let { return secure.open(Base64.decode(it, Base64.NO_WRAP)) }
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        prefs.edit().putString(K_SEALED, Base64.encodeToString(secure.seal(key), Base64.NO_WRAP)).apply()
        return key
    }

    private const val DB_NAME = "maknoon.db"
    private const val DB_WRAP_ALIAS = "maknoon.db.wrap"
    private const val K_SEALED = "dbkey.sealed.v1"
}
