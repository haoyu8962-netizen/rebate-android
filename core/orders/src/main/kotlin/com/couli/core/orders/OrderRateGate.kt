package com.couli.core.orders

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 订单请求的 42901 禁用期（APP-08 共性第 2 条）：按会话共享一份——订单列表、翻页、详情、待跟单查询与关闭（含商品详情 / 搜索页的卡片位置）
 * 共用同一个截止时间。任一订单请求收到 42901 都按 Retry-After（缺省或非法 5 秒）延长它；每个请求入口发送前先查 [active]（执行层），页面按
 * [blocked] 同步禁用刷新、翻页、筛选、搜索、关闭与【重试】。重进页面拿到的仍是同一份（不能绕过）；会话换代时 [reset]（新会话一份新的）。
 * [active] 可在任意线程读（截止时刻 @Volatile）：各请求把它作为发送前闸门（CallOwnership.sendAllowed，不放进 stillOwned），网络层每次实际
 * 写出前——含 GET 自动重试 / 退避、刷新后重放、重签后的重发——都会核对，禁用期内一律不再发包（APP-08 共性第 13 条）；已写出的请求不因禁用期
 * 被取消，成功与错误响应（含 10405）照常处理（共性第 27 条，R3-01）。其余入口只在主线程使用。
 */
class OrderRateGate(
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    @Volatile
    private var deadline = 0L
    private var timer: Job? = null
    private val mutable = MutableStateFlow(false)

    /** 禁用中（页面按它置灰；到期自动恢复）。 */
    val blocked: StateFlow<Boolean> = mutable.asStateFlow()

    /** 执行层的判定（按时钟现算；任意线程可读）。 */
    val active: Boolean get() = clock() < deadline

    /** 开始（或延长）禁用 [millis] 毫秒；不缩短已有的截止时刻。 */
    fun start(millis: Long) {
        if (millis <= 0) return
        deadline = maxOf(deadline, clock() + millis)
        mutable.value = true
        timer?.cancel()
        timer =
            scope.launch {
                while (active) delay((deadline - clock()).coerceAtLeast(1L))
                mutable.value = false
            }
    }

    /** 会话换代：旧会话的禁用期不带给新会话。 */
    fun reset() {
        timer?.cancel()
        timer = null
        deadline = 0L
        mutable.value = false
    }
}
