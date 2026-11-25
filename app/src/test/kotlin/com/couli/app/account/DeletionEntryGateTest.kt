@file:Suppress("MagicNumber")

package com.couli.app.account

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.shell.DELETION_PROMPT_TAG
import com.couli.app.update.FORCE_UPDATE_DELETE_TAG
import com.couli.app.update.FORCE_UPDATE_PRIVACY_TAG
import com.couli.core.router.GuardDecision
import com.couli.core.router.GuardKind
import com.couli.core.router.GuardReason
import com.couli.core.router.RouteAvailability
import com.couli.core.router.RouteRequest
import com.couli.core.router.RouteSource
import com.couli.core.router.UpdateState
import com.couli.feature.me.AccountTags
import com.couli.feature.me.MeTags
import java.io.File
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * FU-AND-DELETION-ENTRY-GATE（D10-2913 ①、D10-2914、D10-2967；iOS 同类做法 D10-2903 ②(b)）：注销的本地发布闸。
 * 契约 DeleteAccount since.android=1.0.0 不变（RouteAvailability 仍列出它），但它不在变体路由覆盖里时按「本端未发布」处理：
 * - 可用路由集合（AppRouting.availableRoutes）不含 DeleteAccount；
 * - 设置页注销行、封禁说明页 / 申诉页注销入口、强更页「注销账号 / 撤销注销」次要入口都不显示；
 * - App 内打开 DeleteAccount（10007 cooling 的落点）只提示 error.10007、不导航；深链 / 推送打开给升级提示（UPGRADE），不进注销页；
 * - 变体覆盖含 DeleteAccount 时（Debug / Staging 原设计）照常打开；Release 源码集的变体覆盖恒为空集。
 * [override] 是变体覆盖的测试替身（AccountHarness 缺省 = {DeleteAccount}，本文件按用例显式给值）。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h844dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Suppress("TooManyFunctions")
class DeletionEntryGateTest : AccountHarness() {
    // ---------------------------------------------------------------- 可用路由与契约

    @Test
    fun gate_withoutOverrideAvailableRoutesDropDeleteAccountWhileTheContractStillListsIt() {
        override = emptySet()
        launch()
        assertTrue("契约层事实不变：since.android=1.0.0，本端 1.0.0 可用", RouteAvailability.available(DELETE_ACCOUNT, "1.0.0"))
        assertTrue("契约层事实不变：RouteAvailability.all 仍含 DeleteAccount", DELETE_ACCOUNT in RouteAvailability.all("1.0.0"))
        val available = routing.availableRoutes()
        assertFalse("本地发布闸：没有变体覆盖时可用路由不含 DeleteAccount", DELETE_ACCOUNT in available)
        assertTrue("闸只关 DeleteAccount，别的路由照旧", SETTINGS in available)
    }

    @Test
    fun gate_withOverrideAvailableRoutesKeepDeleteAccount() {
        override = setOf(DELETE_ACCOUNT)
        launch()
        assertTrue("变体覆盖含 DeleteAccount（Debug / Staging）时可用", DELETE_ACCOUNT in routing.availableRoutes())
    }

    // ---------------------------------------------------------------- 入口

    @Test
    fun gate_withoutOverrideTheSettingsDeletionRowIsHidden() {
        override = emptySet()
        launch()
        open(SETTINGS)
        idleUntil("设置页（换绑手机行已出）") { has(MeTags.SETTINGS_PAGE) && has(PHONE_ROW) }
        assertFalse("没有变体覆盖时设置页不显示注销行", has(DELETE_ROW))
    }

    @Test
    fun gate_withoutOverrideTheRiskNoticeDeletionLinkIsHidden() {
        serverState.risk = "banned"
        override = emptySet()
        launch()
        open("RiskNotice")
        idleUntil("封禁说明页（客服出口已出）") { has(MeTags.RISK_PAGE) && has(MeTags.RISK_CS) }
        assertFalse("没有变体覆盖时封禁说明页不显示注销入口", has(AccountTags.RISK_DELETE))
    }

    @Test
    fun gate_withoutOverrideTheAppealDeletionLinkIsHidden() {
        openUpheldAppeal(emptySet())
        assertFalse("没有变体覆盖时申诉页（维持结果）不显示注销入口", has(AccountTags.APPEAL_DELETE))
    }

    /** 上一条的对照：同一套申诉夹具在有覆盖时确实走到带注销入口的出口行（防止上一条因夹具没到位而空过）。 */
    @Test
    fun gate_withOverrideTheAppealDeletionLinkIsShown() {
        openUpheldAppeal(setOf(DELETE_ACCOUNT))
        assertTrue("变体覆盖含 DeleteAccount 时申诉页（维持结果）显示注销入口", has(AccountTags.APPEAL_DELETE))
    }

    @Test
    fun gate_withoutOverrideForceUpdateShowsOnlyThePrivacyPolicy() {
        override = emptySet()
        launch(forced = true)
        idleUntil("强更页（隐私政策入口已出）") { top() == FORCE_UPDATE && has(FORCE_UPDATE_PRIVACY_TAG) }
        settle()
        assertFalse("没有变体覆盖时强更页不显示「注销账号」次要入口", has(FORCE_UPDATE_DELETE_TAG))
        assertFalse(hasText(t("app_update.delete_account")))
    }

    @Test
    fun gate_withoutOverrideForceUpdateHasNoCancelDeletionEntryWhileCooling() {
        serverState.deletion = deletionJson("cooling", canCancel = true)
        override = emptySet()
        launch(forced = true)
        idleUntil("强更页（隐私政策入口已出）") { top() == FORCE_UPDATE && has(FORCE_UPDATE_PRIVACY_TAG) }
        settle()
        assertFalse("冷静期可撤回也不显示「撤销注销」次要入口", has(FORCE_UPDATE_DELETE_TAG))
        assertFalse(hasText(t("app_update.cancel_deletion")))
    }

    // ---------------------------------------------------------------- Router 落点

    @Test
    fun gate_withoutOverrideAnInAppOpenOnlyPrompts10007AndDoesNotNavigate() {
        override = emptySet()
        launch()
        open(SETTINGS)
        idleUntil { has(MeTags.SETTINGS_PAGE) }
        val before = stack()
        val prompts = routing.deletionPrompt.value
        // 10007 cooling 的落点：App 内经 Router 打开 DeleteAccount
        composeRule.runOnUiThread { routing.router.open(RouteRequest(DELETE_ACCOUNT, JsonObject(emptyMap()), RouteSource.IN_APP)) }
        settle()
        assertEquals("不导航（不进注销页、不进升级页）", before, stack())
        assertEquals("只提示 error.10007 一次", prompts + 1, routing.deletionPrompt.value)
        assertTrue("外壳轻提示 error.10007", has(DELETION_PROMPT_TAG) || announcedText(t("error.10007")))
    }

    @Test
    fun gate_withoutOverrideADeepLinkGetsTheUpgradePrompt() {
        override = emptySet()
        launch()
        val prompts = routing.deletionPrompt.value
        val decision = openWithUpdateOk(RouteSource.DEEPLINK)
        assertEquals("深链打开未发布的 DeleteAccount 给升级提示", GuardKind.UPGRADE, decision.kind)
        assertEquals("拦在路由判定（本端未发布），不是被别的闸门抢先拦下", GuardReason.UNKNOWN_ROUTE, decision.reason)
        assertNotEquals("不进注销页", DELETE_ACCOUNT, top())
        assertEquals("深链不走 10007 提示", prompts, routing.deletionPrompt.value)
    }

    @Test
    fun gate_withoutOverrideAPushGetsTheUpgradePrompt() {
        override = emptySet()
        launch()
        val prompts = routing.deletionPrompt.value
        val decision = openWithUpdateOk(RouteSource.PUSH)
        assertEquals("推送打开未发布的 DeleteAccount 给升级提示", GuardKind.UPGRADE, decision.kind)
        assertEquals("拦在路由判定（本端未发布），不是被别的闸门抢先拦下", GuardReason.UNKNOWN_ROUTE, decision.reason)
        assertNotEquals("不进注销页", DELETE_ACCOUNT, top())
        assertEquals("推送不走 10007 提示", prompts, routing.deletionPrompt.value)
    }

    /** 上两条的对照：同一前置（强更 OK、真实 AppRouting 组状态）、有覆盖时深链走到路由判定的入口检查，被拦为 entry_not_allowed。 */
    @Test
    fun gate_withOverrideADeepLinkReachesTheEntryCheck() {
        override = setOf(DELETE_ACCOUNT)
        launch()
        val decision = openWithUpdateOk(RouteSource.DEEPLINK)
        assertEquals("有覆盖：深链入口没声明，被拦", GuardKind.REJECT, decision.kind)
        assertEquals("拦在入口检查（请求确实走到了路由判定）", GuardReason.ENTRY_NOT_ALLOWED, decision.reason)
        assertNotEquals("不进注销页", DELETE_ACCOUNT, top())
    }

    /** 覆盖含 DeleteAccount（Debug / Staging 原设计）：设置注销行显示，App 内打开直接进注销页、不弹 10007 提示。 */
    @Test
    fun gate_withOverrideTheSettingsRowAndInAppOpenWorkAsBefore() {
        override = setOf(DELETE_ACCOUNT)
        launch()
        open(SETTINGS)
        idleUntil("设置页注销行") { has(MeTags.SETTINGS_PAGE) && has(DELETE_ROW) }
        composeRule.runOnUiThread { navController.popBackStack() }
        settle()
        val prompts = routing.deletionPrompt.value
        composeRule.runOnUiThread { routing.router.open(RouteRequest(DELETE_ACCOUNT, JsonObject(emptyMap()), RouteSource.IN_APP)) }
        idleUntil("进注销页") { top() == DELETE_ACCOUNT && has(AccountTags.DELETE_PAGE) }
        assertEquals("可用时不弹 10007 提示", prompts, routing.deletionPrompt.value)
        assertFalse(has(DELETION_PROMPT_TAG))
    }

    // ---------------------------------------------------------------- Release 源码集

    @Test
    fun gate_releaseVariantRouteOverridesIsAlwaysEmpty() {
        val source = File(repoRoot(), RELEASE_OVERRIDE_FILE).readText()
        val squashed = source.replace(Regex("\\s+"), "")
        assertTrue(
            "Release 的 variantRouteOverrides 恒返回空集",
            Regex("""funvariantRouteOverrides\([^)]*\):\(\)->Set<String>=\{emptySet\(\)\}""").containsMatchIn(squashed),
        )
        listOf("readLines", "File(", "DebugMockGate", "COULI_ROUTE_OVERRIDE_APP13A", "\"$DELETE_ACCOUNT\"").forEach {
            assertFalse("Release 覆盖源码不得出现 $it", source.contains(it))
        }
    }

    // ---------------------------------------------------------------- 工具

    /** 封禁 + 最近一次账户申诉维持原结果：申诉页底部固定栏有【再次申诉】与出口行（客服 | 注销 | 退出）。 */
    private fun openUpheldAppeal(overrides: Set<String>) {
        serverState.risk = "banned"
        override = overrides
        // 列表读取可能不止一次（进页、回到前台），多排几份同样的应答
        repeat(APPEAL_ANSWERS) { enqueue("GET", APPEALS) { envelope(UPHELD_APPEALS) } }
        launch()
        open("Appeal")
        idleUntil("申诉页维持结果的固定栏") { has(MeTags.APPEAL_PAGE) && has(MeTags.APPEAL_AGAIN) && has(MeTags.APPEAL_CS) }
    }

    /**
     * 从 [source] 打开 DeleteAccount，显式给强更结论 OK（仍由真实 AppRouting 组状态，带着本地闸与覆盖）：夹具里强更检查没有结论（UNKNOWN），
     * 守卫的强更闸门先于路由判定，会把深链 reject、把推送改开 Messages，走不到本单要钉的路由判定。
     */
    private fun openWithUpdateOk(source: RouteSource): GuardDecision {
        val request = RouteRequest(DELETE_ACCOUNT, JsonObject(emptyMap()), source)
        val decision = composeRule.runOnIdle { routing.router.open(request, routing.guardState(UpdateState.OK)) }
        settle()
        return decision
    }

    private fun repoRoot(): File =
        generateSequence(File(System.getProperty("user.dir")).canonicalFile) { it.parentFile }
            .first { File(it, "contract.lock").isFile }

    private companion object {
        const val APPEALS = "/v1/me/appeals"
        const val APPEAL_ANSWERS = 6
        const val RELEASE_OVERRIDE_FILE = "app/src/release/kotlin/com/couli/app/routing/VariantRouteOverride.kt"
        const val UPHELD_APPEALS =
            """{"items":[{"appeal_id":"appeal-old","target_type":"account","target_id":null,"status":"upheld",""" +
                """"content":"测试申诉，请核查。","created_at":"2026-10-10T10:00:00+08:00",""" +
                """"closed_at":"2026-10-11T15:00:00+08:00"}],"next_cursor":null}"""
    }
}
