package com.couli.feature.web

import android.webkit.WebView
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * I2/I3/I6/E01（v3）：真实产品存储端口，P（新配置文件）/ F（构造参数强制，先拆后清默认配置文件）各跑一遍——E01 与冷启动残留是合并门槛。
 * sessionStorage 页内清理脚本相关用例随方案作废（旧文档已销毁，sessionStorage 随 WebView 实例消失，由 E01 的新实例读证）。
 */
@RunWith(Parameterized::class)
class Fx25WebStorageTest(
    private val mode: Fx25WebMode,
) {
    @Test
    fun e01RealStorageIsGoneInRebuiltDocumentAndExternalProfileRetainsAllDataAndCache() {
        Fx25WebFixture(mode).use { f ->
            val page = f.open()
            val external = f.openExternal()
            f.seed(checkNotNull(f.onMain { page.web }))
            f.seed(external)
            val old = f.probe(page).read("window.fx25Document")
            val outside = f.probe(external).read("window.fx25Document")
            assertEquals("undefined", f.probe(external).read("typeof window.__REBATE_BRIDGE__").trim('"'))
            assertEquals("user-a", f.probe(page).read("window.fx25CacheVersion").trim('"'))
            val cachedRequests = f.cacheRequests()
            f.cacheVersion = "user-b"
            f.switch()
            assertTrue(f.settle())
            f.awaitDocument(page, old)
            assertEquals(Fx25WebFixture.EMPTY, f.readStorage(checkNotNull(f.onMain { page.web })))
            assertEquals("", f.trustedCookies())
            assertEquals("user-b", f.probe(page).read("window.fx25CacheVersion").trim('"'))
            assertEquals(cachedRequests + 1, f.cacheRequests())
            assertEquals(1, f.authEvents(page).count { it.getValue("document").jsonPrimitive.content == old.trim('"') })
            assertEquals(outside, f.probe(external).read("window.fx25Document"))
            assertEquals(Fx25WebFixture.STORED, f.readStorage(external))
            reloadExternal(f, external, outside)
            assertEquals("外部 HTTP 缓存仍命中", cachedRequests + 1, f.cacheRequests())
            assertEquals("user-a", f.probe(external).read("window.fx25CacheVersion").trim('"'))
            assertEquals(Fx25WebFixture.STORED, f.readStorage(external))
        }
    }

    @Test
    fun i6ExternalOnlyMustNotFallBackToGlobalCacheOrCookieCleanup() {
        Fx25WebFixture(mode).use { f ->
            val external = f.openExternal()
            f.seed(external)
            val document = f.probe(external).read("window.fx25Document")
            val requests = f.cacheRequests()
            f.switch()
            assertTrue(f.settle())
            assertEquals(document, f.probe(external).read("window.fx25Document"))
            assertEquals(Fx25WebFixture.STORED, f.readStorage(external))
            reloadExternal(f, external, document)
            assertEquals("零可信页也不能清外部缓存", requests, f.cacheRequests())
            assertEquals(Fx25WebFixture.STORED, f.readStorage(external))
        }
    }

    /** I3 冷启动：事务中途「杀进程」，新协调器在任何可信页加载前补完；补清失败时可信页不加载、标记保留；补清不是 auth 事件。 */
    @Test
    fun i3NewCoordinatorMustFinishPendingTransactionBeforeColdTrustedPageLoads() {
        Fx25WebFixture(mode).use { f ->
            val oldPage = f.open()
            f.seed(checkNotNull(f.onMain { oldPage.web }))
            f.onMain { f.store.holdRotate = true }
            f.switch()
            WebWait.until("旧页已拆、换存储前") { f.onMain { f.store.held.isNotEmpty() } }
            assertNotNull(f.onMain { f.journal.readPending() })
            f.restart()
            f.onMain { f.store.failRotate = true }
            val cold = f.open("cold", expectLoaded = false)
            assertEquals("首次 loadUrl 都不能发出", 0, f.onMain { cold.loads() })
            f.assertNavigationBlocked(cold)
            f.onMain { f.sync.observe(f.identity) }
            assertFalse(f.settle())
            assertEquals(0, f.onMain { cold.loads() })
            assertNotNull(f.onMain { f.journal.readPending() })
            f.onMain {
                f.store.failRotate = false
                f.sync.retry()
            }
            assertTrue(f.settle())
            f.awaitDocument(cold)
            assertEquals(Fx25WebFixture.EMPTY, f.readStorage(checkNotNull(f.onMain { cold.web })))
            assertEquals("", f.trustedCookies())
            assertTrue("冷启动补清不是 auth 事件", f.authEvents(cold).isEmpty())
            assertNull(f.onMain { f.journal.readPending() })
        }
    }

    @Test
    fun i2PreviouslyVisitedTrustedOriginMustAlsoLoseDomStorageAndIndexedDb() {
        Fx25WebFixture(mode).use { f ->
            val page = f.open()
            f.seed(checkNotNull(f.onMain { page.web }))
            val first = f.probe(page).read("window.fx25Document")
            f.onMain { assertTrue(page.product.load("${f.historyOrigin}/previously-visited")) }
            f.awaitDocument(page, first)
            f.seed(checkNotNull(f.onMain { page.web }))
            val second = f.probe(page).read("window.fx25Document")
            f.switch()
            assertTrue(f.settle())
            f.awaitDocument(page, second)
            assertEquals(Fx25WebFixture.EMPTY, f.readStorage(checkNotNull(f.onMain { page.web })))
            val switched = f.probe(page).read("window.fx25Document")
            f.onMain { assertTrue(page.product.load("${f.origin}/foreground")) }
            f.awaitDocument(page, switched)
            assertEquals(Fx25WebFixture.EMPTY, f.readStorage(checkNotNull(f.onMain { page.web })))
        }
    }

    private fun reloadExternal(
        f: Fx25WebFixture,
        web: WebView,
        oldDocument: String,
    ) {
        f.onMain { web.reload() }
        f.probe(web).await(
            "外部容器由测试显式重载完成",
            "document.readyState === 'complete' && !!window.fx25Document && window.fx25Document !== $oldDocument",
        ) { it == "true" }
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun modes(): List<Array<Any>> = listOf(arrayOf(Fx25WebMode.PROFILE), arrayOf(Fx25WebMode.CLEARING))
    }
}
