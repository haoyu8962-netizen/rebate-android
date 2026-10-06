package com.couli.core.designsystem

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale
import kotlin.math.abs

/**
 * 金额与时间格式化（纯函数；三端同名）。金额只用整数分运算（BR-TEXT-10），时间固定 Asia/Shanghai、24 小时制（BR-TEXT-11）。
 * 期望输出以 specs/format-vectors.json 为准。
 */
object CouliFormat {
    private const val FEN_PER_YUAN = 100L
    private const val TEN = 10L
    private val SHANGHAI: ZoneId = ZoneId.of("Asia/Shanghai")
    private val MONTH_DAY = DateTimeFormatter.ofPattern("MM-dd", Locale.ROOT)
    private val FULL_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT)
    private val HOUR_MINUTE = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)
    private val MONTH_DAY_MINUTE = DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.ROOT)
    private val FULL_MINUTE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT)

    /** App 写法：可选负号 + ¥ + 元，最多两位小数，去末尾 0 与多余小数点，无千分位。 */
    fun money(fen: Long): String {
        val sign = if (fen < 0) PriceTerms.MINUS else ""
        return sign + PriceTerms.YUAN_SIGN + unsignedYuan(fen)
    }

    /** 流水写法：正数加「+」，负数「-」，零按 [money]。 */
    fun moneySigned(fen: Long): String = if (fen > 0) PriceTerms.PLUS + money(fen) else money(fen)

    /**
     * 返利区间：min < max「¥a–¥b」；min = max > 0 单值；min = max = 0 返回 null（「暂无返利」由 RebateTag 决定是否显示）。
     * 规则空白（rule-gaps.md）：min > max、负数区间 08 没有规定；保守处理为 null（不显示任何金额）。
     */
    fun rebateRange(
        minFen: Long,
        maxFen: Long,
    ): String? =
        when {
            minFen < 0 || maxFen < 0 -> null
            minFen < maxFen -> money(minFen) + PriceTerms.RANGE_DASH + money(maxFen)
            minFen == maxFen && minFen > 0 -> money(minFen)
            else -> null
        }

    /**
     * 列表里的过去时间：同一天「今天 HH:mm」，前一天「昨天 HH:mm」，同一年「MM-DD」，其他「YYYY-MM-DD」。
     * 规则空白：传入未来时刻 08 没有规定；保守按同一套日期比较（不会出现「明天」）。
     */
    fun timeListPast(
        epochMillis: Long,
        nowEpochMillis: Long,
    ): String {
        val time = shanghai(epochMillis)
        val today = shanghai(nowEpochMillis).toLocalDate()
        val day = time.toLocalDate()
        return when {
            day == today -> PriceTerms.TODAY + " " + time.format(HOUR_MINUTE)
            day == today.minusDays(1) -> PriceTerms.YESTERDAY + " " + time.format(HOUR_MINUTE)
            day.year == today.year -> time.format(MONTH_DAY)
            else -> time.format(FULL_DATE)
        }
    }

    /** 未来时间：同年「MM-DD HH:mm」，跨年「YYYY-MM-DD HH:mm」，不用「明天」等相对词。 */
    fun timeListFuture(
        epochMillis: Long,
        nowEpochMillis: Long,
    ): String {
        val time = shanghai(epochMillis)
        return if (time.year == shanghai(nowEpochMillis).year) time.format(MONTH_DAY_MINUTE) else time.format(FULL_MINUTE)
    }

    /**
     * 纯日期「YYYY-MM-DD」：与今天（上海）同年「MM-DD」，否则原样「YYYY-MM-DD」，不用相对词。
     * 规则空白：非法日期 08 没有规定；保守原样返回输入，不抛错、不猜。
     */
    fun dateOnly(
        isoDate: String,
        nowEpochMillis: Long,
    ): String {
        val date =
            runCatching { LocalDate.parse(isoDate, FULL_DATE) }
                .recover { if (it is DateTimeParseException) null else throw it }
                .getOrNull() ?: return isoDate
        return if (date.year == shanghai(nowEpochMillis).year) date.format(MONTH_DAY) else date.format(FULL_DATE)
    }

    /** 精确到分钟「YYYY-MM-DD HH:mm」（订单时间线、提现详情）。 */
    fun timeMinute(epochMillis: Long): String = shanghai(epochMillis).format(FULL_MINUTE)

    /** 读屏用的金额读法：「19.9 元」「负 1.5 元」（adaptation-a11y-spec §9）。 */
    fun moneySpoken(fen: Long): String {
        val sign = if (fen < 0) PriceTerms.SPOKEN_NEGATIVE + " " else ""
        return sign + unsignedYuan(fen) + " " + PriceTerms.SPOKEN_YUAN
    }

    /** 不带符号的元字符串：yuan = |fen| div 100，cent = |fen| mod 100（逐项取绝对值，Long.MIN_VALUE 也不溢出）。 */
    private fun unsignedYuan(fen: Long): String {
        val yuan = abs(fen / FEN_PER_YUAN)
        val cent = abs(fen % FEN_PER_YUAN)
        return when {
            cent == 0L -> yuan.toString()
            cent % TEN == 0L -> "$yuan.${cent / TEN}"
            else -> "$yuan." + cent.toString().padStart(2, '0')
        }
    }

    private fun shanghai(epochMillis: Long): ZonedDateTime = Instant.ofEpochMilli(epochMillis).atZone(SHANGHAI)
}
