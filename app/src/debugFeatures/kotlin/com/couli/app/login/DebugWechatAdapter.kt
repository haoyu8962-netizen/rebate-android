package com.couli.app.login

import android.content.Context
import com.couli.app.DebugMockGate
import com.couli.feature.login.InstalledAppProbe
import com.couli.feature.login.OAuthProvider
import com.couli.feature.login.ThirdPartyAuthAdapter
import com.couli.feature.login.ThirdPartyAuthKind
import com.couli.feature.login.ThirdPartyAuthResult
import com.couli.feature.login.UnavailableWechatAdapter
import kotlinx.coroutines.delay

/**
 * Debug / Staging 专用的假微信适配器（APP-05b 设计稿比对、冒烟与测试；src/debugFeatures，Release 不编译，release-scan 查
 * [FAKE_WECHAT_ADAPTER_MARKER]）。只在统一闸门（开关文件在 + 当前环境允许 MOCK_DATA + 环境就绪，F-X-02）放行时可用：不调用任何 SDK、不连任何地址，[DELAY_MILLIS] 后带回
 * 演示用的授权码与 state = nonce（客户端自查通过）。假服务关闭时与 Release 一样不可用（按钮不显示）。
 */
internal class DebugWechatAdapter(
    /** 统一闸门（F-X-02，[com.couli.app.DebugMockGate]）：每次调用现判，不在构造时判一次。 */
    private val gate: () -> Boolean,
    private val fallback: ThirdPartyAuthAdapter,
    private val delayMillis: Long = DELAY_MILLIS,
) : ThirdPartyAuthAdapter {
    override val provider: String = OAuthProvider.WECHAT

    override fun adapterAvailable(): Boolean = gate()

    override fun installed(): Boolean? = if (gate()) true else fallback.installed()

    @Suppress("ReturnCount") // 入口与交付前两次闸门
    override suspend fun authorize(nonce: String): ThirdPartyAuthResult {
        if (!gate()) return fallback.authorize(nonce)
        delay(delayMillis)
        // 交付前复核（等待期间切到 prod 的不交出假授权码，按 Release 的不可用适配器回答）
        if (!gate()) return fallback.authorize(nonce)
        return ThirdPartyAuthResult(ThirdPartyAuthKind.CREDENTIAL, code = DEMO_CODE, state = nonce)
    }

    private companion object {
        const val DELAY_MILLIS = 600L
        const val DEMO_CODE = "fake-wechat-code"
    }
}

/** 假微信适配器的唯一标记（Release 制品里不得出现）。 */
@Suppress("MayBeConst") // 不用 const：保证字符串留在制品里，release-scan 才查得到（Debug / Staging 必须命中）
internal val FAKE_WECHAT_ADAPTER_MARKER: String = "COULI_FAKE_WECHAT_ADAPTER_APP05B_4c9e27d1"

/** debug / staging：假服务打开时用假微信适配器，否则与 Release 相同（不可用）。 */
internal fun variantWechatAdapter(
    context: Context,
    probe: InstalledAppProbe,
): ThirdPartyAuthAdapter {
    check(FAKE_WECHAT_ADAPTER_MARKER.isNotEmpty())
    return DebugWechatAdapter(DebugMockGate.of(context), UnavailableWechatAdapter(probe))
}
