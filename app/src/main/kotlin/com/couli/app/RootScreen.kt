package com.couli.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import com.couli.core.config.EnvironmentGate
import com.couli.core.designsystem.CouliColors
import com.couli.core.designsystem.CouliSpacing
import com.couli.core.designsystem.CouliTypography
import com.couli.generated.DefaultTexts

/** UI 冒烟测试用的首屏标记。 */
const val ROOT_SCREEN_TAG = "root_screen"

/** 环境清理失败时的阻断页标记。 */
const val ENVIRONMENT_BLOCKED_TAG = "environment_blocked"

/** 阻断页【重试】按钮标记。 */
const val ENVIRONMENT_RETRY_TAG = "environment_blocked_retry"

/**
 * 阻断页文案键（BR-TEXT-12，不硬编码中文）。契约里还没有专用的「启动失败」「通用重试」键：
 * - 说明用 fallbacks 的 `error.50001`（通用「出了点问题，请稍后再试」，不带 trace 占位符）；
 * - 按钮用 texts 的 `external_page.retry`（「重试」）。
 * TODO(APP-02): 契约补通用文案键后替换（待补文案键已报编排者登记）。
 */
internal object EnvironmentBlockedTexts {
    const val MESSAGE_FALLBACK_KEY = "error.50001"
    const val RETRY_TEXT_KEY = "external_page.retry"

    val message: String get() = DefaultTexts.fallback(MESSAGE_FALLBACK_KEY).orEmpty()
    val retry: String get() = DefaultTexts.text(RETRY_TEXT_KEY).orEmpty()
}

/**
 * 最小首屏：背景 color.background.canvas。
 * - 环境闸门 [EnvironmentGate.State.Blocked]（启动清理失败）：只显示阻断页（一句说明 + 【重试】），
 *   不进入正常界面，不读取任何会话或缓存（[VariantRootContent] 不被组合）。
 * - [EnvironmentGate.State.Settled]：内容随构建类型不同：debug / staging（src/debugFeatures）显示令牌版本、
 *   构建类型与调试面板入口；release（src/release）只显示品牌色背景。
 *
 * [settledContent] 只为测试替换（默认即 [VariantRootContent]，它经 Hilt 取依赖）。
 */
@Composable
fun RootScreen(
    gateState: EnvironmentGate.State,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    settledContent: @Composable () -> Unit = { VariantRootContent() },
) {
    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(CouliColors.backgroundCanvas)
                .testTag(ROOT_SCREEN_TAG),
    ) {
        when (gateState) {
            is EnvironmentGate.State.Settled -> settledContent()
            is EnvironmentGate.State.Blocked -> EnvironmentBlockedContent(onRetry = onRetry)
        }
    }
}

@Composable
private fun EnvironmentBlockedContent(onRetry: () -> Unit) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(CouliSpacing.space4)
                .testTag(ENVIRONMENT_BLOCKED_TAG),
        verticalArrangement = Arrangement.spacedBy(CouliSpacing.space4, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = EnvironmentBlockedTexts.message,
            style = CouliTypography.body,
            color = CouliColors.textPrimary,
            textAlign = TextAlign.Center,
        )
        TextButton(
            onClick = onRetry,
            modifier = Modifier.testTag(ENVIRONMENT_RETRY_TAG),
        ) {
            Text(text = EnvironmentBlockedTexts.retry)
        }
    }
}
