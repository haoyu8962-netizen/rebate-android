package com.couli.app.wallet

import com.couli.feature.wallet.RealNameBackend

private const val BUILD_KIND_DEBUG = "debug"

/**
 * 生产默认实名后端（D10-887）：debug 用进程内假服务（没有假实现时回落真实），staging / release 一律用真实实现（[real]，经 ApiClient）。
 * [kind] 取 BuildConfig.BUILD_KIND。
 */
internal fun chooseRealNameBackend(
    kind: String,
    fake: RealNameBackend?,
    real: RealNameBackend,
): RealNameBackend = if (kind == BUILD_KIND_DEBUG) fake ?: real else real
