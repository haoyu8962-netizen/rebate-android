package com.couli.core.network

import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ErrorCollector
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.MethodInsnNode

/** 精确卡住读-算-写中的写回；只给当前 JVM 的隔离类副本加调度点，不改产品文件或待测算式。 */
class ApiClientBarrierNotReadableNotificationTest {
    @get:Rule
    val failures = ErrorCollector()

    @Test
    fun concurrentSessionStoredNeverRegressesCacheOrDropsLaterUnreadableSaveNotification() {
        BarrierNotReadableNotificationGate.reset()
        val loader = NotificationLoader(javaClass.classLoader)
        val scenario = loader.loadClass("com.couli.core.network.BarrierNotReadableNotificationScenario")
        val run = scenario.getMethod("run")
        val observed = run.invoke(null) as LongArray
        assertEquals("确实只在 sessionStored 缓存更新前插入一个暂停点", 1, loader.inserted)
        assertEquals("B 已处理世代 2", 2L, observed[0])
        failures.checkSucceeds {
            assertTrue("A 恢复后缓存不得倒退：${observed.toList()}", observed[1] >= observed[0])
        }
        failures.checkSucceeds {
            assertTrue("后续成功保存的拒读通知不得落后于已处理世代：${observed.toList()}", observed[2] >= observed[0])
        }
    }

    private class NotificationLoader(
        parent: ClassLoader,
    ) : ClassLoader(parent) {
        var inserted = 0
            private set

        override fun loadClass(
            name: String,
            resolve: Boolean,
        ): Class<*> =
            synchronized(this) {
                if (isIsolated(name)) {
                    val loaded = findLoadedClass(name) ?: defineIsolated(name)
                    if (resolve) resolveClass(loaded)
                    loaded
                } else {
                    super.loadClass(name, resolve)
                }
            }

        private fun isIsolated(name: String): Boolean =
            listOf("ApiClient", "BarrierNotReadableFix8Fixture", "BarrierNotReadableNotificationScenario").any {
                name == "com.couli.core.network.$it" || name.startsWith("com.couli.core.network.$it$")
            }

        private fun defineIsolated(name: String): Class<*> {
            val path = name.replace('.', '/') + ".class"
            val original = checkNotNull(parent.getResourceAsStream(path)).use { it.readBytes() }
            val bytes = if (name == ApiClient::class.java.name) instrument(original) else original
            return defineClass(name, bytes, 0, bytes.size)
        }

        private fun instrument(original: ByteArray): ByteArray {
            val node = ClassNode()
            ClassReader(original).accept(node, 0)
            val method = node.methods.single { it.name == "sessionStored" }
            val writes =
                method.instructions.toArray().filter { instruction ->
                    // 当前版本：maxOf 已算好、PUTFIELD 尚未发生。
                    // 原子修复：在原子更新之前停，恢复后必须重读最新值。
                    val fieldWrite =
                        instruction is FieldInsnNode && instruction.opcode == Opcodes.PUTFIELD &&
                            instruction.name == "lastNotifiedGeneration" && instruction.desc == "J"
                    val atomicWrite =
                        instruction is MethodInsnNode && instruction.owner == "java/util/concurrent/atomic/AtomicLong" &&
                            instruction.name in
                            setOf("accumulateAndGet", "updateAndGet", "getAndUpdate", "getAndAccumulate", "compareAndSet")
                    fieldWrite || atomicWrite
                }
            check(writes.size == 1) { "缓存实现改变，须重新核对精确调度点；找到 ${writes.size} 处，禁止静默跳过" }
            method.instructions.insertBefore(
                writes.single(),
                MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "com/couli/core/network/BarrierNotReadableNotificationGate",
                    "beforeCacheWrite",
                    "()V",
                    false,
                ),
            )
            inserted += 1
            return ClassWriter(0).also { node.accept(it) }.toByteArray()
        }
    }
}

/** 只暂停 A。B 的算式、缓存更新、真实 onSessionStored 回调全部照常执行。 */
object BarrierNotReadableNotificationGate {
    private var reached = CountDownLatch(1)
    private var resume = CountDownLatch(1)

    fun reset() {
        reached = CountDownLatch(1)
        resume = CountDownLatch(1)
    }

    @JvmStatic
    fun beforeCacheWrite() {
        if (Thread.currentThread().name == "fix8-notification-A") {
            reached.countDown()
            check(resume.await(5, TimeUnit.SECONDS)) { "A 未恢复" }
        }
    }

    fun awaitPaused() = assertTrue("A 已算好世代 1 的缓存值且停在写回之前", reached.await(5, TimeUnit.SECONDS))

    fun release() = resume.countDown()
}

/** 隔离加载本场景和 ApiClient；其余存储、屏障、传输与原夹具共用。 */
object BarrierNotReadableNotificationScenario {
    @JvmStatic
    fun run(): LongArray {
        val rig = BarrierNotReadableFix8Fixture()
        rig.stored.save("access-1", "refresh-1", SessionScopeRaw.FULL, "account-A")
        val a = FutureTask { rig.tokens.discarded() }
        val thread = Thread(a, "fix8-notification-A").apply { isDaemon = true }
        thread.start()
        val handled =
            try {
                BarrierNotReadableNotificationGate.awaitPaused()
                rig.stored.save("access-2", "refresh-2", SessionScopeRaw.FULL, "account-A")
                rig.tokens.discarded()
                assertEquals(listOf(2L), rig.events.toList())
                cachedGeneration(rig.api)
            } finally {
                BarrierNotReadableNotificationGate.release()
                thread.join(5_000)
                assertFalse("A 工作线程退出", thread.isAlive)
            }
        a.get(5, TimeUnit.SECONDS)
        val afterA = cachedGeneration(rig.api)
        assertEquals("A 恢复后真实回调已交付两次", 2, rig.events.size)
        assertEquals("B 先处理 2", 2L, rig.events.first())
        val pause = BarrierNotReadablePause()
        rig.answer = { 0 to BarrierNotReadableFix8Fixture.LOGIN }
        rig.afterSaved = pause::stopHere
        val call = rig.login()
        val observed = pause.finishInClosedWindow(rig.control) { rig.api.execute(call) }
        assertTrue("第三次通知确实来自成功登录保存", observed.value is ApiResult.Success)
        assertEquals("account-B", rig.stored.userId)
        assertEquals(3L, rig.stored.generation)
        assertEquals(1, rig.tokens.saveAttempts.get())
        observed.assertNoReads()
        assertEquals("第三次通知没有被生产者静默跳过", 3, rig.events.size)
        return longArrayOf(handled, afterA, rig.events.last())
    }

    private fun cachedGeneration(api: ApiClient): Long {
        val field = api.javaClass.getDeclaredField("lastNotifiedGeneration").apply { isAccessible = true }
        return when (val value = field.get(api)) {
            is AtomicLong -> value.get()
            is Long -> value
            else -> error("缓存类型改变，须重核只读观察点")
        }
    }
}
