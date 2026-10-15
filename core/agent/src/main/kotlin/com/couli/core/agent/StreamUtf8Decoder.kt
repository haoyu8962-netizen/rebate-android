package com.couli.core.agent

/**
 * APP-10b §2 第 2 条（F-X-04，G20、G21）：增量 UTF-8 解码。流式响应的字节只在这里解码一次（[AgentSse.feedBytes] 的完整行）。
 *
 * 规则按 WHATWG Encoding「UTF-8 decoder」：跨块的多字节字符留到下一块；非法序列替换为 U+FFFD 后继续（最大有效子串替换：遇到不合的后续字节
 * 先出 U+FFFD 再把这个字节当新开头重新处理）；结束时还有未完成的序列 → 一个 U+FFFD。[stripBom] 时流的第一个码点是 U+FEFF 就丢弃（G21）。
 * 不用 JDK CharsetDecoder：它对非法序列的分组（一次替换几个字节）没有与 WHATWG 一致的保证，三端（鸿蒙 StreamUtf8Decoder.ets 同一写法）要逐字节可核对。
 * 字节上限一律在解码前按原始字节计（[AgentSse]），不受替换影响。只在调用方线程使用。
 */
class StreamUtf8Decoder(
    private val stripBom: Boolean = true,
) {
    private var needed = 0
    private var seen = 0
    private var codePoint = 0
    private var lower = CONT_MIN
    private var upper = CONT_MAX

    /** 还没有输出过任何码点（BOM 只在流的第一个码点处丢弃）。 */
    private var atStart = true

    /** 本流出现过的非法序列次数（只给本机日志用次数，不记内容，G20）。 */
    var invalidSequences: Int = 0
        private set

    /** 尚未凑成字符、留在解码器里的字节数（未完成的多字节序列）。 */
    val bufferedByteCount: Int get() = if (needed == 0) 0 else seen + 1

    /** 增量喂入一块原始字节，返回这一块里已完整的文字（未完成的序列留到下一块）。 */
    fun feedBytes(chunk: ByteArray): String = decode(chunk, 0, chunk.size)

    /** 解码 [bytes] 的 [from, to) 段。 */
    @Suppress("CyclomaticComplexMethod", "NestedBlockDepth", "LoopWithTooManyJumpStatements") // WHATWG 解码器逐字节的状态机
    fun decode(
        bytes: ByteArray,
        from: Int,
        to: Int,
    ): String {
        val out = StringBuilder(to - from)
        var i = from
        while (i < to) {
            val b = bytes[i].toInt() and BYTE_MASK
            if (needed == 0) {
                i += 1
                when (b) {
                    in 0..ASCII_MAX -> {
                        emit(out, b)
                    }

                    in LEAD2_MIN..LEAD2_MAX -> {
                        needed = 1
                        codePoint = b and LOW5
                    }

                    in LEAD3_MIN..LEAD3_MAX -> {
                        if (b == LEAD3_MIN) lower = LEAD3_E0_LOWER
                        if (b == LEAD3_ED) upper = LEAD3_ED_UPPER
                        needed = 2
                        codePoint = b and LOW4
                    }

                    in LEAD4_MIN..LEAD4_MAX -> {
                        if (b == LEAD4_MIN) lower = LEAD4_F0_LOWER
                        if (b == LEAD4_MAX) upper = LEAD4_F4_UPPER
                        needed = CONT_FOR_FOUR
                        codePoint = b and LOW3
                    }

                    else -> {
                        invalid(out)
                    }
                }
                continue
            }
            if (b < lower || b > upper) {
                // 不合的后续字节：先替换已收的残缺序列，再把这个字节当新的开头重新处理（不前进 i）
                reset()
                invalid(out)
                continue
            }
            i += 1
            lower = CONT_MIN
            upper = CONT_MAX
            codePoint = (codePoint shl CONT_BITS) or (b and LOW6)
            seen += 1
            if (seen == needed) {
                val cp = codePoint
                reset()
                emit(out, cp)
            }
        }
        return out.toString()
    }

    /** 行或分段结束：未完成的序列按一个 U+FFFD 输出（不重置 BOM 状态）。 */
    fun flush(): String {
        if (needed == 0) return ""
        reset()
        val out = StringBuilder()
        invalid(out)
        return out.toString()
    }

    /** 流结束：同 [flush]，并回到初始状态。 */
    fun finish(): String {
        val tail = flush()
        atStart = true
        return tail
    }

    private fun reset() {
        needed = 0
        seen = 0
        codePoint = 0
        lower = CONT_MIN
        upper = CONT_MAX
    }

    private fun invalid(out: StringBuilder) {
        invalidSequences += 1
        emit(out, REPLACEMENT)
    }

    private fun emit(
        out: StringBuilder,
        cp: Int,
    ) {
        if (atStart) {
            atStart = false
            if (stripBom && cp == BOM) return
        }
        out.appendCodePoint(cp)
    }

    companion object {
        const val REPLACEMENT = 0xFFFD
        const val BOM = 0xFEFF
        private const val BYTE_MASK = 0xFF
        private const val ASCII_MAX = 0x7F
        private const val LEAD2_MIN = 0xC2
        private const val LEAD2_MAX = 0xDF
        private const val LEAD3_MIN = 0xE0
        private const val LEAD3_ED = 0xED
        private const val LEAD3_MAX = 0xEF
        private const val LEAD4_MIN = 0xF0
        private const val LEAD4_MAX = 0xF4
        private const val LEAD3_E0_LOWER = 0xA0
        private const val LEAD3_ED_UPPER = 0x9F
        private const val LEAD4_F0_LOWER = 0x90
        private const val LEAD4_F4_UPPER = 0x8F
        private const val CONT_MIN = 0x80
        private const val CONT_MAX = 0xBF
        private const val CONT_BITS = 6

        /** 四字节序列还要的后续字节数。 */
        private const val CONT_FOR_FOUR = 3
        private const val LOW3 = 0x07
        private const val LOW4 = 0x0F
        private const val LOW5 = 0x1F
        private const val LOW6 = 0x3F

        /** 一段完整字节一次解码。 */
        fun decodeAll(
            bytes: ByteArray,
            stripBom: Boolean = true,
        ): String {
            val d = StreamUtf8Decoder(stripBom)
            return d.feedBytes(bytes) + d.finish()
        }
    }
}
