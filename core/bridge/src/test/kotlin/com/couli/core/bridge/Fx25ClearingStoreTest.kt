package com.couli.core.bridge

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** F 路径（不支持多配置文件）：四类（sessionStorage 随 WebView 实例消失，不单列）；最后一页销毁后宽限 2 s 再清（D10-310 (2)）。 */
@OptIn(ExperimentalCoroutinesApi::class)
class Fx25ClearingStoreTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun i2ClearingCoversExactlyCookieLocalIndexedDbAndHttpCache() {
        assertEquals(
            "sessionStorage 不再由页面脚本清（旧文档已销毁），只剩四类",
            listOf("COOKIE", "LOCAL_STORAGE", "INDEXED_DB", "HTTP_CACHE"),
            TrustedWebDataKind.entries.map { it.name },
        )
    }

    @Test
    fun i2ClearingWaitsGracePeriodAfterLastRetireBeforeFirstRequest() =
        runTest {
            Fx25AuthFixture.create(this, temporary.newFolder(), Fx25StoreMode.CLEARING).use { f ->
                f.baseline()
                val page = f.page()
                f.change()
                f.acknowledgeEvents()
                f.retireAll()
                advanceTimeBy(ClearingTrustedWebStore.GRACE_MS - 1)
                runCurrent()
                assertTrue("宽限期内不清（隔离前已在途的 keepalive 迟到 Set-Cookie）", f.cleaner.requests.isEmpty())
                advanceTimeBy(2)
                runCurrent()
                assertTrue("宽限期过后开始清", f.cleaner.requests.isNotEmpty())
                f.completeTransaction()
                assertFalse(page.isolated)
                TrustedWebDataKind.entries.forEach { kind -> assertEquals(1, f.cleaner.requests.count { it.kind == kind }) }
            }
        }

    @Test
    fun i6ClearingStoreNeverChangesTheTrustedProfileIdentity() =
        runTest {
            Fx25AuthFixture.create(this, temporary.newFolder(), Fx25StoreMode.CLEARING).use { f ->
                assertEquals(ClearingTrustedWebStore.DEFAULT_STORE, f.store.current())
                var discarded: Boolean? = null
                f.store.discardPending { discarded = it }
                runCurrent()
                assertEquals("F 路径没有可删的旧配置文件", true, discarded)
                assertTrue(f.cleaner.requests.isEmpty())
            }
        }
}
