package com.couli.feature.orders

import android.app.Application
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.core.network.ApiCall
import com.couli.core.network.ApiResult
import com.couli.core.network.CallEpochs
import com.couli.core.network.CallOwnership
import com.couli.core.network.PageApi
import com.couli.core.network.SessionIdentity
import com.couli.core.orders.OrderDetail
import com.couli.core.orders.OrderStaleCache
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * FU-AND-0717 F-X-17（iOS FU-IOS-0712 R1-01 S0 同类）：订单列表 / 详情缓存的写入归属来自请求开始时固定的「账号 + 持久化登录世代」，随请求带到写入点，
 * 写入时不再读当前世代。交错点：结果已通过最后一次身份核对（accept）、进入缓存写入，在缓存目录之外的待清理标记第一次被核验时同账号重新登录（E1 → E2）。
 * 断言：新世代分区读不到旧数据（同一实例 + 重建实例）；旧数据只在旧世代分区；会话对账（按 E2 的固定标签）把它整体删掉。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36])
class FollowupAnd0717OrderCacheEpochTest {
    private val root: File = Files.createTempDirectory("fuand0717-order-cache").toFile()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Volatile private var partition = "login-1"

    @Volatile private var epoch = 1L

    /** 同账号重新登录（令牌存储换新的持久化世代、本进程登录世代加 1）。 */
    private fun relogin() {
        partition = "login-2"
        epoch = 2L
    }

    /**
     * 交错点（应答已交回之后第一次触发）：执行器的时钟（结果通过最后一次身份核对后、求写入实参时调用——实参里若再读「当前世代」就会读到 E2，
     * iOS FU-IOS-0712 R2-02 同类）或缓存目录之外的待清理标记（存储内部、写盘之前）。
     */
    private inner class InterleavingFlag : OrderStaleCache.PendingClearFlag {
        @Volatile var armed = false

        @Volatile var fired = false

        fun fireIfArmed() {
            if (armed && !fired) {
                fired = true
                relogin()
            }
        }

        override fun isSet(): Boolean {
            fireIfArmed()
            return false
        }

        override fun set() = true

        override fun clear() = true
    }

    private val flag = InterleavingFlag()
    private val cache = OrderStaleCache.Store(root, flag).apply { partitionByLoginEpoch() }

    private inner class Session : OrderEnv {
        override fun loggedIn() = true

        override fun sessionEpoch() = epoch

        override fun account() = USER

        override fun identity() = SessionIdentity(known = true, account = USER, loginEpoch = epoch, partition = partition)

        override fun helpLinks(): JsonObject? = null

        override fun copy(text: String) = true

        override fun open(
            route: String,
            params: JsonObject,
        ) = Unit

        override fun forceUpdate() = Unit

        override fun onDetailLoaded(detail: OrderDetail) = Unit
    }

    /** 列表第一页 / 详情都立即成功；回答之前把交错点武装起来（下一次标记核验就在缓存写入里）。 */
    private inner class Api : PageApi {
        override suspend fun send(call: ApiCall): ApiResult {
            flag.armed = true
            val body = if (call.operationId == OrderListController.LIST_OPERATION) PAGE else DETAIL
            return ApiResult.Success(Json.parseToJsonElement(body), "trace-000123", null)
        }

        override fun cancelOwned(owner: CallOwnership) = Unit

        override fun epochs() = CallEpochs(0, 0)
    }

    @After
    fun tearDown() = scope.cancel()

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun lists(): List<File> = root.walkTopDown().filter { it.isFile && it.name.startsWith("list_") }.toList()

    @Test
    fun fuand0717_x17_aLateListWriteAfterTheLastIdentityCheckStaysOutOfTheNewLoginPartition() {
        val controller = OrderListController(Api(), Session(), cache, scope, { flag.fireIfArmed().let { 0L } })
        controller.open(null)
        idle()
        assertTrue("前提：交错点已触发（最后一次身份核对之后）", flag.fired)
        assertEquals("前提：已是新世代", "login-2", partition)
        assertNull("新世代分区读不到旧列表", cache.readList(USER, "self", "login-2"))
        assertNull("重建实例（进程重建）同样读不到", OrderStaleCache.Store(root, flag).apply { partitionByLoginEpoch() }.readList(USER, "self", "login-2"))
        assertNotNull("旧数据只落在旧世代分区", cache.readList(USER, "self", "login-1"))
        // 会话对账（生产入口用这次已确认的身份 E2 比较固定标签）：旧分区整体删除
        assertTrue(cache.reconcileOwner(USER, "login-2"))
        assertTrue("对账不保留旧世代的数据", lists().isEmpty())
        assertNull(cache.readList(USER, "self", "login-1"))
    }

    @Test
    fun fuand0717_x17_aLateDetailWriteAfterTheLastIdentityCheckStaysOutOfTheNewLoginPartition() {
        val controller = OrderDetailController(Api(), Session(), cache, scope, ORDER_ID, { flag.fireIfArmed().let { 0L } })
        controller.load()
        idle()
        assertTrue("前提：交错点已触发（最后一次身份核对之后）", flag.fired)
        assertNull("新世代分区读不到旧详情", cache.readDetail(USER, ORDER_ID, "login-2"))
        assertNull(OrderStaleCache.Store(root, flag).apply { partitionByLoginEpoch() }.readDetail(USER, ORDER_ID, "login-2"))
        assertNotNull("旧详情只落在旧世代分区", cache.readDetail(USER, ORDER_ID, "login-1"))
        assertTrue(cache.reconcileOwner(USER, "login-2"))
        assertNull("对账不保留", cache.readDetail(USER, ORDER_ID, "login-1"))
    }

    private companion object {
        const val USER = "user-a"
        const val ORDER_ID = "ord-1"
        const val ITEM =
            """{"order_id":"ord-1","platform":"taobao","title":"样例商品 A","image_url":null,"pay_amount_fen":4560,"quantity":2,""" +
                """"order_no":"3741234567890123456","display_status":"PAID","reason":null,"est_rebate_fen":234,""" +
                """"paid_at":"2026-10-08T09:05:00+08:00","is_other_product":false}"""
        const val PAGE = """{"items":[$ITEM],"next_cursor":null,"earliest_visible_date":null}"""
        val DETAIL = ITEM.dropLast(1) + ""","product_key":"tb:sampleA","timeline":[]}"""
    }
}
