@file:OptIn(com.github.takahirom.roborazzi.ExperimentalRoborazziApi::class)

package com.couli.app.me

import android.app.Application
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.feature.me.MeTags
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * FU-AND-RISK-WDR 新截图入口（brief §4.1「新快照」）：src/test/snapshots/risk-v/risk-wdr-*（与既有 risk-v 文件名不冲突）。类名以 RiskVisualSnapshotTest
 * 结尾：随 verify.sh 既有 risk 快照步（--tests "*RiskVisualSnapshotTest"）比对，不改 CI 脚本。
 * J3：既有 RiskNoticeBanned-v2-no-subline-GR08-* 不改名，由实现方重录同名；本类只加四态一页（manual / auto / PAYING / 不认识的通道）浅 / 深 / 200% × w390 /
 * 200% × w320 / 840 与冻结页一张。截图前先核每行副文案 = 同源期望，所以底版在单测步就红。只声明入口，不 record / verify、不生成基准。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h1100dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FuRiskWdrRiskVisualSnapshotTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { FuRiskWdrHost(rule) }

    @After fun tearDown() = host.close()

    @Test fun fourStatesLight() = shot(FuRiskWdr.fourStates, "risk-wdr-sublines-four-states-light")

    @Test fun fourStatesDark() = shot(FuRiskWdr.fourStates, "risk-wdr-sublines-four-states-dark", dark = true)

    @Test
    @Config(qualifiers = "w390dp-h844dp-xhdpi")
    fun fourStatesFont200W390() = shot(FuRiskWdr.fourStates, "risk-wdr-sublines-four-states-font200-w390-light", fontScale = 2f)

    @Test
    @Config(qualifiers = "w320dp-h844dp-xhdpi")
    fun fourStatesFont200W320() = shot(FuRiskWdr.fourStates, "risk-wdr-sublines-four-states-font200-w320-light", fontScale = 2f)

    @Test
    @Config(qualifiers = "w840dp-h1000dp-xhdpi")
    fun fourStatesWide() = shot(FuRiskWdr.fourStates, "risk-wdr-sublines-four-states-w840-light")

    @Test fun frozenPendingLight() = shot(listOf(FuRiskWdr.fixturePending), "risk-wdr-frozen-pending-light", risk = "risk_frozen")

    private fun shot(
        items: List<JsonObject>,
        name: String,
        risk: String = "risk_banned",
        dark: Boolean = false,
        fontScale: Float = 1f,
    ) {
        host.launch(items, risk = risk, dark = dark, fontScale = fontScale)
        // v2（实现交回 Q1）：200% × h844 时第 2–4 行滚出视口，boundsInRoot 被裁成 0 会排错行；改按未裁切的 positionInRoot 排序（与 v1 同一意图：自上而下逐行核）
        val rows =
            rule
                .onAllNodes(hasTestTag(MeTags.RISK_WITHDRAWALS), useUnmergedTree = true)
                .fetchSemanticsNodes()
                .sortedBy {
                    it.layoutInfo.coordinates
                        .positionInRoot()
                        .y
                }
        assertEquals("$name：每笔在途一行", items.size, rows.size)
        val tops =
            rows.map {
                it.layoutInfo.coordinates
                    .positionInRoot()
                    .y
            }
        assertTrue("$name：各行未裁切位置互不相同、自上而下：$tops", tops.zipWithNext().all { (a, b) -> a < b })
        items.zip(rows).forEach { (item, row) ->
            assertEquals(
                "$name：行内 = 标签、值 + 同源副文案",
                listOf(FuRiskWdr.label, FuRiskWdr.value(item)) + FuRiskWdr.sublines(item, null),
                row.wdrTexts(),
            )
        }
        rule.mainClock.autoAdvance = false
        rule.mainClock.advanceTimeBy(FRAME_SETTLE_MS)
        rule.onRoot().captureRoboImage("$DIR/$name.png")
    }

    private companion object {
        const val DIR = "src/test/snapshots/risk-v"
        const val FRAME_SETTLE_MS = 300L
    }
}
