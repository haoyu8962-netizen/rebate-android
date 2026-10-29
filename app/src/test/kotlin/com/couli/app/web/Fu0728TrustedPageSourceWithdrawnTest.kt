package com.couli.app.web

import android.app.Application
import android.os.Looper
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.core.bridge.TrustedAuthSync
import com.couli.core.bridge.TrustedWebStore
import com.couli.core.bridge.WebAuthIdentity
import com.couli.core.bridge.WebDataJournal
import com.couli.core.bridge.WebDataOwner
import com.couli.core.bridge.WebDataOwnerRecord
import com.couli.feature.web.TrustedWebConfig
import com.couli.feature.web.TrustedWebScreen
import com.couli.feature.web.TrustedWebViewFactory
import com.couli.generated.DefaultTexts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * FU-AND-0728 U7（F-AND-72 补，F-X-25 Android v2 评审 r3 S2-01）：切号进行中远程配置撤下了某可信页的入口源，切号成功后该页是空白——既没有错误层也没有【重试】。
 *
 * 可达路径（按现行代码静态核对：页面的 [TrustedWebConfig] 随 TrustedWebTarget 在建页时取定，rebuild 里 `url == null` 只发生在「入口不在本页可信源里」时）：
 * 切号事务进行中打开（或重建）这一页，此刻生效配置已不含它的源 → 外层显示加载失败层（holder.failed）且页面在切号隔离中；用户点【重试】→ 因 authBlocked
 * 走 retryAuth（先收起错误层、交协调器）→ 事务成功：拆旧页 → 换存储 → 本页 rebuild 时没有可信入口按完成处理 → 解除隔离，但既不 onAuthRecovered 也不 onLoadFailed
 * → 错误层不再出现，页面空白。期望：沿用既有错误层（h5.load_failed）与【重试】（external_page.retry），不新增文案；之后【重试】因已解除隔离走 retryLoad。
 *
 * 用真实 [TrustedAuthSync]（只换持久化与存储端口）+ 正式 [TrustedWebScreen]（Robolectric，同 TrustedWebRetryFreshConfigTest 的宿主写法）。
 * 切号事务的断言只作「先正」（换存储恰一次、归属写成新账号、标记清掉），不改不加事务判据。
 * 底版：sourceWithdrawnDuringSwitchShowsTheErrorLayerAfterSuccess 红（成功后错误层数 0）；另一条绿。修正后全绿。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36])
class Fu0728TrustedPageSourceWithdrawnTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val journal = Journal()
    private val owner = Owner(WebDataOwner(OLD_ACCOUNT))
    private val store = Store()
    private val sync by lazy { TrustedAuthSync(scope, journal, owner, store, TIMEOUT_MS) }

    @After fun tearDown() {
        composeRule.runOnUiThread {
            sync.close()
            scope.cancel()
        }
    }

    @Test fun sourceWithdrawnDuringSwitchShowsTheErrorLayerAfterSuccess() {
        start()
        assertEquals("先正：入口源不在生效配置里，打开即显示加载失败层", 1, failedCount())
        clickRetry()
        assertEquals("先正：切号隔离中点【重试】交给协调器、先收起错误层", 0, failedCount())
        switchAccount()
        assertEquals("先正：切号事务走完（换存储恰一次）", 1, store.rotations)
        assertEquals("先正：归属写成新账号", NEW_ACCOUNT, owner.written.lastOrNull()?.account)
        assertEquals("先正：待清标记已删", 1, journal.clears)
        assertEquals("切号成功后该页显示既有的加载失败层（不留空白）", 1, failedCount())
        assertEquals("并带【重试】", 1, retryCount())
        val rotations = store.rotations
        clickRetry()
        assertEquals("已解除隔离：【重试】走 retryLoad（入口仍不可信 → 继续显示失败层）", 1, failedCount())
        assertEquals("【重试】不再触发切号事务", rotations, store.rotations)
    }

    @Test fun pageOpenedUntrustedDuringSwitchKeepsItsErrorLayerThroughSuccess() {
        start()
        assertEquals("先正：打开即显示加载失败层", 1, failedCount())
        switchAccount()
        assertEquals("先正：切号事务走完", 1, store.rotations)
        assertEquals("没点【重试】：切号成功后失败层仍在（只一层）", 1, failedCount())
        assertEquals(1, retryCount())
    }

    // ---------------------------------------------------------------- 辅助

    private fun start() {
        val factory = TrustedWebViewFactory(store, profiles = false)
        composeRule.setContent {
            TrustedWebScreen(
                url = WITHDRAWN_URL,
                config = TrustedWebConfig(setOf(TRUSTED_ORIGIN), emptySet(), "1.0.0"),
                hostFactory = { _, _ -> null },
                onOpenExternal = {},
                onClose = {},
                authSync = sync,
                webViews = factory,
            )
        }
        settle()
    }

    /** 进程内第一次已知身份与归属不符 → 切号事务（拆 → 换存储 → 写归属 → 删标记 → rebuild → 解除）。 */
    private fun switchAccount() {
        composeRule.runOnUiThread { sync.observe(WebAuthIdentity(true, NEW_ACCOUNT, 1L)) }
        settle()
    }

    private fun clickRetry() {
        composeRule.onNode(hasText(RETRY) and hasClickAction()).performClick()
        settle()
    }

    private fun failedCount(): Int = composeRule.onAllNodes(hasText(FAILED)).fetchSemanticsNodes().size

    private fun retryCount(): Int = composeRule.onAllNodes(hasText(RETRY) and hasClickAction()).fetchSemanticsNodes().size

    private fun settle() {
        repeat(SETTLE_ROUNDS) {
            shadowOf(Looper.getMainLooper()).idle()
            composeRule.waitForIdle()
        }
    }

    private class Journal : WebDataJournal {
        var pending: WebAuthIdentity? = null
        var clears = 0

        override fun readPending(): WebAuthIdentity? = pending

        override fun markPending(owner: WebAuthIdentity): Boolean {
            pending = owner
            return true
        }

        override fun clearPending(): Boolean {
            pending = null
            clears += 1
            return true
        }
    }

    private class Owner(
        private val initial: WebDataOwner?,
    ) : WebDataOwnerRecord {
        val written = mutableListOf<WebDataOwner>()

        override fun read(): WebDataOwner? = written.lastOrNull() ?: initial

        override fun write(owner: WebDataOwner): Boolean {
            written += owner
            return true
        }
    }

    private class Store : TrustedWebStore {
        var rotations = 0

        override fun current(): String = "store-$rotations"

        override fun rotate(completed: (Boolean) -> Unit) {
            rotations += 1
            completed(true)
        }

        override fun discardPending(completed: (Boolean) -> Unit) = completed(true)
    }

    private companion object {
        const val OLD_ACCOUNT = "fu0728-old"
        const val NEW_ACCOUNT = "fu0728-new"
        const val TRUSTED_ORIGIN = "https://h5.example.test"
        const val WITHDRAWN_URL = "https://withdrawn.example.test/find-order"
        const val TIMEOUT_MS = 60_000L
        const val SETTLE_ROUNDS = 8
        val FAILED = DefaultTexts.text("h5.load_failed").orEmpty()
        val RETRY = DefaultTexts.text("external_page.retry").orEmpty()
    }
}
