package com.couli.app.me

import android.content.Context
import com.couli.core.push.NoPushTokenSource
import com.couli.core.push.PushTokenSource
import com.couli.feature.me.NicknameBackend
import com.couli.feature.me.NotifyBackend

/** Release：改昵称接口契约还没有（G01）：没有实现，昵称行不可点、不发请求。TODO(N-33) */
@Suppress("UnusedParameter", "FunctionOnlyReturningConstant") // 与 debug / staging 源码集同一签名
internal fun variantNicknameBackend(context: Context): NicknameBackend? = null

/** Release：通知设置接口契约还没有（G02）：不显示服务类推送开关行。TODO(N-33) */
@Suppress("UnusedParameter", "FunctionOnlyReturningConstant")
internal fun variantNotifyBackend(context: Context): NotifyBackend? = null

/** Release：推送 SDK 与凭据未就绪（G17）：恒为无令牌，不初始化任何推送 SDK、不上报。TODO(N-33) */
@Suppress("UnusedParameter")
internal fun variantPushTokenSource(context: Context): PushTokenSource = NoPushTokenSource()

/** Release：没有覆盖（微信已装检测一律按生成物，现为 unknown）。 */
@Suppress("UnusedParameter", "FunctionOnlyReturningConstant")
internal fun variantWechatInstalled(context: Context): String? = null

@Suppress("UnusedParameter", "FunctionOnlyReturningConstant")
internal fun variantOpenExternalFails(context: Context): Boolean = false

/** Release：没有覆盖（通知被拒时刻只来自 PermissionRequester 的记录）。 */
@Suppress("UnusedParameter", "FunctionOnlyReturningConstant")
internal fun variantPushDeniedAt(context: Context): Long? = null
