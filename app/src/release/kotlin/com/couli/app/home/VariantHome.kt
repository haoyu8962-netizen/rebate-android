package com.couli.app.home

import android.content.Context
import androidx.compose.runtime.Composable
import com.couli.app.routing.RoutingTrace
import com.couli.core.config.AppEnvironment
import com.couli.core.home.HomeContractPort

/** Release：首页走真实接口（契约 getPage / listPageSectionItems 经 ApiClient，FU-X-HOME-PAGE-API）；没有本机假服务。 */
@Suppress("UnusedParameter") // 与 debug / staging 源码集同一签名
internal fun variantHomePort(
    context: Context,
    real: HomeContractPort,
): HomeContractPort = real

/** Release 没有 HomePreview（debug_only 路由）：没有草稿数据源。 */
@Suppress("UnusedParameter", "FunctionOnlyReturningConstant") // 与 debug / staging 源码集同一签名
internal fun variantPreviewPort(context: Context): HomeContractPort? = null

/** Release 只有 prod。 */
@Suppress("UnusedParameter")
internal fun variantEnvironmentKey(context: Context): String = AppEnvironment.PROD.key

/** Release 的首页 Tab 内容：正式首页（没有调试入口）。 */
@Suppress("UnusedParameter")
@Composable
internal fun VariantHomeContent(
    home: AppHome?,
    trace: RoutingTrace?,
) {
    if (home != null) HomeRoute(home)
}
