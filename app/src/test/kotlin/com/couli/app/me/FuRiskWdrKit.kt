package com.couli.app.me

import android.content.Context
import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText as textMatcher
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.couli.app.account.AppAccountTexts
import com.couli.app.wallet.visual.WithdrawRecordsVisualApi
import com.couli.app.wallet.visual.WithdrawRecordsVisualEnv
import com.couli.app.wallet.visual.WithdrawRecordsVisualFixtures
import com.couli.core.clientcore.AmountMaskStore
import com.couli.core.designsystem.CouliColors
import com.couli.core.designsystem.CouliFormat
import com.couli.core.designsystem.CouliTheme
import com.couli.core.designsystem.LocalCouliAnnouncer
import com.couli.core.me.MeLocalStore
import com.couli.core.me.account.AccountShared
import com.couli.core.wallet.StepUpTokenCache
import com.couli.core.wallet.WalletDraftTexts
import com.couli.core.wallet.WalletGates
import com.couli.core.wallet.WalletJson
import com.couli.core.wallet.WalletRateGate
import com.couli.core.wallet.WithdrawalText
import com.couli.core.wallet.stepup.SmsDeadlines
import com.couli.core.wallet.stepup.StepUpDeps
import com.couli.feature.me.AccountDeps
import com.couli.feature.me.LocalAccountDeps
import com.couli.feature.me.MeDeps
import com.couli.feature.me.MeEnv
import com.couli.feature.me.MeGates
import com.couli.feature.me.MeTags
import com.couli.feature.me.RiskNoticeConfig
import com.couli.feature.me.RiskNoticeScreen
import com.couli.feature.wallet.WalletDeps
import com.couli.feature.wallet.WalletEnv
import com.couli.feature.wallet.WalletPageConfig
import com.couli.feature.wallet.WalletTags
import com.couli.feature.wallet.WithdrawRecordsScreen
import java.io.File
import java.time.Duration
import java.util.TimeZone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.robolectric.Shadows.shadowOf

/*
 * FU-AND-RISK-WDR（封禁页「进行中的提现」副文案，共性第 36 条）测试辅助。只拼报文、给期望值（期望值一律由产品纯函数 WithdrawalText.of / render 现算，
 * 不在测试里另写副文案规则），不实现任何产品规则。
 */

/** 报文与期望值。 */
internal object FuRiskWdr {
    /** BR-TEXT-07 原文（字面，不从草稿表取；与 T1 的 WITHDRAWAL_SLA 同值）。 */
    const val ORIGINAL_SLA = "人工审核，工作日 24 小时内处理，节假日顺延"

    /** 注入配置用的另一种写法（D10-594 J2：运营可经 texts["withdraw.sla_text"] 改写）。 */
    const val CONFIG_SLA = "测试配置：提现会在审核通过后尽快到账"
    const val CONFIG_SLA_LATER = "测试配置（改后）：以审核结果为准"
    const val SLA_KEY = "withdraw.sla_text"

    /** fixture withdrawals_pending 的第一笔（PENDING_REVIEW、manual、¥10）。 */
    val fixturePending: JsonObject get() = RiskVisualFixtures.withdrawalItems("withdrawals_pending").first()

    @Suppress("LongParameterList")
    fun item(
        id: String,
        status: String,
        reviewMode: String?,
        fen: Long,
        channel: String = "alipay",
        channelName: String = "支付宝",
        masked: String = "138****0000",
        extra: Map<String, JsonElement> = emptyMap(),
    ): JsonObject =
        JsonObject(
            mapOf(
                "withdrawal_id" to JsonPrimitive(id),
                "status" to JsonPrimitive(status),
                "review_mode" to (reviewMode?.let(::JsonPrimitive) ?: JsonNull),
                "amount_fen" to JsonPrimitive(fen),
                "fee_fen" to JsonPrimitive(0),
                "tax_fen" to JsonPrimitive(0),
                "net_fen" to JsonPrimitive(fen),
                "payout_channel" to JsonPrimitive(channel),
                "payout_channel_name" to JsonPrimitive(channelName),
                "masked_account" to JsonPrimitive(masked),
                "reason_code" to JsonNull,
                "fail_action" to JsonNull,
                "created_at" to JsonPrimitive("2026-11-05T09:00:00+08:00"),
            ) + extra,
        )

    fun list(items: List<JsonObject>): JsonObject = JsonObject(mapOf("items" to JsonArray(items), "next_cursor" to JsonNull))

    /** 在途五态（manual / auto / PAYING / 不认识的通道 / review_mode=null）+ 终态与表外状态各一（不应显示）。 */
    val mixed: List<JsonObject> by lazy {
        listOf(
            item("m1", "PENDING_REVIEW", "manual", 1_000),
            item("m2", "APPROVED", "auto", 2_000, "bank_card", "银行卡", "6222****0000"),
            item("m3", "PAYING", "manual", 3_000),
            item("m4", "PENDING_REVIEW", "manual", 4_000, "wechat", "微信", "wx****0000"),
            item("m5", "APPROVED", null, 5_000),
            item("t1", "PAID_MANUAL", "manual", 6_000),
            item("t2", "REJECTED", "manual", 7_000),
            item("t3", "FAILED", "manual", 8_000),
            item("t4", "HOLD", "manual", 9_000),
        )
    }
    val mixedShown: List<JsonObject> get() = mixed.take(5)
    val mixedHidden: List<JsonObject> get() = mixed.drop(5)

    /** 封禁页不带配置时的「四态一行」（截图用：manual、auto、PAYING、不认识的通道）。 */
    val fourStates: List<JsonObject> get() = mixed.take(4)

    fun fen(item: JsonObject): Long =
        item
            .getValue("amount_fen")
            .jsonPrimitive.content
            .toLong()

    /** 同源期望（共性第 36 条）：提现记录页同一个纯函数对同一单、同一 SLA 的结果。 */
    fun sublines(
        item: JsonObject,
        sla: String?,
    ): List<String> = WithdrawalText.render(WithdrawalText.of(requireNotNull(WalletJson.withdrawal(item)), sla), WalletDraftTexts)

    /** 值文字「¥10 · 审核中」（risk.withdrawal_row，与现视图同一取法）。 */
    fun value(item: JsonObject): String {
        val title = WithdrawalText.of(requireNotNull(WalletJson.withdrawal(item)), null).titleKey
        return AppMeTexts.format(
            "risk.withdrawal_row",
            mapOf("amount" to CouliFormat.money(fen(item)), "status" to WalletDraftTexts.text(title)),
        )
    }

    val label: String get() = AppMeTexts.text("risk.withdrawals")

    fun config(sla: JsonElement): JsonObject = JsonObject(mapOf("texts" to JsonObject(mapOf(SLA_KEY to sla))))

    fun config(sla: String): JsonObject = config(JsonPrimitive(sla))

    /** 三端包内默认配置（生成物，只读）：服务端不下发时 configData() 的最后一档（D10-594 J2）。 */
    val bundledConfig: JsonObject by lazy {
        Json
            .parseToJsonElement(File(RiskVisualFixtures.root, "core/generated/src/main/resources/default_config.json").readText())
            .jsonObject
    }

    val bundledSla: String get() =
        bundledConfig
            .getValue("texts")
            .jsonObject
            .getValue(SLA_KEY)
            .jsonPrimitive.content
}

/** 提现行（[MeTags.RISK_WITHDRAWALS]）：按页面自上而下。 */
internal fun ComposeContentTestRule.wdrRows(merged: Boolean = false): List<SemanticsNode> =
    onAllNodes(hasTestTag(MeTags.RISK_WITHDRAWALS), useUnmergedTree = !merged)
        .fetchSemanticsNodes()
        .sortedBy { it.boundsInRoot.top }

/** 一行里各文字节点的文字（未合并树，组合顺序 = 读屏顺序）。 */
internal fun SemanticsNode.wdrTexts(): List<String> =
    wdrFlatten().mapNotNull { node -> node.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text } }

internal fun SemanticsNode.wdrFlatten(): List<SemanticsNode> = listOf(this) + children.flatMap { it.wdrFlatten() }

/** 提现行之下文字恰为 [text] 的未合并节点。 */
internal fun ComposeContentTestRule.wdrText(text: String): List<SemanticsNodeInteraction> {
    val inRow = textMatcher(text) and hasAnyAncestor(hasTestTag(MeTags.RISK_WITHDRAWALS))
    val count = onAllNodes(inRow, useUnmergedTree = true).fetchSemanticsNodes().size
    return (0 until count).map { onAllNodes(inRow, useUnmergedTree = true)[it] }
}

/** 整页文字恰为 [text] 的未合并节点数。 */
internal fun ComposeContentTestRule.wdrCountExact(text: String): Int =
    onAllNodes(textMatcher(text), useUnmergedTree = true).fetchSemanticsNodes().size

/** 整页文字含 [text] 的未合并节点数。 */
internal fun ComposeContentTestRule.wdrCountContaining(text: String): Int =
    onAllNodes(textMatcher(text, substring = true), useUnmergedTree = true).fetchSemanticsNodes().size

/** 配置注入版环境（brief §3.3：接口委托包一层 MeEnv，只覆写 configData；不改 RiskVisualEnv / RiskVisualTestHost）。 */
internal class FuRiskWdrEnv(
    private val base: RiskVisualEnv,
) : MeEnv by base {
    var config: JsonObject? = null
    var configReads = 0

    override fun configData(): JsonObject? {
        configReads += 1
        return config
    }
}

/** 提现记录页的配置注入版环境（同上，只覆写 configData）。 */
internal class FuRecordsEnv(
    private val base: WithdrawRecordsVisualEnv,
) : WalletEnv by base {
    var config: JsonObject? = null

    override fun configData(): JsonObject? = config
}

/**
 * 配置注入版宿主（照 RiskVisualTestHost：真实 RiskNoticeScreen → 真实 RiskNoticeController → 真实 MeGates 读取闸门；只替换传输层与环境出口）。
 * 多出：① [FuRiskWdrEnv] 可注入 /v1/config；② 读取闸门的时钟可推进（[advance]，禁用期到期后【重试】恢复）；③ 可在同一窗口上方并排放真实提现记录页
 * （WithdrawRecordsScreen，注入同一份配置），核「同源同值」。所有入口用真实触摸。
 */
@Suppress("TooManyFunctions")
internal class FuRiskWdrHost(
    private val rule: ComposeContentTestRule,
) : AutoCloseable {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val service = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val oldZone = TimeZone.getDefault()
    val base = RiskVisualEnv()
    val env = FuRiskWdrEnv(base)
    val api = RiskVisualApi()
    val accountEnv = RiskVisualAccountEnv(base)
    val records = WithdrawRecordsVisualApi()
    val recordsEnv = FuRecordsEnv(WithdrawRecordsVisualEnv())
    val announcements = MeVisualAnnouncementProbe(rule)
    val palette = RiskVisualPalette()
    var now: Long = RiskVisualFixtures.now
    lateinit var deps: MeDeps
    private lateinit var account: AccountDeps

    @Suppress("LongParameterList")
    fun launch(
        withdrawals: List<JsonObject>,
        config: JsonObject? = null,
        risk: String = "risk_banned",
        withRecords: Boolean = false,
        dark: Boolean = false,
        fontScale: Float = 1f,
    ) {
        api.answers.getOrPut(RiskVisualApi.GET_ME) { RiskVisualFixtures.ok(RiskVisualFixtures.me(risk)) }
        api.answers.getOrPut(RiskVisualApi.LIST_APPEALS) { RiskVisualFixtures.ok(RiskVisualFixtures.appeals("appeals_empty")) }
        api.answers.getOrPut(RiskVisualApi.LIST_WITHDRAWALS) { RiskVisualFixtures.ok(FuRiskWdr.list(withdrawals)) }
        base.entered10006 = RiskVisualFixtures.entered10006(risk)
        base.routes = RiskVisualFixtures.routes(public = true)
        env.config = config
        recordsEnv.config = config
        prepareDeps()
        val recordsConfig = if (withRecords) recordsPage(withdrawals) else null
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale),
                LocalCouliAnnouncer provides announcements.announcer,
                LocalAccountDeps provides account,
            ) {
                CouliTheme(darkTheme = dark) {
                    palette.Capture()
                    Column(Modifier.fillMaxSize().background(CouliColors.backgroundCanvas)) {
                        if (recordsConfig != null) {
                            Box(
                                Modifier.fillMaxWidth().height(RECORDS_HEIGHT.dp).testTag(RECORDS_SIDE),
                            ) { WithdrawRecordsScreen(recordsConfig) }
                        }
                        Row(Modifier.fillMaxWidth().height(RiskVisualTestHost.STATUS_BAR.dp)) {}
                        RiskNoticeScreen(RiskNoticeConfig(deps = deps, onBack = {}))
                    }
                }
            }
        }
        idle()
        settle("账号状态已显示") { has(MeTags.RISK_PRIMARY) || has(MeTags.RISK_ERROR) }
    }

    private fun prepareDeps() {
        val clock = { now }
        deps =
            MeDeps(
                api,
                env,
                MeGates(service, clock = clock),
                AppMeTexts,
                MeLocalStore(context).also { it.clearAll() },
                clock = clock,
                newKey = { "fu-risk-wdr-key" },
                service = service,
            )
        accountEnv.accountIdentityFlow.value = accountEnv.accountIdentityFlow.value.copy(me = base.identity())
        val stepUp =
            StepUpDeps(
                api = api,
                env = accountEnv,
                smsGate = WalletRateGate(service, clock),
                verifyGate = WalletRateGate(service, clock),
                sms = SmsDeadlines(),
                tokens = StepUpTokenCache(),
                clock = clock,
                service = service,
                texts = AppAccountTexts,
            )
        account =
            AccountDeps(
                me = deps,
                api = RiskVisualAccountApi(api),
                env = accountEnv,
                stepUp = stepUp,
                shared = AccountShared(service, clock) { _, _ -> true },
                texts = AppAccountTexts,
                walletRead = WalletRateGate(service, clock),
                deletionRead = WalletRateGate(service, clock),
                clock = clock,
                service = service,
            )
    }

    /** 真实提现记录页（照 WithdrawRecordsVisualTestHost.launch 的依赖）：同一批提现单、同一份注入配置。 */
    private fun recordsPage(withdrawals: List<JsonObject>): WalletPageConfig {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
        context
            .getSharedPreferences("couli_amount_mask", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        records.answer = WithdrawRecordsVisualFixtures.ok(FuRiskWdr.list(withdrawals))
        val deps =
            WalletDeps(
                records,
                recordsEnv,
                WalletGates(service) { WithdrawRecordsVisualFixtures.now },
                StepUpTokenCache(),
                null,
                AmountMaskStore(context),
                null,
                null,
                texts = WalletDraftTexts,
                clock = { WithdrawRecordsVisualFixtures.now },
                service = service,
            )
        return WalletPageConfig(deps, onBack = {})
    }

    /** 记录页上某一单（record_{id}）之下文字恰为 [text] 的未合并节点数。 */
    fun recordTextCount(
        id: String,
        text: String,
    ): Int =
        rule
            .onAllNodes(
                textMatcher(
                    text,
                ) and hasAnyAncestor(hasTestTag(WalletTags.RECORD_PREFIX + id)) and hasAnyAncestor(hasTestTag(RECORDS_SIDE)),
                useUnmergedTree = true,
            ).fetchSemanticsNodes()
            .size

    /** 推进读取闸门的时钟并让禁用期计时器跑完（WalletRateGate 按 delay 到期后放开 blocked）。 */
    fun advance(millis: Long) {
        now += millis
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis))
        idle()
    }

    fun node(tag: String): SemanticsNodeInteraction = rule.onNodeWithTag(tag, useUnmergedTree = true)

    fun has(tag: String): Boolean = rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    fun tap(tag: String) {
        val node = node(tag)
        if (node.insideScroll()) node.performScrollTo()
        node.performTouchInput { click(center) }
        idle()
    }

    fun idle() {
        repeat(IDLE_ROUNDS) {
            shadowOf(Looper.getMainLooper()).idle()
            rule.waitForIdle()
        }
    }

    fun settle(
        what: String,
        condition: () -> Boolean,
    ) {
        repeat(SETTLE_ROUNDS) {
            idle()
            if (condition()) return
            Thread.sleep(SETTLE_SLEEP_MS)
        }
        error("等待超时：$what")
    }

    override fun close() {
        rule.runOnUiThread { service.cancel() }
        TimeZone.setDefault(oldZone)
    }

    companion object {
        const val RECORDS_SIDE = "fu_risk_wdr_records_side"
        const val RECORDS_HEIGHT = 420
        private const val IDLE_ROUNDS = 4
        private const val SETTLE_ROUNDS = 200
        private const val SETTLE_SLEEP_MS = 10L
    }
}
