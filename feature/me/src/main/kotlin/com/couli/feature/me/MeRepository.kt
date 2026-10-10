// 执行器的每个入口逐项核对身份、版本、禁用期与结果类别（APP-09 第 52、63、65 条）
@file:Suppress("CyclomaticComplexMethod", "ComplexCondition")

package com.couli.feature.me

import com.couli.core.me.Me
import com.couli.core.me.MeJson
import com.couli.core.network.ApiCall
import com.couli.core.network.ApiResult
import com.couli.core.network.CallOwnership
import com.couli.core.wallet.WalletError
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 「我的」与设置共用的 Me 快照。[identity] 是这份快照所属的身份；[version] 每次内容变化加 1（按钮回调带渲染时的版本）。 */
data class MeSnapshot(
    val identity: MeIdentity? = null,
    val phase: String = IDLE,
    val me: Me? = null,
    val error: MeNotice? = null,
    val refreshing: Boolean = false,
    val version: Long = 0,
) {
    companion object {
        const val IDLE = "idle"
        const val LOADING = "loading"
        const val CONTENT = "content"
        const val ERROR = "error"
    }
}

/**
 * 发起 Me 读取的页面（Android R1-03，共性第 21 条）：页面执行器本身就是这次进入的实例（「我的」Tab 每次进入建新的执行器；设置页的执行器随回退栈条目）。
 * [reading] 为 false（离页、出栈、换代）之后，它发起的读请求不再写出（含刷新后的重放）、结果不再结算；页面的同步出口调 [MeRepository.release] 取消已交给传输层的请求。
 */
fun interface MeReader {
    fun reading(): Boolean
}

/**
 * Me 快照（GET /v1/me；G36、G06、G05、G07，brief §2.2）：会话级、不做断网缓存（G29）。
 * - 进入 Tab、下拉、回前台、登录成功、从子页返回各取一次（页面调 [refresh]），不轮询；游客与基本模式不请求；
 * - 同类读取按请求序号只收最新一次；每次读取绑定身份（账号 + 登录世代 + 隐私 / 环境世代）与 expectedAccount，换代（[reset]）同步清空已显示的快照并作废在途；
 * - 昵称保存成功推进读取世代（[nicknameSaved]）：保存前发出的 getMe 晚到不把旧昵称覆盖回来；
 * - 坏数据（必填缺失、未知 risk.state）与 5xxxx 显示加载失败与【重试】，不回落到游客态；42901 按 Retry-After 禁用（执行层 + 页面）。
 * 只在主线程使用。
 */
class MeRepository(
    private val deps: MeDeps,
) {
    private val mutable = MutableStateFlow(MeSnapshot())
    val state: StateFlow<MeSnapshot> = mutable.asStateFlow()
    private val s: MeSnapshot get() = mutable.value
    private var seq = 0L
    private var generation = 0L
    private var ids = 0L
    private val owners = mutableMapOf<CallOwnership, MeReader>()

    /** 最近一次读取的发起页面（只收最新一次）。 */
    private var latest: MeReader? = null

    /** 当前快照（只给同一身份）。 */
    fun meOf(identity: MeIdentity): Me? = s.me?.takeIf { s.identity == identity }

    /**
     * 取一次 Me。[identity] 是发起页面固定的身份：与快照的身份不同时先同步清空（换代）；已不是当前身份时不发。[page] 是发起页面（R1-03）：读取许可绑定
     * 身份与发起页面，从实际写出一直到结果结算都核对。返回是否发出。
     */
    @Suppress("ReturnCount")
    fun refresh(
        identity: MeIdentity,
        page: MeReader,
        pull: Boolean = false,
    ): Boolean {
        if (!page.reading()) return false
        if (s.identity != identity) reset(identity)
        if (!identity.member || !identity.current(deps.env)) return false
        if (deps.gates.read.active) {
            gated(identity)
            return false
        }
        val request = ++seq
        val gen = generation
        update { if (it.me == null) it.copy(phase = MeSnapshot.LOADING, error = null) else it.copy(refreshing = pull) }
        val probe = deps.gates.read.probe()
        latest = page
        val owner =
            CallOwnership(deps.api.epochs(), sendAllowed = probe::allowed) {
                seq == request && generation == gen && s.identity == identity && identity.current(deps.env) && page.reading()
            }.also { owners[it] = page }
        deps.service.launch {
            val result = deps.api.send(ApiCall(OPERATION, ownership = owner, expectedAccount = identity.account))
            owners -= owner
            // 被更新的读取、昵称保存、换代、发起页面离开取代：这次结果不改任何状态（含快照与限流，共性第 19、21、43 条）
            if (seq != request || generation != gen || s.identity != identity || !identity.current(deps.env) || !page.reading()) {
                return@launch
            }
            val me = (result as? ApiResult.Success)?.let { MeJson.me(it.data) }
            if (me != null) {
                update { MeSnapshot(identity, MeSnapshot.CONTENT, me, version = it.version + 1) }
                return@launch
            }
            val error = MeErrors.of(result)
            if (error.kind == WalletError.CANCELLED) {
                if (probe.gated(error)) {
                    gated(identity)
                } else {
                    update {
                        it.copy(
                            refreshing = false,
                            phase =
                                if (it.me ==
                                    null
                                ) {
                                    MeSnapshot.IDLE
                                } else {
                                    it.phase
                                },
                        )
                    }
                }
                return@launch
            }
            if (error.kind == WalletError.RETRY_AFTER) deps.gates.read.start(error.disableMillis)
            if (error.kind == WalletError.FORCE_UPDATE) deps.env.forceUpdate()
            failed(MeErrors.notice(error, ++ids))
        }
        return true
    }

    private fun gated(identity: MeIdentity) {
        if (s.identity == identity) failed(MeNotice(RATE_KEY, ++ids))
    }

    /** 失败：没有快照 → 加载失败态（不回落游客）；已有快照 → 保留并给一次提示。 */
    private fun failed(notice: MeNotice) {
        update {
            if (it.me == null) {
                it.copy(phase = MeSnapshot.ERROR, error = notice, refreshing = false, version = it.version + 1)
            } else {
                it.copy(error = notice, refreshing = false, version = it.version + 1)
            }
        }
    }

    /**
     * 发起页面离开（出栈、切走 Tab，页面的同步出口）：撤销并取消它发起的读请求；它是最近一次读取时，快照回到可重新读取的状态（加载中 → 空闲）。共享快照本身保留。
     */
    fun release(page: MeReader) {
        val mine = owners.filterValues { it === page }.keys.toList()
        mine.forEach { owners.remove(it) }
        if (latest === page) {
            latest = null
            seq += 1
            update {
                it.copy(
                    phase =
                        if (it.me == null &&
                            it.phase == MeSnapshot.LOADING
                        ) {
                            MeSnapshot.IDLE
                        } else {
                            it.phase
                        },
                    refreshing = false,
                )
            }
        }
        mine.forEach(deps.api::cancelOwned)
    }

    /**
     * 昵称保存成功（[identity] 是保存表单的固定身份）：推进读取世代并作废在途的 getMe（保存之前发出的不再覆盖），把快照里的昵称换成新值，再按同一身份由
     * [page]（设置页）重取。
     */
    fun nicknameSaved(
        identity: MeIdentity,
        nickname: String,
        page: MeReader,
    ) {
        if (s.identity != identity || !identity.current(deps.env)) return
        generation += 1
        seq += 1
        cancelAll()
        update { snap ->
            val me = snap.me ?: return@update snap
            val raw = JsonObject(me.raw + ("nickname" to JsonPrimitive(nickname)))
            snap.copy(me = me.copy(raw = raw, nickname = nickname), version = snap.version + 1)
        }
        refresh(identity, page)
    }

    /** 已显示的错误提示消费掉（快照与错误态不变）。 */
    fun consumeError(id: Long) {
        if (s.error?.id == id && s.me != null) update { it.copy(error = null) }
    }

    /** 换代（会话失效、登出、换账号、撤回同意、切换环境）：同步清空快照并作废在途。 */
    fun reset(identity: MeIdentity? = null) {
        seq += 1
        generation += 1
        cancelAll()
        mutable.value = MeSnapshot(identity = identity, version = s.version + 1)
    }

    private fun cancelAll() {
        val cancelled = owners.keys.toList()
        owners.clear()
        latest = null
        cancelled.forEach(deps.api::cancelOwned)
    }

    private inline fun update(change: (MeSnapshot) -> MeSnapshot) {
        mutable.value = change(mutable.value)
    }

    companion object {
        const val OPERATION = "getMe"
        const val RATE_KEY = "error.42901"
    }
}
