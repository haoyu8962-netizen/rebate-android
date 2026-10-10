package com.couli.app.home

import androidx.compose.runtime.Composable
import com.couli.app.routing.AppRouting
import com.couli.app.routing.RouteDestination

/** Release：没有 HomePreview（03 §3.6，AC-S1-86 ②③）：不注册页面、不含调用 previewPage 的代码与文案。 */
@Suppress("UnusedParameter", "FunctionOnlyReturningConstant") // 与 debug / staging 源码集同一签名
internal fun variantHomePreviewPages(
    home: AppHome?,
    routing: AppRouting,
    onExit: (String?) -> Unit,
): Map<String, @Composable (RouteDestination) -> Unit> = emptyMap()
