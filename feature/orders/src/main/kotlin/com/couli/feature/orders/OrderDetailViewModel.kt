package com.couli.feature.orders

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.couli.core.network.IdentityVerdict
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 订单详情的 ViewModel：状态来自 [OrderDetailController]。页面一确定出栈（App 在回退栈移除的同步出口调 bindLeave 给的入口）即作废请求；
 * ViewModel 清理时兜底再调一次。进程被系统回收后恢复本页时先等本轮版本检查结果（restoreGate）再取详情。会话身份在构造时固定（执行器构造即取），
 * 恢复等待前捕获票据、等待结束后只比较（RC-01）。
 */
class OrderDetailViewModel(
    initial: OrderDetailConfig,
    saved: SavedStateHandle,
) : ViewModel() {
    var config: OrderDetailConfig = initial
        private set
    val controller =
        OrderDetailController(initial.api, initial.env, initial.cache, viewModelScope, initial.orderId, initial.clock, initial.rateGate)

    /** 进程恢复时等待版本检查的任务（等待结束前为非 null）。 */
    private var restoreJob: Job? = null

    init {
        initial.bindLeave(controller::leave)
        initial.bindSession {
            // 会话换代：等待中的恢复任务作废（换代入口已按新会话重取，等待结束后不再按旧会话补一次；共性第 21 条）
            restoreJob?.cancel()
            restoreJob = null
            controller.onSessionChanged()
        }
        // App 下发的已确认快照（FU-AND-0717 第 3 轮统一的页面恢复入口，R2-03）：会话换代按它清空重取；判据没变时第一次确认接管、加载曾因身份中止的补取
        initial.bindIdentity { identity, changed ->
            val verdict = if (changed) IdentityVerdict.CHANGED else controller.verdict(identity)
            if (verdict == IdentityVerdict.CHANGED) {
                restoreJob?.cancel()
                restoreJob = null
                controller.onSessionChanged(identity)
            } else {
                controller.onIdentityConfirmed(identity)
            }
        }
        val restored = saved.get<Boolean>(K_STARTED) == true
        saved[K_STARTED] = true
        val gate = initial.restoreGate
        if (restored && gate != null) {
            // 等待前捕获固定身份、绑定代次与版本；等待结束后先比较，失效直接返回，请求沿用原身份（RC-01，APP-08 共性第 21 条）
            val ticket = controller.restoreTicket()
            restoreJob =
                viewModelScope.launch {
                    gate()
                    restoreJob = null
                    controller.resumeRestore(ticket)
                }
        } else {
            controller.load()
        }
    }

    fun update(next: OrderDetailConfig) {
        config = next
    }

    override fun onCleared() {
        controller.leave()
        super.onCleared()
    }

    private companion object {
        const val K_STARTED = "order_detail.started"
    }
}
