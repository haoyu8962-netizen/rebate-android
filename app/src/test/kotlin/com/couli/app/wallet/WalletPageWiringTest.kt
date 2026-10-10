package com.couli.app.wallet

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.core.network.ApiCall
import com.couli.core.network.ApiResult
import com.couli.core.network.CallEpochs
import com.couli.core.network.CallOwnership
import com.couli.core.network.PendingKeyRecord
import com.couli.core.network.PendingKeyStore
import com.couli.core.network.PendingTarget
import com.couli.core.wallet.StepUpTokenCache
import com.couli.core.wallet.WalletGates
import com.couli.feature.wallet.LaborAgreementScreen
import com.couli.feature.wallet.LaborBackend
import com.couli.feature.wallet.LocalWalletCallbackTap
import com.couli.feature.wallet.PageHooks
import com.couli.feature.wallet.PayoutAccountController
import com.couli.feature.wallet.PayoutAccountScreen
import com.couli.feature.wallet.RealNameBackend
import com.couli.feature.wallet.RealNameScreen
import com.couli.feature.wallet.WalletApi
import com.couli.feature.wallet.WalletCallbackTap
import com.couli.feature.wallet.WalletDeps
import com.couli.feature.wallet.WalletEnv
import com.couli.feature.wallet.WalletPageConfig
import com.couli.feature.wallet.WalletTags
import com.couli.feature.wallet.WithdrawController
import com.couli.feature.wallet.WithdrawScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * APP-09 Android 第二次定向复核 RC-01（S0，共性第 59、63 条）的真实页面接线：渲染 A 的页面，经 [LocalWalletCallbackTap] 保留页面交给控件的真实回调；
 * 换成 B 的会话并执行清空通知、等 State 更新与重组完成后，再执行 A 的旧回调。断言 A 的身份证号、姓名、收款账号、金额不进入 B 的表单，旧的提交、保存、
 * 签署、放弃确认、确认上一笔不生成键、不发请求。回调若在执行时读委托 State（拿到换代后的 B），这些断言都会失败。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36])
@Suppress("TooManyFunctions") // 每个页面一个场景
class WalletPageWiringTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val env = Env()
    private val api = FakeApi(env)
    private val tap = Tap()
    private var session: (() -> Unit)? = null
    private var keys = 0
    private val realnameSubmits = mutableListOf<String>()
    private val laborSigns = mutableListOf<String>()

    @After
    fun tearDown() = scope.cancel()

    private class Env : WalletEnv {
        var epoch = 1L
        var user: String? = "user-a"
        val overlays = mutableListOf<Boolean>()

        override fun loggedIn() = user != null

        override fun sessionEpoch() = epoch

        override fun account() = user

        override fun configData(): JsonObject? = null

        override fun appVersion() = "1.0.0"

        override fun earningsSince(): String? = null

        override fun open(
            route: String,
            params: JsonObject,
        ) = Unit

        override fun replaceTop(
            route: String,
            params: JsonObject,
            entryId: String?,
        ) = Unit

        override fun copy(text: String) = true

        override fun forceUpdate() = Unit

        override fun stepUpPhone(account: String) = "13800005678"

        override fun sensitiveOverlay(showing: Boolean) {
            overlays += showing
        }
    }

    private class FakeApi(
        private val env: Env,
    ) : WalletApi {
        val calls = mutableListOf<ApiCall>()
        val confirms = mutableListOf<PendingTarget>()
        val abandons = mutableListOf<PendingTarget>()

        /** 两个账号各有一条键相同的收款账号未决记录（让旧回调只能靠表单身份被拦下）。 */
        var payoutPendingKey: String? = null

        override fun pendingFor(operationId: String): PendingKeyRecord? {
            val key = payoutPendingKey?.takeIf { operationId == PayoutAccountController.OPERATION }
            val account = env.user
            if (key == null || account == null) return null
            return PendingKeyRecord(
                key,
                operationId,
                """{"payout_method":"alipay"}""",
                account,
                """{"method":"alipay","account":"a***@example.com","payee":"**甲"}""",
                PendingKeyStore.UNKNOWN,
                everUnknown = true,
            )
        }

        override fun restorePending() = true

        override suspend fun confirmPending(
            operationId: String,
            stepUpToken: String?,
            target: PendingTarget,
        ): ApiResult {
            confirms += target
            return ApiResult.LocalReject("not_used")
        }

        override suspend fun abandonPending(
            operationId: String,
            target: PendingTarget,
        ): ApiResult {
            abandons += target
            return ApiResult.LocalReject("not_used")
        }

        override suspend fun send(call: ApiCall): ApiResult {
            calls += call
            return when (call.operationId) {
                WithdrawController.RULES_OPERATION -> ok(RULES_OK)
                PayoutAccountController.GET_OPERATION -> ok("{}")
                else -> ApiResult.LocalReject("not_used")
            }
        }

        override fun cancelOwned(owner: CallOwnership) = Unit

        override fun epochs() = CallEpochs(0, 0)

        private fun ok(data: String) = ApiResult.Success(Json.parseToJsonElement(data), "trace-000123", null)

        fun writes(): List<ApiCall> =
            calls.filter { it.operationId == WithdrawController.OPERATION || it.operationId == PayoutAccountController.OPERATION }
    }

    /** 记下页面交给控件的最新回调（按 testTag）。 */
    private class Tap : WalletCallbackTap {
        private val map = mutableMapOf<String, Function<*>>()

        override fun record(
            tag: String,
            callback: Function<*>,
        ) {
            map[tag] = callback
        }

        fun raw(tag: String): Function<*> = requireNotNull(map[tag]) { "页面没有登记 $tag 的回调" }

        @Suppress("UNCHECKED_CAST")
        fun text(tag: String): (String) -> Unit = raw(tag) as (String) -> Unit

        @Suppress("UNCHECKED_CAST")
        fun click(tag: String): () -> Unit = raw(tag) as () -> Unit
    }

    private fun deps(): WalletDeps =
        WalletDeps(
            api = api,
            env = env,
            gates = WalletGates(scope),
            tokens = StepUpTokenCache(),
            cache = null,
            mask = null,
            realname =
                object : RealNameBackend {
                    override suspend fun submit(
                        name: String,
                        idNo: String,
                        idempotencyKey: String,
                    ): ApiResult {
                        realnameSubmits += idNo
                        return ApiResult.LocalReject("not_used")
                    }
                },
            labor =
                object : LaborBackend {
                    override suspend fun current(): ApiResult =
                        ApiResult.Success(
                            Json.parseToJsonElement("""{"version":"v1","text":"协议正文","text_sha256":"abc","need_sign":true}"""),
                            "trace-000123",
                            null,
                        )

                    override suspend fun sign(
                        version: String,
                        textSha256: String,
                    ): ApiResult {
                        laborSigns += version
                        return ApiResult.LocalReject("not_used")
                    }
                },
            newKey = {
                keys += 1
                "key-$keys"
            },
            service = scope,
        )

    private fun show(page: @androidx.compose.runtime.Composable (WalletPageConfig) -> Unit) {
        val config = WalletPageConfig(deps(), onBack = {}, hooks = PageHooks(bindSession = { session = it }))
        composeRule.setContent {
            CompositionLocalProvider(LocalWalletCallbackTap provides tap) { page(config) }
        }
        composeRule.waitForIdle()
    }

    /** 换成 B 的会话并同步执行清空通知；等 State 更新与重组完成。 */
    private fun switchToB() {
        composeRule.runOnUiThread {
            env.user = "user-b"
            env.epoch += 1
            requireNotNull(session).invoke()
        }
        composeRule.waitForIdle()
    }

    private fun run(block: () -> Unit) {
        composeRule.runOnUiThread(block)
        composeRule.waitForIdle()
    }

    private fun type(
        tag: String,
        text: String,
    ) {
        composeRule.onNodeWithTag(tag, useUnmergedTree = true).performTextReplacement(text)
        composeRule.waitForIdle()
    }

    private fun fieldText(tag: String): String =
        composeRule
            .onNodeWithTag(tag, useUnmergedTree = true)
            .fetchSemanticsNode()
            .config
            .getOrNull(SemanticsProperties.EditableText)
            ?.text
            .orEmpty()

    private fun click(tag: String) {
        composeRule.onNodeWithTag(tag, useUnmergedTree = true).performClick()
        composeRule.waitForIdle()
    }

    private fun has(tag: String): Boolean = composeRule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    /** 实名页：A 的姓名、身份证号输入回调与【提交认证】回调在换号、重组之后到达，不进 B 的表单、不为 B 打开同意面板、不提交。 */
    @Test
    fun rc01_realNameOldCallbacksAfterSwitchAndRecompose() {
        show { RealNameScreen(it) }
        type(WalletTags.REALNAME_NAME, NAME_A)
        type(WalletTags.REALNAME_ID, ID_A)
        val oldName = tap.text(WalletTags.REALNAME_NAME)
        val oldId = tap.text(WalletTags.REALNAME_ID)
        val oldSubmit = tap.click(WalletTags.REALNAME_SUBMIT)
        switchToB()
        assertEquals("换号后 B 的表单是空的", "", fieldText(WalletTags.REALNAME_ID))
        assertNotSame("重组后控件已换成 B 的回调", oldId, tap.raw(WalletTags.REALNAME_ID))
        run {
            oldName(NAME_A)
            oldId(ID_A)
        }
        assertEquals("A 的姓名不得进 B 的表单", "", fieldText(WalletTags.REALNAME_NAME))
        assertEquals("A 的身份证号不得进 B 的表单", "", fieldText(WalletTags.REALNAME_ID))
        // B 自己填好后，A 的旧【提交认证】回调到达：不为 B 打开单独同意，也不提交
        type(WalletTags.REALNAME_NAME, NAME_B)
        type(WalletTags.REALNAME_ID, ID_B)
        env.overlays.clear()
        run { oldSubmit() }
        assertTrue("A 的旧提交回调不得为 B 打开同意面板", env.overlays.isEmpty())
        assertTrue(!has(WalletTags.CONSENT_SHEET))
        assertTrue(realnameSubmits.isEmpty())
        assertEquals(0, keys)
    }

    /** 劳务协议页：A 的旧勾选与【同意并签署】回调在换号、重组之后到达，不改 B 的勾选、不签署。 */
    @Test
    fun rc01_laborOldToggleAndSignAfterSwitchAndRecompose() {
        show { LaborAgreementScreen(it) }
        click(WalletTags.LABOR_CHECK)
        val oldToggle = tap.click(WalletTags.LABOR_CHECK)
        val oldSign = tap.click(WalletTags.LABOR_SIGN)
        switchToB()
        assertNotSame(oldSign, tap.raw(WalletTags.LABOR_SIGN))
        // B 读到协议并自己勾选
        click(WalletTags.LABOR_CHECK)
        assertEquals(ToggleableState.On, toggle(WalletTags.LABOR_CHECK))
        run { oldToggle() }
        assertEquals("A 的旧勾选不得改 B 的勾选", ToggleableState.On, toggle(WalletTags.LABOR_CHECK))
        run { oldSign() }
        assertTrue("A 的旧签署回调不得以 B 的身份签署", laborSigns.isEmpty())
    }

    private fun toggle(tag: String): ToggleableState? =
        composeRule
            .onNodeWithTag(tag, useUnmergedTree = true)
            .fetchSemanticsNode()
            .config
            .getOrNull(SemanticsProperties.ToggleableState)

    /** 收款账号页：A 的账号、姓名输入回调与【保存】回调在换号、重组之后到达，不进 B 的表单、不生成键、不发保存请求。 */
    @Test
    fun rc01_payoutOldInputsAndSaveAfterSwitchAndRecompose() {
        show { PayoutAccountScreen(it) }
        click(WalletTags.PAYOUT_METHOD_ALIPAY)
        type(WalletTags.PAYOUT_LOGON, LOGON_A)
        type(WalletTags.PAYOUT_PAYEE, PAYEE_A)
        val oldLogon = tap.text(WalletTags.PAYOUT_LOGON)
        val oldPayee = tap.text(WalletTags.PAYOUT_PAYEE)
        val oldSave = tap.click(WalletTags.PAYOUT_SAVE)
        switchToB()
        assertNotSame(oldSave, tap.raw(WalletTags.PAYOUT_SAVE))
        // B 自己选好方式、填好账号与姓名
        click(WalletTags.PAYOUT_METHOD_ALIPAY)
        type(WalletTags.PAYOUT_LOGON, LOGON_B)
        type(WalletTags.PAYOUT_PAYEE, PAYEE_B)
        run {
            oldLogon(LOGON_A)
            oldPayee(PAYEE_A)
        }
        assertEquals("A 的收款账号不得进 B 的表单", LOGON_B, fieldText(WalletTags.PAYOUT_LOGON))
        assertEquals("A 的收款人姓名不得进 B 的表单", PAYEE_B, fieldText(WalletTags.PAYOUT_PAYEE))
        run { oldSave() }
        assertEquals("A 的旧保存回调不得生成键", 0, keys)
        assertTrue("A 的旧保存回调不得发保存请求", api.writes().isEmpty())
    }

    /** 收款账号待确认：A 的【确定放弃】与【确认上一笔】回调在换号、重组之后到达（B 恰有同键记录），不作废、不确认。 */
    @Test
    fun rc01_payoutOldAbandonAndConfirmAfterSwitchAndRecompose() {
        api.payoutPendingKey = SAME_KEY
        show { PayoutAccountScreen(it) }
        assertTrue(has(WalletTags.PENDING_CARD))
        val oldConfirm = tap.click(WalletTags.PENDING_CONFIRM)
        click(WalletTags.PENDING_ABANDON)
        val oldAbandonOk = tap.click(WalletTags.ABANDON_DIALOG)
        switchToB()
        assertTrue("B 也有同键的待确认记录", has(WalletTags.PENDING_CARD))
        assertNotSame(oldConfirm, tap.raw(WalletTags.PENDING_CONFIRM))
        run { oldAbandonOk() }
        run { oldConfirm() }
        assertTrue("A 的旧放弃确认不得以 B 的身份作废", api.abandons.isEmpty())
        assertTrue("A 的旧确认不得以 B 的身份重发", api.confirms.isEmpty())
        assertTrue(api.writes().isEmpty())
        assertEquals(0, keys)
    }

    /** 提现页：A 的金额输入、【全部提现】、快捷档位、【提交】回调在换号、重组之后到达，不写进 B 的金额框、不发估算与提交。 */
    @Test
    fun rc01_withdrawOldAmountCallbacksAfterSwitchAndRecompose() {
        show { WithdrawScreen(it) }
        type(WalletTags.WITHDRAW_INPUT, "10")
        val oldInput = tap.text(WalletTags.WITHDRAW_INPUT)
        val oldAll = tap.click(WalletTags.WITHDRAW_ALL)
        val oldQuick = tap.click(WalletTags.WITHDRAW_QUICK_PREFIX + QUICK_FEN)
        val oldSubmit = tap.click(WalletTags.WITHDRAW_SUBMIT)
        switchToB()
        assertEquals("", fieldText(WalletTags.WITHDRAW_INPUT))
        assertNotSame(oldInput, tap.raw(WalletTags.WITHDRAW_INPUT))
        api.calls.clear()
        run {
            oldInput("12")
            oldAll()
            oldQuick()
            oldSubmit()
        }
        composeRule.mainClock.advanceTimeBy(WithdrawController.ESTIMATE_DEBOUNCE_MILLIS * 2)
        composeRule.waitForIdle()
        assertEquals("A 的金额不得进 B 的金额框", "", fieldText(WalletTags.WITHDRAW_INPUT))
        assertTrue("不得为 A 的金额以 B 的身份估算", api.calls.none { it.operationId == WithdrawController.RULES_OPERATION && it.query != null })
        assertTrue(api.writes().isEmpty())
        assertEquals(0, keys)
    }

    private companion object {
        const val NAME_A = "甲某"
        const val NAME_B = "乙某"
        const val ID_A = "11010519491231002X"
        const val ID_B = "110105194912310037"
        const val LOGON_A = "a@example.com"
        const val LOGON_B = "b@example.com"
        const val PAYEE_A = "甲某"
        const val PAYEE_B = "乙某"
        const val SAME_KEY = "pending-same-key"
        const val QUICK_FEN = 500L
        const val RULES_OK =
            """{"can_withdraw":true,"block_code":null,"block_reason":null,"max_withdrawable_fen":1200,"withdrawable_fen":1234,""" +
                """"daily_remaining":1,"monthly_remaining":10,"est_fee_fen":0,"est_tax_fen":0,"est_net_fen":1000,""" +
                """"quick_amounts_fen":[500,1000]}"""
    }
}
