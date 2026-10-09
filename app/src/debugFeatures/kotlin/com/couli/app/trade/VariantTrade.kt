package com.couli.app.trade

import android.content.Context
import com.couli.app.FAKE_TRADE_STATE_FILE
import com.couli.app.fakeLoginServerSwitch
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
internal fun variantWebCodeAuthorizer(context: Context): WebCodeAuthorizer {
    val switch = fakeLoginServerSwitch(context)
    return object : WebCodeAuthorizer {
        override val available: Boolean get() = switch.exists()

        override suspend fun authorize(
            authUrl: String,
            state: String,
        ): AuthStepResult {
            if (!switch.exists()) return AuthStepResult.Failed
            delay(FAKE_AUTH_MILLIS)
            return AuthStepResult.Credential(AuthCredential.WebCode(DEBUG_CODE))
        }
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
    val switch = fakeLoginServerSwitch(context)
    val state = File(switch.parentFile, FAKE_TRADE_STATE_FILE)
    return object : JumpPorts by base {
        override fun installed(platform: String): String {
            if (!switch.exists()) return base.installed(platform)
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
