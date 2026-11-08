package com.couli.core.designsystem

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * FU-X-CTA-KEY-DRIFT 测试 v2（Codex 评审 r1 S1）：默认诊断（不传 onCtaFallback）在一个进程里遇到很多不同的未知键时，每个新键第一次出现都要
 * 进系统日志——R3「每次映射遇缺键记一条」，同键去重可以，新键的首次诊断不能丢。连续映射 70 个从没出现过的未知键，逐个查 ShadowLog（任意 tag / 级别）。
 * 去重缓存是进程内的：本类的键带专用前缀、三位定长编号（互不为子串）；别的用例先记过多少个键都不影响判断——缓存若被别的键先占满，
 * 「满了就不再记」的写法会一个都不记，照样红。同一个键重复映射记几条不判。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class CtaKeyDiagnosticCapTest {
    @Test
    fun everyNewUnknownKeyIsLoggedEvenAfterMoreThan64DistinctKeys() {
        ShadowLog.clear()
        val keys = (1..KEYS).map { "btn.buy.cap_probe_%03d_".format(it) }
        keys.forEach { key ->
            val mapped = ProductCardMapper.map(CtaKeyVectors.card(key), CtaKeyVectors.texts("contract"), nowMillis = CtaKeyVectors.NOW)
            assertEquals("$key 照常回落「购买」", "购买", mapped.data.buyBase)
        }
        val lines = ShadowLog.getLogs().map { "${it.tag} ${it.msg}" }
        val missing = keys.filter { key -> lines.none { it.contains(key) } }
        assertEquals("每个新键都要有一条默认诊断（第 65 个之后的新键也要有），漏记：$missing", emptyList<String>(), missing)
    }

    private companion object {
        /** 超过评审指出的 64 个上限（S1）。 */
        const val KEYS = 70
    }
}
