package com.couli.app.update

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.core.router.UpdateCheck
import com.couli.core.update.MemoryUpdateStore
import com.couli.core.update.SharedPreferencesUpdateStore
import com.couli.core.update.UpdateCheckOutcome
import com.couli.core.update.UpdateCheckResult
import com.couli.core.update.UpdateGate
import com.couli.core.update.UpdateStatus
import com.couli.core.update.UpdateStore
import com.couli.core.update.UpdateStoreListing
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * FU-AND-0607：APP-07 共性第 20、21、24 条与 F-AND-23——服务端已判定强更（parse 行内 10405、登录受限会话、网络层整体 10405
 * 没带最低版本）进闸门本身（UpdateGate.onServerForced）并落盘：显示、【去更新】选店、跨进程保留都按它；成功复查或本机升级后解除。
 * 撤修复记录见 NATIVE/FU-AND-0607/revert-checks.txt。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class AppUpdateInline10405Test {
    private val now = 10_000_000L
    private val answers = ArrayDeque<CompletableDeferred<UpdateCheckOutcome>>()

    /** 可替身的商店：[listed] 里的都能打开，记录打开过哪家。 */
    private class Stores(
        private val openable: Set<String>,
    ) : StoreAccess {
        val opened = mutableListOf<String>()
        override val installSource: String? = null

        override fun canOpen(storeKey: String) = storeKey in openable

        override fun open(storeKey: String): Boolean {
            opened += storeKey
            return true
        }
    }

    private fun result(
        min: String,
        listed: String = "1.0.0",
    ) = UpdateCheckResult(
        minSupported = min,
        recommended = min,
        latest = listed,
        defaultStore = "huawei",
        stores = listOf(UpdateStoreListing("huawei", listed)),
    )

    private fun build(
        store: UpdateStore,
        stores: StoreAccess = Stores(emptySet()),
        appVersion: String = "1.0.0",
    ): AppUpdate {
        val gate =
            UpdateGate(store, clock = { now }, appVersion = appVersion, platform = "android", channel = "official", installSource = null)
        return AppUpdate(
            gate = gate,
            store = store,
            checker = { CompletableDeferred<UpdateCheckOutcome>().also { answers.addLast(it) }.await() },
            stores = stores,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
            canCheck = { true },
            clock = { now },
        )
    }

    private fun answer(outcome: UpdateCheckOutcome) {
        answers.removeFirst().complete(outcome)
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** 冷启动成功检查：缓存最低版本 1.0.0，本机 1.0.0 不低于。 */
    private fun startedOk(update: AppUpdate) {
        update.coldStart()
        answer(UpdateCheckOutcome.Success(result("1.0.0")))
        assertEquals("缓存的最低版本 1.0.0：本机不低于", UpdateStatus.OK, update.ui.value.status)
    }

    @Test
    fun anInline10405ForcesTheUpdateScreenEvenThoughTheCachedMinimumIsLower() {
        val update = build(MemoryUpdateStore())
        startedOk(update)

        update.onServerForced()
        assertEquals("行内 10405：强制强更界面", UpdateStatus.BELOW_MIN, update.ui.value.status)
        assertEquals(UpdateCheck.BELOW_MIN, update.routerUpdate)
    }

    /** F1-01：复查超时后点【去更新】：Gate 结论同为强更，没有高于本机的商店 → 显示 unavailable 与【重试】，不是无响应。 */
    @Test
    fun f1_01_goToStoreAfterAFailedRecheckShowsUnavailableInsteadOfDoingNothing() {
        val stores = Stores(setOf("huawei"))
        val update = build(MemoryUpdateStore(), stores)
        startedOk(update)
        update.onServerForced()
        update.recheck()
        answer(UpdateCheckOutcome.Failure)
        assertEquals(UpdateStatus.BELOW_MIN, update.ui.value.status)

        update.goToStore()
        assertTrue("商店上架 1.0.0 不高于本机：不能选，显示暂时无法更新与【重试】", update.ui.value.forceUnavailable)
        assertTrue("不打开不高于本机的商店", stores.opened.isEmpty())
    }

    /** F1-01：商店版本高于本机时【去更新】打开它（选店要求高于本机，不按缓存最低版本）。 */
    @Test
    fun f1_01_goToStoreOpensAStoreWhoseListedVersionIsAboveThisApp() {
        val store = MemoryUpdateStore()
        val stores = Stores(setOf("huawei"))
        val update = build(store, stores)
        update.coldStart()
        answer(UpdateCheckOutcome.Success(result("1.0.0", listed = "1.1.0")))
        update.onServerForced()
        assertEquals(UpdateStatus.BELOW_MIN, update.ui.value.status)
        assertEquals("目标版本不取不高于本机的缓存最低版本", null, update.ui.value.state.targetVersion)

        update.goToStore()
        assertEquals(listOf("huawei"), stores.opened)
        assertFalse(update.ui.value.forceUnavailable)
    }

    /** F1-02：进程被回收后冷启动检查失败，已知强更仍在（经真实 SharedPreferences 落盘）。 */
    @Test
    fun f1_02_theServerForcedUpdateSurvivesAColdStartWhoseCheckFails() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val first = build(SharedPreferencesUpdateStore(context))
        startedOk(first)
        first.onServerForced()
        first.recheck()
        answer(UpdateCheckOutcome.Failure)

        val cold = build(SharedPreferencesUpdateStore(context))
        assertEquals("冷启动在检查回来之前就是强更", UpdateStatus.BELOW_MIN, cold.ui.value.status)
        cold.coldStart()
        answer(UpdateCheckOutcome.Failure)
        assertEquals("冷启动检查失败：保留已知强更", UpdateStatus.BELOW_MIN, cold.ui.value.status)
        assertEquals(UpdateCheck.BELOW_MIN, cold.routerUpdate)
    }

    /** F1-02：同一进程里新的成功检查确认本机受支持 → 解除。 */
    @Test
    fun f1_02_aSuccessfulRecheckReleasesTheServerForcedUpdate() {
        val update = build(MemoryUpdateStore())
        startedOk(update)
        update.onServerForced()
        assertEquals(UpdateStatus.BELOW_MIN, update.ui.value.status)

        update.recheck()
        answer(UpdateCheckOutcome.Success(result("1.0.0")))
        assertEquals("成功复查：后台已调低最低版本，本机受支持", UpdateStatus.OK, update.ui.value.status)
        assertEquals(UpdateCheck.OK, update.routerUpdate)
    }

    /** F1-02：本机升级后（appVersion 变化）服务端判定不再成立。 */
    @Test
    fun f1_02_anAppUpgradeReleasesTheServerForcedUpdate() {
        val store = MemoryUpdateStore()
        val old = build(store)
        startedOk(old)
        old.onServerForced()

        val upgraded = build(store, appVersion = "1.1.0")
        upgraded.coldStart()
        answer(UpdateCheckOutcome.Failure)
        assertEquals("升级后不再按旧版本的服务端判定拦截（冷启动失败按 unknown）", UpdateStatus.UNKNOWN, upgraded.ui.value.status)
    }

    /** F-AND-23：网络层整体 10405 没带最低版本 = 服务端已判定，同样不按缓存最低版本比较；带最低版本时仍按它比较。 */
    @Test
    fun fAnd23_aWhole10405WithoutMinimumIsServerForcedWhileOneWithAMinimumIsCompared() {
        val update = build(MemoryUpdateStore())
        startedOk(update)
        update.on10405("1.0.0")
        assertEquals("带回的最低版本不高于本机：Gate 防御不改结论", UpdateStatus.OK, update.ui.value.status)
        update.on10405(null)
        assertEquals(UpdateStatus.BELOW_MIN, update.ui.value.status)
        update.recheck()
        answer(UpdateCheckOutcome.Failure)
        assertEquals("复查失败仍强更", UpdateStatus.BELOW_MIN, update.ui.value.status)
    }

    /**
     * F2-02（03 §4.2）：首次检查超时、没有成功缓存时收到行内 10405 → 立即补查一次；服务已恢复 → 自动拿到商店列表，首次点【去更新】
     * 就能打开商店。补查在途时再收到 10405 复用同一次检查。
     */
    @Test
    fun f2_02_aServerForcedUpdateWithoutACachedResultChecksAgainAndGetsTheStores() {
        val stores = Stores(setOf("huawei"))
        val update = build(MemoryUpdateStore(), stores)
        update.coldStart()
        answer(UpdateCheckOutcome.Failure)
        assertEquals("首次检查超时：没有商店列表", null, update.ui.value.result)

        update.onServerForced()
        assertEquals(UpdateStatus.BELOW_MIN, update.ui.value.status)
        assertEquals("立即补查一次", 1, answers.size)
        update.onServerForced()
        assertEquals("在途检查复用，不另发", 1, answers.size)

        answer(UpdateCheckOutcome.Success(result("1.1.0", listed = "1.1.0")))
        assertEquals(UpdateStatus.BELOW_MIN, update.ui.value.status)
        assertEquals(
            "自动拿到商店列表",
            "huawei",
            update.ui.value.result
                ?.stores
                ?.single()
                ?.store,
        )
        update.goToStore()
        assertEquals("首次点【去更新】即打开商店", listOf("huawei"), stores.opened)
        assertFalse(update.ui.value.forceUnavailable)
    }

    /** F2-02：补查仍失败：保持已知强更（不因失败放行）。 */
    @Test
    fun f2_02_aFailedFollowUpCheckKeepsTheServerForcedUpdate() {
        val update = build(MemoryUpdateStore())
        update.coldStart()
        answer(UpdateCheckOutcome.Failure)
        update.onServerForced()
        answer(UpdateCheckOutcome.Failure)
        assertEquals(UpdateStatus.BELOW_MIN, update.ui.value.status)
        assertEquals(UpdateCheck.BELOW_MIN, update.routerUpdate)
    }

    /**
     * F3-01（03 §4.2；APP-07 共性第 27 条：任何路径进入强更）：首次检查超时、没有成功缓存，网络层整体 10405 带回高于本机的最低版本
     * （经 on10405(min) 进闸门）→ 同样立即补查；补查在途时再收到 10405 复用同一次检查；自动拿到商店列表，首次点【去更新】即打开。
     */
    @Test
    fun f3_01_aWhole10405WithAHigherMinimumAndNoCacheChecksAgainAndGetsTheStores() {
        val stores = Stores(setOf("huawei"))
        val update = build(MemoryUpdateStore(), stores)
        update.coldStart()
        answer(UpdateCheckOutcome.Failure)
        assertEquals("首次检查超时：没有商店列表", null, update.ui.value.result)

        update.on10405("1.1.0")
        assertEquals("最低版本高于本机：进强更", UpdateStatus.BELOW_MIN, update.ui.value.status)
        assertEquals("立即补查一次", 1, answers.size)
        update.on10405("1.1.0")
        update.onServerForced()
        assertEquals("在途检查复用，不另发", 1, answers.size)

        answer(UpdateCheckOutcome.Success(result("1.1.0", listed = "1.1.0")))
        assertEquals(UpdateStatus.BELOW_MIN, update.ui.value.status)
        assertEquals(
            "自动拿到商店列表",
            "huawei",
            update.ui.value.result
                ?.stores
                ?.single()
                ?.store,
        )
        update.goToStore()
        assertEquals("首次点【去更新】即打开商店", listOf("huawei"), stores.opened)
        assertFalse(update.ui.value.forceUnavailable)
    }

    /** F3-01：整体 10405 带较高最低版本、补查仍失败 → 保持强更。 */
    @Test
    fun f3_01_aFailedFollowUpCheckAfterAWhole10405WithAMinimumKeepsTheForcedUpdate() {
        val update = build(MemoryUpdateStore())
        update.coldStart()
        answer(UpdateCheckOutcome.Failure)
        update.on10405("1.1.0")
        assertEquals("立即补查一次", 1, answers.size)
        answer(UpdateCheckOutcome.Failure)
        assertEquals(UpdateStatus.BELOW_MIN, update.ui.value.status)
        assertEquals(UpdateCheck.BELOW_MIN, update.routerUpdate)
    }

    /** F3-01：带回的最低版本不高于本机（没进强更）→ 不补查；有成功缓存时进强更也不补查。 */
    @Test
    fun f3_01_noFollowUpCheckWhenNotForcedOrWhenACachedResultExists() {
        val update = build(MemoryUpdateStore())
        update.coldStart()
        answer(UpdateCheckOutcome.Failure)
        update.on10405("1.0.0")
        assertTrue("没进强更：不补查", answers.isEmpty())

        val cached = build(MemoryUpdateStore())
        startedOk(cached)
        cached.on10405("1.1.0")
        assertEquals(UpdateStatus.BELOW_MIN, cached.ui.value.status)
        assertTrue("有成功缓存：不补查", answers.isEmpty())
    }
}
