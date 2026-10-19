package com.couli.core.clipboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class CandidateExtractor0724Test {
    @Test
    fun filterBoundariesAreInclusiveAndNumbersAndLettersAreSeparateClasses() {
        val rules = ClipboardRules.defaults

        // 给 productRejection 一个已识别候选，独立检验格式门槛，避免“没有链接”掩盖错误过滤。
        fun rejection(text: String) = ClipboardClassifier.productRejection(text, listOf("candidate"), rules)
        assertEquals("too_short", rejection("中".repeat(9)))
        assertNull(rejection("中".repeat(10)))
        assertNull(rejection("😀".repeat(500)))
        assertEquals("too_long", rejection("中".repeat(501)))
        for (unit in listOf("7", "a")) {
            assertEquals("pure_alnum", rejection(unit.repeat(20)))
            assertNull(rejection(unit.repeat(21)))
        }
        assertNull("数字字母混合串不属于纯数字或纯字母", rejection("a7".repeat(10)))
        assertEquals(
            "excluded_word",
            ClipboardClassifier.productRejection("商品请勿识别这段文本", listOf("candidate"), rules.copy(excludedWords = listOf("请勿识别"))),
        )
        assertNull(ClipboardClassifier.productRejection("商品允许识别这段文本", listOf("candidate"), rules.copy(excludedWords = listOf("请勿识别"))))
    }

    @Test
    fun extractsAllSupportedCandidatesInOrderAndMinimizesTheTitle() {
        val tb = "https://e.tb.cn/h.AbCdEf?tk=Xy12"
        val jd = "https://u.jd.com/AbC123"
        val raw = "私密聊天前缀 $tb $jd 「耳机」 私密尾巴 https://private.example.test/key"
        val extracted = CandidateExtractor.extract(raw)
        assertEquals(listOf(tb, jd), extracted.candidates.map { it.raw })
        assertEquals("$tb\n$jd\n耳机", extracted.uploadText)
        assertFalse(extracted.toString().contains("私密"))
        assertFalse(extracted.toString().contains("private.example.test"))
        val dangerousTitle = CandidateExtractor.extract("$tb 「账号 https://private.example.test/token」")
        assertEquals(tb, dangerousTitle.uploadText)
    }

    @Test
    fun filteredTextAndOffPlatformsCannotProduceUploadCandidates() {
        val tb = "https://e.tb.cn/h.AbCdEf?tk=Xy12"
        val jd = "https://u.jd.com/AbC123"
        val rules = ClipboardRules.defaults.copy(excludedWords = listOf("禁止识别"))
        assertEquals(emptyList<ClipboardCandidate>(), CandidateExtractor.extract("禁止识别 $tb", rules).candidates)
        assertEquals(listOf(tb), CandidateExtractor.extract("允许识别 $tb", rules).candidates.map { it.raw })
        val onlyJd = rules.tightened(ClipboardLimits(platforms = listOf("jd")))
        assertEquals(listOf(jd), CandidateExtractor.extract("$tb $jd", onlyJd).candidates.map { it.raw })
        for (raw in listOf("普通聊天没有任何商品链接", "12345678901234567890", "abcdefghijklmnopqrst", "中".repeat(501) + tb)) {
            assertEquals(raw, emptyList<ClipboardCandidate>(), CandidateExtractor.extract(raw).candidates)
        }
    }
}
