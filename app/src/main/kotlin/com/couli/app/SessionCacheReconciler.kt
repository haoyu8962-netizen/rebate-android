package com.couli.app

import com.couli.core.network.SessionIdentity

/**
 * 会话变化对账（订单、钱包共用；FU-AND-0717：F-X-17 补充、F-X-19 及补充、补充二、补充三；鸿蒙 FU-HM-0723 OrderSessionReconciler、iOS FU-IOS-0712
 * R2-01 / R3-01..03、复核一 RC-01..03 同法）。每次检查：
 * 1. 只取**一份**身份快照（[SessionIdentity]：已知与否、账号、登录世代、持久化登录世代同一次读出），判据、归属对账与页面替换都只用它，不再另读；
 * 2. 先完成已登记的待清理（身份暂时未知也照做，补充二 ②）；
 * 3. 身份暂时未知（凭据不可读、屏障未过、读取失败）：只延后——不对账、不删、不记判据（保留上次已确认的那一份），即使本进程登录世代变了也不重置页面
 *    （第 1 轮修复：未知不决策、确认换代才重置），返回 [Decision.UNKNOWN]；账号已确认而持久化世代缺失（合法旧会话）：只按本进程登录世代判换代，不按分区对账；
 * 4. 身份已知：直接按这份身份对账（比较固定的分区标签「账号 + 持久化登录世代」，补充：不先用不含登录世代的计数判有没有变）。对账没完成（待清理删不掉、
 *    归属读不出导致的删除失败）记为待重试，下一次检查（身份已知时）重做，不因判据相同而跳过（补充三 ③）；
 * 5. 判据：本进程登录世代变了（登录、清除），或上次已确认的是**已有账号**而这次账号 / 持久化世代不同（换号、同账号换代——含计数不变而持久化世代换了），
 *    才算会话换代（[Decision.CHANGED]）；冷启动的「未知 → 已知」「没有会话 → 有会话」（同一登录世代里凭据变可读、隐私结算）不算（[Decision.SAME]），
 *    只做第 4 步的标签对账（标签一致不删）。
 * 只在主线程用。
 */
internal class SessionCacheReconciler(
    /** 一份身份快照（App 接 ApiClient.sessionIdentity）。 */
    private val identity: () -> SessionIdentity,
    /** 构造时的本进程登录世代（不读凭据）。 */
    initialEpoch: Long,
    /** 完成已登记的待清理；返回此刻是否没有待清理。 */
    private val completePendingClear: () -> Boolean,
    /** 按已确认的身份对账（账号为 null = 已确认没有会话）；返回是否完成。 */
    private val reconcile: (SessionIdentity) -> Boolean,
) {
    /** 一次检查的结论。 */
    enum class Decision {
        /** 身份暂时未知：什么都没做（待清理除外）。 */
        UNKNOWN,

        /** 身份已知、不是会话换代（含冷启动的未知 → 已知）。 */
        SAME,

        /** 会话换代。 */
        CHANGED,
    }

    private var lastEpoch: Long = initialEpoch

    /** 上次已确认的身份（账号 + 持久化登录世代）；从没确认过为 null。 */
    private var lastTag: Pair<String?, String?>? = null

    /** 对账没完成、等下一次（身份已知时）重试。 */
    var reconcilePending: Boolean = false
        private set

    /** 最近一次检查用的那份快照。 */
    var snapshot: SessionIdentity? = null
        private set

    /** 检查一次（会话通知、Router 会话状态变化、启动）。 */
    @Suppress("ReturnCount") // 未知、分区未知、已知三个出口
    fun check(): Decision {
        val id = identity()
        snapshot = id
        completePendingClear()
        // 身份暂时未知：不决策——不删、不记判据，本进程登录世代也不记（第 1 轮修复：未知不决策，确认换代才重置页面）
        if (!id.known) return Decision.UNKNOWN
        val epochChanged = id.loginEpoch != lastEpoch
        lastEpoch = id.loginEpoch
        // 账号已确认、持久化世代缺失（合法旧会话，R1-01）：不记标签、不按标签判换代，只按本进程登录世代；对账照调（按分区的存储给不出世代即不动，
        // 不分区的存储按账号对账）
        if (!id.partitionKnown) {
            reconcilePending = !reconcile(id)
            return if (epochChanged) Decision.CHANGED else Decision.SAME
        }
        val tag = id.account to id.partition
        val previous = lastTag
        val tagChanged = previous?.first != null && previous != tag
        lastTag = tag
        reconcilePending = !reconcile(id)
        return if (epochChanged || tagChanged) Decision.CHANGED else Decision.SAME
    }
}
