package com.couli.app

import android.content.Context
import com.couli.feature.wallet.LaborBackend
import com.couli.feature.wallet.RealNameBackend
import java.io.File

/**
 * debug / staging：实名与劳务协议的进程内假实现（契约没有接口，G01 / G02），与假钱包服务共用状态；统一闸门（开关 + MOCK_DATA + 环境就绪，F-X-02）关着时一律按断网回答。
 */
internal fun variantRealNameBackend(context: Context): RealNameBackend? = fakeWallet(context).realnameBackend

internal fun variantLaborBackend(context: Context): LaborBackend? = fakeWallet(context).laborBackend

/**
 * debug / staging：二次验证发码用的测试号码（假登录服务接管时，按会话给样例号码 sample-data §12 的 138****5678 对应的测试号）。Release 取不到号码，
 * 不发码（Android 空白 A01）。
 */
internal fun variantStepUpPhone(context: Context): (String) -> String? {
    val gate = DebugMockGate.of(context)
    // 每次取号码时过统一闸门（F-X-02）：prod / 切换中取不到号码，不编造、不给测试号发码
    return { account -> if (account.isNotEmpty() && gate()) TEST_PHONE else null }
}

/** 进程内唯一的假钱包服务；实名、劳务两个端口每次调用都过统一闸门（F-X-02：旧实例切到 prod 后按断网）。 */
private fun fakeWallet(context: Context): DebugFakeWallet =
    DebugFakeWallet.of(File(DebugMockGate.stateDir(context), FAKE_WALLET_STATE_FILE)).also { it.switchOn = DebugMockGate.of(context) }

private const val TEST_PHONE = "13800005678"
