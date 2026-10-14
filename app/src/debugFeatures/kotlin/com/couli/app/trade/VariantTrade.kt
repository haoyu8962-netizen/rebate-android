package com.couli.app.trade

import android.content.Context
import com.couli.app.DebugMockGate
import com.couli.app.FAKE_TRADE_STATE_FILE
import com.couli.app.home.variantEnvironmentKey
import com.couli.feature.trade.AuthCredential
import com.couli.feature.trade.AuthStepResult
import com.couli.feature.trade.JumpPorts
import com.couli.feature.trade.OpenPurchaseController
import com.couli.feature.trade.WebCodeAuthorizer
import java.io.File
import kotlinx.coroutines.delay

/**
 * debug / staging：本机假服务打开时（同一个开关文件，APP-05a）用假的 web_code 授权跑通 AuthSheet → bindings → 自动执行一次 open 的链路
 * （设计稿比对与冒烟用；不打开任何授权页，返回演示用的假授权码）。假服务关着时与 release 相同：不可执行。Release 没有这段。
 */
internal fun variantWebCodeAuthorizer(context: Context): WebCodeAuthorizer = DebugWebCodeAuthorizer(DebugMockGate.of(context))

/**
 * 假 web_code 授权（F-X-02）：可用与否、开始授权、交出假授权码三处都过统一闸门；等待期间切到 prod 的不交出假授权码（与 Release 一样失败）。
 */
internal class DebugWebCodeAuthorizer(
    private val gate: () -> Boolean,
    private val waitMillis: Long = FAKE_AUTH_MILLIS,
) : WebCodeAuthorizer {
    override val available: Boolean get() = gate()

    @Suppress("ReturnCount") // 入口与交付前两次闸门
    override suspend fun authorize(
        authUrl: String,
        state: String,
    ): AuthStepResult {
        if (!gate()) return AuthStepResult.Failed
        delay(waitMillis)
        if (!gate()) return AuthStepResult.Failed
        return AuthStepResult.Credential(AuthCredential.WebCode(DEBUG_CODE))
    }
}

/** 「将通过浏览器打开」的提示停留时长（debug 与 release 相同）。 */
internal fun variantBrowserNoticeMillis(): Long = OpenPurchaseController.BROWSER_NOTICE_MILLIS

/**
 * debug / staging：本机假服务打开时，安装状态可由假购买服务的状态开关文件覆盖（installed.<平台>=true|false|unknown；设计稿比对
 * ProductJumpNotInstalled 用，A-R1-13：只有明确 false 才显示安装提示）。没有覆盖、假服务关着时与 release 相同。
 */
internal fun variantJumpPorts(
    context: Context,
    base: JumpPorts,
): JumpPorts {
    val gate = DebugMockGate.of(context)
    val state = File(DebugMockGate.stateDir(context), FAKE_TRADE_STATE_FILE)
    return object : JumpPorts by base {
        override fun installed(platform: String): String {
            // 每次查询过统一闸门（F-X-02）：prod / 切换中按系统实现
            if (!gate()) return base.installed(platform)
            val line =
                runCatching { state.takeIf { it.exists() }?.readLines() }
                    .getOrNull()
                    ?.firstOrNull { it.trim().startsWith("$INSTALLED_PREFIX$platform=") }
            return line?.substringAfter('=')?.trim()?.takeIf { it in INSTALLED_VALUES } ?: base.installed(platform)
        }
    }
}

private const val INSTALLED_PREFIX = "installed."
private val INSTALLED_VALUES = setOf("true", "false", "unknown")
private const val FAKE_AUTH_MILLIS = 600L
private const val DEBUG_CODE = "debug-web-code"

/**
 * 商品图缓存与在途结果的来源（F-X-02，FU-AND-0714 R1-05）：当前环境 + Debug 统一闸门开没开（[DebugMockGate.of]，每次现判）。任一变化
 * （例如在 test 经假服务加载过 img.example.test 的图片后切到 prod、关掉假服务），[AppProductImages] 先清空缓存、丢弃在途结果。
 */
internal fun variantImageSource(context: Context): () -> String {
    val app = context.applicationContext
    val gate = DebugMockGate.of(app)
    return { variantEnvironmentKey(app) + (if (gate()) "|mock" else "|real") }
}
