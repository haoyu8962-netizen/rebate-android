package com.couli.app.home

import android.content.Context
import androidx.compose.runtime.Composable
import com.couli.app.routing.RoutingTrace
import com.couli.core.config.AppEnvironment
import com.couli.core.home.HomeContractPort

/**
 * Release：首页的待定 operation（G02 getPage、G03 listSectionItems）契约还没有，不发请求——首页只显示 LKG / 包内默认，商品池与物料卡隐藏。TODO(N-35)
 */
@Suppress("UnusedParameter", "FunctionOnlyReturningConstant") // 与 debug / staging 源码集同一签名
internal fun variantHomePort(context: Context): HomeContractPort? = null

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
