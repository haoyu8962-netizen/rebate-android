package com.couli.app.wallet

import com.couli.feature.wallet.LaborBackend

private const val BUILD_KIND_DEBUG = "debug"

/**
 * 生产默认劳务协议后端（FU-AND-LABOR-AGREEMENT-API，D10-2634；与 [chooseRealNameBackend] 同一写法）：debug 用进程内假服务（没有假实现时回落真实），
 * staging / release 一律用真实实现（[real]，RemoteLaborBackend 经 ApiClient）。[kind] 取 BuildConfig.BUILD_KIND。
 */
internal fun chooseLaborBackend(
    kind: String,
    fake: LaborBackend?,
    real: LaborBackend,
): LaborBackend = if (kind == BUILD_KIND_DEBUG) fake ?: real else real
