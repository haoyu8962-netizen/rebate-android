package com.couli.core.network

import java.util.concurrent.atomic.AtomicInteger

/**
 * 凭据读取屏障（F-X-03，FU-AND-0714；对照 iOS FU-IOS-0711 的 readBarrier）：令牌与设备凭据的每一次读取都包在 [read] 里，「现判可读 → 读」；
 * 撤回同意（PrivacyGate 记下待清理）、切换环境（关环境闸门）用 [raise] 竖屏障。
 *
 * 顺序（FU-AND-0714 第 1 轮评审 R1-01）：
 * 1. 先关**本屏障自己的内部闸**（[closed] 加 1，外部不可见）：从这一刻起新进入 [read] 的读取一律按不可读处理（不读）；
 * 2. 等此刻已经准入（已通过检查、可能停在「检查通过与实际读取之间」）的读取全部结束；
 * 3. 执行 [raise] 的 beforePublish：网络世代作废、在途请求取消；
 * 4. 最后执行 publish：发布对外可见状态（switching、撤回的待清理标记）——之后的读取由这份状态本身判为不可读；
 * 5. 打开内部闸。
 * 所以**状态一发布，就不会再有任何凭据读取**（发布之前已准入的读取都已结束，发布之后的读取看到新状态）。
 *
 * 读取方先登记（[active] 加 1）再看内部闸与现判；[raise] 先关内部闸再看 [active]：两边都是顺序一致的原子操作，要么读取方看到闸已关（不读），
 * 要么 [raise] 看到它已登记（等它读完）。
 *
 * 不用读写锁：读取方在读的过程中会取会话锁、刷新锁等，读写锁的「写者排队时新读者阻塞」会与这些锁形成环；这里读取方从不阻塞，只有
 * [raise] 自旋等待（读取都是本机的短操作，不等网络）。同一线程在读取里调用 [raise] 时只等别的线程；[raise] 期间本线程的读取同样按不可读处理。
 * App 里一个进程一份（AppModule 单例），ApiClient 与 DeviceRegistry 共用；单测各自 new 一份。
 */
class CredentialReadBarrier {
    private val active = AtomicInteger()
    private val closed = AtomicInteger()
    private val depth = ThreadLocal.withInitial { IntArray(1) }

    /** 内部闸没关、[allowed] 现判为 true 才执行 [read]，否则 [denied]（不读）。 */
    fun <T> read(
        allowed: () -> Boolean,
        denied: () -> T,
        read: () -> T,
    ): T {
        val mine = depth.get()
        active.incrementAndGet()
        mine[0] += 1
        try {
            return if (closed.get() == 0 && allowed()) read() else denied()
        } finally {
            mine[0] -= 1
            active.decrementAndGet()
        }
    }

    /** 竖屏障（不需要在发布前作废网络时）：同 [raise]，beforePublish 为空。 */
    fun raise(publish: () -> Unit) = raise(beforePublish = {}, publish = publish)

    /**
     * 竖屏障：关内部闸 → 等已准入的读取结束 → [beforePublish]（网络作废、在途取消）→ [publish]（发布对外可见状态，只改状态、不做 I/O 等待）→
     * 开内部闸。[beforePublish] 抛错时照样发布（宁可多挡），错误在发布之后抛出。
     */
    fun raise(
        beforePublish: () -> Unit,
        publish: () -> Unit,
    ) {
        closed.incrementAndGet()
        try {
            val own = depth.get()[0]
            while (active.get() > own) Thread.yield()
            val failure = runCatching(beforePublish).exceptionOrNull()
            publish()
            failure?.let { throw it }
        } finally {
            closed.decrementAndGet()
        }
    }
}
