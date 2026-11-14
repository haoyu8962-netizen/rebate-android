package com.couli.app.numwrap

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FU-AND-NUM-WRAP 最简源头守卫（D10-1456 ①、D10-1458）：扫 `core/`（除 generated）、`feature/`、`app` 的 `src/main` Kotlin 源码，三条规则 + 一条兜底：
 * - [NumWrapSourceGuard.DIRECT]（D10-1458 b）：数字串格式化入口（[NumWrapSourceGuard.ENTRY_CALLS]、带数字占位的文案键
 *   [NumWrapSourceGuard.TEMPLATE_KEYS]）**直接**出现在裸文字组件（Text / BasicText / ClickableText）的 text 实参里即红；
 *   入口函数在去掉注释与字符串内容的代码上匹配，文案键在只去注释的文本上按字面量匹配（注释里的字样都不算，复核 S2）。
 *   只抓这一跳：先存变量、经别的函数或 Triple / Pair 再画的不抓（留给 FU-AND-NUM-WRAP-2）。
 * - [NumWrapSourceGuard.ROW]（D10-1458 c，r5 S1）：同一个 `Row` 里同时调用金额绘制件（[NumWrapSourceGuard.AMOUNT_COMPONENTS]）与固定宽
 *   尾随控件（[NumWrapSourceGuard.FIXED_TRAILING]）即红，必须改用让行容器；让行容器（[NumWrapSourceGuard.YIELDING]）范围内的固定宽控件不计，
 *   容器里的金额件仍与外层兄弟控件一起判（复核 S1-1）。
 * - [NumWrapSourceGuard.SINGLE_LINE]（r4 S1 的最接近规则）：同时写了 `softWrap = false` 与 `maxLines = 1` 的裸 Text 必须处在
 *   WholeNumberLayout / WholeNumberText 的调用范围内（否则放不下即被裁、没有横滑兜底）。r4 那条不是「格式化入口直接交给裸 Text」能抓到的
 *   （AmountText 里画的是参数 text），所以单列这一条。
 * 例外只走 [NumWrapSourceGuard.EXEMPTIONS]（每条写文件、匹配片段、理由），且每条豁免必须仍命中现行源码（过期即红）。
 * 自证：下面的 `r4…` / `r5…` 用例把修复前的原文（取自 bbde9ea 与实现交回 M22 / M23 变异）喂给检查函数必须红、修复后的原文必须绿。
 */
class NumWrapYieldGuardTest {
    private val root: File by lazy {
        val start = File(System.getProperty("user.dir") ?: ".").canonicalFile
        generateSequence(start) { it.parentFile }.first { File(it, "contract.lock").isFile }
    }

    private val findings: List<Finding> by lazy { sources().flatMap { (path, text) -> NumWrapSourceGuard.scan(path, text) } }

    private fun sources(): List<Pair<String, String>> {
        val modules =
            File(root, "core").listFiles().orEmpty().filter { it.name != "generated" } +
                File(root, "feature").listFiles().orEmpty() +
                File(root, "app")
        return modules
            .map { File(it, "src/main") }
            .filter(File::isDirectory)
            .flatMap { dir -> dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
            .sortedBy { it.path }
            .map { it.relativeTo(root).invariantSeparatorsPath to it.readText() }
    }

    @Test fun scanReachesTheProductSources() {
        val paths = sources().map { it.first }
        assertTrue("扫描范围过小：${paths.size}", paths.size >= MIN_FILES)
        KEY_FILES.forEach { key -> assertTrue("扫描范围缺 $key", paths.any { it.endsWith(key) }) }
    }

    @Test fun currentSourcesHaveOnlyExemptedFindings() {
        val open = findings.filterNot { f -> NumWrapSourceGuard.EXEMPTIONS.any { it.covers(f) } }
        assertTrue("数字串未经不断行组件 / 未让行（改产品或进带理由的豁免清单）：\n" + open.joinToString("\n"), open.isEmpty())
    }

    @Test fun everyExemptionStillMatchesTheSource() {
        val stale = NumWrapSourceGuard.EXEMPTIONS.filter { e -> findings.none { e.covers(it) } }
        assertTrue("豁免已不再命中（删掉或更新）：$stale", stale.isEmpty())
    }

    @Test fun r5GridPriceRowBeforeFixIsRedAndAfterFixIsGreen() {
        assertEquals(listOf(NumWrapSourceGuard.ROW), rules(Samples.R5_GRID_BEFORE))
        assertEquals(emptyList<String>(), rules(Samples.R5_GRID_AFTER))
    }

    @Test fun r5HomeTilePriceRowBeforeFixIsRedAndAfterFixIsGreen() {
        assertEquals(listOf(NumWrapSourceGuard.ROW), rules(Samples.R5_HOME_BEFORE))
        assertEquals(emptyList<String>(), rules(Samples.R5_HOME_AFTER))
    }

    @Test fun r4AmountTextBeforeFixIsRedAndAfterFixIsGreen() {
        assertEquals(listOf(NumWrapSourceGuard.SINGLE_LINE), rules(Samples.R4_AMOUNT_TEXT_BEFORE))
        assertEquals(emptyList<String>(), rules(Samples.R4_AMOUNT_TEXT_AFTER))
    }

    @Test fun directUseOfEveryEntryKindIsRed() {
        Samples.DIRECT_RED.forEach { sample -> assertEquals(sample, listOf(NumWrapSourceGuard.DIRECT), rules(sample)) }
        assertEquals(listOf(NumWrapSourceGuard.ROW), rules(Samples.ROW_ICON_BUTTON_RED))
    }

    @Test fun boundariesStayGreen() {
        Samples.GREEN.forEach { sample -> assertEquals(sample, emptyList<String>(), rules(sample)) }
    }

    @Test fun reviewS1OuterSiblingButtonBesideYieldingContainerIsRed() {
        assertEquals(listOf(NumWrapSourceGuard.ROW), rules(Samples.S1_OUTER_SIBLING_RED))
        assertEquals(emptyList<String>(), rules(Samples.S1_OUTER_SIBLING_FIXED))
    }

    @Test fun reviewS1PayoutAccountEntriesAreRed() {
        Samples.S1_PAYOUT_RED.forEach { sample -> assertEquals(sample, listOf(NumWrapSourceGuard.DIRECT), rules(sample)) }
        Samples.S1_PAYOUT_FIXED.forEach { sample -> assertEquals(sample, emptyList<String>(), rules(sample)) }
    }

    @Test fun reviewS2TemplateKeyOnlyInCommentIsGreen() {
        assertEquals(listOf(NumWrapSourceGuard.DIRECT), rules(Samples.S2_KEY_RED))
        Samples.S2_KEY_IN_COMMENT.forEach { sample -> assertEquals(sample, emptyList<String>(), rules(sample)) }
    }

    private fun rules(sample: String): List<String> = NumWrapSourceGuard.scan("Sample.kt", sample).map { it.rule }

    private companion object {
        const val MIN_FILES = 300
        val KEY_FILES =
            listOf(
                "core/designsystem/src/main/kotlin/com/couli/core/designsystem/ProductCard.kt",
                "core/designsystem/src/main/kotlin/com/couli/core/designsystem/PriceComponents.kt",
                "feature/home/src/main/kotlin/com/couli/feature/home/HomeTiles.kt",
                "feature/orders/src/main/kotlin/com/couli/feature/orders/OrderUi.kt",
                "feature/wallet/src/main/kotlin/com/couli/feature/wallet/WithdrawScreen.kt",
                "core/wallet/src/main/kotlin/com/couli/core/wallet/stepup/StepUpSheet.kt",
            )
    }
}

/** 自证样本：`*_BEFORE` 逐字取自修复前（bbde9ea / 实现交回 R4.3 M22、R5.3 M23 的回退原文），`*_AFTER` 取自现行源码。 */
private object Samples {
    val R5_GRID_BEFORE =
        """
        if (data.price != null) {
            // 返利区不显示（暂无返利）时口径 i 放在价格行右侧，不随返利区一起消失（F-AND-06）
            Row(verticalAlignment = Alignment.CenterVertically) {
                PriceTagMain(content = data.price, size = PriceTagSize.AI, modifier = Modifier.weight(1f, fill = false))
                if (!rebateShown && info != null) {
                    DisclaimerInfoButton(label = data.infoLabel, expanded = false, onToggle = info)
                }
            }
        }
        """.trimIndent()

    val R5_GRID_AFTER =
        """
        if (data.price != null) {
            NumberLine(
                trailing =
                    if (!rebateShown && info != null) {
                        { DisclaimerInfoButton(label = data.infoLabel, expanded = false, onToggle = info) }
                    } else {
                        null
                    },
            ) {
                PriceTagMain(content = data.price, size = PriceTagSize.AI)
            }
        }
        """.trimIndent()

    val R5_HOME_BEFORE =
        """
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 价格读屏已并入整卡一句：这里整块清掉（含 PriceTagMain 自带的读屏句），不重复
                Box(modifier = Modifier.weight(1f, fill = false).clearAndSetSemantics {}) {
                    PriceTagMain(content = price, size = PriceTagSize.AI)
                }
                if (!rebateShown && info != null) {
                    DisclaimerInfoButton(label = data.infoLabel, expanded = false, onToggle = info)
                }
            }
            PriceSubLines(lines = price.subLines(specNote = data.specNote), modifier = Modifier.clearAndSetSemantics {})
        }
        """.trimIndent()

    val R5_HOME_AFTER =
        """
        Column {
            NumberLine(
                trailing =
                    if (!rebateShown && info != null) {
                        { DisclaimerInfoButton(label = data.infoLabel, expanded = false, onToggle = info) }
                    } else {
                        null
                    },
            ) {
                // 价格读屏已并入整卡一句：这里整块清掉（含 PriceTagMain 自带的读屏句），不重复
                Box(modifier = Modifier.clearAndSetSemantics {}) {
                    PriceTagMain(content = price, size = PriceTagSize.AI)
                }
            }
            PriceSubLines(lines = price.subLines(specNote = data.specNote), modifier = Modifier.clearAndSetSemantics {})
        }
        """.trimIndent()

    val R4_AMOUNT_TEXT_BEFORE =
        """
        @Composable
        internal fun AmountText(
            text: String,
            style: TextStyle,
            color: Color,
            modifier: Modifier = Modifier,
        ) {
            val observer = LocalAmountLayoutObserver.current
            Text(
                text = text,
                style = style,
                color = color,
                softWrap = false,
                maxLines = 1,
                modifier = modifier,
                onTextLayout = { observer?.invoke(text, it) },
            )
        }
        """.trimIndent()

    val R4_AMOUNT_TEXT_AFTER =
        """
        @Composable
        internal fun AmountText(
            text: String,
            style: TextStyle,
            color: Color,
            modifier: Modifier = Modifier,
        ) {
            val observer = LocalAmountLayoutObserver.current
            WholeNumberLayout(style = style, color = color, modifier = modifier, wholeLine = true) {
                Text(
                    text = text,
                    style = style,
                    color = color,
                    softWrap = false,
                    maxLines = 1,
                    onTextLayout = { observer?.invoke(text, it) },
                )
            }
        }
        """.trimIndent()

    /** 每类入口各一条「直接交给裸文字」：金额（含字符串模板里）、带金额占位的文案键、订单号、脱敏手机号、脱敏账号、流水号。 */
    val DIRECT_RED =
        listOf(
            "Text(text = CouliFormat.money(display.balanceFen), style = CouliTextStyles.amountDisplay)",
            "Text(\"余额 ${'$'}{CouliFormat.moneySigned(fen)}\", color = CouliColors.textPrimary)",
            "BasicText(text = if (masked) MASK else CouliFormat.rebateRange(min, max).orEmpty())",
            "Text(text = texts.format(\"withdraw.max_line\", mapOf(\"amount\" to shown)), style = CouliTextStyles.caption)",
            "Text(texts.format(\"order_list.order_no\", mapOf(\"no\" to text)), style = CouliTypography.footnote)",
            "Text(text = PhoneMask.mask(phone).orEmpty(), style = CouliTypography.body)",
            "Text(text = PhoneNumber.masked(normalized))",
            "Text(text = item.maskedAccount, color = CouliColors.textSecondary)",
            "Text(text = TradeNoRow.copyText(status, id).orEmpty())",
        )

    val ROW_ICON_BUTTON_RED =
        """
        Row(verticalAlignment = Alignment.CenterVertically) {
            WholeNumberText(text = amount, style = CouliTextStyles.amountTitle, modifier = Modifier.weight(1f, fill = false))
            CouliIconButton(icon = CouliIcons.eye, contentDescription = label, onClick = onToggle)
        }
        """.trimIndent()

    /** 复核 S1-1：金额在带 weight 的 NumberLine 里、固定宽按钮是外层 Row 的兄弟（按钮不会被让行）。 */
    val S1_OUTER_SIBLING_RED =
        """
        Row(verticalAlignment = Alignment.CenterVertically) {
            NumberLine(modifier = Modifier.weight(1f)) {
                PriceTagMain(content = data.price, size = PriceTagSize.AI)
            }
            DisclaimerInfoButton(label = data.infoLabel, expanded = false, onToggle = info)
        }
        """.trimIndent()

    /** 修正写法：按钮交给 NumberLine 的 trailing。 */
    val S1_OUTER_SIBLING_FIXED =
        """
        Row(verticalAlignment = Alignment.CenterVertically) {
            NumberLine(
                modifier = Modifier.weight(1f),
                trailing = { DisclaimerInfoButton(label = data.infoLabel, expanded = false, onToggle = info) },
            ) {
                PriceTagMain(content = data.price, size = PriceTagSize.AI)
            }
        }
        """.trimIndent()

    /** 复核 S1-2：收款账号脱敏入口直接交给裸 Text。 */
    val S1_PAYOUT_RED =
        listOf(
            "Text(text = PayoutPendingSummary.summarize(form).account.orEmpty())",
            "Text(text = summary.account.orEmpty(), style = CouliTypography.body)",
            "Text(texts.format(\"payout.bank_tail\", mapOf(\"bank\" to bank, \"tail\" to tail)))",
            "Text(text = state.payout?.line(texts).orEmpty())",
            "Text(text = PayoutAccountView.view(account).maskedAccount.orEmpty())",
            "Text(text = PayoutAccountController.pendingSummary(pending.summary).second.orEmpty())",
        )

    /** 修正写法：经共用整段数字件；以及同名的 account() 方法调用不算字段。 */
    val S1_PAYOUT_FIXED =
        listOf(
            "WholeNumberText(text = PayoutPendingSummary.summarize(form).account.orEmpty(), style = CouliTypography.body)",
            "Text(text = ports.account().orEmpty())",
        )

    /** 复核 S2：文案键真在实参里要红；只出现在注释里要绿。 */
    const val S2_KEY_RED = "Text(text = texts.format(\"order_list.order_no\", mapOf(\"no\" to no)))"
    val S2_KEY_IN_COMMENT =
        listOf(
            "Text(text = label /* \"order_list.order_no\" */)",
            "Text(\n    text = label, // \"order_list.order_no\"\n)",
        )

    /** 边界（必须绿）：注释与字符串里的字样不算；只看 text 实参（读屏描述不算）；先存变量再画的隔层不抓；让行容器里的组合不算；已套外壳的单行 Text 不算。 */
    val GREEN =
        listOf(
            "// 改前：Text(text = CouliFormat.money(fen)) 会断\nval note = \"Text(CouliFormat.money(fen))\"",
            "Text(text = label, modifier = Modifier.semantics { contentDescription = CouliFormat.money(fen) })",
            "val shown = CouliFormat.money(fen)\nText(text = shown)",
            "WholeNumberText(text = CouliFormat.money(fen), style = CouliTextStyles.amountTitle)",
            "Text(text = texts.text(\"order_list.copy\"), softWrap = false)",
            """
            Row {
                NumberLine(trailing = { DisclaimerInfoButton(label = l, expanded = false, onToggle = t) }) {
                    PriceTagMain(content = price, size = PriceTagSize.AI)
                }
            }
            """.trimIndent(),
            "Row { Icon(icon, contentDescription = null); WholeNumberText(text = label, style = style) }",
        )
}

/** 一条命中：[code] 是整个调用（空白压成单个空格），豁免按片段匹配它。 */
internal data class Finding(
    val rule: String,
    val file: String,
    val line: Int,
    val code: String,
) {
    override fun toString(): String = "[$rule] $file:$line  ${code.take(SHOWN_CODE)}"

    private companion object {
        const val SHOWN_CODE = 160
    }
}

/** 豁免：[rule] 相同、文件路径以 [file] 结尾、调用原文含 [contains] 的命中不算红。 */
internal data class Exemption(
    val rule: String,
    val file: String,
    val contains: String,
    val reason: String,
) {
    fun covers(finding: Finding): Boolean = finding.rule == rule && finding.file.endsWith(file) && contains in finding.code
}

/** 三条规则的常量表与检查函数（只读源码文本，不编译、不做调用图）。 */
internal object NumWrapSourceGuard {
    const val DIRECT = "格式化入口直接交给裸文字"
    const val ROW = "金额件与固定宽尾随控件同在一个 Row"
    const val SINGLE_LINE = "单行裸 Text 无横滑外壳"

    /** 数字串格式化入口（金额、订单号、手机号与脱敏账号、流水号），在去掉注释与字符串内容后的代码上匹配。读屏专用的 moneySpoken 等不列。 */
    val ENTRY_CALLS =
        listOf(
            // 金额：core/designsystem CouliFormat
            Regex("""\bCouliFormat\s*\.\s*(money|moneySigned|rebateRange)\s*\("""),
            // 金额输入框回填：feature/search SearchViewModel.yuanText
            Regex("""\byuanText\s*\("""),
            // 脱敏手机号：core/me PhoneMask.summary / mask；feature/login PhoneNumber.masked
            Regex("""\bPhoneMask\s*\.\s*(summary|mask)\s*\("""),
            Regex("""\bPhoneNumber\s*\.\s*masked\s*\("""),
            // 订单号：core/orders OrderNumberDisplay.of、OrderSummary.orderNo / maskedOrderNo
            Regex("""\bOrderNumberDisplay\s*\.\s*of\s*\("""),
            // 流水号：core/wallet TradeNoRow.copyText、channelOrderId；脱敏收款账号：maskedAccount
            Regex("""\bTradeNoRow\s*\.\s*copyText\s*\("""),
            Regex("""\.\s*(orderNo|maskedOrderNo|maskedAccount|channelOrderId)\b"""),
            // 脱敏收款账号（复核 S1-2）：core/wallet PayoutPendingSummary.summarize（银行卡尾号 / 脱敏手机号 / 邮箱）及其 .account 字段、
            // PayoutAccountView.view、PayoutAccountDisplay.line(texts)（「支付宝 138****5678」「招商银行 尾号 1234」）、
            // feature/wallet PayoutAccountController.pendingSummary（读回本机脱敏摘要）
            Regex("""\bPayoutPendingSummary\s*\.\s*summarize\s*\("""),
            Regex("""\bPayoutAccountView\s*\.\s*view\s*\("""),
            Regex("""\bPayoutAccountController\s*\.\s*pendingSummary\s*\("""),
            Regex("""\.\s*line\s*\(\s*texts\s*\)"""),
            Regex("""\.\s*account\b(?!\s*\()"""),
        )

    /**
     * 带数字占位（金额 / 手机号 / 账号 / 单号）的文案键：取自实现交回的模板源头表 impl-tools/template-sources-r4.txt（62 键），
     * 另补 payout.bank_tail（复核 S1-2，银行卡尾号）；去掉实现已裁定不含数字串的 3 个（trade.price_changed.before / now 只填口径词，settings.nickname.hint 是 1–2 位计数）。
     * 在原文上按 `"键"` 字面量匹配。
     */
    val TEMPLATE_KEYS =
        listOf(
            "bind_phone.merge.account",
            "bind_phone.merge.target",
            "btn.buy.rebate",
            "btn.buy.rebate_suffix",
            "coupon.tag",
            "error.30303.above_max",
            "error.30303.above_method_max",
            "error.30303.below_min",
            "error.30303.not_multiple",
            "error.30416",
            "invite.reward.tip",
            "invite.reward.tip_inviter",
            "ledger.balance_after",
            "login.code.sent_to",
            "order_list.deposit_amount",
            "order_list.order_no",
            "order_status.CREDITED_PART_CLAWED.label",
            "payout.bank_tail",
            "pending_confirm.payout_account.desc",
            "pending_confirm.phone_change.desc",
            "pending_confirm.withdraw.desc",
            "phone_change.done.body",
            "phone_change.done.desc",
            "risk.withdrawal_row",
            "search.filter.price_max_only",
            "search.filter.price_min_only",
            "search.filter.price_range",
            "share.est_promo",
            "step_up.summary.phone_change",
            "trade.rebate_gone.body",
            "trade.rebate_gone.body_changed",
            "trade.rebate_gone.body_plain",
            "wallet.negative",
            "wallet.paused",
            "wallet.received",
            "withdraw.balance_line",
            "withdraw.estimate",
            "withdraw.estimate.net_only",
            "withdraw.max_line",
            "withdraw.method_max_line.wechat",
            "withdraw.spoken.amount",
            "withdraw.spoken.quick",
            "withdraw_detail.net.actual",
            "withdraw_detail.net.expected",
            "withdraw_detail.sub_text.failed_account",
            "withdraw_detail.sub_text.failed_manual",
            "withdraw_detail.sub_text.failed_manual.no_hold",
            "withdraw_detail.sub_text.failed_other",
            "withdraw_detail.sub_text.paid_api",
            "withdraw_detail.sub_text.paid_manual",
            "withdraw_detail.sub_text.rejected",
            "withdraw_detail.sub_text.tax_withheld",
            "withdraw_records.channel_line",
            "withdraw_records.item",
            "withdrawal.draft.channel",
            "withdrawal.draft.frozen",
            "withdrawal.draft.paid",
            "withdrawal.draft.returned",
            "withdrawal.draft.returned.change_account",
            "withdrawal.draft.tax",
        )

    /** 裸文字组件（会在任意字符处折行、或单行被裁）。 */
    val BARE_TEXT = listOf("Text", "BasicText", "ClickableText")

    /** 不断行共用外壳：在它们的调用范围内的单行 Text 有横滑兜底。 */
    val SHELLS = listOf("WholeNumberLayout", "WholeNumberText")

    /** 金额 / 数字串绘制件（core/designsystem 的价格、返利、钱包金额件与共用整段数字件）。 */
    val AMOUNT_COMPONENTS =
        listOf(
            "PriceTagMain",
            "PriceTag",
            "AmountText",
            "RebateTag",
            "RebateAmountSegments",
            "EstNetLine",
            "PriceSubLines",
            "AmountGrid",
            "WholeNumberText",
        )

    /** 固定宽尾随控件：48dp 口径说明按钮、图标按钮、关闭按钮。 */
    val FIXED_TRAILING =
        listOf(
            "DisclaimerInfoButton",
            "CouliIconButton",
            "IconButton",
            "FilledIconButton",
            "FilledTonalIconButton",
            "OutlinedIconButton",
            "IconToggleButton",
            "PopupCloseButton",
        )

    /** 让行容器（及本来就会换行的 FlowRow）：Row 里套着的这些调用，其范围内的固定宽控件不计入外层 Row 的判定（容器里的金额件仍计入）。 */
    val YIELDING = listOf("NumberLine", "TrailingWrapRow", "KeyValueRow", "EndWrapRow", "FlowRow")

    val EXEMPTIONS =
        listOf(
            Exemption(
                SINGLE_LINE,
                "feature/me/src/main/kotlin/com/couli/feature/me/PhoneChangeParts.kt",
                "text = number.toString()",
                "换号步骤圆点里的步骤序号（1–3 单个数字），wrapContentSize(unbounded = true) 不裁切；不是金额 / 号码类数字串",
            ),
        )

    fun scan(
        file: String,
        source: String,
    ): List<Finding> {
        val code = SourceMask(source).run()
        val plain = SourceMask(source, keepStrings = true).run()
        val texts = KotlinCalls.calls(code, BARE_TEXT).filter { it.argsOpen >= 0 }
        return direct(Sources(file, source, code, plain), texts) + rows(file, source, code) + singleLine(file, source, code, texts)
    }

    /** 同一文件的三种文本：[source] 原文、[code] 去注释与字符串内容、[plain] 只去注释（下标一一对应）。 */
    private class Sources(
        val file: String,
        val source: String,
        val code: String,
        val plain: String,
    )

    private fun direct(
        text: Sources,
        texts: List<Call>,
    ): List<Finding> =
        texts
            .filter { call ->
                val arg = textArgument(text.code, call)
                arg != null && entryIn(text.code.substring(arg.first, arg.last + 1), text.plain.substring(arg.first, arg.last + 1))
            }.map { finding(DIRECT, text.file, text.source, it) }

    /** 入口函数在去掉注释与字符串内容的代码上匹配；文案键在只去注释的文本上按 `"键"` 字面量匹配（注释里的键名不算，复核 S2）。 */
    private fun entryIn(
        code: String,
        plain: String,
    ): Boolean = ENTRY_CALLS.any { it.containsMatchIn(code) } || TEMPLATE_KEYS.any { "\"$it\"" in plain }

    /** text 实参：具名 `text =` 优先，否则第一个位置实参。 */
    private fun textArgument(
        code: String,
        call: Call,
    ): IntRange? {
        val args = KotlinCalls.topArgs(code, call)
        val named = args.firstOrNull { NAMED_TEXT.containsMatchIn(code.substring(it.first, it.last + 1)) }
        return named ?: args.firstOrNull { !NAMED_ANY.containsMatchIn(code.substring(it.first, it.last + 1)) }
    }

    private fun rows(
        file: String,
        source: String,
        code: String,
    ): List<Finding> =
        KotlinCalls
            .calls(code, listOf("Row"))
            .filter { row ->
                val body = withoutYieldedControls(code.substring(row.start + "Row".length, row.end))
                AMOUNT_COMPONENTS.any { callIn(body, it) } && FIXED_TRAILING.any { callIn(body, it) }
            }.map { finding(ROW, file, source, it) }

    /**
     * 让行容器只替它自己里面的固定宽控件让行：把容器调用范围内的固定宽控件抹掉，容器里的金额件保留，
     * 仍与外层 Row 里的兄弟控件一起判（复核 S1-1：`Row { NumberLine(weight) { 金额 }; 按钮 }` 要红）。
     */
    private fun withoutYieldedControls(body: String): String {
        val chars = body.toCharArray()
        KotlinCalls
            .calls(body, YIELDING)
            .flatMap { box ->
                KotlinCalls.calls(body.substring(box.start, box.end), FIXED_TRAILING).map { box.start + it.start until box.start + it.end }
            }.forEach { range -> range.forEach { chars[it] = ' ' } }
        return String(chars)
    }

    private fun callIn(
        body: String,
        name: String,
    ): Boolean = Regex("""(?<![A-Za-z0-9_])$name\s*\(""").containsMatchIn(body)

    private fun singleLine(
        file: String,
        source: String,
        code: String,
        texts: List<Call>,
    ): List<Finding> {
        val shells = KotlinCalls.calls(code, SHELLS)
        return texts
            .filter { call ->
                val args = code.substring(call.argsOpen, call.argsClose)
                val wrapped = shells.any { it.start < call.start && call.start < it.end }
                NO_WRAP.containsMatchIn(args) && ONE_LINE.containsMatchIn(args) && !wrapped
            }.map { finding(SINGLE_LINE, file, source, it) }
    }

    private fun finding(
        rule: String,
        file: String,
        source: String,
        call: Call,
    ): Finding {
        val line = source.substring(0, call.start).count { it == '\n' } + 1
        val text = source.substring(call.start, call.end).trim().replace(WHITESPACE, " ")
        return Finding(rule, file, line, text)
    }

    private val NAMED_TEXT = Regex("""^\s*text\s*=(?!=)""")
    private val NAMED_ANY = Regex("""^\s*[A-Za-z_][A-Za-z0-9_]*\s*=(?!=)""")
    private val NO_WRAP = Regex("""\bsoftWrap\s*=\s*false\b""")
    private val ONE_LINE = Regex("""\bmaxLines\s*=\s*1\b""")
    private val WHITESPACE = Regex("""\s+""")
}

/** 一次调用：名字起点 [start]，实参括号 [argsOpen]..[argsClose]（没有括号时为 -1），[end] 是调用（含尾随 lambda）之后的下标。 */
internal data class Call(
    val name: String,
    val start: Int,
    val argsOpen: Int,
    val argsClose: Int,
    val end: Int,
)

/** 在去掉注释与字符串内容的代码上找调用、配括号、切顶层实参。 */
internal object KotlinCalls {
    fun calls(
        code: String,
        names: List<String>,
    ): List<Call> =
        Regex("(?<![A-Za-z0-9_])(" + names.joinToString("|") + ")\\b")
            .findAll(code)
            .filterNot { code.substring(maxOf(0, it.range.first - "fun ".length), it.range.first) == "fun " }
            .mapNotNull { callAt(code, it.groupValues[1], it.range.first, it.range.last + 1) }
            .toList()

    /** [open] 处开括号对应的闭括号下标（三种括号一起计深度；找不到为 -1）。 */
    fun close(
        code: String,
        open: Int,
    ): Int {
        var depth = 0
        var found = -1
        var j = open
        while (j < code.length && found < 0) {
            when (code[j]) {
                '(', '[', '{' -> {
                    depth++
                }

                ')', ']', '}' -> {
                    depth--
                    if (depth == 0) found = j
                }
            }
            j++
        }
        return found
    }

    /** 顶层实参的下标范围（逗号切分，括号内的逗号不算）。 */
    fun topArgs(
        code: String,
        call: Call,
    ): List<IntRange> {
        val out = mutableListOf<IntRange>()
        var depth = 0
        var from = call.argsOpen + 1
        for (j in call.argsOpen + 1 until call.argsClose) {
            when (code[j]) {
                '(', '[', '{' -> {
                    depth++
                }

                ')', ']', '}' -> {
                    depth--
                }

                ',' -> {
                    if (depth == 0) {
                        out.add(from until j)
                        from = j + 1
                    }
                }
            }
        }
        if (code.substring(from, call.argsClose).isNotBlank()) out.add(from until call.argsClose)
        return out
    }

    private fun callAt(
        code: String,
        name: String,
        start: Int,
        nameEnd: Int,
    ): Call? {
        val j = skipSpace(code, nameEnd)
        val argsOpen = if (j < code.length && code[j] == '(') j else -1
        val argsClose = if (argsOpen >= 0) close(code, argsOpen) else -1
        val after = if (argsClose >= 0) skipSpace(code, argsClose + 1) else j
        val lambdaClose = if (after < code.length && code[after] == '{') close(code, after) else -1
        val end = maxOf(argsClose, lambdaClose)
        return if (end < 0) null else Call(name, start, argsOpen, argsClose, end + 1)
    }

    private fun skipSpace(
        code: String,
        from: Int,
    ): Int {
        var j = from
        while (j < code.length && code[j].isWhitespace()) j++
        return j
    }
}

/** 把注释与字符串字面量的内容换成空格（换行与下标不变）；字符串模板 `${…}` 里的代码保留，引号本身保留；[keepStrings] 时只去注释。 */
internal class SourceMask(
    private val source: String,
    /** true：只去注释，字符串内容保留（文案键字面量在这一版上匹配，复核 S2）。 */
    private val keepStrings: Boolean = false,
) {
    private class Mode(
        val string: Boolean,
        val raw: Boolean,
    ) {
        var depth = 0
    }

    private val out = source.toCharArray()
    private val modes = ArrayDeque<Mode>()
    private var i = 0

    fun run(): String {
        modes.addLast(Mode(string = false, raw = false))
        while (i < source.length) {
            val mode = modes.last()
            if (mode.string) stringStep(mode) else codeStep(mode)
        }
        return String(out)
    }

    private fun codeStep(mode: Mode) {
        when {
            source.startsWith("//", i) -> lineComment()
            source.startsWith("/*", i) -> blockComment()
            source.startsWith(TRIPLE, i) -> open(raw = true, width = TRIPLE.length)
            source[i] == '"' -> open(raw = false, width = 1)
            source[i] == '\'' -> charLiteral()
            else -> brace(mode)
        }
    }

    private fun stringStep(mode: Mode) {
        when {
            mode.raw && source.startsWith(TRIPLE, i) -> {
                closeRaw()
            }

            !mode.raw && source[i] == '\\' -> {
                blankStep(2)
            }

            !mode.raw && source[i] == '"' -> {
                closeString()
            }

            source.startsWith(TEMPLATE, i) -> {
                modes.addLast(Mode(string = false, raw = false))
                i += TEMPLATE.length
            }

            else -> {
                blankStep(1)
            }
        }
    }

    private fun open(
        raw: Boolean,
        width: Int,
    ) {
        modes.addLast(Mode(string = true, raw = raw))
        i += width
    }

    private fun closeString() {
        modes.removeLast()
        i++
    }

    private fun closeRaw() {
        var j = i + TRIPLE.length
        while (j < source.length && source[j] == '"') j++
        modes.removeLast()
        i = j
    }

    /** 代码里的花括号：模板 `${` 开的那层在深度 0 遇到 `}` 时结束，回到字符串。 */
    private fun brace(mode: Mode) {
        val closesTemplate = source[i] == '}' && mode.depth == 0 && modes.size > 1
        when {
            closesTemplate -> modes.removeLast()
            source[i] == '{' -> mode.depth++
            source[i] == '}' -> mode.depth--
        }
        i++
    }

    private fun lineComment() {
        val end = source.indexOf('\n', i).let { if (it < 0) source.length else it }
        blank(i, end)
        i = end
    }

    /** Kotlin 块注释可嵌套。 */
    private fun blockComment() {
        var depth = 0
        var j = i
        do {
            when {
                source.startsWith("/*", j) -> {
                    depth++
                    j += 2
                }

                source.startsWith("*/", j) -> {
                    depth--
                    j += 2
                }

                else -> {
                    j++
                }
            }
        } while (depth > 0 && j < source.length)
        blank(i, j)
        i = j
    }

    private fun charLiteral() {
        var j = i + 1
        j += if (j < source.length && source[j] == '\\') 2 else 1
        while (j < source.length && source[j] != '\'') j++
        blank(i + 1, j)
        i = j + 1
    }

    private fun blankStep(width: Int) {
        if (!keepStrings) blank(i, i + width)
        i += width
    }

    private fun blank(
        from: Int,
        to: Int,
    ) {
        for (k in from until minOf(to, source.length)) {
            if (out[k] != '\n') out[k] = ' '
        }
    }

    private companion object {
        const val TRIPLE = "\"\"\""
        const val TEMPLATE = "\${"
    }
}
