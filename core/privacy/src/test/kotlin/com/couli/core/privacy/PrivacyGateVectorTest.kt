package com.couli.core.privacy

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * 实现前测试；只读会话未编译或运行。
 *
 * 接线约定，不改变 brief.md §2.1 的构造器、方法及参数名：
 * - PrivacyStore interface：
 *   var decisionRaw: String
 *   var consentedVersion: String?
 *   var consentedAtMillis: Long?
 *   var basicMode: Boolean
 * - clock: () -> Long；now、delayMillis 均为毫秒。
 * - Gate 可注入属性 retryScheduler: RetryScheduler、
 *   withdrawalCleanup: () -> Unit。
 *   生产 withdrawalCleanup 接 APP-01 清理器注册表，此处用 spy。
 * - RetryScheduler.schedule(delayMillis: Long, action: () -> Unit)。
 * - registerInitStep(name: String, action: () -> Unit)；
 *   device_register 抛异常表示本次失败，正常返回表示成功。
 * - action 同步执行；重试仅由假调度器驱动。
 * - expect.decision 为 JSON null 时只检查实际值属于合法枚举。
 *
 * 测试依赖使用仓库已有 JUnit4、kotlinx.serialization.json；
 * 无需 Robolectric，不使用真实网络、SDK 或 Thread.sleep。
 */
class PrivacyGateVectorTest {
    private class MemoryStore(initial: JsonObject) : PrivacyStore {
        override var decisionRaw: String = initial.string("decision")
        override var consentedVersion: String? = initial.nullableString("consentedVersion")
        override var consentedAtMillis: Long? = if (consentedVersion == null) null else 0L
        override var basicMode: Boolean = initial.bool("basicMode")
    }

    private class TestClock {
        var nowMillis: Long = 0L
    }

    private class CleanupSpy {
        var calls: Int = 0
        val credentials: MutableList<String> =
            mutableListOf("token", "device_id", "install_secret")

        fun clear() {
            calls += 1
            credentials.clear()
        }
    }

    private class RecordingScheduler(
        private val clock: TestClock,
    ) : RetryScheduler {
        private data class Job(
            val dueMillis: Long,
            val action: () -> Unit,
        )

        val delays: MutableList<Long> = mutableListOf()
        private val jobs: MutableList<Job> = mutableListOf()
        val pendingCount: Int get() = jobs.size

        override fun schedule(
            delayMillis: Long,
            action: () -> Unit,
        ) {
            delays += delayMillis
            jobs += Job(clock.nowMillis + delayMillis, action)
        }

        fun advance(target: Long) {
            check(target >= clock.nowMillis) { "虚拟时间不能倒退" }
            var executed = 0
            while (true) {
                val job = jobs.minByOrNull { it.dueMillis } ?: break
                if (job.dueMillis > target) break
                executed += 1
                check(executed <= 128) { "重试循环超过测试保护上限" }
                jobs.remove(job)
                clock.nowMillis = job.dueMillis
                job.action()
            }
            clock.nowMillis = target
        }
    }

    private data class Observation(
        val name: String,
        val decisionRaw: String,
        val version: String?,
        val atMillis: Long?,
        val basicMode: Boolean,
    )

    private class InitProbe {
        val observations: MutableList<Observation> = mutableListOf()
        var attempts: Int = 0
    }

    private val vectors: JsonObject by lazy {
        val start =
            File(
                System.getProperty("user.dir")
                    ?: throw AssertionError("找不到 user.dir"),
            ).canonicalFile
        val root =
            generateSequence(start) { it.parentFile }
                .firstOrNull { File(it, "contract.lock").isFile }
                ?: throw AssertionError("从 $start 向上找不到含 contract.lock 的仓库根")
        val file = File(root, "specs/privacy-gate-vectors.json")
        Json.parseToJsonElement(file.readText(Charsets.UTF_8)).jsonObject.also {
            assertEquals(file.path, 1, it.integer("version"))
            assertTrue(file.path, it.getValue("sources").jsonArray.isNotEmpty())
            assertTrue(file.path, it.getValue("gaps").jsonArray.isNotEmpty())
            for (group in listOf("gate", "init")) {
                val rows = it.getValue(group).jsonArray.map { value -> value.jsonObject }
                assertTrue("$group 不能为空", rows.isNotEmpty())
                val names = rows.map { row -> row.string("name") }
                assertEquals("$group 名称重复", names.size, names.toSet().size)
                for (row in rows) {
                    assertTrue(row.string("name").isNotBlank())
                    assertTrue(row.string("note").isNotBlank())
                    assertTrue(row.getValue("steps").jsonArray.isNotEmpty())
                }
            }
        }
    }

    @Test
    fun gateVectors() {
        for (element in vectors.getValue("gate").jsonArray) {
            checkGate(element.jsonObject)
        }
    }

    @Test
    fun initializationVectors() {
        for (element in vectors.getValue("init").jsonArray) {
            checkInitialization(element.jsonObject)
        }
    }

    private fun checkGate(row: JsonObject) {
        val label = "${row.string("name")}: ${row.string("note")}"
        val version = row.string("bundledPolicyVersion")
        val store = MemoryStore(row.objectAt("initial"))
        val clock = TestClock()
        val scheduler = RecordingScheduler(clock)
        val cleanup = CleanupSpy()

        fun makeGate(): PrivacyGate =
            PrivacyGate(
                store = store,
                clock = { clock.nowMillis },
                bundledPolicyVersion = version,
            ).apply {
                retryScheduler = scheduler
                withdrawalCleanup = { cleanup.clear() }
            }

        var gate = makeGate()
        for ((index, element) in row.getValue("steps").jsonArray.withIndex()) {
            val step = element.jsonObject
            val op = step.string("op")
            val now = step.millis("atMillis")
            val context = "$label step[$index] $op"
            clock.nowMillis = now
            val previousCleanupCalls = cleanup.calls

            when (op) {
                "coldStart" -> {
                    // 模拟重建，复用后端而不复用 Gate 的内存状态。
                    gate = makeGate()
                    gate.coldStart(now = now)
                }
                "agree" -> {
                    gate.agree(now = now)
                    assertRecorded(store, version, now, context)
                }
                "disagree" -> gate.disagree(now = now)
                "stillDisagree" -> gate.stillDisagree(now = now)
                "requestFullFunction" -> gate.requestFullFunction(now = now)
                "withdraw" -> gate.withdraw(now = now)
                else -> throw AssertionError("$context: 未知 gate op")
            }

            val expected = step.objectAt("expect")
            val actual = gate.state(now = now)
            assertTrue(
                "$context: 非法 decisionRaw",
                actual.decisionRaw in setOf("not_decided", "consented", "basic_mode"),
            )
            expected.nullableString("decision")?.let {
                assertEquals(context, it, actual.decisionRaw)
            }
            assertEquals(context, expected.bool("mustPrompt"), actual.mustPrompt)
            assertEquals(context, expected.string("promptStage"), actual.promptStageRaw)

            if (op == "withdraw") {
                assertTrue("$context: 清理入口未调用", cleanup.calls > previousCleanupCalls)
                assertTrue(context, cleanup.credentials.isEmpty())
            } else {
                assertEquals("$context: 不应误走撤回清理", previousCleanupCalls, cleanup.calls)
            }

            if (op == "stillDisagree" || op == "withdraw") {
                assertEquals(context, "basic_mode", store.decisionRaw)
                assertTrue("$context: 基本模式必须持久化", store.basicMode)
            }
        }
    }

    private fun checkInitialization(row: JsonObject) {
        val label = "${row.string("name")}: ${row.string("note")}"
        val version = row.string("bundledPolicyVersion")
        val store = MemoryStore(row.objectAt("initial"))
        val clock = TestClock()
        val scheduler = RecordingScheduler(clock)
        val probe = InitProbe()
        var acceptedAt = store.consentedAtMillis
        val expected = row.objectAt("expect")
        val order = expected.getValue("order").jsonArray.map { it.jsonPrimitive.content }
        val delays =
            expected.getValue("deviceRegisterRetryDelaysMillis")
                .jsonArray.map { it.jsonPrimitive.long }
        val results =
            row.getValue("deviceRegisterResults").jsonArray.map { it.jsonPrimitive.boolean }
        assertTrue("$label: 注册结果脚本不能为空", results.isNotEmpty())

        val gate =
            PrivacyGate(
                store = store,
                clock = { clock.nowMillis },
                bundledPolicyVersion = version,
            ).apply {
                retryScheduler = scheduler
            }

        for (name in order.reversed()) {
            gate.registerInitStep(
                name = name,
                action = {
                    probe.observations +=
                        Observation(
                            name,
                            store.decisionRaw,
                            store.consentedVersion,
                            store.consentedAtMillis,
                            store.basicMode,
                        )
                    if (name == "device_register") {
                        probe.attempts += 1
                        if (results.getOrNull(probe.attempts - 1) == false) {
                            throw IllegalStateException("injected device registration failure")
                        }
                    }
                },
            )
        }
        assertTrue("$label: 注册不能执行步骤", probe.observations.isEmpty())
        assertTrue("$label: 注册不能安排重试", scheduler.delays.isEmpty())

        for ((index, element) in row.getValue("steps").jsonArray.withIndex()) {
            val step = element.jsonObject
            val op = step.string("op")
            val now = step.millis("atMillis")
            val context = "$label step[$index] $op"

            if (op == "advance") {
                scheduler.advance(now)
            } else {
                clock.nowMillis = now
                when (op) {
                    "coldStart" -> gate.coldStart(now = now)
                    "agree" -> {
                        acceptedAt = now
                        gate.agree(now = now)
                        assertRecorded(store, version, now, context)
                    }
                    "disagree" -> gate.disagree(now = now)
                    "stillDisagree" -> gate.stillDisagree(now = now)
                    "requestFullFunction" -> gate.requestFullFunction(now = now)
                    "onConsented" -> gate.onConsented(now = now)
                    else -> throw AssertionError("$context: 未知 init op")
                }
            }

            // 不在 action 内断言，防止断言异常被被测错误处理吞掉。
            for (observed in probe.observations) {
                assertEquals(context, "consented", observed.decisionRaw)
                assertEquals(context, version, observed.version)
                assertEquals("$context: 必须保存点击时间而非重试时间", acceptedAt, observed.atMillis)
                assertFalse(context, observed.basicMode)
            }
            assertTrue("$context: 超出注册结果脚本", probe.attempts <= results.size)

            step["expect"]?.let { value ->
                val checkpoint = value.jsonObject
                val attempts = checkpoint.integer("deviceRegisterAttempts")
                val trace =
                    if (checkpoint.bool("started")) {
                        order + List((attempts - 1).coerceAtLeast(0)) { "device_register" }
                    } else {
                        emptyList()
                    }
                assertEquals(
                    "$context: 完整调用轨迹",
                    trace,
                    probe.observations.map { it.name },
                )
                assertEquals(context, attempts, probe.attempts)
                assertEquals(
                    "$context: 累计退避延迟",
                    delays.take(checkpoint.integer("scheduledRetries")),
                    scheduler.delays,
                )
                assertEquals(context, checkpoint.integer("pendingRetries"), scheduler.pendingCount)
            }
        }

        assertTrue("$label: 本组要求幂等初始化", expected.bool("calledOnce"))
        for (name in order.filter { it != "device_register" }) {
            assertEquals(label, 1, probe.observations.count { it.name == name })
        }
        assertEquals(label, order, probe.observations.take(order.size).map { it.name })
        assertEquals(label, delays, scheduler.delays)
        assertEquals("$label: 成功后不得残留重试", 0, scheduler.pendingCount)
    }

    private fun assertRecorded(
        store: MemoryStore,
        version: String,
        atMillis: Long,
        label: String,
    ) {
        assertEquals(label, "consented", store.decisionRaw)
        assertEquals(label, version, store.consentedVersion)
        assertEquals(label, atMillis, store.consentedAtMillis)
        assertFalse(label, store.basicMode)
    }
}

private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content

private fun JsonObject.nullableString(key: String): String? {
    val value = getValue(key)
    return if (value == JsonNull) null else value.jsonPrimitive.content
}

private fun JsonObject.bool(key: String): Boolean = getValue(key).jsonPrimitive.boolean

private fun JsonObject.integer(key: String): Int = getValue(key).jsonPrimitive.int

private fun JsonObject.millis(key: String): Long = getValue(key).jsonPrimitive.long

private fun JsonObject.objectAt(key: String): JsonObject = getValue(key).jsonObject
