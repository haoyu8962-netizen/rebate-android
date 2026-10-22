package com.couli.app.wallet.visual

import android.app.Application
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.product.productPixels
import com.couli.app.product.textLayout
import com.couli.feature.wallet.WalletTags
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h1000dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WithdrawRecordsVisualDetailTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { WithdrawRecordsVisualTestHost(rule) }

    @After fun close() = host.close()

    @Test fun w24FailureReasonAndReturnAreSeparateUnchangedLines() {
        host.launch("failed", focus = "FAILED")
        val reason = rule.recordText("收款支付宝账号状态异常", "FAILED").assertIsDisplayed()
        val returned = rule.recordText("¥8 已退回可提现余额，可修改收款账号后重新提现", "FAILED").assertIsDisplayed()
        assertEquals(
            17f,
            reason
                .textLayout()
                .layoutInput.style.fontSize.value,
            .01f,
        )
        assertEquals(
            host.colors.getValue("text.primary"),
            reason
                .textLayout()
                .layoutInput.style.color,
        )
        assertEquals(
            14f,
            returned
                .textLayout()
                .layoutInput.style.fontSize.value,
            .01f,
        )
        assertEquals(
            host.colors.getValue("text.secondary"),
            returned
                .textLayout()
                .layoutInput.style.color,
        )
        assertTrue(reason.fetchSemanticsNode().boundsInRoot.bottom <= returned.fetchSemanticsNode().boundsInRoot.top)
    }

    @Test fun w24FrozenSupplementIsTwelveSecondary() {
        host.launch("pending", focus = "PENDING")
        val frozen = rule.recordText("¥10 已冻结", "PENDING").textLayout()
        assertEquals(12f, frozen.layoutInput.style.fontSize.value, .01f)
        assertEquals(host.colors.getValue("text.secondary"), frozen.layoutInput.style.color)
    }

    @Test fun w25SeparatorsSeparateStatusDetailsAndService() {
        host.launch("failed", focus = "FAILED")
        val button = rule.onNodeWithTag(WalletTags.RECORD_CHANGE_ACCOUNT).fetchSemanticsNode()
        val amount = rule.recordText("收款账号", "FAILED").fetchSemanticsNode()
        val service = rule.onNodeWithTag(WalletTags.RECORD_CS).fetchSemanticsNode()
        val time = rule.recordText("2026-10-08 11:30", "FAILED").fetchSemanticsNode()
        val d = button.layoutInfo.density.density
        val card = rule.record("FAILED").fetchSemanticsNode().boundsInRoot
        val pixels = rule.productPixels()
        val gaps = listOf(button.boundsInRoot.bottom to amount.boundsInRoot.top, time.boundsInRoot.bottom to service.boundsInRoot.top)
        gaps.forEach { (top, bottom) ->
            assertTrue("分隔区须位于相邻区块之间", bottom > top)
            val rect = Rect(card.left + 16 * d, top, card.right - 16 * d, bottom)
            assertTrue(
                "每条分隔线都要真实画出",
                pixels.recordColorCount(rect, host.colors.getValue("border.subtle").toArgb()) >= rect.width * d * .75f,
            )
        }
    }

    @Test fun w26ValuesAreEndAlignedTabularWithEightDpRowGaps() {
        host.launch("independent", focus = "INDEPENDENT")
        val detailTag = WalletTags.RECORD_DETAIL_PREFIX + "INDEPENDENT"
        val values = listOf("¥123.45", "¥1.23", "¥4.56", "¥98.76")
        val nodes = values.map { value -> rule.onNode(hasText(value) and hasAnyAncestor(hasTestTag(detailTag)), true) }
        nodes.forEach {
            val style = it.textLayout().layoutInput.style
            assertEquals(androidx.compose.ui.text.style.TextAlign.End, style.textAlign)
            assertTrue("明细等宽数字", style.fontFeatureSettings.orEmpty().contains("tnum"))
        }
        val first = nodes[0].fetchSemanticsNode()
        val second = nodes[1].fetchSemanticsNode()
        assertEquals(8f, (second.boundsInRoot.top - first.boundsInRoot.bottom) / first.layoutInfo.density.density, 1f)
    }

    @Test fun w27TradeNumberLabelAboveNumberAndCopyHasFourteenMediumLinkText() {
        host.launch("paid", focus = "PAID")
        val label = rule.recordText("交易流水号", "PAID").performScrollTo().fetchSemanticsNode()
        val number = rule.recordText("20260928200040011100", "PAID").fetchSemanticsNode()
        assertTrue("标签和号码分两行", label.boundsInRoot.bottom <= number.boundsInRoot.top)
        val copy = rule.recordText("复制", "PAID").textLayout()
        assertEquals(14f, copy.layoutInput.style.fontSize.value, .01f)
        assertEquals(
            500,
            copy.layoutInput.style.fontWeight
                ?.weight,
        )
        assertEquals(host.colors.getValue("text.link"), copy.layoutInput.style.color)
    }

    @Test fun w28ChangeAccountIsFullWidthOutlinedBeforeDetails() {
        host.launch("failed", focus = "FAILED")
        val button = rule.onNodeWithTag(WalletTags.RECORD_CHANGE_ACCOUNT).fetchSemanticsNode()
        val label = rule.recordText("修改收款账号", "FAILED")
        val d = button.layoutInfo.density.density
        val card = rule.record("FAILED").fetchSemanticsNode().boundsInRoot
        val detail = rule.onNodeWithTag(WalletTags.RECORD_DETAIL_PREFIX + "FAILED").fetchSemanticsNode()
        assertTrue("修改账号必须在明细之前", button.boundsInRoot.bottom <= detail.boundsInRoot.top)
        assertEquals(card.width - 32 * d, button.boundsInRoot.width, d)
        assertTrue(button.touchBoundsInRoot.height >= 48 * d)
        assertEquals(
            14f,
            label
                .textLayout()
                .layoutInput.style.fontSize.value,
            .01f,
        )
        assertEquals(
            500,
            label
                .textLayout()
                .layoutInput.style.fontWeight
                ?.weight,
        )
        val interior =
            Rect(
                button.boundsInRoot.left + 8 * d,
                button.boundsInRoot.top + 8 * d,
                button.boundsInRoot.right - 8 * d,
                button.boundsInRoot.bottom - 8 * d,
            )
        assertTrue(
            "描边按钮内部为卡片色",
            rule.productPixels().recordColorCount(
                interior,
                host.colors.getValue("background.surface").toArgb(),
            ) > interior.width * interior.height * .65f,
        )
    }

    @Test fun w29ServiceHasLinkToneAndMediumWeightAfterDetails() {
        host.launch("pending", focus = "PENDING")
        val service = rule.recordText("联系客服", "PENDING").performScrollTo()
        assertEquals(
            host.colors.getValue("text.link"),
            service
                .textLayout()
                .layoutInput.style.color,
        )
        assertEquals(
            14f,
            service
                .textLayout()
                .layoutInput.style.fontSize.value,
            .01f,
        )
        assertEquals(
            500,
            service
                .textLayout()
                .layoutInput.style.fontWeight
                ?.weight,
        )
        assertTrue(
            service.fetchSemanticsNode().boundsInRoot.top >=
                rule
                    .recordText("2026-10-12 10:00", "PENDING")
                    .fetchSemanticsNode()
                    .boundsInRoot.bottom,
        )
    }
}
