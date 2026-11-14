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

/**
 * Release：二次验证发码不再需要本机号码（B1-02zzz：purpose=step_up 不带 phone，带本人令牌，服务端发往账号绑定的手机号）。这里只给发码槽位的键：
 * 有当前账号就给固定的 [BOUND_PHONE_SLOT]（不是号码，不上送、不显示），槽位按「账号 + action」隔离；没有账号为 null（不发码）。
 * 未绑手机的账号由服务端回 20001（data.fields=[code]），本端不编造号码、不让用户另填。
 */
@Suppress("UnusedParameter")
internal fun variantStepUpPhone(context: Context): (String) -> String? = { account -> BOUND_PHONE_SLOT.takeIf { account.isNotEmpty() } }

/** 发码槽位里代表「账号当前绑定的手机号」的占位（不含数字，不会被当成号码）。 */
private const val BOUND_PHONE_SLOT = "bound"
