package com.couli.core.agent

import kotlinx.serialization.json.JsonObject

/**
 * 来源页（订单详情、商品详情的【问问 AI】）构造时取得的票据（不可变，鸿蒙 R1-04，APP-10 共性第 4 条）：构造时的归属键（[AgentIdentity.ownerKey]）+ 来源页实例
 * 是否仍在。之后只比较，不在点击时读当前身份重新绑定。
 */
class AgentContextSource(
    val ownerKey: AgentIdentity,
    val pageAlive: () -> Boolean,
)

/** 一次【问问 AI】：来源票据 + 渲染时固定的 context（{order_id} / {product_key} / {text}）。toString 不带 context。 */
class AgentContextTicket(
    val source: AgentContextSource,
    val context: JsonObject?,
) {
    override fun toString(): String = "AgentContextTicket"
}

/**
 * G27 情境入口的进程内交接（鸿蒙 H05、R1-04）：context 不走 AgentChat 路由参数（push / 深链 / 桥带来的 AgentChat.context 一律不读，03 §4.4）。
 * 旧回调（来源页已离开、换号、撤回、切环境之后）一律拒绝：不打开 AgentChat、不把 context 交给任何控制器。只在主线程使用。
 */
class AgentContextHandoff {
    private var pending: AgentContextTicket? = null

    /**
     * 交接：来源票据的归属键必须等于此刻的归属键、来源页仍在，否则拒绝（返回 false，调用方不打开 AgentChat）。控制器已在且归属相同 → 附在它接下来的
     * 第一条消息上；还没有控制器 → 记下，建控制器时按归属键交给它（[take]）。
     */
    @Suppress("ReturnCount") // 拒绝、交给现有控制器、记下依次返回
    fun offer(
        t: AgentContextTicket,
        ownerKeyNow: AgentIdentity?,
        controller: AgentController?,
    ): Boolean {
        val alive = runCatching { t.source.pageAlive() }.getOrDefault(false)
        if (!alive || ownerKeyNow == null || t.source.ownerKey != ownerKeyNow) return false
        if (controller != null && controller.alive() && controller.boundIdentity.ownerKey == t.source.ownerKey) {
            pending = null
            controller.attachContext(t.context)
            return true
        }
        pending = t
        return true
    }

    /** 建控制器时取走：归属键不同（期间换号、撤回、切环境）就丢弃。 */
    fun take(ownerKey: AgentIdentity): JsonObject? {
        val p = pending
        pending = null
        return p?.takeIf { it.source.ownerKey == ownerKey }?.context
    }

    /** 撤回隐私同意、撤回 AI 同意、切换环境：同步清掉还没交出的 context（不留在内存里）。 */
    fun clear() {
        pending = null
    }

    val hasPending: Boolean get() = pending != null
}
