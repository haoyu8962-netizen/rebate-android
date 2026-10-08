package com.couli.app.login

import android.content.Context
import com.couli.app.fakeLoginServerSwitch
import com.couli.feature.login.InstalledAppProbe
import com.couli.feature.login.OAuthProvider
import com.couli.feature.login.ThirdPartyAuthAdapter
import com.couli.feature.login.ThirdPartyAuthKind
import com.couli.feature.login.ThirdPartyAuthResult
import com.couli.feature.login.UnavailableWechatAdapter
import java.io.File
import kotlinx.coroutines.delay

/**
 * Debug / Staging 专用的假微信适配器（APP-05b 设计稿比对、冒烟与测试；src/debugFeatures，Release 不编译，release-scan 查
 * [FAKE_WECHAT_ADAPTER_MARKER]）。只在本机假登录服务打开（开关文件存在）时可用：不调用任何 SDK、不连任何地址，[DELAY_MILLIS] 后带回
 * 演示用的授权码与 state = nonce（客户端自查通过）。假服务关闭时与 Release 一样不可用（按钮不显示）。
 */
internal class DebugWechatAdapter(
    private val switch: File,
    private val fallback: ThirdPartyAuthAdapter,
) : ThirdPartyAuthAdapter {
    override val provider: String = OAuthProvider.WECHAT

    override fun adapterAvailable(): Boolean = switch.exists()

    override fun installed(): Boolean? = if (switch.exists()) true else fallback.installed()

    override suspend fun authorize(nonce: String): ThirdPartyAuthResult {
        if (!switch.exists()) return fallback.authorize(nonce)
        delay(DELAY_MILLIS)
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
    return DebugWechatAdapter(fakeLoginServerSwitch(context), UnavailableWechatAdapter(probe))
}
