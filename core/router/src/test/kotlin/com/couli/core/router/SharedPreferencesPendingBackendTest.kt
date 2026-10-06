package com.couli.core.router

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.core.config.AppEnvironment
import com.couli.core.config.CouliPreferenceNamespaces
import com.couli.core.config.SharedPreferencesNamespaceCleaner
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** SharedPreferences 后端：跨 Store 实例恢复（模拟进程重建）、登记进命名空间、切换环境时被清理。 */
@RunWith(AndroidJUnit4::class)
class SharedPreferencesPendingBackendTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun recordSurvivesNewStoreInstanceAndIsClearedWithNamespace() {
        val params = JsonObject(mapOf("route" to JsonPrimitive("Wallet")))
        assertTrue(PendingActionStore(SharedPreferencesPendingBackend(context)) { 0L }.save(PendingActionStore.OPEN_ROUTE, params))

        val restored = PendingActionStore(SharedPreferencesPendingBackend(context)) { 1L }.load()
        assertEquals(params, restored?.params)

        assertTrue(CouliPreferenceNamespaces.ROUTER in CouliPreferenceNamespaces.registered)
        SharedPreferencesNamespaceCleaner(context).clean(AppEnvironment.TEST, AppEnvironment.STAGING)
        assertNull(SharedPreferencesPendingBackend(context).read())
    }
}
