package com.couli.app.me

import android.content.Context
import com.couli.app.DebugFakeMe
import com.couli.app.FAKE_ME_STATE_FILE
import com.couli.app.fakeLoginServerSwitch
import com.couli.core.push.PushTokenSource
import com.couli.feature.me.NicknameBackend
import com.couli.feature.me.NotifyBackend
import java.io.File

/**
 * debug / staging：「我的」的待定 operation（G01 改昵称、G02 通知设置）由进程内假服务实现（与假登录服务同一个开关文件，开关不在时一律按断网回答）；推送令牌
 * 是测试令牌（假服务接管时才有，G17）；微信已装检测、打开微信失败、通知被拒时刻可用状态文件覆盖，只给设计稿截图（H04、H14 的 Android 对应）。
 */
internal fun variantNicknameBackend(context: Context): NicknameBackend? = fakeMe(context).nicknameBackend

internal fun variantNotifyBackend(context: Context): NotifyBackend? = fakeMe(context).notifyBackend

internal fun variantPushTokenSource(context: Context): PushTokenSource =
    DebugPushTokenSource {
        fakeMe(context).takeIf { fakeLoginServerSwitch(context).exists() }?.state()
    }

internal fun variantWechatInstalled(context: Context): String? =
    fakeMe(context)
        .takeIf {
            fakeLoginServerSwitch(context).exists()
        }?.state()
        ?.get("wechat")

internal fun variantOpenExternalFails(context: Context): Boolean =
    fakeLoginServerSwitch(context).exists() && fakeMe(context).state()["cs_open"] == "fail"

@Suppress("ReturnCount") // 开关不在、没有覆盖两个出口
internal fun variantPushDeniedAt(context: Context): Long? {
    if (!fakeLoginServerSwitch(context).exists()) return null
    val hours = fakeMe(context).state()["push_denied_hours"]?.toLongOrNull() ?: return null
    return System.currentTimeMillis() - hours * HOUR_MS
}

private const val HOUR_MS = 3_600_000L

private fun fakeMe(context: Context): DebugFakeMe {
    val switch = fakeLoginServerSwitch(context)
    return DebugFakeMe.of(File(switch.parentFile, FAKE_ME_STATE_FILE)).also { it.switchOn = { switch.exists() } }
}

/** Debug 的推送令牌来源：假服务接管时给测试令牌（状态文件 push_token 可改值），[start] 之前没有令牌；令牌值不进日志。 */
internal class DebugPushTokenSource(
    private val state: () -> Map<String, String>?,
) : PushTokenSource {
    private var started = false
    private val listeners = mutableListOf<() -> Unit>()

    override fun start() {
        if (started) return
        started = true
        listeners.toList().forEach { it() }
    }

    override fun stop() {
        started = false
    }

    override fun current(): String? = if (started) state()?.let { it["push_token"] ?: TEST_TOKEN } else null

    override fun subscribe(listener: () -> Unit): () -> Unit {
        listeners += listener
        return { listeners -= listener }
    }

    private companion object {
        const val TEST_TOKEN = "test-push-token-0001"
    }
}
