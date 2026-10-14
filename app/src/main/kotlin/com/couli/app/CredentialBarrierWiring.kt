package com.couli.app

import com.couli.core.network.CredentialReadBarrier

/**
 * 运行中切换环境的第一步（AppModule 的切换监听 onSwitchStarting；F-X-03，FU-AND-0714 第 1 轮评审 R1-01）：在凭据读取屏障里，
 * 先关屏障内部闸并等已准入的令牌 / 设备凭据读取结束，再 [invalidateNetwork]（作废网络世代、取消在途请求），**最后**才 [publishSwitching]
 * （关环境闸门，switching 对外可见）。返回时状态已发布，且发布之后不会再有凭据读取。
 */
internal fun beginEnvironmentSwitchBehindBarrier(
    barrier: CredentialReadBarrier,
    invalidateNetwork: () -> Unit,
    publishSwitching: () -> Unit,
) = barrier.raise(beforePublish = invalidateNetwork, publish = publishSwitching)

/**
 * 撤回同意的屏障（交给 PrivacyGate.credentialBarrier；F-X-03，R1-01）：PrivacyGate 交来的动作是「记下待清理标记」（对外可见的撤回状态）。
 * 屏障先关内部闸、等已准入的读取结束，再 [invalidateNetwork]（隐私世代加 1、取消在途请求），最后才执行它。撤回清理名单的第一项之后再作废一次。
 */
internal fun withdrawalBarrier(
    barrier: CredentialReadBarrier,
    invalidateNetwork: () -> Unit,
): (() -> Unit) -> Unit = { publishWithdrawal -> barrier.raise(beforePublish = invalidateNetwork, publish = publishWithdrawal) }
