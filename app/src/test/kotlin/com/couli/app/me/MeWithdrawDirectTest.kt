package com.couli.app.me

import android.app.Application
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.core.me.MeLevel
import com.couli.core.me.MeView
import com.couli.core.wallet.WalletRoutes
import com.couli.feature.me.MeTags
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * FU-AND-WITHDRAW-DIRECT（D10-2714 ① 方案 A）：「我的」余额卡【提现】直接打开提现申请页（WalletRoutes.WITHDRAW），不再经「我的 → 钱包」一跳；
 * 宫格「钱包」仍进钱包。拦截口径照钱包【提现】（WalletController.withdraw，G11），以 Me 快照里实际有的字段为准：
 * - 渲染时的快照版本不符、身份已变、游客、错误态（没有快照）→ 不动作；
 * - 负余额：Me 没有 negative_fen，按 balance.available_fen < 0 判（对应钱包摘要 negative_fen > 0）→ 不动作（执行器层拦，不只是按钮置灰）；
 *   余额为 0 不算负余额，照常打开（钱包也不按 0 拦，能不能提由提现页 rules 决定）；
 * - 冻结：Me 现在没有「冻结时留本页轻提示」，不新增；照常打开提现页，冻结交给提现页 rules（30303 account_frozen）；
 * - withdrawEnabled == false：Me 快照没有 withdraw_enabled 字段，跳过（功能关闭由提现页 rules 30306 与钱包页处理）；
 * - 断网缓存态：Me 不做断网缓存（G29，MeRepository「不做断网缓存」），没有 staleAt 一类字段，跳过；刷新失败保留旧快照只给一次提示，本组不钉它。
 * 「我的」执行器只经 MeEnv.open 打开（App 侧再经 Router 与 AppWallet 的实名预取，见 MeWithdrawDirectAppTest），不另写前置判断、不发 rules / 摘要请求。
 *
 * 先红（00bdbaf）：执行器仍打开 MeView.WALLET，下面每个用例都在「应打开 Withdraw / 不应打开任何页」的断言上变红；本来就成立的拦截断言（版本、身份、
 * 游客、错误态）各带一次放行对照（恢复条件后打开的是 Withdraw），对照在 00bdbaf 上变红。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h1032dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MeWithdrawDirectTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { MeVisualTestHost(rule) }

    @After fun close() = host.close()

    private fun routes() = host.env.opened.map { it.first }

    private fun version() = host.deps.repo.state.value.version

    private fun tapWithdraw() {
        rule.meTag(MeTags.ME_WITHDRAW).performClick()
        rule.waitForIdle()
    }

    private fun awaitLoaded() = rule.waitUntil(WAIT_MS) { host.deps.repo.meOf(host.env.identity()) != null }

    @Test fun withdrawOpensWithdrawDirectlyNotWallet() {
        host.launch()
        tapWithdraw()
        assertEquals("「我的」【提现】直达提现申请页", listOf(WalletRoutes.WITHDRAW), routes())
        assertTrue(
            "不带参数（与钱包【提现】同一打开方式）",
            host.env.opened
                .single()
                .second
                .isEmpty(),
        )
        assertTrue("不经钱包", MeView.WALLET !in routes())
        // 只读一次 Me；「我的」不预取 rules / 钱包摘要（PageApi 只记到 getMe）
        host.assertReads(1)
    }

    @Test fun staleRenderedVersionDoesNothingThenCurrentVersionOpensWithdraw() {
        host.launch()
        val controller = host.controller
        val rendered = version()
        host.refresh(MeVisualFixtures.ok())
        val current = version()
        assertNotEquals("刷新后快照版本前进", rendered, current)
        rule.runOnIdle { controller.withdraw(rendered) }
        assertTrue("渲染时的快照已过期：不动作", host.env.opened.isEmpty())
        rule.runOnIdle { host.controller.withdraw(current) }
        assertEquals("对照：当前版本打开 Withdraw", listOf(WalletRoutes.WITHDRAW), routes())
        host.assertReads(2)
    }

    @Test fun changedIdentityDoesNothingThenNewIdentityOpensWithdraw() {
        host.launch()
        val old = host.controller
        val rendered = version()
        rule.runOnIdle { host.env.identityFlow.value = host.env.identity().copy(epoch = 2) }
        rule.waitForIdle()
        rule.runOnIdle {
            old.withdraw(rendered)
            old.withdraw(version())
        }
        assertTrue("身份已变：旧执行器一律不动作", host.env.opened.isEmpty())
        awaitLoaded()
        tapWithdraw()
        assertEquals("对照：新身份的执行器打开 Withdraw", listOf(WalletRoutes.WITHDRAW), routes())
    }

    @Test fun guestHasNoWithdrawAndDoesNothingThenMemberOpensWithdraw() {
        val member = host.env.identity()
        host.env.identityFlow.value = member.copy(level = MeLevel.GUEST, account = null)
        host.launch()
        rule.meTag(MeTags.ME_WITHDRAW).assertDoesNotExist()
        rule.runOnIdle { host.controller.withdraw(version()) }
        assertTrue("游客：不动作", host.env.opened.isEmpty())
        host.assertReads(0)
        rule.runOnIdle { host.env.identityFlow.value = member }
        rule.waitForIdle()
        awaitLoaded()
        tapWithdraw()
        assertEquals("对照：登录后打开 Withdraw", listOf(WalletRoutes.WITHDRAW), routes())
        host.assertReads(1)
    }

    @Test fun errorStateWithoutSnapshotDoesNothingThenLoadedOpensWithdraw() {
        host.api.answer = MeVisualFixtures.failure()
        host.launch()
        rule.meTag(MeTags.ME_ERROR).assertExists()
        rule.meTag(MeTags.ME_WITHDRAW).assertDoesNotExist()
        rule.runOnIdle { host.controller.withdraw(version()) }
        assertTrue("错误态（没有快照）：不动作", host.env.opened.isEmpty())
        host.api.answer = MeVisualFixtures.ok()
        rule.meTag(MeTags.ME_RETRY).performClick()
        rule.meTag(MeTags.ME_BALANCE).assertExists()
        tapWithdraw()
        assertEquals("对照：重试成功后打开 Withdraw", listOf(WalletRoutes.WITHDRAW), routes())
        host.assertReads(2)
    }

    @Test fun negativeAvailableDoesNotOpenAnythingEvenFromTheExecutor() {
        host.launch("me_negative")
        tapWithdraw()
        assertTrue("负余额（available_fen < 0）：点按钮不动作（或已置灰）", host.env.opened.isEmpty())
        rule.runOnIdle { host.controller.withdraw(version()) }
        assertTrue("执行器层也拦（与钱包 negativeFen > 0 同口径），不只靠按钮置灰", host.env.opened.isEmpty())
        rule.onNodeWithText("待抵扣", substring = true).assertDoesNotExist()
        host.assertReads(1)
    }

    @Test fun zeroBalanceIsNotNegativeAndStillOpensWithdraw() {
        host.launch("me_new_user")
        tapWithdraw()
        assertEquals("余额为 0 不按负余额拦，交给提现页 rules", listOf(WalletRoutes.WITHDRAW), routes())
        host.assertReads(1)
    }

    @Test fun frozenStillOpensWithdrawAndLeavesTheBlockToRules() {
        host.launch("me_frozen")
        tapWithdraw()
        assertEquals("冻结：照常打开提现页，由 rules 的 account_frozen 拦", listOf(WalletRoutes.WITHDRAW), routes())
        assertNull("不新增「冻结留本页轻提示」", host.controller.toast.value)
        host.assertReads(1)
    }

    @Test fun walletGridCellStillOpensWalletWhileWithdrawOpensWithdraw() {
        host.env.routes = MeVisualFixtures.routes(public = true)
        host.launch()
        val walletCell = MeView.MEMBER_GRID.first { it.route == MeView.WALLET }
        rule.onNodeWithText(walletCell.item).performScrollTo().performClick()
        rule.waitForIdle()
        rule.meTag(MeTags.ME_WITHDRAW).performScrollTo()
        tapWithdraw()
        assertEquals("宫格「钱包」仍进钱包；【提现】进提现页", listOf(MeView.WALLET, WalletRoutes.WITHDRAW), routes())
        assertTrue(host.env.opened.all { it.second.isEmpty() })
        host.assertReads(1)
    }

    private companion object {
        const val WAIT_MS = 5_000L
    }
}
