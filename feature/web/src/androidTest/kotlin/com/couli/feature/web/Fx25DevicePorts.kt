package com.couli.feature.web

import com.couli.core.bridge.TrustedWebDataCleaner
import com.couli.core.bridge.TrustedWebDataKind
import com.couli.core.bridge.TrustedWebStore

/**
 * 设备夹具的存储路径。
 * - [PROFILE]：产品 AndroidTrustedWebStore(profilesSupported = { true })，每次切号换新配置文件；
 * - [CLEARING]：产品 AndroidTrustedWebStore(profilesSupported = { false })，构造参数强制 F（不经任何 debug 开关）；
 * - [CLEARING_INJECTED]：产品 ClearingTrustedWebStore + 真实 AndroidTrustedWebDataCleaner，只在逐类完成信号上注入故障。
 */
enum class Fx25WebMode { PROFILE, CLEARING, CLEARING_INJECTED }

/**
 * 包住产品存储端口，只记录、扣住或让 rotate 失败；成功的 rotate 一律交给产品实现（新配置文件 / 真实清理）。
 * [onRotated] 在 rotate 成功确认后调用（回环服务据此放出扣住的迟到 Set-Cookie 响应）。只在主线程使用。
 */
internal class Fx25RecordingStore(
    val delegate: TrustedWebStore,
) : TrustedWebStore {
    var rotations = 0
        private set
    var successfulRotations = 0
        private set
    var holdRotate = false
    var failRotate = false
    val held = mutableListOf<(Boolean) -> Unit>()
    val discards = mutableListOf<Boolean>()
    var onRotated: () -> Unit = {}

    /**
     * v5（评审 v2 r2 S2-1）：一次性钩子，下一次有人读活动存储时（读之前）在调用线程上执行一次。打开页时产品读隔离位之后、建控制器之前
     * 会经工厂读活动存储，用来在这个窗口里确定性地插入一次并发判定。
     */
    var onNextCurrent: (() -> Unit)? = null

    override fun current(): String {
        onNextCurrent?.let { hook ->
            onNextCurrent = null
            hook()
        }
        return delegate.current()
    }

    override fun rotate(completed: (Boolean) -> Unit) {
        rotations += 1
        val finish: (Boolean) -> Unit = { ok ->
            if (ok) {
                successfulRotations += 1
                onRotated()
            }
            completed(ok)
        }
        when {
            failRotate -> finish(false)
            holdRotate -> held += finish
            else -> delegate.rotate(finish)
        }
    }

    /** 放开扣住的 rotate：[success] 为 true 交给产品真正执行，false 直接按失败回调。 */
    fun release(success: Boolean) {
        val pending = held.toList()
        held.clear()
        holdRotate = false
        pending.forEach { finish -> if (success) delegate.rotate(finish) else finish(false) }
    }

    override fun discardPending(completed: (Boolean) -> Unit) {
        delegate.discardPending { ok ->
            discards += ok
            completed(ok)
        }
    }
}

/** F 路径逐类故障：[missing] 那一类永不回调（其他类照常交给真实清理）；[confirmed] 只记真实清理确认成功的类。 */
internal class Fx25FaultCleaner(
    private val delegate: TrustedWebDataCleaner,
) : TrustedWebDataCleaner {
    var missing: TrustedWebDataKind? = null
    val requested = mutableListOf<TrustedWebDataKind>()
    val confirmed = mutableSetOf<TrustedWebDataKind>()

    override fun clear(
        kind: TrustedWebDataKind,
        completed: (Boolean) -> Unit,
    ) {
        requested += kind
        if (kind == missing) return
        delegate.clear(kind) { ok ->
            if (ok) confirmed += kind
            completed(ok)
        }
    }
}
