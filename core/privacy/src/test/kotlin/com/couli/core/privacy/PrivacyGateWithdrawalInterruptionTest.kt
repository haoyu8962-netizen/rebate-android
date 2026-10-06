package com.couli.core.privacy

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.core.config.CouliPreferenceNamespaces
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 评审 R2-01（S0）：撤回同意对进程中断安全。
 * - 「待清理」标记 + 基本模式 + 清同意记录在同一个 SharedPreferences 的一次 edit().commit() 里落盘；
 * - 冷启动只要盘上有标记，不论其他字段一律按基本模式处理（并补写基本模式、清同意记录）；清理成功只删标记、不改基本模式；
 * - 在每个可能的中断点模拟进程被杀，重启后：基本模式、不弹窗、不初始化、不能读凭据（清理完成前）、不放行完整功能。
 */
@RunWith(AndroidJUnit4::class)
class PrivacyGateWithdrawalInterruptionTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs get() = context.getSharedPreferences(CouliPreferenceNamespaces.PRIVACY_DECISION, Context.MODE_PRIVATE)
    private val session get() = context.getSharedPreferences(CouliPreferenceNamespaces.SESSION, Context.MODE_PRIVATE)
    private var cleanupFails = false
    private val initialized = mutableListOf<String>()

    /** 模拟进程在此处被杀：抛出后不再执行任何后续代码，之后用新的 Gate（= 新进程）读同一份 SharedPreferences。 */
    private class ProcessKilled : RuntimeException()

    @Before
    fun reset() {
        prefs.edit().clear().commit()
        session.edit().clear().commit()
        session
            .edit()
            .putString(PlaceholderCredentialKeys.ACCESS_TOKEN, "t")
            .putString(PlaceholderCredentialKeys.DEVICE_ID, "d")
            .putString(PlaceholderCredentialKeys.INSTALL_SECRET, "s")
            .commit()
        initialized.clear()
    }

    private fun gate(store: PrivacyStore = SharedPreferencesPrivacyStore(context)): PrivacyGate {
        val registry = CredentialCleanerRegistry().apply { registerPlaceholderCredentialCleaners(context) }
        return PrivacyGate(store, clock = { 1_000L }, bundledPolicyVersion = "1").apply {
            withdrawalCleanup = {
                check(!cleanupFails) { "删除失败" }
                registry.cleanAll()
            }
            retryScheduler = RetryScheduler { _, _ -> }
            PrivacyGate.INIT_ORDER.forEach { name -> registerInitStep(name) { initialized += name } }
        }
    }

    /** 已同意当前版本、尚未撤回的盘上状态。 */
    private fun consentedOnDisk() {
        gate().apply {
            agree()
            assertTrue(state().fullyConsented)
        }
        initialized.clear()
    }

    /** 重启（新进程）后必须是：基本模式、不弹窗、不放行完整功能、不初始化；清理成功后标记去掉、基本模式不变、凭据已清。 */
    private fun assertRestartIsBasicMode(label: String) {
        val restarted = gate()
        assertEquals("$label：构造后（冷启动前）就按基本模式", PrivacyDecision.BASIC_MODE, restarted.state().decision)
        restarted.coldStart()
        val state = restarted.state()
        assertEquals("$label：基本模式", PrivacyDecision.BASIC_MODE, state.decision)
        assertFalse("$label：基本模式冷启动不弹窗", state.mustPrompt)
        assertFalse("$label：不放行完整功能", state.fullyConsented)
        restarted.onConsented()
        assertTrue("$label：不初始化", initialized.isEmpty())

        val store = SharedPreferencesPrivacyStore(context)
        assertFalse("$label：清理成功后只删标记", store.withdrawalCleanupPending)
        assertTrue("$label：基本模式保留", store.basicMode)
        assertEquals("$label：决定是基本模式", PrivacyDecision.BASIC_MODE.raw, store.decisionRaw)
        assertNull("$label：同意版本已清", store.consentedVersion)
        assertNull("$label：同意时间已清", store.consentedAtMillis)
        assertTrue("$label：凭据已清", session.all.isEmpty())

        // 再冷启动一次（标记已删）：仍是基本模式，不会回到旧同意
        val again = gate()
        again.coldStart()
        assertEquals("$label：再次重启仍是基本模式", PrivacyDecision.BASIC_MODE, again.state().decision)
        again.onConsented()
        assertTrue(initialized.isEmpty())
    }

    /** 评审给出的路径：标记落盘后、基本模式落盘前进程被杀（旧实现的分步写法），其余字段仍是「已同意」。 */
    @Test
    fun killedAfterMarkBeforeBasicModeRestartsInBasicMode() {
        consentedOnDisk()
        prefs.edit().putBoolean(SharedPreferencesPrivacyStore.KEY_WITHDRAWAL_CLEANUP_PENDING, true).commit()
        assertRestartIsBasicMode("标记后、基本模式前")
    }

    /** 逐字段写入（缺省实现顺序）的每个前缀处被杀：只要标记已在盘上，重启一律基本模式。 */
    @Test
    fun everyPartialFieldWriteRestartsInBasicMode() {
        val writes: List<SharedPreferences.Editor.() -> Unit> =
            listOf(
                { putBoolean(SharedPreferencesPrivacyStore.KEY_WITHDRAWAL_CLEANUP_PENDING, true) },
                { putString(SharedPreferencesPrivacyStore.KEY_DECISION, PrivacyDecision.BASIC_MODE.raw) },
                { putBoolean(SharedPreferencesPrivacyStore.KEY_BASIC_MODE, true) },
                { remove(SharedPreferencesPrivacyStore.KEY_CONSENTED_VERSION) },
                { remove(SharedPreferencesPrivacyStore.KEY_CONSENTED_AT) },
            )
        for (prefix in 1..writes.size) {
            reset()
            consentedOnDisk()
            writes.take(prefix).forEach { write -> prefs.edit().apply(write).commit() }
            assertRestartIsBasicMode("写到第 $prefix 个字段后被杀")
        }
    }

    /** 标记与其他字段任意组合（记录损坏、旧版本留下的中间状态）：标记在就按基本模式，不论其他字段。 */
    @Test
    fun markWinsOverAnyOtherFieldCombination() {
        val combos =
            PrivacyDecision.entries.flatMap { decision ->
                listOf(false, true).flatMap { basic -> listOf(null, "1", "99").map { version -> Triple(decision, basic, version) } }
            }
        for ((decision, basic, version) in combos) {
            reset()
            prefs
                .edit()
                .putBoolean(SharedPreferencesPrivacyStore.KEY_WITHDRAWAL_CLEANUP_PENDING, true)
                .putString(SharedPreferencesPrivacyStore.KEY_DECISION, decision.raw)
                .putBoolean(SharedPreferencesPrivacyStore.KEY_BASIC_MODE, basic)
                .apply { if (version != null) putString(SharedPreferencesPrivacyStore.KEY_CONSENTED_VERSION, version) }
                .commit()
            assertRestartIsBasicMode("decision=${decision.raw} basic=$basic version=$version")
        }
    }

    /** 撤回事务一次 commit 落盘后、清理开始前被杀；以及清理已做完、删标记前被杀。 */
    @Test
    fun killedBeforeOrAfterCleanupRestartsInBasicMode() {
        consentedOnDisk()
        killDuringWithdraw(gate().apply { withdrawalCleanup = { throw ProcessKilled() } })
        assertTrue("凭据还在", session.contains(PlaceholderCredentialKeys.ACCESS_TOKEN))
        assertRestartIsBasicMode("清理开始前")

        reset()
        consentedOnDisk()
        val registry = CredentialCleanerRegistry().apply { registerPlaceholderCredentialCleaners(context) }
        killDuringWithdraw(
            gate().apply {
                withdrawalCleanup = {
                    registry.cleanAll()
                    throw ProcessKilled()
                }
            },
        )
        assertTrue("标记还在", SharedPreferencesPrivacyStore(context).withdrawalCleanupPending)
        assertRestartIsBasicMode("清理完成、删标记前")
    }

    /** 撤回后同一进程里又同意，但清理没完成就被杀：重启按基本模式（需重新同意），不恢复同意、不初始化。 */
    @Test
    fun reconsentBeforeCleanupThenKilledRestartsInBasicMode() {
        consentedOnDisk()
        cleanupFails = true
        val first = gate()
        runCatching { first.withdraw() }
        first.requestFullFunction()
        first.agree()
        assertEquals(PrivacyDecision.CONSENTED.raw, SharedPreferencesPrivacyStore(context).decisionRaw)
        cleanupFails = false
        assertRestartIsBasicMode("撤回后重新同意、清理未完成")
    }

    /** 清理在重启时仍失败：照样基本模式、凭据不可读、不初始化，标记保留。 */
    @Test
    fun restartWithCleanupStillFailingStaysBasicAndLocked() {
        consentedOnDisk()
        prefs.edit().putBoolean(SharedPreferencesPrivacyStore.KEY_WITHDRAWAL_CLEANUP_PENDING, true).commit()
        cleanupFails = true
        val restarted = gate()
        restarted.coldStart()
        assertEquals(PrivacyDecision.BASIC_MODE, restarted.state().decision)
        assertFalse(restarted.state().fullyConsented)
        assertFalse(restarted.credentialAccess.readable())
        restarted.onConsented()
        assertTrue(initialized.isEmpty())
        val store = SharedPreferencesPrivacyStore(context)
        assertTrue(store.withdrawalCleanupPending)
        assertTrue(store.basicMode)
        assertNull(store.consentedVersion)
    }

    /** 原子性：withdraw 在执行清理之前只做了一次 commit，且这一次 commit 同时带着标记、基本模式与清除同意记录。 */
    @Test
    fun withdrawalTransactionIsASingleCommit() {
        consentedOnDisk()
        val commits = mutableListOf<Set<String>>()
        val counting = SharedPreferencesPrivacyStore(countingPrefs(prefs, commits))
        var commitsBeforeCleanup = -1
        val gate =
            gate(counting).apply {
                withdrawalCleanup = {
                    commitsBeforeCleanup = commits.size
                    // 清理开始时盘上已是完整的撤回事务
                    val disk = SharedPreferencesPrivacyStore(context)
                    assertTrue(disk.withdrawalCleanupPending)
                    assertTrue(disk.basicMode)
                    assertEquals(PrivacyDecision.BASIC_MODE.raw, disk.decisionRaw)
                    assertNull(disk.consentedVersion)
                    assertNull(disk.consentedAtMillis)
                }
            }
        gate.withdraw()
        assertEquals("清理前只有一次 commit", 1, commitsBeforeCleanup)
        assertEquals(
            "那一次 commit 带齐五个键",
            setOf(
                SharedPreferencesPrivacyStore.KEY_WITHDRAWAL_CLEANUP_PENDING,
                SharedPreferencesPrivacyStore.KEY_DECISION,
                SharedPreferencesPrivacyStore.KEY_BASIC_MODE,
                SharedPreferencesPrivacyStore.KEY_CONSENTED_VERSION,
                SharedPreferencesPrivacyStore.KEY_CONSENTED_AT,
            ),
            commits.first(),
        )
        assertEquals("清理成功后再一次 commit，只删标记", setOf(SharedPreferencesPrivacyStore.KEY_WITHDRAWAL_CLEANUP_PENDING), commits[1])
        assertTrue(SharedPreferencesPrivacyStore(context).basicMode)
    }

    private fun killDuringWithdraw(gate: PrivacyGate) {
        try {
            gate.withdraw()
            fail("应在清理中被杀")
        } catch (expected: ProcessKilled) {
            // 进程被杀：之后不再使用这个 Gate
        }
    }

    /** 包一层 SharedPreferences：记录每次 commit() 涉及的键（put / remove）。 */
    private fun countingPrefs(
        real: SharedPreferences,
        commits: MutableList<Set<String>>,
    ): SharedPreferences =
        proxy(real) { method, args, invoke ->
            if (method == "edit") {
                val editor = invoke() as SharedPreferences.Editor
                val keys = mutableSetOf<String>()
                proxy(editor) { name, editorArgs, call ->
                    if (name.startsWith("put") || name == "remove") keys += editorArgs?.get(0) as String
                    if (name == "commit") commits += keys.toSet()
                    val result = call()
                    if (result === editor) null else result
                }
            } else {
                invoke()
            }
        }

    private inline fun <reified T : Any> proxy(
        target: T,
        crossinline handler: (name: String, args: Array<out Any?>?, invoke: () -> Any?) -> Any?,
    ): T {
        lateinit var self: T
        self =
            Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
                val result =
                    handler(method.name, args) {
                        try {
                            method.invoke(target, *(args ?: emptyArray()))
                        } catch (e: InvocationTargetException) {
                            throw e.targetException
                        }
                    }
                // Editor 的链式调用返回自己：换成代理，保证后续 put / commit 仍经过记录
                result ?: if (method.returnType == T::class.java) self else null
            } as T
        return self
    }
}
