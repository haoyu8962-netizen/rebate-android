package com.couli.feature.web

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.core.bridge.TrustedWebDataKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * F 路径（产品 ClearingTrustedWebStore + 真实 AndroidTrustedWebDataCleaner）：四类里任一类拿不到真实完成信号，都不得新建页面——
 * 旧页已拆，页面槽位保持隔离、无 WebView、不加载，待清标记保留；放开后重试在新文档里读不到旧数据。
 */
@RunWith(AndroidJUnit4::class)
class Fx25WebClearingTest {
    @Test
    fun i2EveryClearingSignalIsRequiredBeforeAnyRebuild() {
        for (kind in TrustedWebDataKind.entries) {
            Fx25WebFixture(Fx25WebMode.CLEARING_INJECTED).use { f ->
                val page = f.open()
                f.seed(checkNotNull(f.onMain { page.web }))
                val old = f.probe(page).read("window.fx25Document")
                f.onMain { f.faults.missing = kind }
                f.switch()
                WebWait.until("$kind 已请求") { f.onMain { kind in f.faults.requested } }
                assertFalse("$kind 无完成信号不能算成功", f.settle())
                assertEquals("$kind：旧页已拆、没有新建", 1, f.onMain { f.created.size })
                assertTrue(f.onMain { f.created.single().destroyedByProduct })
                assertEquals(null, f.onMain { page.web })
                f.assertNavigationBlocked(page)
                assertNotNull(f.onMain { f.journal.readPending() })
                f.onMain {
                    f.faults.missing = null
                    f.sync.retry()
                }
                assertTrue(f.settle())
                f.awaitDocument(page, old)
                assertEquals(Fx25WebFixture.EMPTY, f.readStorage(checkNotNull(f.onMain { page.web })))
                assertEquals("恢复不重复 A", 1, f.authEvents(page).size)
            }
        }
    }
}
