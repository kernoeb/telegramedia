package app.telegramedia.security

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented test — exercises the real Android Keystore, so it must run on a device
 * (`./gradlew connectedDebugAndroidTest`), not the JVM.
 */
@RunWith(AndroidJUnit4::class)
class DatabaseKeyStoreTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun clearStoredKey() {
        // Start each test from a clean slate so "first use" generation is exercised.
        context.getSharedPreferences("secure_db_key", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun generatesA32ByteKey() {
        val key = DatabaseKeyStore(context).getOrCreateKey()
        assertEquals("TDLib expects a 256-bit key", 32, key.size)
        assertFalse("key must not be all-zero", key.all { it.toInt() == 0 })
    }

    @Test
    fun returnsTheSameKeyAcrossInstances() {
        // The critical contract: a relaunch (new instance) must recover the same key,
        // or the encrypted database would fail to reopen.
        val first = DatabaseKeyStore(context).getOrCreateKey()
        val second = DatabaseKeyStore(context).getOrCreateKey()
        assertTrue("key must be stable across instances", first.contentEquals(second))
    }
}
