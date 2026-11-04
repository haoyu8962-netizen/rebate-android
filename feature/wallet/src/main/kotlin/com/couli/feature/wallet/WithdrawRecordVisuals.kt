package com.couli.feature.wallet

import androidx.compose.ui.graphics.vector.ImageVector
import com.couli.core.designsystem.CouliFormat
import com.couli.core.designsystem.CouliIcons
import com.couli.core.designsystem.CouliTagTone
import com.couli.core.designsystem.PriceTerms
import com.couli.core.wallet.WalletDraftTexts
import com.couli.core.wallet.WalletTexts
import com.couli.core.wallet.Withdrawal
import com.couli.core.wallet.WithdrawalDisplay
import com.couli.core.wallet.WithdrawalText
import java.time.Instant
import java.time.ZoneId

/*
 * 提现记录视觉（APP-09v-b，W18–W35）的页面层纯函数：只决定「怎么摆」，不改数据、分页、定位与 WithdrawalText 的结论。
 */

/** 状态胶囊色调（W21）：审核中 / 已审核 / 打款中 / 未知 → 中性；已到账 → 成功；未通过 / 打款未成功 → 警告。 */
internal fun withdrawalStatusTone(status: String): CouliTagTone =
    when (status) {
        "PAID_API", "PAID_MANUAL" -> CouliTagTone.SUCCESS
        "REJECTED", "FAILED" -> CouliTagTone.WARNING
        else -> CouliTagTone.NEUTRAL
    }

/** 状态胶囊图标（W21）：状态不只靠颜色——中性钟表、成功对勾、警告三角。 */
internal fun withdrawalStatusIcon(status: String): ImageVector =
    when (withdrawalStatusTone(status)) {
        CouliTagTone.SUCCESS -> CouliIcons.check
        CouliTagTone.WARNING -> CouliIcons.alert
        else -> CouliIcons.clock
    }

/** 状态块里一句话的层级（W24）：主句 17 主文字；同一行分号后的半句 14 次文字；其余附句（时效、冻结、代扣个税）12 次文字。 */
internal enum class RecordLineLevel {
    LEAD,
    FOLLOW,
    NOTE,
}

/**
 * [WithdrawalText.render] 的结果在状态块里的分级排版（W24）：只在 `；` 处切开展示，文本一字不改；第一行切出的首段为主句，
 * 其后各段为半句，第二行起为附句。
 */
internal fun recordStatusLines(
    lines: List<String>,
    join: String = defaultJoin(),
): List<Pair<String, RecordLineLevel>> =
    lines.flatMapIndexed { index, line ->
        (if (join.isEmpty()) listOf(line) else line.split(join)).filter { it.isNotEmpty() }.mapIndexed { part, text ->
            val level =
                when {
                    index > 0 -> RecordLineLevel.NOTE
                    part == 0 -> RecordLineLevel.LEAD
                    else -> RecordLineLevel.FOLLOW
                }
            text to level
        }
    }

/**
 * 记录行实际画出来的副文案（W22 / W24，G-W08）：收起只取第一项（保留其内部标点，含分号）；展开按 [recordStatusLines] 拆开。
 * 完整内容另进该行读屏描述；不修改输入、不补兜底句。
 */
@JvmOverloads
internal fun recordVisibleSubLines(
    lines: List<String>,
    expanded: Boolean,
    join: String = defaultJoin(),
): List<String> = if (expanded) recordStatusLines(lines, join).map { it.first } else lines.take(1)

/** 连接符取字典（`withdrawal.draft.join`，与 [WithdrawalText.render] 拼接时同一个键）；页面传当前字典的值。 */
private fun defaultJoin(): String = WalletDraftTexts.text(JOIN_KEY)

/** 列表里的一段（一个 Lazy 项）：定位置顶区单独一段；其余按原顺序把相邻同月的记录合成一段（W20）。 */
internal data class RecordSection(
    val key: String,
    val year: Int,
    val month: Int,
    /** 月份标题：定位单之后紧跟的同月一段不再重复标题。 */
    val showMonth: Boolean,
    val items: List<Withdrawal>,
)

/**
 * 分段（W20，G-W08）：列表顺序沿用控制器给的 [items]（定位单已在最前），这里只切段、不重排——定位单（[focusId] 且排在第一）
 * 单独成段；余下记录按原序把「相邻且同月」的合成一段，不跨段归组（九月的其他单不会被提到十月之前）。
 */
internal fun recordSections(
    items: List<Withdrawal>,
    focusId: String?,
): List<RecordSection> {
    val pinned = items.firstOrNull()?.takeIf { focusId != null && it.withdrawalId == focusId }
    val out = mutableListOf<RecordSection>()
    pinned?.let {
        val (year, month) = monthOf(it.createdAtMillis)
        out += RecordSection(FOCUS_KEY_PREFIX + it.withdrawalId, year, month, true, listOf(it))
    }
    var run = mutableListOf<Withdrawal>()
    var runMonth: Pair<Int, Int>? = null

    val seen = HashMap<Pair<Int, Int>, Int>()

    fun close() {
        val month = runMonth ?: return
        val continuesPinned = out.size == 1 && pinned != null && monthOf(pinned.createdAtMillis) == month
        // 月段 key 只由年月（及同月再次出现的序号）决定，不随段首记录变化（评审 r1 S2-1）
        val occurrence = seen.merge(month, 1, Int::plus) ?: 1
        val key = MONTH_KEY_PREFIX + "${month.first}_${month.second}" + if (occurrence > 1) "_$occurrence" else ""
        out += RecordSection(key, month.first, month.second, !continuesPinned, run)
        run = mutableListOf()
    }
    (if (pinned != null) items.drop(1) else items).forEach { item ->
        val month = monthOf(item.createdAtMillis)
        if (month != runMonth) close()
        runMonth = month
        run += item
    }
    close()
    return out
}

/** 一段之内再按展开与否切块：相邻收起行共用一张月卡，展开项单独成卡（G-W08）。 */
internal fun recordBlocks(
    items: List<Withdrawal>,
    expanded: Set<String>,
): List<List<Withdrawal>> {
    val out = mutableListOf<List<Withdrawal>>()
    var rows = mutableListOf<Withdrawal>()
    items.forEach { item ->
        if (item.withdrawalId in expanded) {
            if (rows.isNotEmpty()) out += rows
            rows = mutableListOf()
            out += listOf(item)
        } else {
            rows += item
        }
    }
    if (rows.isNotEmpty()) out += rows
    return out
}

/** 渲染一张记录要的共用上下文（回调只捕获渲染时的 [RecordsSnapshot.version]，共性第 63 条）。 */
internal class RecordRenderContext(
    val controller: WithdrawRecordsController,
    val state: RecordsSnapshot,
    val texts: WalletTexts,
    val slaText: String?,
    val now: Long,
    /** 记录级位置（锚点与翻页，评审 r1 S1 / S2-1）。 */
    val keeper: RecordListKeeper,
)

/** 一张记录的展示值（与改版前同一套取值：金额取列表单，副文案与明细取详情或列表单）。 */
internal class RecordRowView(
    val item: Withdrawal,
    ctx: RecordRenderContext,
) {
    private val texts = ctx.texts
    val expanded: Boolean = item.withdrawalId in ctx.state.expanded
    val shown: Withdrawal = ctx.state.details[item.withdrawalId] ?: item
    val display: WithdrawalDisplay = WithdrawalText.of(shown, ctx.slaText, texts)
    val lines: List<String> = WithdrawalText.render(display, texts)
    val amount: String = CouliFormat.money(item.amountFen)
    val title: String = texts.format("withdraw_records.item", mapOf("amount" to amount))
    val status: String = texts.text(display.titleKey)
    val channel: String =
        texts.format(
            "withdraw_records.channel_line",
            mapOf(
                "date" to CouliFormat.timeListPast(item.createdAtMillis, ctx.now),
                "account" to "${item.payoutChannelName} ${item.maskedAccount}",
            ),
        )
    val toggleLabel: String = texts.text(if (expanded) "withdraw_records.collapse" else "withdraw_records.expand")

    /** 整行读屏描述：标题、状态、时间与账号、完整副文案（收起时只画首句，读屏仍读全文，G-W08）。 */
    val description: String = listOf(title, status, spokenMask(texts, channel)).plus(lines).joinToString(PriceTerms.SPOKEN_PAUSE)

    /**
     * 展开卡头读屏描述（评审 r1 S2-2）：只读金额、状态、时间与账号；副文案由其下状态块逐句读一次，卡头不再重复。
     * 收起动作由卡头的点击名 / 状态描述给出。
     */
    val headerDescription: String = listOf(title, status, spokenMask(texts, channel)).joinToString(PriceTerms.SPOKEN_PAUSE)

    /** 渲染时的快照版本：回调只捕获它（共性第 63 条）。 */
    val version: Long = ctx.state.version

    /** 客服行说明键：认识的通道且审核中 / 已审核 / 打款中 / 打款未成功 / 未通过才有（未通过见 F-AND-82，与 iOS / 画板同）；其余 null。 */
    val serviceHintKey: String? =
        when {
            !display.knownChannel -> null
            WithdrawalText.isReviewing(item.status) -> "withdraw.irrevocable"
            item.status == "PAYING" || item.status == "FAILED" || item.status == "REJECTED" -> "withdraw_detail.doubt"
            else -> null
        }
}

private val SHANGHAI: ZoneId = ZoneId.of("Asia/Shanghai")

/** created_at 在 +08:00 下的（年, 月）。 */
internal fun monthOf(millis: Long): Pair<Int, Int> = Instant.ofEpochMilli(millis).atZone(SHANGHAI).let { it.year to it.monthValue }

/** 原因片段与退回片段的连接符键；只在展示时切开，不改文本。 */
internal const val JOIN_KEY = "withdrawal.draft.join"
private const val FOCUS_KEY_PREFIX = "focus_"
private const val MONTH_KEY_PREFIX = "month_"
