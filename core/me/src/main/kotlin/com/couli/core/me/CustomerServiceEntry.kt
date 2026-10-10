package com.couli.core.me

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 统一客服出口要的系统能力（App 给；都在主线程调用）。 */
interface CustomerServicePorts {
    /** /v1/config.kf.url（生效配置：当前 → LKG → 包内）；没有为 null。 */
    fun kfUrl(): String?

    /** 微信是否已装：yes / no / unknown（只查生成物登记的查询项；Android 现为 unknown，rule-gaps A 段）。 */
    fun wechatInstalled(): String

    /** 经系统打开外部链接（不在 App 内 WebView 加载）；拉不起返回 false。 */
    fun openExternal(url: String): Boolean

    /** 经 APP-07 统一写入口写剪贴板（先记自写 hash 并确认落盘）；返回是否写入。 */
    fun copy(text: String): Boolean

    /** 经 Router 打开帮助中心（Help，h5）。 */
    fun openHelp()
}

/** 正在显示的客服半屏（[serial] 每次打开或换版式都加 1；按钮回调捕获它，过期的点击不生效）。 */
data class CsSheet(
    val serial: Long,
    val decision: CsDecision,
    val fromRoute: String,
)

/** 半屏上的一次性提示（复制结果）：[key] 文案键，[serial] 用于只播报一次。 */
data class CsToast(
    val key: String,
    val serial: Long,
)

/**
 * 统一客服出口（brief §2.9，G20，BR-ID-04 细则「未安装微信时」、BR-ID-33 细则、AC-S1-88 ④ / AC-S1-134 ④）。三端 Core 一个入口 [open]，App 层给界面
 * （CsHandoff / CsWechatMissing / CsOpenFailed 三张半屏）：
 * - 决策只看 kf.url 与微信是否已装（[CsEntryDecision]）；链接原样 kf.url（[CsLink]），不拼任何账号标识与发起页路由名；
 * - 已装或未知 → CsHandoff，【打开微信】经系统打开链接，拉不起 → CsOpenFailed（【重试】【复制客服链接】【去帮助中心】）；
 * - 未装 → CsWechatMissing（不直接打开链接）；kf 缺失或不是 https → 只有【去帮助中心】的降级半屏；
 * - 复制客服链接只走统一写入口，成功提示 cs.copy_link.done；
 * - 桥 cs.open（APP-14）复用 [openForBridge]（返回 opened）。
 * 只在主线程使用。
 */
class CustomerServiceEntry(
    private val ports: CustomerServicePorts,
) {
    private var serials = 0L
    private val mutable = MutableStateFlow<CsSheet?>(null)
    private val mutableToast = MutableStateFlow<CsToast?>(null)

    val sheet: StateFlow<CsSheet?> = mutable.asStateFlow()
    val toast: StateFlow<CsToast?> = mutableToast.asStateFlow()

    /** 打开客服出口（「我的」宫格、设置、RiskNotice、申诉页、登录页、授权管理、订单、钱包等），显示对应半屏。 */
    fun open(fromRoute: String) {
        val decision = CsEntryDecision.model(link(fromRoute), ports.wechatInstalled(), null)
        mutable.value = CsSheet(++serials, decision, fromRoute)
    }

    /** 桥 cs.open 的入口（APP-14 复用）：能直接打开就打开并返回 true；不能（未装、拉不起、没有链接）返回 false，并显示对应半屏。 */
    fun openForBridge(fromRoute: String): Boolean {
        val url = link(fromRoute)
        val decision = CsEntryDecision.model(url, ports.wechatInstalled(), null)
        if (decision.sheetRaw == CsEntryDecision.HANDOFF && decision.openUrl != null && ports.openExternal(decision.openUrl)) return true
        val shown =
            if (decision.sheetRaw ==
                CsEntryDecision.HANDOFF
            ) {
                CsEntryDecision.model(url, ports.wechatInstalled(), FAILED)
            } else {
                decision
            }
        mutable.value = CsSheet(++serials, shown, fromRoute)
        return false
    }

    /** 原样 kf.url（G20：不拼账号标识，也不拼发起页路由名）。 */
    private fun link(fromRoute: String): String? = ports.kfUrl()?.let { CsLink.build(it, fromRoute, EMPTY) }

    private fun current(serial: Long): CsSheet? = mutable.value?.takeIf { it.serial == serial }

    /** 【打开微信】/【重试】：拉起成功关闭半屏，拉不起换成 CsOpenFailed。 */
    fun openWechat(serial: Long) {
        val sheet = current(serial) ?: return
        val url = sheet.decision.openUrl ?: return
        if (ports.openExternal(url)) {
            mutable.value = null
        } else {
            mutable.value = sheet.copy(serial = ++serials, decision = CsEntryDecision.model(url, ports.wechatInstalled(), FAILED))
        }
    }

    /** 【复制客服链接】：复制的是 kf.url 原串（同一入口），成功提示 cs.copy_link.done，写不进提示复制失败。 */
    fun copyLink(serial: Long) {
        val text = current(serial)?.decision?.copyText ?: return
        mutableToast.value = CsToast(if (ports.copy(text)) COPIED else COPY_FAILED, ++serials)
    }

    /** 【去帮助中心】：关闭半屏后经 Router 打开 Help。 */
    fun goHelp(serial: Long) {
        current(serial) ?: return
        mutable.value = null
        ports.openHelp()
    }

    fun dismiss(serial: Long) {
        if (current(serial) != null) mutable.value = null
    }

    fun consumeToast(serial: Long) {
        if (mutableToast.value?.serial == serial) mutableToast.value = null
    }

    /** 撤回同意、切换环境：收起半屏与提示。 */
    fun invalidate() {
        mutable.value = null
        mutableToast.value = null
    }

    companion object {
        const val COPIED = "cs.copy_link.done"
        const val COPY_FAILED = "me.copy_failed"
        private const val FAILED = "failed"
        private val EMPTY = kotlinx.serialization.json.JsonObject(emptyMap())
    }
}
