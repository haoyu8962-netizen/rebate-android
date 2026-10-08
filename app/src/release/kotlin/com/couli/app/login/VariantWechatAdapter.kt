package com.couli.app.login

import android.content.Context
import com.couli.feature.login.InstalledAppProbe
import com.couli.feature.login.ThirdPartyAuthAdapter
import com.couli.feature.login.UnavailableWechatAdapter

/** Release：微信适配器恒为不可用（不引入微信 SDK、没有 AppID，任务书 §2.2）→ 登录页不显示微信按钮。 */
@Suppress("UnusedParameter") // 与 debug / staging 源码集同一签名
internal fun variantWechatAdapter(
    context: Context,
    probe: InstalledAppProbe,
): ThirdPartyAuthAdapter = UnavailableWechatAdapter(probe)
