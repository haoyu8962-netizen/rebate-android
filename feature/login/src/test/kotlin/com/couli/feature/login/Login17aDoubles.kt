package com.couli.feature.login

import com.couli.core.network.MemorySessionTokens
import com.couli.core.network.SessionTokens
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred

internal class MethodBackend17a : LastLoginMethodBackend {
    var value: String? = null
    var writable = true
    val writes = CopyOnWriteArrayList<String?>()

    override fun read() = value

    override fun write(method: String?): Boolean {
        if (!writable) return false
        writes += method
        value = method
        return true
    }
}

internal class LoginTokens17a(
    private val memory: MemorySessionTokens = MemorySessionTokens(),
) : SessionTokens by memory {
    var failSave = false
    var afterSave: () -> Unit = {}

    override fun saveIf(
        expectedGeneration: Long,
        accessToken: String,
        refreshToken: String,
        scope: String,
        userId: String?,
    ): Boolean {
        check(!failSave) { "测试：持久化失败" }
        val saved = memory.saveIf(expectedGeneration, accessToken, refreshToken, scope, userId)
        if (saved) afterSave()
        return saved
    }
}

internal class LoginAdapter17a : ThirdPartyAuthAdapter {
    override val provider = "wechat"
    val launches = AtomicInteger()
    val finished = AtomicInteger()

    @Volatile
    private var response = CompletableDeferred<ThirdPartyAuthResult>()

    override fun adapterAvailable() = true

    override fun installed() = true

    override suspend fun authorize(nonce: String): ThirdPartyAuthResult {
        launches.incrementAndGet()
        return try {
            response.await()
        } finally {
            finished.incrementAndGet()
        }
    }

    /** 只在上一轮 SDK 回调和页面动作结算后启用下一次独立授权屏障，不复用旧凭证回调。 */
    fun prepareNextAuthorization() {
        check(response.isCompleted && finished.get() == launches.get())
        response = CompletableDeferred()
    }

    fun succeed() = response.complete(ThirdPartyAuthResult(ThirdPartyAuthKind.CREDENTIAL, code = "code-17a", state = "nonce-17a"))

    fun cancel() = response.complete(ThirdPartyAuthResult.of(ThirdPartyAuthKind.CANCELLED))
}

/** 受限登录对应强更来源；否则 deletion_only 会触发另一条升级会话复查链。 */
internal class Login17aHost(
    private val restricted: Boolean,
) : LoginHost {
    override fun updateState() = if (restricted) AfterLoginUpdate.BELOW else AfterLoginUpdate.SUPPORTED

    override fun hasPendingAction() = false

    override fun closeLogin() = Unit

    override fun resumePending() = Unit

    override fun discardPending() = Unit

    override fun enterForceUpdate() = Unit

    override fun sessionChanged() = Unit

    override fun sessionSynced() = Unit

    override fun sessionEnded() = Unit
}
