package com.couli.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.couli.core.designsystem.CouliColors

/** Release 首屏内容：只显示品牌色背景。没有调试能力、没有环境切换（03 §3.6）。 */
@Composable
internal fun VariantRootContent() {
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(CouliColors.brandPrimary),
    )
}
