package com.couli.app.wallet

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import com.couli.app.wallet.visual.WithdrawRecordsVisualFixtures
import com.couli.app.wallet.visual.WithdrawRecordsVisualTestHost
import com.couli.core.designsystem.CouliAnnouncer
import com.couli.core.designsystem.CouliColors
import com.couli.core.designsystem.CouliTheme
import com.couli.core.designsystem.LocalCouliAnnouncer
import com.couli.core.wallet.WalletDraftTexts
import com.couli.core.wallet.stepup.Notice
import com.couli.feature.wallet.OpenErrorBar
import com.couli.feature.wallet.RETRY_SUFFIX
import com.couli.feature.wallet.WalletTags
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * FU-AND-0728 U4（F-AND-70 ②，APP-09v-e 评审 r1 S2-2）：W92 共用 ErrorBar 改成 ERROR 语气后，三处此前没有截图的调用方——钱包页刷新错误
 * （WALLET_REFRESH_ERROR）、提现记录刷新错误（RECORDS_REFRESH_ERROR）、发起页「打开前查询失败」（OpenErrorBar / OPEN_ERROR_TEXT）——的场景搭建
 * （结构断言与截图入口共用）。真实 Screen / ViewModel / Controller；钱包页与记录页先成功加载再刷新失败（旧数据保留）。
 * 发起页的 OpenErrorBar 在生产里由 AppWallet 的 WalletRoute 叠在发起页之上（Box 底部居中）；这里只排这个覆盖层本身（同一公开组件、同一参数，画布色底），
 * 不连 AppWallet（它需要整套 App 接线）。
 */
internal class Fu0728ErrorBarScenes(
    private val rule: ComposeContentTestRule,
) : AutoCloseable {
    private var wallet: WalletVisualTestHost? = null
    private var records: WithdrawRecordsVisualTestHost? = null

    /** 渲染时取出的令牌色（浅 / 深各自取值）。 */
    var errorText = Color.Unspecified
        private set
    var errorBackground = Color.Unspecified
        private set

    fun walletRefreshError(dark: Boolean = false) {
        val host = WalletVisualTestHost(rule).also { wallet = it }
        host.launch(dark = dark, statusBar = true)
        rule.onNodeWithTag(WalletTags.WALLET_HERO, useUnmergedTree = true).assertExists()
        host.refresh(WalletVisualFixtures.failure())
        rule.onNodeWithTag(WalletTags.WALLET_REFRESH_ERROR, useUnmergedTree = true).assertExists()
        if (!dark) captureLightTokens()
    }

    fun recordsRefreshError(dark: Boolean = false) {
        val host = WithdrawRecordsVisualTestHost(rule).also { records = it }
        host.launch("list", dark = dark, shell = true)
        rule.onNodeWithTag(WalletTags.RECORDS_PAGE, useUnmergedTree = true).assertExists()
        host.refresh(WithdrawRecordsVisualFixtures.failure(50001))
        rule.onNodeWithTag(WalletTags.RECORDS_REFRESH_ERROR, useUnmergedTree = true).assertExists()
        errorText = host.colors.getValue("status.error.text")
        errorBackground = host.colors.getValue("status.error.background")
    }

    fun openError(dark: Boolean = false) {
        rule.setContent {
            CompositionLocalProvider(LocalCouliAnnouncer provides CouliAnnouncer { }) {
                CouliTheme(darkTheme = dark) {
                    errorText = CouliColors.statusErrorText
                    errorBackground = CouliColors.statusErrorBackground
                    Box(Modifier.fillMaxSize().background(CouliColors.backgroundCanvas)) {
                        OpenErrorBar(
                            notice = Notice(OPEN_ERROR_KEY, 1L, traceId = OPEN_TRACE, showTrace6 = true),
                            texts = WalletDraftTexts,
                            onRetry = {},
                            onDismiss = {},
                        )
                    }
                }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag(WalletTags.OPEN_ERROR, useUnmergedTree = true).assertExists()
    }

    /** 钱包宿主不留错误色：按浅色令牌表取（深色场景只截图，结构断言只跑浅色）。 */
    private fun captureLightTokens() {
        errorText =
            parse(
                com.couli.generated.DesignTokens.colors
                    .getValue("status.error.text"),
            )
        errorBackground =
            parse(
                com.couli.generated.DesignTokens.colors
                    .getValue("status.error.background"),
            )
    }

    private fun parse(hex: String): Color = Color(android.graphics.Color.parseColor(hex))

    /**
     * W92 ERROR 语气的结构：错误条节点 assertive 播报；条内文字红字（status.error.text）500 / 14；条左缘内 4dp 处是红色浅底（status.error.background）；
     * 【重试】（[retryTag]）在且可点。
     */
    fun assertErrorTone(
        tag: String,
        retryTag: String,
    ) {
        val bar = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode()
        assertEquals("$tag：assertive 播报", LiveRegionMode.Assertive, bar.config.getOrNull(SemanticsProperties.LiveRegion))
        val text =
            rule
                .onNode(
                    SemanticsMatcher.keyIsDefined(SemanticsProperties.Text) and hasAnyAncestor(hasTestTag(tag)),
                    useUnmergedTree = true,
                ).fetchSemanticsNode()
        val layouts = mutableListOf<TextLayoutResult>()
        text.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        val style = layouts.single().layoutInput.style
        assertEquals("$tag：红字", errorText, style.color)
        assertEquals("$tag：500", FontWeight.W500, style.fontWeight)
        assertEquals("$tag：14", 14f, style.fontSize.value, .01f)
        val d = bar.layoutInfo.density.density
        val pixels = pixels()
        val b = bar.boundsInRoot
        val sample = pixels.getPixel((b.left + 4 * d).toInt(), b.center.y.toInt())
        assertTrue(
            "$tag：红色浅底（实际 #${Integer.toHexString(sample)}）",
            listOf(16, 8, 0).all { abs((sample ushr it and 255) - (errorBackground.toArgb() ushr it and 255)) <= 2 },
        )
        val retry = rule.onNodeWithTag(retryTag, useUnmergedTree = true).fetchSemanticsNode()
        assertTrue("$tag：【重试】可点", retry.config.contains(SemanticsActions.OnClick))
    }

    private fun pixels(): Bitmap {
        val view = (rule.onRoot().fetchSemanticsNode().root as ViewRootForTest).view
        val image = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        rule.runOnUiThread { view.draw(Canvas(image)) }
        return image
    }

    override fun close() {
        wallet?.close()
        records?.close()
    }

    companion object {
        const val OPEN_ERROR_KEY = "error.50001"
        const val OPEN_TRACE = "trace-me-ABC999"
        val WALLET_RETRY = WalletTags.WALLET_REFRESH_ERROR + RETRY_SUFFIX
        val RECORDS_RETRY = WalletTags.RECORDS_REFRESH_ERROR + RETRY_SUFFIX
    }
}
