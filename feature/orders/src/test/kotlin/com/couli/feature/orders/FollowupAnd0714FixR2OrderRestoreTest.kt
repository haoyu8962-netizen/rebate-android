package com.couli.feature.orders

import android.app.Application
import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.core.network.ApiCall
import com.couli.core.network.ApiResult
import com.couli.core.network.CallEpochs
import com.couli.core.network.CallOwnership
import com.couli.core.network.PageApi
import com.couli.core.orders.OrderDetail
import com.couli.core.orders.OrderDraftTexts
import com.couli.core.orders.OrderRestoreStore
import com.couli.core.orders.OrderStaleCache
import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * FU-AND-0714 第 2 轮评审 R2-04（页面接线）：[OrderListViewModel] 固定身份时登录世代读不出（boundLoginEpoch 未知）——之后世代恢复可读、再换登录，
 * 这一页的搜索框输入（迟到写入）都不落进恢复记录，不会被当前世代补绑；对照：固定身份时世代已知的页面照常写入并能恢复。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36])
class FollowupAnd0714FixR2OrderRestoreTest {
    private val root: File = Files.createTempDirectory("fuand0714-r2-04-page").toFile()

    @Volatile private var loginEpoch: String? = null

    private val store = OrderRestoreStore(root, OrderStaleCache.SiblingFileFlag(root)).apply { bindLoginEpoch { loginEpoch } }

    private class Session : OrderEnv {
        override fun loggedIn() = true

        override fun sessionEpoch() = 1L

        override fun account() = "user-a"

        override fun helpLinks(): JsonObject? = null

        override fun copy(text: String) = true

        override fun open(
            route: String,
            params: JsonObject,
        ) = Unit

        override fun forceUpdate() = Unit

        override fun onDetailLoaded(detail: OrderDetail) = Unit
    }

    private object Sends : PageApi {
        override suspend fun send(call: ApiCall): ApiResult = ApiResult.Success(Json.parseToJsonElement(EMPTY_PAGE), "trace-000123", null)

        override fun cancelOwned(owner: CallOwnership) = Unit

        override fun epochs() = CallEpochs(0, 0)
    }

    private fun page(): OrderListViewModel =
        OrderListViewModel(
            OrderListConfig(Sends, Session(), OrderDraftTexts, cache = null, mask = null, pending = null, restoreStore = store),
            SavedStateHandle(),
        ).also { idle() }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun records(): List<File> = root.listFiles().orEmpty().filter { it.isFile && it.name.endsWith(".json") }

    private fun type(
        vm: OrderListViewModel,
        text: String,
    ) {
        vm.onInput(vm.ticket.value, text)
        idle()
    }

    @Test
    fun r2_04_page_bound_while_epoch_unreadable_never_persists_after_epoch_recovers_or_relogin() {
        // 固定身份时：令牌存储读不出
        val unknown = page()
        // 世代恢复可读：这一页的输入不补绑当前世代
        loginEpoch = "login-1"
        type(unknown, ORDER_NO)
        assertTrue("世代恢复后：未知页面的输入不落盘", records().isEmpty())
        // 换登录（会话通知还没到）的迟到写入：同样不落盘
        loginEpoch = "login-2"
        type(unknown, "$ORDER_NO-late")
        assertTrue("换登录后的迟到写入：不落盘", records().isEmpty())

        // 对照：固定身份时世代已知的页面照常写入，同一世代能恢复
        val known = page()
        type(known, ORDER_NO)
        val written = records()
        assertEquals("已知世代的页面照常写入", 1, written.size)
        assertEquals(ORDER_NO, store.read(written.single().name.removeSuffix(".json"))?.input)
    }

    private companion object {
        const val ORDER_NO = "3741234567890123456"
        const val EMPTY_PAGE = """{"items":[],"next_cursor":null,"earliest_visible_date":null}"""
    }
}
