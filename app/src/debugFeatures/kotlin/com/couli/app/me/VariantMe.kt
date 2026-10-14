package com.couli.app.me

import android.content.Context
import com.couli.app.DebugFakeMe
import com.couli.app.DebugMockGate
import com.couli.app.FAKE_ME_STATE_FILE
import com.couli.core.push.PushTokenSource
import com.couli.feature.me.NicknameBackend
import com.couli.feature.me.NotifyBackend
import java.io.File

/**
 * debug / staging：「我的」的待定 operation（G01 改昵称、G02 通知设置）由进程内假服务实现（与假登录服务同一道统一闸门：开关 + MOCK_DATA + 环境就绪，关着时一律按断网回答，F-X-02）；推送令牌
 * 是测试令牌（假服务接管时才有，G17）；微信已装检测、打开微信失败、通知被拒时刻可用状态文件覆盖，只给设计稿截图（H04、H14 的 Android 对应）。
 */
internal fun variantNicknameBackend(context: Context): NicknameBackend? = fakeMe(context).nicknameBackend

internal fun variantNotifyBackend(context: Context): NotifyBackend? = fakeMe(context).notifyBackend

internal fun variantPushTokenSource(context: Context): PushTokenSource {
    val gate = DebugMockGate.of(context)
    // 每次取令牌都过统一闸门（F-X-02）：prod / 切换中为 null（同 Release 的 NoPushTokenSource）
    return DebugPushTokenSource { fakeMe(context).takeIf { gate() }?.state() }
}

internal fun variantWechatInstalled(context: Context): String? =
    fakeMe(context)
        .takeIf {
            DebugMockGate.of(context)()
        }?.state()
        ?.get("wechat")

internal fun variantOpenExternalFails(context: Context): Boolean =
    DebugMockGate.of(context)() && fakeMe(context).state()["cs_open"] == "fail"

@Suppress("ReturnCount") // 闸门关着、没有覆盖两个出口
internal fun variantPushDeniedAt(context: Context): Long? {
    if (!DebugMockGate.of(context)()) return null
    val hours = fakeMe(context).state()["push_denied_hours"]?.toLongOrNull() ?: return null
    return System.currentTimeMillis() - hours * HOUR_MS
}

private const val HOUR_MS = 3_600_000L

/** 进程内唯一的假「我的」服务；改昵称、通知设置两个端口每次调用都过统一闸门（F-X-02：旧实例切到 prod 后按断网）。 */
private fun fakeMe(context: Context): DebugFakeMe =
    DebugFakeMe.of(File(DebugMockGate.stateDir(context), FAKE_ME_STATE_FILE)).also { it.switchOn = DebugMockGate.of(context) }

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
