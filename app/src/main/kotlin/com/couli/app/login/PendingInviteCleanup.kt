package com.couli.app.login

import com.couli.app.privacy.AppPrivacy
import com.couli.core.config.EnvironmentSwitchCleanerRegistry
import com.couli.core.router.PrivacyState
import com.couli.feature.login.PendingInviteCode

/**
 * 待绑定邀请码的清理由容器自己登记（APP-17c；iOS Q2、鸿蒙评审 S2-6：不借道剪贴板，剪贴板没建出来也照样清）：
 * - 隐私结论变成任何「不是已同意」的状态都清：撤回同意 / 基本模式（BASIC_MODE）、隐私政策升级后尚无结论（NOT_DECIDED，冷启动发布）；
 *   已同意之内的刷新不清；
 * - 切换环境：登记进生产的 [EnvironmentSwitchCleanerRegistry]，在新环境提交之前清（码属于旧环境的邀请关系）。
 * 会话变化、离开登录页、进出后台都不清（进程内有效，R04）。只由 AppModule 的提供函数调用一次。
 */
internal fun PendingInviteCode.registerCleanup(
    privacy: AppPrivacy,
    cleaners: EnvironmentSwitchCleanerRegistry,
): PendingInviteCode {
    privacy.onConclusionChanged { conclusion -> if (conclusion != PrivacyState.CONSENTED) clear() }
    cleaners.register { _, _ -> clear() }
    return this
}
