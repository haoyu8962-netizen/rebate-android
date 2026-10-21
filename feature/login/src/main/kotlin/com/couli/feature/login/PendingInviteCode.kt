package com.couli.feature.login

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate

/**
 * 待绑定邀请码（APP-17c；R04、G18–G21、D10-107）：进程内唯一的一份（App 由 Hilt 单例提供，剪贴板确认、邀请链接分流写入，短信登录
 * 提交时取走）。只在内存里，不落盘、不上传、不进日志；新进程从空开始。
 * - [set]：先 BR-INV-02 规范化（[InviteField.normalize]），再按 BR-INV-01 校验（[InviteField.isValidCode]，与登录页、分流同一个
 *   PATTERN）；不合格不写、旧码保留、不给界面提示（D10-107：三端共用样例 specs/login-vectors.json pendingInviteSet）。返回值只供
 *   调用方与测试判断是否写入，调用方不据此提示。
 * - [consumeForRequest]：短信登录在全部本地校验之后、发出之前的同步段里取走「本次那一份」（G18）；请求在途时新写入的码留给下一次。
 * - [remove]：用户在小字行点【移除】；[clear]：隐私不再是已同意、切换环境时由 App 登记的清理入口调用。
 * - [state]：小字行的可观察来源（闸后渲染据此刷新）。
 */
class PendingInviteCode {
    private val mutableState = MutableStateFlow<String?>(null)

    /** 当前的待绑定码（规范化后的 6 位码）；没有为 null。 */
    val state: StateFlow<String?> = mutableState.asStateFlow()

    /** 写入（替换旧码）。不合格返回 false，容器不变。 */
    fun set(raw: String): Boolean {
        val normalized = InviteField.normalize(raw)
        if (!InviteField.isValidCode(normalized)) return false
        mutableState.value = normalized
        return true
    }

    /** 读取，不取走。 */
    fun peek(): String? = mutableState.value

    /** 用户移除（小字行【移除】）。 */
    fun remove() {
        mutableState.value = null
    }

    /** 取走并清空（原子操作）：只给提交登录请求用。 */
    fun consumeForRequest(): String? = mutableState.getAndUpdate { null }

    /** 系统清理（撤回同意、基本模式、政策升级未决、切换环境）。 */
    fun clear() {
        mutableState.value = null
    }
}

/**
 * 登录页的待绑定码小字行（R04；10-19 口径：只有「已识别好友邀请码 {code}」与【移除】，不含告知句）。受限登录与没有码时不显示
 * （[LoginViewModel.pendingInviteRow] 为 null）。画法属视觉闸后。
 */
data class PendingInviteRow(
    val code: String,
    val textKey: String = TEXT_KEY,
    val removeKey: String = REMOVE_KEY,
) {
    companion object {
        const val TEXT_KEY = "login.pending_invite"
        const val REMOVE_KEY = "login.pending_invite.remove"
    }
}
