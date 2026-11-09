package com.couli.app

import android.content.Context
import com.couli.feature.wallet.LaborBackend
import com.couli.feature.wallet.RealNameBackend

/** Release 没有实名假服务（真实实名后端由 chooseRealNameBackend 接 RemoteRealNameBackend，D10-887）。 */
@Suppress("UnusedParameter", "FunctionOnlyReturningConstant") // 与 debug / staging 源码集同一签名
internal fun variantRealNameBackend(context: Context): RealNameBackend? = null

/** Release：劳务协议接口契约还没有（G02）：没有实现，页面置灰、不发请求。TODO(N-32) */
@Suppress("UnusedParameter", "FunctionOnlyReturningConstant")
internal fun variantLaborBackend(context: Context): LaborBackend? = null

/** Release：本机没有保存当前账号的手机号（Android 空白 A01，G31）：不发码、不编造、不让用户另填。TODO(N-32) */
@Suppress("UnusedParameter")
internal fun variantStepUpPhone(context: Context): (String) -> String? = { null }
