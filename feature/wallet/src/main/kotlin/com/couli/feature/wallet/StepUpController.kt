package com.couli.feature.wallet

import kotlinx.coroutines.CoroutineScope

/**
 * 二次验证执行器已挪到 core:wallet（APP-13a brief §3 Android：钱包与「我的」共用，Feature 之间不互相依赖）；这里保留 APP-09 的名字与构造方式，
 * 行为不变。
 */
typealias StepUpController = com.couli.core.wallet.stepup.StepUpController

/** 面板状态（挪到 core:wallet）。 */
typealias StepUpUiState = com.couli.core.wallet.stepup.StepUpUiState

/** 面板与页面上的一条提示（挪到 core:wallet）。 */
typealias Notice = com.couli.core.wallet.stepup.Notice

/** APP-09 的构造方式：钱包依赖里的二次验证部分（同一份发码截止时间、token 缓存与闸门）。 */
fun StepUpController(
    deps: WalletDeps,
    scope: CoroutineScope,
    alive: () -> Boolean,
): StepUpController =
    com.couli.core.wallet.stepup
        .StepUpController(deps.stepUpDeps, scope, alive)
