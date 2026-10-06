package com.couli.core.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * APP-02c Android 评审第 1 轮补测（向量之外的 Gate 行为）：
 * - R1-02：冷启动时缓存的「不低于」（ok / below_recommended）不作数，本次失败按 unknown；没有发检查（基本模式）也是 unknown；
 *   同一进程成功过之后失败保持本进程结论；「暂不更新」记录不失效；失败后下次回前台再试（不看间隔）。
 * - R1-04（rule-gaps G15，编排者裁定）：落盘的 below_min（成功检查或 10405）在本机版本升级后按新 appVersion 与缓存的最低版本重比，
 *   已不低于就不是已知强更（冷启动失败 → unknown）；仍低于照旧 below_min；10405 没带最低版本时升级即失效。
 */
class UpdateGateColdStartUpgradeTest {
    private var now = 1_000L
    private val store = MemoryUpdateStore()

    private fun gate(appVersion: String = "1.0.0") =
        UpdateGate(
            store = store,
            clock = { now },
            appVersion = appVersion,
            platform = "android",
            channel = "official",
            installSource = "huawei",
        )

    private fun result(
        min: String?,
        recommended: String?,
        latest: String = "1.2.0",
    ) = UpdateCheckResult(
        minSupported = min,
        recommended = recommended,
        latest = latest,
        defaultStore = "huawei",
        stores = listOf(UpdateStoreListing("huawei", latest)),
    )

    private fun UpdateGate.status() = state(now).statusRaw

    @Test
    fun cachedOkDoesNotCountAfterColdStartAndFailureIsUnknown() {
        gate().apply(result("1.0.0", "1.0.0"), now)

        val cold = gate()
        assertTrue(cold.onColdStart(now))
        assertEquals("checking", cold.status())
        now += 100
        cold.applyFailure(now)
        assertEquals("unknown", cold.status())
        // 失败后下次回前台再试，即使距旧成功检查不足复查间隔
        now += 100
        assertTrue(cold.onForeground(now))
        cold.apply(result("1.0.0", "1.0.0"), now)
        assertEquals("ok", cold.status())
        // 本进程成功之后：间隔内回前台不查，失败保持本进程的 ok
        now += 100
        assertFalse(cold.onForeground(now))
        cold.applyFailure(now)
        assertEquals("ok", cold.status())
    }

    @Test
    fun cachedOkWithoutAnyCheckThisProcessIsUnknown() {
        // 基本模式不发检查（rule-gaps G06）：不调用 onColdStart，缓存的 ok 也不作数
        gate().apply(result("1.0.0", "1.0.0"), now)
        assertEquals("unknown", gate().status())
    }

    @Test
    fun cachedBelowRecommendedColdFailureIsUnknownAndDismissalSurvives() {
        val first = gate()
        first.apply(result("1.0.0", "1.1.0"), now)
        first.dismissPrompt(now)

        val cold = gate()
        cold.onColdStart(now)
        cold.applyFailure(now)
        val unknown = cold.state(now)
        assertEquals("unknown", unknown.statusRaw)
        assertFalse(unknown.showPrompt)
        assertEquals(null, unknown.targetVersion)

        cold.onForeground(now)
        cold.apply(result("1.0.0", "1.1.0"), now)
        val again = cold.state(now)
        assertEquals("below_recommended", again.statusRaw)
        assertFalse("同一 latest 的暂不更新记录仍有效", again.showPrompt)
    }

    @Test
    fun knownBelowMinStillBlocksAfterColdStartFailure() {
        gate().apply(result("1.1.0", "1.2.0"), now)
        val cold = gate()
        cold.onColdStart(now)
        assertEquals("below_min", cold.status())
        cold.applyFailure(now)
        assertEquals("below_min", cold.status())
    }

    @Test
    fun checkedBelowMinIsRecomparedAfterUpgrade() {
        gate("1.0.0").apply(result("1.1.0", "1.2.0"), now)

        for (upgraded in listOf("1.1.0", "1.2.0")) {
            val cold = gate(upgraded)
            assertTrue(cold.onColdStart(now))
            assertEquals("$upgraded checking", "checking", cold.status())
            cold.applyFailure(now)
            assertEquals("$upgraded unknown", "unknown", cold.status())
        }
        val stillLow = gate("1.0.5")
        stillLow.onColdStart(now)
        stillLow.applyFailure(now)
        assertEquals("below_min", stillLow.status())
        assertEquals("1.1.0", stillLow.state(now).targetVersion)
    }

    @Test
    fun persisted10405IsRecomparedAfterUpgrade() {
        gate("1.0.0").on10405("1.1.0", now)
        assertEquals("below_min", gate("1.0.0").status())

        for (upgraded in listOf("1.1.0", "1.2.0")) {
            val cold = gate(upgraded)
            cold.onColdStart(now)
            assertEquals("$upgraded checking", "checking", cold.status())
            cold.applyFailure(now)
            assertEquals("$upgraded unknown", "unknown", cold.status())
        }
        val stillLow = gate("1.0.9")
        stillLow.onColdStart(now)
        stillLow.applyFailure(now)
        assertEquals("below_min", stillLow.status())
    }

    @Test
    fun persisted10405WithoutMinimumExpiresWhenAppVersionChanges() {
        gate("1.0.0").on10405(null, now)
        val same = gate("1.0.0")
        same.onColdStart(now)
        same.applyFailure(now)
        assertEquals("below_min", same.status())

        val upgraded = gate("1.0.1")
        upgraded.onColdStart(now)
        upgraded.applyFailure(now)
        assertEquals("unknown", upgraded.status())
    }

    @Test
    fun persisted10405AfterUpgradeFallsBackToCachedCheckBelowMin() {
        // 10405 的最低版本已满足，但成功检查缓存的最低版本更高：仍按更高的那个强更
        gate("1.0.0").apply(result("1.3.0", "1.3.0", latest = "1.3.0"), now)
        gate("1.0.0").on10405("1.1.0", now)
        val cold = gate("1.2.0")
        cold.onColdStart(now)
        cold.applyFailure(now)
        assertEquals("below_min", cold.status())
        assertEquals("1.3.0", cold.state(now).targetVersion)
    }
}
